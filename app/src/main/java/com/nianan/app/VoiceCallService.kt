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

/**
 * 念安语音通话服务 — ForegroundService
 * WebSocket 连接用独立线程池实现（不依赖 OkHttp，避免编译依赖问题）
 */
class VoiceCallService : Service() {

    companion object {
        const val TAG = "NianAnVoice"
        const val CHANNEL_ID = "nianan_call"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CALL = "com.nianan.app.START_CALL"
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000
        const val CHANNELS = 1
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val WS_URL = "ws://127.0.0.1:8765"
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
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "nianan:voicecall"
        ).apply { acquire(3600_000) }

        connectWebSocket()

        Handler(Looper.getMainLooper()).postDelayed({
            startRecording()
            startPlayback()
        }, 500)
    }

    private fun stopCall() {
        Log.i(TAG, "结束通话")
        recording = false
        stopRecording()
        stopPlayback()
        disconnectWebSocket()
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
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                scoStarted = true
                Log.i(TAG, "蓝牙 SCO 已启动")
            }
        } catch (e: Exception) {
            Log.w(TAG, "蓝牙 SCO 失败: ${e.message}")
        }
    }

    private fun stopBluetoothSco() {
        if (scoStarted) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.stopBluetoothSco()
                am.mode = AudioManager.MODE_NORMAL
                scoStarted = false
            } catch (e: Exception) {}
        }
    }

    // ─── 录音 ───

    private fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, ENCODING
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO,
            ENCODING, bufferSize * 2
        )
        recording = true
        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val len = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (len > 0 && wsConnected && wsOutput != null) {
                    try {
                        // 发送音频帧: 4字节长度头 + 数据
                        val header = java.nio.ByteBuffer.allocate(4).putInt(len).array()
                        wsOutput!!.write(header)
                        wsOutput!!.write(buffer, 0, len)
                        wsOutput!!.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "发送音频失败: ${e.message}")
                    }
                }
            }
        }.start()
        Log.i(TAG, "录音已启动 | buffer=$bufferSize")
    }

    private fun stopRecording() {
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    // ─── 播放 ───

    private fun startPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, ENCODING
        )
        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_OUT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(ENCODING)
                .build(),
            bufferSize, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
        Log.i(TAG, "播放器已就绪")
    }

    private fun stopPlayback() {
        audioTrack?.apply { stop(); release() }
        audioTrack = null
    }

    // ─── WebSocket（原生 Java Socket 实现，不依赖 OkHttp） ───

    private fun connectWebSocket() {
        wsThread = Thread {
            try {
                val uri = java.net.URI.create(WS_URL)
                val socket = java.net.Socket(uri.host, uri.port)
                wsSocket = socket
                wsInput = socket.getInputStream()
                wsOutput = socket.getOutputStream()

                // WebSocket 握手
                val key = java.util.Base64.getEncoder().encodeToString(
                    "nianan-voice-${System.currentTimeMillis()}".toByteArray()
                )
                val handshake = buildString {
                    append("GET / HTTP/1.1\r\n")
                    append("Host: ${uri.host}:${uri.port}\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                wsOutput!!.write(handshake.toByteArray())
                wsOutput!!.flush()

                // 读握手响应
                val reader = java.io.BufferedReader(java.io.InputStreamReader(wsInput))
                var line: String?
                do { line = reader.readLine() } while (line != null && line.isNotEmpty())

                wsConnected = true
                Log.i(TAG, "WebSocket 已连接（原生Socket）")

                // 发送 hello
                sendWsText("{\"type\":\"hello\",\"role\":\"android_client\"}")

                // 读音频数据
                val buf = java.io.DataInputStream(wsInput!!)
                while (wsConnected) {
                    try {
                        // 读取帧: opcode(1) + len(4) + data
                        val opcode = buf.readByte()
                        if (opcode == 0x02.toByte()) {
                            // 二进制帧
                            val len = buf.readInt()
                            if (len > 0 && len < 102400) {
                                val audio = ByteArray(len)
                                buf.readFully(audio)
                                Handler(Looper.getMainLooper()).post {
                                    audioTrack?.write(audio, 0, audio.size)
                                }
                            }
                        }
                    } catch (e: java.io.EOFException) {
                        break
                    } catch (e: Exception) {
                        if (wsConnected) Log.w(TAG, "WS读异常: ${e.message}")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebSocket连接失败: ${e.message}")
            }
            wsConnected = false
        }
        wsThread!!.start()
    }

    private fun sendWsText(msg: String) {
        try {
            val payload = msg.toByteArray(Charsets.UTF_8)
            val len = payload.size
            val frame = java.io.ByteArrayOutputStream()
            frame.write(0x81) // FIN + text opcode
            if (len < 126) {
                frame.write(len)
            } else if (len < 65536) {
                frame.write(126)
                frame.write(java.nio.ByteBuffer.allocate(2).putShort(len.toShort()).array())
            }
            frame.write(payload)
            wsOutput?.write(frame.toByteArray())
            wsOutput?.flush()
        } catch (e: Exception) {}
    }

    private fun disconnectWebSocket() {
        wsConnected = false
        try { wsSocket?.close() } catch (e: Exception) {}
        wsSocket = null; wsInput = null; wsOutput = null
    }

    // ─── 通知栏 ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "念安通话", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "念安语音通话状态"
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun showCallNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("通话中 — 念安")
            .setContentText("蓝牙耳机实时对话")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }
}
