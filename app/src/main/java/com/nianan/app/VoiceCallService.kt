package com.nianan.app

import android.app.*
import android.os.IBinder
import android.content.Intent

class VoiceCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
