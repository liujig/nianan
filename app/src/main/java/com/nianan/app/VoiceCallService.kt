package com.nianan.app

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import java.io.*
import java.net.Socket

class VoiceCallService : Service() {

    companion object {
        const val TAG = "NianAnVoice"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nc"
        const val TCP_HOST = "127.0.0.1"
        const val TCP_PORT = 8766
        const val SAMPLE_RATE_IN = 16000   // 录音采样率
        const val SAMPLE_RATE_OUT = 24000  // 播放采样率(TTS)
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var tcpSocket: Socket? = null
    private var tcpOutput: DataOutputStream? = null
    private var tcpInput: DataInputStream? = null
    private var recording = false
    private var playing = false
    private var scoStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var handler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "通话", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification()
        startBluetoothSco()
        acquireWakeLock()
        connectTcp()
        startRecording()
        startPlayback()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recording = false
        playing = false
        stopRecording()
        stopPlayback()
        disconnectTcp()
        stopBluetoothSco()
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ─── 通知 ───

    private fun showNotification() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        startForeground(NOTIFICATION_ID, nb
            .setContentTitle("通话中 — 念安")
            .setContentText("蓝牙耳机实时对话")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true).setContentIntent(pi).build())
    }

    // ─── 蓝牙 ───

    private fun startBluetoothSco() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                scoStarted = true
            }
        } catch (e: Exception) {}
    }

    private fun stopBluetoothSco() {
        if (scoStarted) {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.stopBluetoothSco()
                am.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) {}
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nianan:call")
        wakeLock?.acquire(3600_000)
    }

    // ─── TCP 连接 ───

    private fun connectTcp() {
        Thread {
            try {
                tcpSocket = Socket(TCP_HOST, TCP_PORT)
                tcpSocket?.tcpNoDelay = true  // 低延迟
                tcpOutput = DataOutputStream(tcpSocket!!.getOutputStream())
                tcpInput = DataInputStream(tcpSocket!!.getInputStream())
            } catch (e: Exception) {
                stopSelf()
            }
        }.start()
    }

    private fun disconnectTcp() {
        try { tcpOutput?.close() } catch (e: Exception) {}
        try { tcpInput?.close() } catch (e: Exception) {}
        try { tcpSocket?.close() } catch (e: Exception) {}
    }

    // ─── 录音 ───

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2
        )
        recording = true
        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val len = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (len > 0 && tcpOutput != null) {
                    try {
                        // 4字节长度头 + PCM数据
                        tcpOutput!!.writeInt(len)
                        tcpOutput!!.write(buffer, 0, len)
                        tcpOutput!!.flush()
                    } catch (e: Exception) {
                        if (recording) {
                            handler?.post { stopSelf() }
                        }
                        break
                    }
                }
            }
        }.start()
    }

    private fun stopRecording() {
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    // ─── 播放 ───

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_OUT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            bufferSize, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
        playing = true

        Thread {
            while (playing && tcpInput != null) {
                try {
                    val len = tcpInput!!.readInt()
                    if (len <= 0 || len > 512000) break
                    val data = ByteArray(len)
                    tcpInput!!.readFully(data)
                    // 跳过WAV头(44字节) → PCM
                    val pcm = if (len > 44) data.copyOfRange(44, len) else data
                    audioTrack?.write(pcm, 0, pcm.size)
                } catch (e: java.io.EOFException) {
                    break
                } catch (e: Exception) {
                    if (playing) break
                }
            }
        }.start()
    }

    private fun stopPlayback() {
        playing = false
        audioTrack?.apply { stop(); release() }
        audioTrack = null
    }
}
