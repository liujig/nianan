package com.nianan.app

import android.app.*
import android.content.Intent
import android.os.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class VoiceCallService : Service() {

    companion object {
        const val ACTION_START_CALL = "com.nianan.app.START_CALL"
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("nc", "通话", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "nc")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val n = nb
            .setContentTitle("通话中")
            .setContentText("念安")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()

        startForeground(1001, n)

        // HTTP心跳
        Thread {
            try {
                val f = File("/storage/emulated/0/nianan_call_test.txt")
                f.writeText("started ${System.currentTimeMillis()}")
                val c = URL("http://127.0.0.1:8765/status").openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.requestMethod = "GET"
                val code = c.responseCode; c.disconnect()
                File("/storage/emulated/0/nianan_http_test.txt").writeText("HTTP $code")
            } catch(e: Exception) {
                File("/storage/emulated/0/nianan_http_test.txt").writeText("FAIL: ${e.message}")
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null
}
