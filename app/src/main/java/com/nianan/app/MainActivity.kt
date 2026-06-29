package com.nianan.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST = 1001
    private val PERMISSION_REQUEST = 1002
    private lateinit var wv: WebView
    private var phoneAccountHandle: PhoneAccountHandle? = null
    private var callActive = false

    companion object {
        const val TAG = "NianAnMain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注册电话账户（用于系统通话框架）
        registerPhoneAccount()

        wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowContentAccess = true
                allowFileAccess = true
                setGeolocationEnabled(true)
                setGeolocationDatabasePath(filesDir.absolutePath + "/geolocation")
            }

            webViewClient = WebViewClient()

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    request?.grant(request.resources)
                }
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    callback?.invoke(origin, true, false)
                }
                override fun onShowFileChooser(
                    webView: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    val chooser = Intent.createChooser(contentIntent, "选择文件")
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST)
                    return true
                }
            }

            // 暴露通话接口给前端
            addJavascriptInterface(CallBridge(), "nianan")

            loadUrl("http://127.0.0.1:9191")
        }

        setContentView(wv)
        requestRuntimePermissions()
    }

    // ─── JavaScript 桥接 ───

    inner class CallBridge {
        @JavascriptInterface
        fun startCall() {
            runOnUiThread {
                if (!callActive) placeCall()
            }
        }

        @JavascriptInterface
        fun endCall() {
            runOnUiThread {
                CallConnectionService.currentConnection?.endCall()
                callActive = false
            }
        }

        @JavascriptInterface
        fun isCallActive(): Boolean = callActive
    }

    // ─── 电话账户注册 ───

    private fun registerPhoneAccount() {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        phoneAccountHandle = PhoneAccountHandle(
            ComponentName(this, CallConnectionService::class.java),
            "nianan"
        )

        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "念安")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
        Log.i(TAG, "PhoneAccount 已注册")
    }

    private fun placeCall() {
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val uri = Uri.fromParts("tel", "0000", null)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
        }
        try {
            telecomManager.placeCall(uri, extras)
            callActive = true
            Log.i(TAG, "去电已发起")
        } catch (e: Exception) {
            Log.e(TAG, "发起通话失败: ${e.message}")
            // 降级：直接启动 VoiceCallService（不走 Telecom）
            val intent = Intent(this, VoiceCallService::class.java).apply {
                action = VoiceCallService.ACTION_START_CALL
            }
            startForegroundService(intent)
            callActive = true
        }
    }

    // ─── 权限 ───

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST) return
        val results: Array<Uri>? = if (resultCode == Activity.RESULT_OK && data?.data != null) {
            arrayOf(data.data!!)
        } else {
            null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (phoneAccountHandle != null) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.unregisterPhoneAccount(phoneAccountHandle!!)
        }
    }
}
