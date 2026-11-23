package app.lookout.app

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import app.lookout.core.ClipDecoders
import app.lookout.core.DirectoryBlobStore
import app.lookout.core.FrameSource
import app.lookout.core.Img
import app.lookout.core.LkClip
import app.lookout.core.LookoutCore
import app.lookout.core.ReplayFrameSource
import app.lookout.server.BaseUrl
import app.lookout.server.TokenStore
import app.lookout.server.claudeCommand
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "lookout")

class LookoutApp : Application() {
    lateinit var runtime: Runtime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = Runtime(this)
    }
}

val Context.runtime: Runtime get() = (applicationContext as LookoutApp).runtime

/** Process-wide objects. The service starts and stops them; the activity observes them. */
class Runtime(private val app: Context) {
    val kv = DataStoreKeyValueStore(app.dataStore)
    val tokens = TokenStore(KeystoreKeyValueStore(kv))
    val device = AndroidDeviceStatus(app)
    val speaker = AndroidSpeaker(app)
    val camera = CameraXFrameSource(app)

    /** Debug: play images from <external files>/replay instead of the camera. Read at process start. */
    val replayFolder = File(app.getExternalFilesDir(null), "replay")
    val replayRequested: Boolean = kv.get(REPLAY_KEY) == "true"
    private val replayImages: List<Img> = if (replayRequested) loadReplay() else emptyList()
    val source: FrameSource = if (replayImages.isNotEmpty()) ReplayFrameSource(replayImages, fps = 2.0, ops = AndroidImageOps) else camera
    val usingReplay: Boolean get() = source !== camera

    val core = LookoutCore(
        AndroidImageOps, source, kv, DirectoryBlobStore(File(app.filesDir, "baselines")), device, speaker,
        deviceName = "lookout-${Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}",
        recordingsDir = File(app.getExternalFilesDir(null), "recordings"),
        clipDecoder = ClipDecoders(AndroidClipDecoder, LkClip.Decoder(AndroidImageOps)),
        textReader = MlKitTextReader(),
    )

    val running = MutableStateFlow(false)
    @Volatile var uiVisible = false

    val baseUrl = BaseUrl { "http://${device.wifiInfo()?.first ?: "127.0.0.1"}:${core.settings.value.port}" }

    /** The Tailscale URL. Null when "Allow Tailscale" is off or Tailscale is not connected. */
    fun tailnetUrl(): String? {
        if (!core.settings.value.allowTailscale) return null
        val ip = device.tailnetAddresses().firstOrNull { it is Inet4Address } ?: return null
        return "http://${ip.hostAddress}:${core.settings.value.port}"
    }

    fun command(): String = claudeCommand(baseUrl.get(), tokens.get())

    fun setReplay(enabled: Boolean) = kv.put(REPLAY_KEY, enabled.toString())

    private fun loadReplay(): List<Img> =
        replayFolder.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f -> BitmapFactory.decodeFile(f.path)?.let(::BitmapImg) }
            .orEmpty()

    companion object {
        const val REPLAY_KEY = "android.replay"
    }
}
