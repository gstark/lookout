package app.lookout.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.lookout.core.DeviceState
import app.lookout.core.DeviceStatus
import app.lookout.core.KeyValueStore
import app.lookout.core.Speaker
import app.lookout.core.Thermal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import app.lookout.server.Lan
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** DataStore with an in-memory cache, so reads are synchronous. Writes run in order on one thread. */
class DataStoreKeyValueStore(private val store: DataStore<Preferences>) : KeyValueStore {
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val cache = ConcurrentHashMap<String, String>().apply {
        runBlocking { store.data.first() }.asMap().forEach { (k, v) -> if (v is String) put(k.name, v) }
    }

    override fun get(key: String): String? = cache[key]

    override fun put(key: String, value: String?) {
        if (value == null) cache.remove(key) else cache[key] = value
        writer.launch {
            store.edit { prefs ->
                val k = stringPreferencesKey(key)
                if (value == null) prefs.remove(k) else prefs[k] = value
            }
        }
    }
}

/** Encrypts values with an AES-GCM key that never leaves the Android Keystore. */
class KeystoreKeyValueStore(private val inner: KeyValueStore) : KeyValueStore {
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    override fun get(key: String): String? = inner.get("secret.$key")?.let { stored ->
        runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, this.key, GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12))
        }.getOrNull()
    }

    override fun put(key: String, value: String?) {
        if (value == null) return inner.put("secret.$key", null)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, this.key)
        inner.put("secret.$key", Base64.getEncoder().encodeToString(c.iv + c.doFinal(value.toByteArray())))
    }

    companion object {
        private const val ALIAS = "lookout-secrets"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

/** Battery, thermal and Wi-Fi state. Values are cached for 2 seconds. */
class AndroidDeviceStatus(private val context: Context) : DeviceStatus {
    private val power = context.getSystemService(PowerManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var cached: DeviceState? = null
    @Volatile private var cachedAt = 0L

    override fun read(): DeviceState {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < 2_000) return it }
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val health = battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val wifi = wifiInfo()
        return DeviceState(
            batteryPct = if (level >= 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            temperatureC = if (temp != Int.MIN_VALUE) temp / 10.0 else null,
            batteryHealth = when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                else -> null
            },
            thermal = Thermal.entries.getOrElse(power.currentThermalStatus) { Thermal.SHUTDOWN },
            wifiRssi = wifi?.second,
            ip = wifi?.first,
        ).also {
            cached = it
            cachedAt = now
        }
    }

    /** The Wi-Fi IPv4 address and RSSI, or null without Wi-Fi. */
    @Suppress("DEPRECATION")
    fun wifiInfo(): Pair<String, Int?>? {
        val networks = listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks.toList()
        for (n in networks.distinct()) {
            val caps = connectivity.getNetworkCapabilities(n) ?: continue
            // A VPN over Wi-Fi also reports the Wi-Fi transport, but its address is not the Wi-Fi address.
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val ip = connectivity.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address } ?: continue
            return ip.hostAddress!! to caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        }
        return null
    }

    /**
     * The Tailscale addresses of this phone. The network must be a VPN, because mobile carriers
     * use the same IPv4 range.
     */
    @Suppress("DEPRECATION")
    fun tailnetAddresses(): List<InetAddress> = connectivity.allNetworks
        .filter { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        .flatMap { connectivity.getLinkProperties(it)?.linkAddresses.orEmpty() }
        .map { it.address }
        .filter(Lan::isTailnet)
}

/** Android TextToSpeech. speak() returns once the utterance ends. */
class AndroidSpeaker(context: Context) : Speaker {
    private val ready = CompletableDeferred<Boolean>()
    private val done = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { done.remove(utteranceId)?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { done.remove(utteranceId)?.complete(false) }
        })
    }

    override suspend fun speak(text: String): Boolean {
        if (withTimeoutOrNull(5_000) { ready.await() } != true) return false
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<Boolean>()
        done[id] = d
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) return false
        return withTimeoutOrNull(60_000) { d.await() } ?: false
    }

    fun shutdown() = tts.shutdown()
}
