package com.nianan.app

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import java.io.*
import java.net.URI
import javax.net.ssl.SSLContext
import java.util.concurrent.*

class VoiceCallService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nc"
        const val WS_URL = "ws://127.0.0.1:8765/ws"
        const val SAMPLE_RATE = 16000
        const val CHUNK_MS = 100  // 100ms per frame for low latency
    }

    private var audioRecord: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scoStarted = false
    private var recording = false
    private var handler: Handler? = null
    private var wsThread: Thread? = null
    private var wsInput: InputStream? = null
    private var wsOutput: OutputStream? = null
    private var socket: java.net.Socket? = null

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
        connectWebSocket()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recording = false
        stopRecording()
        disconnectWebSocket()
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
            .setContentText("WebSocket实时对话")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true).setContentIntent(pi).build())
    }

    // ─── WebSocket 连接 ───
    private fun connectWebSocket() {
        wsThread = Thread {
            try {
                socket = java.net.Socket("127.0.0.1", 8765)
                wsInput = BufferedInputStream(socket!!.getInputStream())
                wsOutput = BufferedOutputStream(socket!!.getOutputStream())

                // WebSocket 握手
                val key = java.util.Base64.getEncoder().encodeToString(
                    "nianan-ws-key-001".toByteArray())
                val handshake = buildString {
                    append("GET /ws HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:8765\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("\r\n")
                }
                wsOutput!!.write(handshake.toByteArray())
                wsOutput!!.flush()

                // 读握手响应
                val buf = ByteArray(4096)
                var pos = 0
                while (pos < buf.size) {
                    val n = wsInput!!.read(buf, pos, 1)
                    if (n < 0) break
                    pos++
                    if (pos >= 4 && buf[pos-4] == '\r'.code.toByte()
                        && buf[pos-3] == '\n'.code.toByte()
                        && buf[pos-2] == '\r'.code.toByte()
                        && buf[pos-1] == '\n'.code.toByte()) break
                }
                val response = String(buf, 0, pos)
                if (!response.contains("101")) {
                    android.util.Log.e("nianan-ws", "握手失败: $response")
                    stopSelf()
                    return@Thread
                }

                // 启动录音+发送
                startRecording()

                // 接收回复
                while (recording || socket!!.isConnected) {
                    val frame = readFrame()
                    if (frame != null && frame.isNotEmpty()) {
                        handler?.post { playAudio(frame) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("nianan-ws", "连接失败", e)
                stopSelf()
            }
        }.start()
    }

    private fun disconnectWebSocket() {
        try { wsOutput?.close() } catch (_: Exception) {}
        try { wsInput?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    // ─── 录音 + WebSocket 发送 ───
    private fun startRecording() {
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        recording = true
        audioRecord?.startRecording()

        val frameBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000  // ~3200 bytes
        val frame = ByteArray(frameBytes)

        Thread {
            while (recording) {
                val len = audioRecord?.read(frame, 0, frameBytes) ?: break
                if (len <= 0) break
                try {
                    sendFrame(frame.copyOf(len))
                } catch (_: Exception) { break }
            }
        }.start()
    }

    private fun stopRecording() {
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    // ─── WebSocket 帧读写 ───
    private fun sendFrame(data: ByteArray) {
        val out = wsOutput ?: return
        synchronized(out) {
            // Binary frame: FIN=1, opcode=2, mask=0, no mask key
            val header = ByteArray(2)
            header[0] = 0x82.toByte()  // FIN + BINARY
            val len = data.size
            if (len < 126) {
                header[1] = len.toByte()
                out.write(header)
            } else if (len < 65536) {
                header[1] = 126.toByte()
                out.write(header)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            out.write(data)
            out.flush()
        }
    }

    private fun readFrame(): ByteArray? {
        val inp = wsInput ?: return null
        // 读2字节头
        val hdr = ByteArray(2)
        if (inp.read(hdr) < 2) return null
        val opcode = hdr[0].toInt() and 0x0F
        var len = hdr[1].toInt() and 0x7F
        if (len == 126) {
            val ext = ByteArray(2)
            if (inp.read(ext) < 2) return null
            len = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        }
        val data = ByteArray(len)
        var pos = 0
        while (pos < len) {
            val n = inp.read(data, pos, len - pos)
            if (n < 0) return null
            pos += n
        }
        return if (opcode == 2) data else null  // binary only
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
