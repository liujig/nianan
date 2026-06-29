package com.nianan.app

import android.app.*
import android.os.IBinder
import android.content.Intent
import java.io.File

class VoiceCallService : Service() {
    companion object {
        const val ACTION_STOP_CALL = "com.nianan.app.STOP_CALL"
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            File("/storage/emulated/0/nianan_svc.txt").writeText("onStartCommand action=${intent?.action}")
            if (ACTION_STOP_CALL == intent?.action) {
                stopSelf()
            }
        } catch(e: Exception) {}
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        try { File("/storage/emulated/0/nianan_svc_destroy.txt").writeText("destroyed") } catch(_: Exception) {}
        super.onDestroy()
    }
}
