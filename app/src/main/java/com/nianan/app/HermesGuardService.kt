package com.nianan.app

import android.app.*
import android.content.ComponentCallbacks2
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
 *  4. onTrimMemory → 发HTTP信号让cron_prefetch降负载
 */
class HermesGuardService : Service() {

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "ng"
        const val HEALTH_URL = "http://127.0.0.1:8642/health"
        const val TRIM_URL = "http://127.0.0.1:8642/trim"
        const val CHECK_INTERVAL_MS = 10000L  // 10秒检测
        const val MAX_FAILURES = 3             // 连续失败阈值
        const val RESTART_COMMAND = "hermes gateway run"
    }

    private val running = AtomicBoolean(false)
    private var guardThread: Thread? = null
    private var failureCount = 0
    private var handler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
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
        return START_STICKY  // 被杀后系统自动重启Service
    }

    override fun onDestroy() {
        running.set(false)
        guardThread?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 系统内存紧张时回调 — 通知Termux降负载
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                signalTrim()  // 紧急 → 告诉cron_prefetch立刻降级
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                // 中等压力，不紧急
            }
        }
    }

    // ─── 前台通知 — 后台权重强化 ───

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
        val notif = buildNotification()
        startForeground(NOTIFICATION_ID, notif)
    }

    // ─── 守护循环 ───

    private fun startGuardLoop() {
        guardThread = Thread {
            android.util.Log.i("nianan-guard", "守护循环启动，检测间隔${CHECK_INTERVAL_MS}ms")
            while (running.get()) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    if (!running.get()) break

                    val alive = checkHealth()
                    if (alive) {
                        if (failureCount > 0) {
                            android.util.Log.i("nianan-guard", "Gateway恢复 ($failureCount 次失败后)")
                        }
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
                    android.util.Log.e("nianan-guard", "守护循环异常: ${e.message}")
                }
            }
            android.util.Log.i("nianan-guard", "守护循环结束")
        }.apply { start() }
    }

    /**
     * HTTP GET health endpoint — 超时3秒
     */
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

    /**
     * 通过Runtime.exec在Termux环境执行命令重启Hermes
     */
    private fun restartHermes() {
        android.util.Log.w("nianan-guard", "执行重启: $RESTART_COMMAND")
        try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", RESTART_COMMAND),
                arrayOf(
                    "HOME=/data/data/com.termux/files/home",
                    "PATH=/data/data/com.termux/files/usr/bin:/system/bin"
                ),
                null
            )
            // 不等待，让hermes后台运行
            android.util.Log.i("nianan-guard", "hermes gateway 启动中 PID=${proc.pid()}")
        } catch (e: Exception) {
            android.util.Log.e("nianan-guard", "重启失败: ${e.message}")
        }
    }

    /**
     * 通知voice_server/cron_prefetch降低负载
     */
    private fun signalTrim() {
        try {
            val conn = URL(TRIM_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.write("trim".toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
