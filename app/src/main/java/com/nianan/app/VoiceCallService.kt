package com.nianan.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import java.io.*
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class VoiceCallService : Service(), SensorEventListener {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "nc"
        const val ACTION_START_CALL = "com.nianan.app.START_CALL"
        const val WS_URL = "ws://127.0.0.1:8765/ws"
        const val SAMPLE_RATE = 16000
        const val CHUNK_MS = 100
        const val PING_INTERVAL_MS = 30000L
        const val MAX_RECONNECT = 5
        const val HEALTH_URL = "http://127.0.0.1:8642/health"
        const val GUARD_INTERVAL_MS = 10000L
        const val MAX_GUARD_FAILURES = 3
    }

    private var audioRecord: AudioRecord? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scoStarted = false
    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var handler: Handler? = null
    private var wsThread: Thread? = null
    private var wsInput: InputStream? = null
    private var wsOutput: OutputStream? = null
    private var socket: Socket? = null
    private var pingThread: Thread? = null
    private var reconnectCount = 0
    private var isGuardMode = false
    private var guardThread: Thread? = null
    private var guardFailures = 0

    // ─── 传感器采集 ───
    private var sensorManager: SensorManager? = null
    private var wifiManager: WifiManager? = null
    private var locationManager: LocationManager? = null
    private var keyguardManager: android.app.KeyguardManager? = null
    private var lightSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var sensorThread: Thread? = null
    private var lastLightVal = 0f
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f
    private val SENSOR_INTERVAL_MS = 5000L
    private val SENSOR_URL = "http://127.0.0.1:8765/sensor"

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "通话", NotificationManager.IMPORTANCE_LOW)
            ch.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ACTION_START_CALL == intent?.action) {
            // 通话模式
            isGuardMode = false
            stopGuard()
            showNotification()
            startBluetoothSco()
            acquireWakeLock()
            running.set(true)
            reconnectCount = 0
            connectWebSocket()
        } else {
            // 守护模式
            isGuardMode = true
            showGuardNotification()
            running.set(true)
            startGuard()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        stopRecording()
        disconnectWebSocket()
        stopMediaPlayer()
        stopBluetoothSco()
        stopGuard()
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return nb
            .setContentTitle("通话中 — 念安")
            .setContentText("WebSocket实时对话")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true).setContentIntent(pi).build()
    }

    private fun showNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    // ─── WebSocket 连接（含断线重连） ───
    private fun connectWebSocket() {
        wsThread = Thread {
            while (running.get() && reconnectCount <= MAX_RECONNECT) {
                try {
                    socket = Socket("127.0.0.1", 8765)
                    wsInput = BufferedInputStream(socket!!.getInputStream())
                    wsOutput = BufferedOutputStream(socket!!.getOutputStream())

                    // WebSocket 握手
                    val keyBytes = ByteArray(16)
                    (Math.random() * Long.MAX_VALUE).toLong()
                        .let { v -> for (i in 0..7) keyBytes[i] = (v shr (i * 8)).toByte() }
                    val key = java.util.Base64.getEncoder().encodeToString(keyBytes)
                    val handshake = buildString {
                        append("GET /ws HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:8765\r\n")
                        append("Upgrade: websocket\r\n")
                        append("Connection: Upgrade\r\n")
                        append("Sec-WebSocket-Key: $key\r\n")
                        append("Sec-WebSocket-Version: 13\r\n")
                        append("\r\n")
                    }
                    wsOutput!!.write(handshake.toByteArray())
                    wsOutput!!.flush()

                    // 读握手响应
                    val buf = ByteArray(4096)
                    var pos = 0
                    while (pos < buf.size) {
                        val n = wsInput!!.read(buf, pos, 1)
                        if (n < 0) break
                        pos++
                        if (pos >= 4 && buf[pos - 4] == '\r'.code.toByte()
                            && buf[pos - 3] == '\n'.code.toByte()
                            && buf[pos - 2] == '\r'.code.toByte()
                            && buf[pos - 1] == '\n'.code.toByte()
                        ) break
                    }
                    val response = String(buf, 0, pos)
                    if (!response.contains("101")) {
                        android.util.Log.e("nianan-ws", "握手失败: $response")
                        closeSocket()
                        scheduleReconnect()
                        return@Thread
                    }

                    // 连接成功
                    connected.set(true)
                    reconnectCount = 0
                    android.util.Log.i("nianan-ws", "WebSocket连接成功")
                    startPing()
                    startRecording()

                    // 接收循环
                    while (running.get() && connected.get()) {
                        val frame = readFrame()
                        if (frame == null) {
                            // 连接断开
                            android.util.Log.w("nianan-ws", "readFrame返回null，连接断开")
                            break
                        }
                        if (frame.isNotEmpty()) {
                            handler?.post { playAudio(frame) }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("nianan-ws", "连接异常: ${e.message}")
                }

                // 清理当前连接
                stopRecording()
                stopPing()
                closeSocket()
                connected.set(false)

                // 重连
                if (running.get()) {
                    scheduleReconnect()
                }
            }

            // 超过最大重连次数
            if (reconnectCount > MAX_RECONNECT) {
                android.util.Log.w("nianan-ws", "超过最大重连次数($MAX_RECONNECT)，停止")
                handler?.post { stopSelf() }
            }
        }.apply { start() }
    }

    private fun scheduleReconnect() {
        reconnectCount++
        if (reconnectCount > MAX_RECONNECT) return
        val delay = Math.min(1000L shl (reconnectCount - 1), 16000L) // 1s/2s/4s/8s/16s
        android.util.Log.i("nianan-ws", "重连 #$reconnectCount，${delay}ms后")
        Thread.sleep(delay)
    }

    private fun closeSocket() {
        try { wsOutput?.close() } catch (_: Exception) {}
        try { wsInput?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        wsOutput = null
        wsInput = null
        socket = null
    }

    private fun disconnectWebSocket() {
        connected.set(false)
        stopPing()
        closeSocket()
    }

    // ─── 心跳 ───
    private fun startPing() {
        pingThread = Thread {
            while (connected.get() && running.get()) {
                try {
                    Thread.sleep(PING_INTERVAL_MS)
                    if (!connected.get()) break
                    // WebSocket ping帧: FIN=1, opcode=9, mask=0
                    synchronized(wsOutput ?: return@Thread) {
                        val out = wsOutput ?: return@Thread
                        out.write(byteArrayOf(0x89.toByte(), 0x00.toByte()))
                        out.flush()
                    }
                } catch (_: Exception) { break }
            }
        }.apply { start() }
    }

    private fun stopPing() {
        try { pingThread?.interrupt() } catch (_: Exception) {}
        pingThread = null
    }

    // ─── 录音 + WebSocket 发送 ───
    private fun startRecording() {
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize * 2)
        audioRecord?.startRecording()

        val frameBytes = SAMPLE_RATE * 2 * CHUNK_MS / 1000
        val frame = ByteArray(frameBytes)

        Thread {
            while (running.get() && connected.get()) {
                val len = audioRecord?.read(frame, 0, frameBytes) ?: break
                if (len <= 0) break
                try {
                    sendFrame(frame.copyOf(len))
                } catch (_: Exception) { break }
            }
        }.apply { start() }
    }

    private fun stopRecording() {
        audioRecord?.apply { stop(); release() }
        audioRecord = null
    }

    // ─── WebSocket 帧编解码 ───
    private fun sendFrame(data: ByteArray) {
        val out = wsOutput ?: return
        synchronized(out) {
            val header = ByteArray(2)
            header[0] = 0x82.toByte()  // FIN + BINARY
            val len = data.size
            if (len < 126) {
                header[1] = len.toByte()
                out.write(header)
            } else if (len < 65536) {
                header[1] = 126.toByte()
                out.write(header)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            } else {
                // 超过65535用127（8字节扩展长度）
                header[1] = 127.toByte()
                out.write(header)
                for (i in 7 downTo 0) out.write((len shr (i * 8)) and 0xFF)
            }
            out.write(data)
            out.flush()
        }
    }

    private fun readFrame(): ByteArray? {
        val inp = wsInput ?: return null
        try {
            val hdr = ByteArray(2)
            if (inp.read(hdr) < 2) return null
            val opcode = hdr[0].toInt() and 0x0F
            val masked = (hdr[1].toInt() and 0x80) != 0
            var len = hdr[1].toInt() and 0x7F

            when {
                len == 126 -> {
                    val ext = ByteArray(2)
                    if (inp.read(ext) < 2) return null
                    len = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
                }
                len == 127 -> {
                    val ext = ByteArray(8)
                    if (inp.read(ext) < 8) return null
                    len = 0
                    for (i in 0..7) len = (len shl 8) or (ext[i].toInt() and 0xFF)
                }
            }

            // 读mask key（如果masked）
            val maskKey = if (masked) {
                val mk = ByteArray(4)
                if (inp.read(mk) < 4) return null
                mk
            } else null

            // 读payload
            val data = ByteArray(len)
            var pos = 0
            while (pos < len) {
                val n = inp.read(data, pos, len - pos)
                if (n < 0) return null
                pos += n
            }

            // unmask
            if (maskKey != null) {
                for (i in data.indices) data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }

            return when (opcode) {
                0x9 -> { sendPong(data); null }  // ping → pong
                0x2 -> data  // binary
                0x1 -> data  // text (兼容)
                else -> null
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun sendPong(data: ByteArray) {
        val out = wsOutput ?: return
        synchronized(out) {
            try {
                val header = ByteArray(2)
                header[0] = 0x8A.toByte()  // FIN + PONG
                val len = data.size
                if (len < 126) {
                    header[1] = len.toByte()
                    out.write(header)
                }
                out.write(data)
                out.flush()
            } catch (_: Exception) {}
        }
    }

    // ─── MediaPlayer 播放 ───
    private fun playAudio(wav: ByteArray) {
        try {
            stopMediaPlayer()
            val file = File(filesDir, "reply.wav")
            file.writeBytes(wav)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                setOnPreparedListener { start() }
                setOnCompletionListener { mp -> mp.release(); mediaPlayer = null }
                prepareAsync()
            }
        } catch (_: Exception) {}
    }

    private fun stopMediaPlayer() {
        try { mediaPlayer?.apply { stop(); release() } } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ─── 蓝牙 ───
    private fun startBluetoothSco() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (am.isBluetoothScoAvailableOffCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                scoStarted = true
            }
        } catch (_: Exception) {}
    }

    private fun stopBluetoothSco() {
        if (scoStarted) try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.stopBluetoothSco()
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nianan:call")
        wakeLock?.acquire(3600_000)
    }

    // ─── 守护模式（Gateway心跳检测+自动拉起）───

    private fun showGuardNotification() {
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("念安守护中")
            .setContentText("Hermes Gateway 存活监控")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun startGuard() {
        startSensorCollection()
        guardThread = Thread {
            android.util.Log.i("nianan-guard", "守护循环启动")
            while (running.get() && isGuardMode) {
                try {
                    Thread.sleep(GUARD_INTERVAL_MS)
                    if (!running.get() || !isGuardMode) break
                    val alive = checkHealth()
                    if (alive) {
                        guardFailures = 0
                    } else {
                        guardFailures++
                        android.util.Log.w("nianan-guard", "Gateway无响应 ($guardFailures/$MAX_GUARD_FAILURES)")
                        if (guardFailures >= MAX_GUARD_FAILURES) {
                            restartHermes()
                            guardFailures = 0
                        }
                    }
                } catch (e: InterruptedException) { break }
                catch (e: Exception) {
                    android.util.Log.e("nianan-guard", "异常: ${e.message}")
                }
            }
        }.apply { start() }
    }

    private fun stopGuard() {
        stopSensorCollection()
        guardThread?.interrupt()
        guardThread = null
        isGuardMode = false
    }

    private fun checkHealth(): Boolean {
        return try {
            val conn = java.net.URL(HEALTH_URL).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) { false }
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

    // ─── 传感器采集 ───

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager?.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> lastLightVal = event.values[0]
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelX = event.values[0]
                lastAccelY = event.values[1]
                lastAccelZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startSensorCollection() {
        initSensors()
        sensorThread = Thread {
            while (running.get()) {
                try {
                    Thread.sleep(SENSOR_INTERVAL_MS)
                    if (!running.get()) break
                    val snap = collectSensorSnapshot()
                    pushSnapshot(snap)
                } catch (e: InterruptedException) { break }
                catch (e: Exception) {
                    android.util.Log.e("nianan-sensor", "采集异常: ${e.message}")
                }
            }
        }.apply { start() }
    }

    private fun stopSensorCollection() {
        sensorThread?.interrupt()
        sensorThread = null
    }

    private fun collectSensorSnapshot(): Map<String, Any> {
        val snap = mutableMapOf<String, Any>()
        snap["ts"] = System.currentTimeMillis() / 1000

        // 光线
        snap["light"] = lastLightVal.toDouble()

        // 加速度
        snap["accel_x"] = lastAccelX.toDouble()
        snap["accel_y"] = lastAccelY.toDouble()
        snap["accel_z"] = lastAccelZ.toDouble()

        // 电池
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            snap["battery"] = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            snap["charging"] = if (bm.isCharging) 1 else 0
        } catch (_: Exception) {
            snap["battery"] = -1
            snap["charging"] = 0
        }

        // WiFi
        try {
            val wi = wifiManager?.connectionInfo
            snap["wifi_ssid"] = wi?.ssid?.trim('"') ?: ""
        } catch (_: Exception) {
            snap["wifi_ssid"] = ""
        }

        // 锁屏
        try {
            snap["keyguard"] = if (keyguardManager?.isDeviceLocked == true) 1 else 0
        } catch (_: Exception) {
            snap["keyguard"] = -1
        }

        return snap
    }

    private fun pushSnapshot(snap: Map<String, Any>) {
        try {
            val json = StringBuilder()
            json.append("{")
            snap.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) json.append(",")
                json.append("\"$k\":")
                when (v) {
                    is String -> json.append("\"${v.replace("\"", "\\\"")}\"")
                    is Number -> json.append(v.toString())
                    else -> json.append("\"$v\"")
                }
            }
            json.append("}")
            val conn = URL(SENSOR_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.write(json.toString().toByteArray())
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) {}
    }
}
