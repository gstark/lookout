package app.lookout.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Bumped after permission and settings changes so the checklist recomposes. */
    private val checks = mutableIntStateOf(0)

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checks.intValue++
        if (hasCamera()) startServing()
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
                    ),
                )
            }
        }
        lifecycleScope.launch {
            rt.core.settings.collect { s ->
                if (s.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        lifecycleScope.launch {
            rt.core.human.toasts.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() }
        }
        if (rt.core.settings.value.startOnLaunch && !rt.running.value) {
            if (hasCamera()) startServing() else requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        runtime.uiVisible = true
        checks.intValue++
    }

    override fun onPause() {
        runtime.uiVisible = false
        super.onPause()
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
        )
    }

    @SuppressLint("BatteryLife")
    private fun fix(id: String) {
        when (id) {
            "camera", "notifications", "lan" -> requestPermissions()
            "battery" -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()))
            "wifi" -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun copy(text: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Lookout", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
