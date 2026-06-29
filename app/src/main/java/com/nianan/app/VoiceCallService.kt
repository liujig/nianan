package com.nianan.app

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
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
import okhttp3.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 念安语音通话服务 — ForegroundService
 * 管理 AudioRecord(录音) + AudioTrack(播放) + WebSocket(传输)
 * 配合 ConnectionService 实现息屏蓝牙实时对话
 */
class VoiceCallService : Service() {

    companion object {
        const val TAG = "NianAnVoice"
        const val CHANNEL_ID = "nianan_call"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_CALL = "com.nianan.app.START_CALL"
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"

        // 音频参数
        const val SAMPLE_RATE_IN = 16000   // 录音采样率 (Paraformer)
        const val SAMPLE_RATE_OUT = 24000  // 播放采样率 (TTS)
        const val CHANNELS = 1
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // WebSocket 地址
        const val WS_URL = "ws://127.0.0.1:8765"
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var recording = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var scoStarted = false

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

    // ─── 通话控制 ───

    private fun startCall() {
        Log.i(TAG, "开始通话")
        showCallNotification()

        // 启动蓝牙 SCO（通话音频通道）
        startBluetoothSco()

        // 获取 WakeLock 防止 CPU 休眠
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "nianan:voicecall"
        ).apply { acquire(3600_000) } // 最长1小时

        // 连接 WebSocket
        connectWebSocket()

        // 延迟启动录音（等 WebSocket 连上）
        android.os.Handler(Looper.getMainLooper()).postDelayed({
            startRecording()
            startPlayback()
        }, 500)
    }

    private fun stopCall() {
        Log.i(TAG, "结束通话")
        recording = false
        stopRecording()
        stopPlayback()
        webSocket?.close(1000, "用户结束通话")
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
            Log.w(TAG, "蓝牙 SCO 启动失败: ${e.message}")
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
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING,
            bufferSize * 2
        )
        recording = true
        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(bufferSize)
            while (recording) {
                val len = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (len > 0) {
                    webSocket?.send(okio.ByteString.of(buffer.copyOf(len)))
                }
            }
        }.start()
        Log.i(TAG, "录音已启动 | buffer=$bufferSize")
    }

    private fun stopRecording() {
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
        Log.i(TAG, "录音已停止")
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
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audioTrack?.play()
        Log.i(TAG, "播放器已就绪 | buffer=$bufferSize")
    }

    private fun stopPlayback() {
        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null
    }

    fun playAudio(data: ByteArray) {
        audioTrack?.write(data, 0, data.size)
    }

    // ─── WebSocket ───

    private fun connectWebSocket() {
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(WS_URL).build()
        webSocket = okHttpClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket 已连接")
                // 发送握手包
                ws.send("{\"type\":\"hello\",\"role\":\"android_client\"}")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // JSON 文本消息（状态/文字）
                Log.d(TAG, "WS text: ${text.take(100)}")
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                // 二进制音频数据 — 直接播放
                playAudio(bytes.toByteArray())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 断开: ${t.message}")
                // 3秒后重连
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    if (recording) connectWebSocket()
                }, 3000)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket 已关闭: $reason")
            }
        })
    }

    // ─── 通知栏 ───

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "念安通话",
                NotificationManager.IMPORTANCE_LOW
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
