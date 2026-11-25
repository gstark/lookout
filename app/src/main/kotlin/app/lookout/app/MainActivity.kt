package app.lookout.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import app.lookout.core.FrameOrientation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    /** Bumped after permission and settings changes so the checklist recomposes. */
    private val checks = mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checks.intValue++
        if (hasCamera()) startServing()
    }

    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        checks.intValue++
        runtime.core.updateSettings { it.copy(allowAudio = ok) }
        if (runtime.running.value) LookoutService.send(this, LookoutService.ACTION_UPDATE_FGS)
        if (!ok) Toast.makeText(this, "Microphone permission denied. Sound recording stays off.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rt = runtime
        setContent {
            LookoutTheme {
                MainScreen(
                    rt = rt,
                    checklist = checklist(checks.intValue),
                    actions = MainActions(
                        start = ::startServing,
                        stop = { LookoutService.send(this, LookoutService.ACTION_STOP) },
                        fixCheck = ::fix,
                        copy = ::copy,
                        setAllowAudio = ::setAllowAudio,
                    ),
                )
            }
        }
        lifecycleScope.launch {
            // Asleep, the screen can turn off even with "Keep screen on".
            rt.core.settings.combine(rt.core.sleep.asleep) { s, asleep -> s to asleep }.collect { (s, asleep) ->
                if (s.keepScreenOn && !asleep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // The screen faces the same way as the camera frames, so the preview matches what the agent sees.
                requestedOrientation = if (s.orientation == FrameOrientation.LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        lifecycleScope.launch {
            rt.core.human.toasts.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
        }
        if (rt.core.settings.value.startOnLaunch && !rt.running.value) {
            if (hasCamera()) startServing() else requestPermissions()
        }
        autostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        autostart(intent)
    }

    /** Opened by [BootReceiver]: start serving, then get out of the way when the service is up. */
    private fun autostart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(EXTRA_AUTOSTART)
        getSystemService(NotificationManager::class.java).cancel(BootReceiver.NOTIFY_ID)
        if (!hasCamera()) return
        startServing()
        // Stay in front until the service is in the foreground, so it gets camera access.
        lifecycleScope.launch {
            withTimeoutOrNull(10_000) { runtime.running.first { it } }
            moveTaskToBack(true)
        }
    }

    /** Any touch on the screen wakes Lookout and restarts its idle timer. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        runtime.core.phoneActive()
    }

    override fun onResume() {
        super.onResume()
        runtime.uiVisible = true
        checks.intValue++
        if (runtime.running.value) LookoutService.send(this, LookoutService.ACTION_UPDATE_FGS)
    }

    override fun onPause() {
        runtime.uiVisible = false
        super.onPause()
    }

    private fun setAllowAudio(allow: Boolean) {
        if (!allow) {
            runtime.core.updateSettings { it.copy(allowAudio = false) }
            if (runtime.running.value) LookoutService.send(this, LookoutService.ACTION_UPDATE_FGS)
            return
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            runtime.core.updateSettings { it.copy(allowAudio = true) }
            if (runtime.running.value) LookoutService.send(this, LookoutService.ACTION_UPDATE_FGS)
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasCamera() = granted(Manifest.permission.CAMERA)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        permissions.launch(wanted.toTypedArray())
    }

    private fun startServing() {
        if (!hasCamera()) return requestPermissions()
        LookoutService.send(this, LookoutService.ACTION_START)
    }

    private fun checklist(@Suppress("UNUSED_PARAMETER") version: Int): List<Check> {
        val power = getSystemService(PowerManager::class.java)
        return listOf(
            Check("camera", "Camera permission", hasCamera()),
            Check("notifications", "Notifications (for the ongoing notice and agent prompts)", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)),
            Check("lan", "Local network access (so agents on the LAN can connect)", Build.VERSION.SDK_INT < 37 || granted(Manifest.permission.ACCESS_LOCAL_NETWORK)),
            Check("battery", "Battery optimization off (so Android does not stop the service)", power.isIgnoringBatteryOptimizations(packageName)),
            Check("wifi", "Connected to Wi-Fi", runtime.device.wifiInfo() != null),
            Check("fullscreen", "Full-screen alerts (for agent prompts and start after restart)", Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()),
        )
    }

    @SuppressLint("BatteryLife")
    private fun fix(id: String) {
        when (id) {
            "camera", "notifications", "lan" -> requestPermissions()
            "battery" -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
            "wifi" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:$packageName".toUri()))
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Lookout", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_AUTOSTART = "app.lookout.AUTOSTART"
    }
}
