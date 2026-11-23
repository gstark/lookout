package app.lookout.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.lookout.R

/**
 * Starts serving again after a restart or an app update, when the setting is on.
 *
 * Android does not let a boot receiver start a camera foreground service, and a service started
 * from the background gets no camera access. So the receiver posts a full-screen notification. It
 * opens [MainActivity] over the lock screen, the activity starts the service from the foreground,
 * then moves to the back.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val rt = context.runtime
        if (!rt.core.settings.value.startOnBoot || rt.running.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Start after restart", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_AUTOSTART, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Lookout is starting")
            .setContentText("Tap to start the camera server.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFY_ID, n)
    }

    companion object {
        private const val CHANNEL = "boot"
        const val NOTIFY_ID = 3
    }
}
