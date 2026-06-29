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
        const val VOICE_URL = "http://127.0.0.1:8765/voice"
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "念安通话", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "念安语音通话"; setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> startCall()
            ACTION_STOP_CALL -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCall() {
        // 诊断
        try { File("/storage/emulated/0/nianan_call_test.txt").writeText("started ${System.currentTimeMillis()}") } catch(_: Exception) {}

        // 通知
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

        // HTTP心跳测试
        Thread {
            try {
                val conn = URL("http://127.0.0.1:8765/status").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000; conn.requestMethod = "GET"
                val code = conn.responseCode; conn.disconnect()
                File("/storage/emulated/0/nianan_http_test.txt").writeText("HTTP → $code")
            } catch(e: Exception) {
                File("/storage/emulated/0/nianan_http_test.txt").writeText("FAIL: ${e.message}")
            }
        }.start()
    }
}
