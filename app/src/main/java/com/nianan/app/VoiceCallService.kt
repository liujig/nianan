package com.nianan.app

import android.app.*
import android.content.Intent
import android.os.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class VoiceCallService : Service() {

    override fun onCreate() {
        super.onCreate()
        try {
            File("/storage/emulated/0/nianan_svc_oncreate.txt").writeText("onCreate ok")
        } catch(_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            File("/storage/emulated/0/nianan_svc_start.txt").writeText("onStartCommand ok")
            
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
                @Suppress("DEPRECATION") Notification.Builder(this)
            }
            startForeground(1001, nb.setContentTitle("通话中").setContentText("念安")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true).setContentIntent(pi).build())
            
            File("/storage/emulated/0/nianan_svc_foreground.txt").writeText("startForeground ok")
            
            Thread {
                try {
                    val c = URL("http://127.0.0.1:8765/status").openConnection() as HttpURLConnection
                    c.connectTimeout = 5000; c.requestMethod = "GET"
                    File("/storage/emulated/0/nianan_http_test.txt").writeText("HTTP ${c.responseCode}")
                    c.disconnect()
                } catch(e: Exception) {
                    File("/storage/emulated/0/nianan_http_test.txt").writeText("FAIL: ${e.message}")
                }
            }.start()
        } catch(e: Exception) {
            File("/storage/emulated/0/nianan_svc_crash.txt").writeText("${e.javaClass.name}: ${e.message}")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null
}
