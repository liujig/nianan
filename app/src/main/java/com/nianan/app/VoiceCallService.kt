package com.nianan.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class VoiceCallService : Service() {

    companion object {
        const val TAG = "NianAnVoice"
        const val CHANNEL_ID = "nianan_call"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CALL = "com.nianan.app.START_CALL"
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"
        const val VOICE_URL = "http://127.0.0.1:8765/voice"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var audioTrack: AudioTrack? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scoStarted = false
    private var handler: Handler? = null
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        createNotificationChannel()
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
        // 诊断：写标记文件
        try { java.io.File("/storage/emulated/0/nianan_call_test.txt").writeText("VoiceCallService started at ${System.currentTimeMillis()}") } catch(e: Exception) {}
        // 诊断：心跳测试
        Thread {
            try {
                val url = java.net.URL("http://127.0.0.1:8765/status")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                // 记下来
                java.io.File("/storage/emulated/0/nianan_http_test.txt").writeText("HTTP /status → $code")
                conn.disconnect()
            } catch(e: Exception) {
                java.io.File("/storage/emulated/0/nianan_http_test.txt").writeText("HTTP FAIL: ${e.message}")
            }
        }.start()

        showCallNotification()
        startBluetoothSco()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nianan:voicecall")
            .apply { acquire(3600_000) }

        startPlayback()
        startSpeechRecognition()
    }

    private fun stopCall() {
        Log.i(TAG, "结束通话")
        stopSpeechRecognition()
        stopPlayback()
        stopBluetoothSco()
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── 语音识别 ───

    private fun startSpeechRecognition() {
        try { java.io.File("/storage/emulated/0/nianan_sr_start.txt").writeText("SR starting...") } catch(e: Exception) {}
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        if (speechRecognizer == null) {
            java.io.File("/storage/emulated/0/nianan_sr_error.txt").writeText("SR is NULL")
            return
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }

            override fun onError(error: Int) {
                Log.w(TAG, "识别错误: $error")
                java.io.File("/storage/emulated/0/nianan_sr_error.txt").writeText("SR error: $error")
                if (listening || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    handler?.postDelayed({ restartRecognition() }, 500)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0]
                    java.io.File("/storage/emulated/0/nianan_sr_text.txt").writeText("识别: $text")
                    Log.i(TAG, "识别: $text")
                    // 发给 voice_server
                    Thread { sendText(text) }.start()
                }
                // 继续听
                restartRecognition()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
        }
        listening = true
        speechRecognizer?.startListening(intent)
        Log.i(TAG, "语音识别已启动")
    }

    private fun restartRecognition() {
        if (!listening) {
            listening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    private fun stopSpeechRecognition() {
        listening = false
        speechRecognizer?.apply {
            stopListening()
            destroy()
        }
        speechRecognizer = null
    }

    // ─── 发送文字 → voice_server ───

    private fun sendText(text: String) {
        try {
            val json = JSONObject().apply { put("text", text) }
            val body = json.toString().toByteArray()

            val conn = URL(VOICE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(body)
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
                if (audio.size > 44) {
                    handler?.post { playAudio(audio) }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "sendText失败: ${e.message}")
        }
    }

    // ─── 播放 ───

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            bufferSize, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    private fun playAudio(wavData: ByteArray) {
        val pcm = if (wavData.size > 44) wavData.copyOfRange(44, wavData.size) else wavData
        audioTrack?.write(pcm, 0, pcm.size)
    }

    private fun stopPlayback() {
        audioTrack?.apply { stop(); release() }
        audioTrack = null
    }

    // ─── 蓝牙 ───

    private fun startBluetoothSco() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false; am.startBluetoothSco(); scoStarted = true
            }
        } catch (e: Exception) {}
    }

    private fun stopBluetoothSco() {
        if (scoStarted) {
            try { val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager; am.stopBluetoothSco(); am.mode = AudioManager.MODE_NORMAL } catch (e: Exception) {}
        }
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
