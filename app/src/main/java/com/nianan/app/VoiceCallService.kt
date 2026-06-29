package com.nianan.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class VoiceCallService : Service() {

    companion object {
        const val TAG = "NianAnVoice"
        const val CHANNEL_ID = "nianan_call"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CALL = "com.nianan.app.START_CALL"
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recording = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var scoStarted = false
    private var wsConnected = false
    private var wsThread: Thread? = null
    private var wsInput: java.io.InputStream? = null
    private var wsOutput: java.io.OutputStream? = null
    private var wsSocket: java.net.Socket? = null

    override fun onCreate() {
        super.onCreate()
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
        showCallNotification()
        startBluetoothSco()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nianan:voicecall")
            .apply { acquire(3600_000) }
        connectWebSocket()
        Handler(Looper.getMainLooper()).postDelayed({ startRecording(); startPlayback() }, 500)
    }

    private fun stopCall() {
        recording = false; stopRecording(); stopPlayback()
        disconnectWebSocket(); stopBluetoothSco(); wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun startBluetoothSco() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false; am.startBluetoothSco(); scoStarted = true
            }
        } catch (e: Exception) { Log.w(TAG, "SCO失败: ${e.message}") }
    }

    private fun stopBluetoothSco() {
        if (scoStarted) { try { val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager; am.stopBluetoothSco(); am.mode = AudioManager.MODE_NORMAL } catch (e: Exception) {}; scoStarted = false }
    }

    private fun startRecording() {
        val sz = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, ENCODING)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, ENCODING, sz * 2)
        recording = true; audioRecord?.startRecording()
        Thread {
            val buf = ByteArray(sz)
            while (recording) { val len = audioRecord?.read(buf, 0, sz) ?: break; if (len > 0 && wsConnected && wsOutput != null) { try { wsOutput!!.write(buf, 0, len); wsOutput!!.flush() } catch (e: Exception) {} } }
        }.start()
    }

    private fun stopRecording() { audioRecord?.apply { stop(); release() }; audioRecord = null }

    private fun startPlayback() {
        val sz = AudioTrack.getMinBufferSize(SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
        audioTrack = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioFormat.Builder().setSampleRate(SAMPLE_RATE_OUT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(ENCODING).build(),
            sz, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
        ); audioTrack?.play()
    }

    private fun stopPlayback() { audioTrack?.apply { stop(); release() }; audioTrack = null }

    fun playAudio(data: ByteArray) { audioTrack?.write(data, 0, data.size) }

    // ─── WebSocket (原生 Java Socket) ───

    private fun connectWebSocket() {
        wsThread = Thread {
            try {
                val uri = java.net.URI.create("ws://127.0.0.1:8765")
                val s = java.net.Socket(uri.host, uri.port)
                wsSocket = s; wsInput = s.getInputStream(); wsOutput = s.getOutputStream()
                val key = java.util.Base64.getEncoder().encodeToString("nianan${System.currentTimeMillis()}".toByteArray())
                wsOutput!!.write("GET / HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n".toByteArray())
                wsOutput!!.flush()
                val r = java.io.BufferedReader(java.io.InputStreamReader(wsInput))
                var l: String?; do { l = r.readLine() } while (l != null && l.isNotEmpty())
                wsConnected = true
                val dis = java.io.DataInputStream(wsInput!!)
                while (wsConnected) {
                    try { val op = dis.readByte(); if (op == 0x02.toByte()) { val len = dis.readInt(); if (len in 1..102399) { val d = ByteArray(len); dis.readFully(d); Handler(Looper.getMainLooper()).post { audioTrack?.write(d, 0, d.size) } } } } catch (e: java.io.EOFException) { break } catch (e: Exception) { if (wsConnected) Log.w(TAG, "WS: ${e.message}"); break }
                }
            } catch (e: Exception) { Log.e(TAG, "WS连接失败: ${e.message}") }
            wsConnected = false
        }; wsThread!!.start()
    }

    private fun disconnectWebSocket() { wsConnected = false; try { wsSocket?.close() } catch (e: Exception) {}; wsSocket = null; wsInput = null; wsOutput = null }

    // ─── 通知 ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "念安通话", NotificationManager.IMPORTANCE_LOW).apply { description = "念安语音通话"; setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun showCallNotification() {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("通话中 — 念安").setContentText("蓝牙耳机实时对话").setSmallIcon(android.R.drawable.ic_menu_call).setOngoing(true).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).build())
    }
}
