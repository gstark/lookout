package app.lookout.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import app.lookout.R
import app.lookout.core.LookoutCore
import app.lookout.core.Prompt
import app.lookout.core.Thermal
import app.lookout.server.Lan
import app.lookout.server.LookoutServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The foreground service (type camera). It owns the camera lifecycle, the HTTP/MCP server, the
 * wake and Wi-Fi locks and the mDNS advertisement. The locks are held only while there is work.
 */
class LookoutService : LifecycleService() {
    private val rt get() = runtime
    private var scope: CoroutineScope? = null
    private var server: LookoutServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var lastIp: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_PAUSE -> rt.core.setPaused(true)
            ACTION_RESUME -> rt.core.setPaused(false)
            else -> if (scope == null) start()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        createChannels()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIFY_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        else startForeground(NOTIFY_ID, notification())
        val core = rt.core
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        rt.camera.attach(this)
        rt.camera.onLost = { reason -> core.session.onCameraLost(reason) }
        core.start(s)

        val srv = LookoutServer(core, rt.tokens, rt.baseUrl, core.settings.value.port, allowLocal = ::isWifiOrLoopback).also { srv ->
            s.launch(Dispatchers.IO) {
                runCatching { srv.start(wait = false) }.onFailure { e ->
                    core.events.add("server", buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) })
                }
            }
        }
        server = srv

        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lookout:serving").apply { setReferenceCounted(false) }
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "lookout:wifi").apply { setReferenceCounted(false) }
        // The locks stop the phone from sleeping, so hold them only while the camera is open or a
        // request is in progress. When idle, the phone can sleep, and an incoming request wakes it.
        s.launch {
            combine(core.session.open, srv.activeCalls) { open, calls -> open || calls > 0 }
                .distinctUntilChanged()
                .collect(::holdLocks)
        }
        thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            core.events.add("thermal", buildJsonObject { put("status", Thermal.entries.getOrElse(status) { Thermal.SHUTDOWN }.name.lowercase()) })
            updateNotification()
        }.also { power.addThermalStatusListener(ContextCompat.getMainExecutor(this), it) }

        watchNetwork()
        registerNsd()
        s.launch { combine(core.paused, core.session.open) { _, _ -> }.collect { updateNotification() } }
        s.launch { core.human.current.collect { p -> promptNotification(p) } }
        rt.running.value = true
    }

    override fun onDestroy() {
        rt.running.value = false
        promptNotification(null)
        server?.stop()
        server = null
        scope?.cancel()
        scope = null
        rt.camera.reset()
        rt.camera.attach(null)
        if (rt.usingReplay) CoroutineScope(Dispatchers.Default).launch { rt.core.stop() }
        holdLocks(false)
        thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        unregisterNsd()
        super.onDestroy()
    }

    @Suppress("WakelockTimeout") // Released when the camera closes and the last request ends.
    private fun holdLocks(held: Boolean) {
        if (held) {
            wakeLock?.acquire()
            wifiLock?.acquire()
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun isWifiOrLoopback(local: String): Boolean {
        if (local == "localhost" || local.startsWith("127.") || local == "::1" || local == "0:0:0:0:0:0:0:1") return true
        val wifi = rt.device.wifiInfo()?.first ?: return false
        return local.removePrefix("/").removePrefix("::ffff:") == wifi && Lan.isPrivate(wifi)
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        lastIp = rt.device.wifiInfo()?.first
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = check()
            override fun onLost(network: Network) = check()
            override fun onAvailable(network: Network) = check()

            private fun check() {
                val ip = rt.device.wifiInfo()?.first
                if (ip != lastIp) {
                    lastIp = ip
                    rt.core.events.add("network", buildJsonObject { put("ip", ip ?: "none") })
                    unregisterNsd()
                    registerNsd()
                    updateNotification()
                }
            }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        networkCallback = cb
    }

    private fun registerNsd() {
        if (rt.device.wifiInfo() == null) return
        val nsd = getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply {
            serviceName = rt.core.deviceName
            serviceType = "_lookout._tcp"
            port = rt.core.settings.value.port
            setAttribute("version", LookoutCore.SERVER_VERSION)
            setAttribute("device", rt.core.deviceName)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                rt.core.events.add("nsd", buildJsonObject { put("error", errorCode) })
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onSuccess { nsdListener = listener }
    }

    private fun unregisterNsd() {
        nsdListener?.let { l -> runCatching { getSystemService(NsdManager::class.java).unregisterService(l) } }
        nsdListener = null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Camera serving", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROMPT, "Agent requests", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun updateNotification() {
        if (scope == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, notification())
    }

    private fun notification(): Notification {
        val core = rt.core
        val paused = core.paused.value
        val thermal = rt.device.read().thermal
        val text = buildString {
            append(if (paused) "Paused · " else "Serving · ")
            append(rt.baseUrl.get())
            if (rt.usingReplay) append(" · replay")
            if (thermal >= Thermal.MODERATE) append(" · thermal ${thermal.name.lowercase()}")
        }
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Lookout camera is on")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(activityIntent())
            .addAction(0, if (paused) "Resume" else "Pause", serviceIntent(if (paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Stop", serviceIntent(ACTION_STOP, 2))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Brings the prompt card to the screen, even when the phone is locked or the app is in the background. */
    private fun promptNotification(p: Prompt?) {
        val nm = getSystemService(NotificationManager::class.java)
        if (p == null || rt.uiVisible) {
            nm.cancel(PROMPT_ID)
            return
        }
        val n = NotificationCompat.Builder(this, CHANNEL_PROMPT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (p.guide) "The agent needs a shot" else "The agent asks")
            .setContentText(p.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(activityIntent(), true)
            .setContentIntent(activityIntent())
            .setAutoCancel(true)
            .build()
        nm.notify(PROMPT_ID, n)
    }

    private fun activityIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, LookoutService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "app.lookout.START"
        const val ACTION_STOP = "app.lookout.STOP"
        const val ACTION_PAUSE = "app.lookout.PAUSE"
        const val ACTION_RESUME = "app.lookout.RESUME"
        private const val CHANNEL_SERVICE = "serving"
        private const val CHANNEL_PROMPT = "prompts"
        private const val NOTIFY_ID = 1
        private const val PROMPT_ID = 2

        fun send(context: Context, action: String) {
            val i = Intent(context, LookoutService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(context, i) else context.startService(i)
        }
    }
}
