package com.nianan.app

import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * 念安通话服务 — 注册为系统电话服务，实现息屏保活
 * 豆包同款方案：ConnectionService → 系统视为"通话中" → 不杀进程
 */
class CallConnectionService : ConnectionService() {

    companion object {
        const val TAG = "NianAnCall"
        var currentConnection: CallConnection? = null
        var callActive = false
    }

    override fun onCreateConnectionService() {
        super.onCreateConnectionService()
        Log.i(TAG, "ConnectionService 已注册")
    }

    override fun onCreateOutgoingConnection(
        phoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "创建去电连接")
        val conn = CallConnection(applicationContext)
        conn.setInitializing()
        currentConnection = conn
        return conn
    }

    override fun onCreateIncomingConnection(
        phoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        // 念安不会主动来电，但保留接口
        return onCreateOutgoingConnection(phoneAccount, request)
    }

    override fun onConnectionServiceFocusLost() {
        // 通话焦点丢失 — 但不主动挂断
        Log.w(TAG, "通话焦点丢失")
    }
}
