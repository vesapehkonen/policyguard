package org.example.devicepolicyguard

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.text.DateFormat
import java.util.Date

object FallbackStatus {
    fun sharingState(context: Context): String {
        if (!FamilyConfig.enabled(context)) return "Off"
        if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "Paused: grant Location access 'Allow all the time'"
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return "Paused: grant Precise Location"
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            return "Paused: grant SMS permission"
        val service = prefsStatus(context)
        if (service.startsWith("Stopped") || service.startsWith("Could not") || service.startsWith("Not running"))
            return service
        if (hasValidatedInternet(context)) return "Paused: validated internet is available"
        return service
    }

    private fun prefsStatus(context: Context): String = FamilyConfig.prefs(context)
        .getString("service_status", "Not running; reopen the app") ?: "Not running"
    fun hasValidatedInternet(context: Context): Boolean = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { false }

    fun summary(context: Context): String {
        val p = FamilyConfig.prefs(context)
        fun time(key: String): String {
            val value = p.getLong(key, 0)
            return if (value == 0L) "None" else DateFormat.getDateTimeInstance().format(Date(value))
        }
        return """
            Connectivity: ${if (hasValidatedInternet(context)) "Validated internet available" else "No validated internet"}
            Automatic family SMS: ${if (FamilyConfig.enabled(context)) "Enabled" else "Disabled"}
            Sharing state: ${sharingState(context)}
            Recipients: ${FamilyConfig.recipients(context).size}; interval: ${FamilyConfig.intervalMinutes(context)} min
            Service: ${p.getString("service_status", "Not started")}
            Last fresh fix: ${time("fix_time")} ${p.getString("fix_summary", "")}
            Last SMS attempt: ${time("attempt_time")}
            Last successful carrier send: ${time("last_success_time")}
            SMS send: ${p.getString("send_result", "None")}
            SMS delivery: ${p.getString("delivery_result", "Not confirmed")}
            Fallback errors: ${p.getString("error", "None")}
        """.trimIndent()
    }
}
