package com.nianan.app

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class VoiceCallService : Service() {

    companion object {
        const val TAG = "NianAnVoice"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nc"
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
        startPlayback()
        startSpeechRecognition()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listening = false
        stopSpeechRecognition()
        stopPlayback()
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
        startForeground(NOTIFICATION_ID,
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(this))
                .setContentTitle("通话中 — 念安")
                .setContentText("蓝牙耳机实时对话")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true).setContentIntent(pi).build())
    }

    // ─── 语音识别 ───
    private fun startSpeechRecognition() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buf: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }

            override fun onError(error: Int) {
                if (listening || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    handler?.postDelayed({ restartRecognition() }, 300)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val text = matches[0]
                    Thread { sendText(text) }.start()
                }
                restartRecognition()
            }

            override fun onPartialResults(partial: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        startListening()
    }

    private fun startListening() {
        listening = true
        speechRecognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800)
        })
    }

    private fun restartRecognition() {
        if (!listening) {
            startListening()
        }
    }

    private fun stopSpeechRecognition() {
        speechRecognizer?.apply { stopListening(); destroy() }
        speechRecognizer = null
    }

    // ─── 发送文字 → 收音频 ───
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
        } catch (e: Exception) {}
    }

    // ─── 播放 ───
    private fun startPlayback() {
        val bufSize = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            bufSize, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
    }

    private fun playAudio(wav: ByteArray) {
        val pcm = if (wav.size > 44) wav.copyOfRange(44, wav.size) else wav
        audioTrack?.write(pcm, 0, pcm.size)
    }

    private fun stopPlayback() {
        audioTrack?.apply { stop(); release() }
        audioTrack = null
    }

    // ─── 蓝牙 ───
    private fun startBluetoothSco() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false; am.startBluetoothSco(); scoStarted = true
            }
        } catch (e: Exception) {}
    }

    private fun stopBluetoothSco() {
        if (scoStarted) {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.stopBluetoothSco(); am.mode = AudioManager.MODE_NORMAL
            } catch (e: Exception) {}
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nianan:call")
        wakeLock?.acquire(3600_000)
    }
}
