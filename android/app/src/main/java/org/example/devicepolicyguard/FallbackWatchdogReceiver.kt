package org.example.devicepolicyguard

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock

/** Restarts opted-in sharing if the foreground service's process is removed. */
class FallbackWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK || !FamilyConfig.enabled(context)) return
        DiagnosticLog.record(context, "Watchdog alarm woke; checking automatic SMS service")
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.record(context, "Watchdog did not restart service: Location permission missing; no SMS")
            return
        }
        schedule(context)
        try {
            context.startForegroundService(Intent(context, AutoFallbackService::class.java))
            DiagnosticLog.record(context, "Watchdog requested service start")
        } catch (e: Exception) {
            DiagnosticLog.record(context, "Watchdog service start failed: ${e.javaClass.simpleName}: ${e.message}")
            FamilyConfig.prefs(context).edit()
                .putString("service_status", "Could not restart automatic SMS")
                .putString("error", "Watchdog restart: ${e.javaClass.simpleName}: ${e.message}").apply()
        }
    }

    companion object {
        private const val ACTION_CHECK = "org.example.devicepolicyguard.CHECK_FALLBACK"
        private const val CHECK_INTERVAL_MS = 5 * 60_000L

        private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, FallbackWatchdogReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun schedule(context: Context, delayMs: Long = CHECK_INTERVAL_MS) {
            if (!FamilyConfig.enabled(context)) return
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent(context))
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        }
    }
}
