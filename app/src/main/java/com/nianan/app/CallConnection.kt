package com.nianan.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log

/**
 * 念安通话连接 — 代表一通"电话"
 * 激活后系统进入通话状态，息屏不杀、蓝牙音频自动路由
 */
class CallConnection(private val ctx: Context) : Connection() {

    companion object {
        const val TAG = "NianAnCall"
    }

    init {
        // 启用蓝牙音频路由
        audioModeIsVoip = true
        setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        connectionProperties = PROPERTY_SELF_MANAGED
    }

    fun activateCall() {
        setActive()
        CallConnectionService.callActive = true
        Log.i(TAG, "通话已激活 | 息屏保活生效")

        // 启动音频服务
        val intent = Intent(ctx, VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_START_CALL
        }
        ctx.startForegroundService(intent)
    }

    fun endCall() {
        CallConnectionService.callActive = false
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()

        // 停止音频服务
        val intent = Intent(ctx, VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_STOP_CALL
        }
        ctx.startService(intent)

        Log.i(TAG, "通话已结束")
    }
}
