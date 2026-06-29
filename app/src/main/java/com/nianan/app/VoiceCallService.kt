package com.nianan.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

class VoiceCallService : Service() {

    companion object {
        const val TAG = "NianAnVoice"
        const val CHANNEL_ID = "nianan_call"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CALL = "com.nianan.app.START_CALL"
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"
        const val SAMPLE_RATE = 16000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val VOICE_URL = "http://127.0.0.1:8765/voice"
        const val CHUNK_SECONDS = 3  // 每段录音时长
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recording = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var scoStarted = false
    private var handler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
        Log.i(TAG, "VoiceCallService 创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> startCall()
            ACTION_STOP_CALL -> stopCall()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCall() {
        Log.i(TAG, "开始通话")
        showCallNotification()
        startBluetoothSco()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nianan:voicecall")
            .apply { acquire(3600_000) }

        startPlayback()
        startRecordingLoop()
    }

    private fun stopCall() {
        Log.i(TAG, "结束通话")
        recording = false
        stopRecording()
        stopPlayback()
        stopBluetoothSco()
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── 蓝牙 SCO ───

    private fun startBluetoothSco() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false; am.startBluetoothSco(); scoStarted = true
                Log.i(TAG, "蓝牙SCO已启动")
            }
        } catch (e: Exception) { Log.w(TAG, "SCO失败: ${e.message}") }
    }

    private fun stopBluetoothSco() {
        if (scoStarted) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.stopBluetoothSco(); am.mode = AudioManager.MODE_NORMAL; scoStarted = false
            } catch (e: Exception) {}
        }
    }

    // ─── 录音+发送循环 ───

    private fun startRecordingLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING, bufferSize * 2
        )
        recording = true
        audioRecord?.startRecording()
        Log.i(TAG, "录音已启动")

        Thread {
            val chunkSize = SAMPLE_RATE * 2 * CHUNK_SECONDS  // 3秒 = 96000 bytes
            val chunk = ByteArray(chunkSize)
            var pos = 0

            while (recording) {
                val len = audioRecord?.read(chunk, pos, chunkSize - pos) ?: break
                if (len < 0) break
                pos += len
                if (pos >= chunkSize) {
                    // 发送音频
                    val audio = chunk.copyOf(pos)
                    pos = 0
                    try {
                        val respAudio = sendAudio(audio)
                        if (respAudio != null && respAudio.isNotEmpty()) {
                            handler?.post { playAudio(respAudio) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "发送失败: ${e.message}")
                    }
                }
            }
        }.start()
    }

    private fun sendAudio(pcmData: ByteArray): ByteArray? {
        // 转 WAV
        val wavData = pcmToWav(pcmData)

        val url = URL(VOICE_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.outputStream.write(wavData)
        conn.outputStream.flush()
        conn.outputStream.close()

        if (conn.responseCode == 200) {
            val input = conn.inputStream
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            while (input.read(buf).also { n = it } != -1) { out.write(buf, 0, n) }
            input.close()
            val audio = out.toByteArray()
            Log.i(TAG, "收到回复音频: ${audio.size} bytes")
            return if (audio.size > 44) audio else null  // > 44 = 有WAV头+数据
        } else {
            Log.w(TAG, "voice_server返回: ${conn.responseCode}")
        }
        conn.disconnect()
        return null
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val totalLen = pcm.size + 44
        val out = ByteArrayOutputStream(totalLen)
        val le = java.nio.ByteOrder.LITTLE_ENDIAN
        val buf = java.nio.ByteBuffer.allocate(4).order(le)

        out.write("RIFF".toByteArray())
        buf.putInt(0, totalLen - 8); out.write(buf.array())
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        buf.putInt(0, 16); out.write(buf.array()) // fmt chunk size
        buf.putShort(0, 1.toShort()); out.write(buf.array(), 0, 2) // PCM
        buf.putShort(0, 1.toShort()); out.write(buf.array(), 0, 2) // mono
        buf.putInt(0, SAMPLE_RATE); out.write(buf.array())
        buf.putInt(0, SAMPLE_RATE * 2); out.write(buf.array()) // byte rate
        buf.putShort(0, 2.toShort()); out.write(buf.array(), 0, 2) // block align
        buf.putShort(0, 16.toShort()); out.write(buf.array(), 0, 2) // bits per sample
        out.write("data".toByteArray())
        buf.putInt(0, pcm.size); out.write(buf.array())
        out.write(pcm)
        return out.toByteArray()
    }

    // ─── 播放 ───

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(ENCODING).build(),
            bufferSize, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    private fun playAudio(wavData: ByteArray) {
        // 跳过WAV头(44字节)，取PCM数据
        val pcm = if (wavData.size > 44) wavData.copyOfRange(44, wavData.size) else wavData
        audioTrack?.write(pcm, 0, pcm.size)
    }

    private fun stopPlayback() {
        audioTrack?.apply { stop(); release() }
        audioTrack = null
    }

    private fun stopRecording() {
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    // ─── 通知 ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "念安通话", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "念安语音通话"; setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun showCallNotification() {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("通话中 — 念安")
                .setContentText("蓝牙耳机实时对话")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi).build())
    }
}
