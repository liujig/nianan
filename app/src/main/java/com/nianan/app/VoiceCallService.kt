package com.nianan.app

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class VoiceCallService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nc"
        const val VOICE_URL = "http://127.0.0.1:8765/voice_audio"
        const val SAMPLE_RATE = 16000
        const val CHUNK_SECS = 3
    }

    private var audioRecord: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scoStarted = false
    private var recording = false
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
        startRecording()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recording = false
        stopRecording()
        stopMediaPlayer()
        stopBluetoothSco()
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showNotification() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        startForeground(NOTIFICATION_ID, nb
            .setContentTitle("通话中 — 念安")
            .setContentText("蓝牙耳机实时对话")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true).setContentIntent(pi).build())
    }

    // ─── 录音+发送 ───
    private fun startRecording() {
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        recording = true
        audioRecord?.startRecording()

        val chunkSize = SAMPLE_RATE * 2 * CHUNK_SECS
        val chunk = ByteArray(chunkSize)
        var pos = 0

        Thread {
            while (recording) {
                val len = audioRecord?.read(chunk, pos, chunkSize - pos) ?: break
                if (len < 0) break
                pos += len
                if (pos >= chunkSize) {
                    val data = chunk.copyOf(pos)
                    pos = 0
                    try {
                        val respAudio = sendAudio(data)
                        if (respAudio != null && respAudio.isNotEmpty()) {
                            handler?.post { playAudio(respAudio) }
                        }
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    private fun sendAudio(pcm: ByteArray): ByteArray? {
        // PCM转WAV
        val wav = ByteArrayOutputStream(pcm.size + 44)
        val le = java.nio.ByteOrder.LITTLE_ENDIAN
        val b4 = java.nio.ByteBuffer.allocate(4).order(le)
        wav.write("RIFF".toByteArray())
        b4.putInt(0, pcm.size + 36); wav.write(b4.array())
        wav.write("WAVE".toByteArray())
        wav.write("fmt ".toByteArray())
        b4.putInt(0, 16); wav.write(b4.array())
        b4.putShort(0, 1.toShort()); wav.write(b4.array(), 0, 2)
        b4.putShort(0, 1.toShort()); wav.write(b4.array(), 0, 2)
        b4.putInt(0, SAMPLE_RATE); wav.write(b4.array())
        b4.putInt(0, SAMPLE_RATE * 2); wav.write(b4.array())
        b4.putShort(0, 2.toShort()); wav.write(b4.array(), 0, 2)
        b4.putShort(0, 16.toShort()); wav.write(b4.array(), 0, 2)
        wav.write("data".toByteArray())
        b4.putInt(0, pcm.size); wav.write(b4.array())
        wav.write(pcm)

        val conn = URL(VOICE_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 10000; conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.outputStream.write(wav.toByteArray())
        conn.outputStream.flush(); conn.outputStream.close()

        if (conn.responseCode == 200) {
            val inp = conn.inputStream
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
            inp.close()
            conn.disconnect()
            val audio = out.toByteArray()
            return if (audio.size > 44) audio else null
        }
        conn.disconnect()
        return null
    }

    private fun stopRecording() {
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    // ─── MediaPlayer 播放 ───
    private fun playAudio(wav: ByteArray) {
        try {
            stopMediaPlayer()
            val file = File(filesDir, "reply.wav")
            file.writeBytes(wav)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                setOnPreparedListener { start() }
                setOnCompletionListener { mp -> mp.release(); mediaPlayer = null }
                prepareAsync()
            }
        } catch (_: Exception) {}
    }

    private fun stopMediaPlayer() {
        try { mediaPlayer?.apply { stop(); release() } } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ─── 蓝牙 ───
    private fun startBluetoothSco() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false; am.startBluetoothSco(); scoStarted = true
            }
        } catch (_: Exception) {}
    }

    private fun stopBluetoothSco() {
        if (scoStarted) try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.stopBluetoothSco(); am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nianan:call")
        wakeLock?.acquire(3600_000)
    }
}
