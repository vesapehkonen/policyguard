package org.example.devicepolicyguard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Restart opted-in sharing after boot or an in-place app update. */
class FallbackBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val afterUpdate = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && !afterUpdate) return
        if (!FamilyConfig.enabled(context)) return
        val event = if (afterUpdate) "update" else "reboot"
        val p = FamilyConfig.prefs(context)
        if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.edit().putString("service_status", "Stopped after $event: background Location permission missing")
                .putString("error", "Grant Location access 'Allow all the time' in Android app settings").apply()
            return
        }
        try {
            context.startForegroundService(Intent(context, AutoFallbackService::class.java))
            p.edit().putString("service_status", "Restart requested after $event").apply()
        } catch (e: Exception) {
            p.edit().putString("service_status", "Could not restart after $event")
                .putString("error", "${e.javaClass.simpleName}: ${e.message}").apply()
        }
    }
}
