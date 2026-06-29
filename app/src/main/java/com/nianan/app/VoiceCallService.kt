package com.nianan.app

import android.app.*
import android.os.IBinder
import android.content.Intent
import android.os.Build

class VoiceCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("nc", "通话", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "nc")
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val n = nb.setContentTitle("通话中").setContentText("念安")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true).setContentIntent(pi).build()
        
        startForeground(1001, n)
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
