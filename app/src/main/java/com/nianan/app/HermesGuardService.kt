package com.nianan.app

import android.app.*
import android.content.Intent
import android.os.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hermes 外部守护服务 — 独立Android进程，不受Termux连坐影响。
 *
 * 职责:
 *  1. 持久前台通知"念安守护中" — 系统OOM killer绕开带通知的进程
 *  2. 每10秒 HTTP GET http://127.0.0.1:8642/health — 检测Hermes Gateway存活
 *  3. 连续3次不通 → Runtime.exec("hermes gateway run") 自动拉起
 *  4. START_STICKY — 被杀后系统自动重启Service
 */
class HermesGuardService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "ng"
        const val HEALTH_URL = "http://127.0.0.1:8642/health"
        const val CHECK_INTERVAL_MS = 10000L
        const val MAX_FAILURES = 3
    }

    private val running = AtomicBoolean(false)
    private var guardThread: Thread? = null
    private var failureCount = 0

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "守护", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                description = "念安Hermes守护服务"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showNotification()
        running.set(true)
        failureCount = 0
        startGuardLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        guardThread?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return nb
            .setContentTitle("念安守护中")
            .setContentText("Hermes Gateway 存活监控")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun showNotification() {
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun startGuardLoop() {
        guardThread = Thread {
            android.util.Log.i("nianan-guard", "守护循环启动")
            while (running.get()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    if (!running.get()) break

                    val alive = checkHealth()
                    if (alive) {
                        failureCount = 0
                    } else {
                        failureCount++
                        android.util.Log.w("nianan-guard", "Gateway无响应 ($failureCount/$MAX_FAILURES)")
                        if (failureCount >= MAX_FAILURES) {
                            restartHermes()
                            failureCount = 0
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    android.util.Log.e("nianan-guard", "异常: ${e.message}")
                }
            }
            android.util.Log.i("nianan-guard", "守护循环结束")
        }.apply { start() }
    }

    private fun checkHealth(): Boolean {
        return try {
            val conn = URL(HEALTH_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }

    private fun restartHermes() {
        android.util.Log.w("nianan-guard", "重启Hermes Gateway")
        try {
            Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "hermes gateway run"),
                arrayOf(
                    "HOME=/data/data/com.termux/files/home",
                    "PATH=/data/data/com.termux/files/usr/bin:/system/bin"
                ),
                null
            )
        } catch (e: Exception) {
            android.util.Log.e("nianan-guard", "重启失败: ${e.message}")
        }
    }
}
