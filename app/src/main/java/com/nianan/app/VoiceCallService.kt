package com.nianan.app

import android.app.*
import android.os.IBinder
import android.content.Intent

class VoiceCallService : Service() {
    companion object {
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            java.io.File(filesDir, "svc_log.txt").writeText("action=${intent?.action}")
        } catch(_: Exception) {}
        if (ACTION_STOP_CALL == intent?.action) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        try { java.io.File(filesDir, "svc_destroy.txt").writeText("ok") } catch(_: Exception) {}
        super.onDestroy()
    }
}
