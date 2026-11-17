package org.example.devicepolicyguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.location.LocationManager
import android.provider.Settings
import android.telephony.TelephonyManager

object PolicyStatus {
    private const val PREFS = "policy_status"
    private const val RESULT = "last_result"
    private const val ERROR = "last_error"
    private const val BEFORE = "mobile_data_before"
    private const val SNAPSHOT = "connectivity_snapshot"
    private const val ORIGINAL_AIR = "original_air_restriction"
    private const val ORIGINAL_LOCATION_RESTRICTION = "original_location_restriction"
    private const val ORIGINAL_LOCATION_ENABLED = "original_location_enabled"
    private const val POLICY_VERSION = "connectivity_policy_version"
    private const val CURRENT_POLICY_VERSION = 6
    private const val CONNECTIVITY_RESULT = "connectivity_result"
    private const val CONNECTIVITY_ERROR = "connectivity_error"

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, GuardAdminReceiver::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOwner(context: Context): Boolean = dpm(context).isDeviceOwnerApp(context.packageName)

    fun mobileData(context: Context): String {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_DATA)) return "Unavailable (no cellular data feature)"
        return try {
            if (context.getSystemService(TelephonyManager::class.java).isDataEnabled) "Enabled" else "Disabled"
        } catch (e: Exception) {
            "Unavailable (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})"
        }
    }

    fun restriction(context: Context): String = try {
        if (!isOwner(context)) "Unavailable (not Device Owner)"
        else {
            val key = UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS
            val setByThisAdmin = dpm(context).getUserRestrictions(admin(context)).getBoolean(key)
            val effective = context.getSystemService(UserManager::class.java).hasUserRestriction(key)
            when {
                setByThisAdmin && effective -> "Enabled"
                setByThisAdmin -> "Set by admin, but not effective"
                effective -> "Effective, but not set by this admin"
                else -> "Disabled"
            }
        }
    } catch (e: Exception) {
        "Unavailable (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})"
    }

    private fun restrictionState(context: Context, key: String): String = try {
        if (!isOwner(context)) "Unavailable (not Device Owner)"
        else {
            val byAdmin = dpm(context).getUserRestrictions(admin(context)).getBoolean(key)
            val effective = context.getSystemService(UserManager::class.java).hasUserRestriction(key)
            when {
                byAdmin && effective -> "Enabled"
                byAdmin -> "Set by this admin, not effective"
                effective -> "Effective, set by another admin"
                else -> "Disabled"
            }
        }
    } catch (e: Exception) {
        "Unavailable (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})"
    }

    fun adminRestriction(context: Context, key: String): Boolean? = try {
        if (!isOwner(context)) null
        else dpm(context).getUserRestrictions(admin(context)).getBoolean(key)
    } catch (_: Exception) { null }

    fun restrictionReadback(context: Context, key: String): String = restrictionState(context, key)

    /** Return a readback error instead of claiming that a policy change succeeded. */
    fun setRestriction(context: Context, key: String, enabled: Boolean): String? {
        if (!isOwner(context)) return "App is not Device Owner"
        return try {
            val manager = dpm(context)
            if (enabled) {
                if (key == UserManager.DISALLOW_CONFIG_LOCATION) manager.setLocationEnabled(admin(context), true)
                manager.addUserRestriction(admin(context), key)
            } else manager.clearUserRestriction(admin(context), key)
            val actual = adminRestriction(context, key)
            when {
                actual == null -> "Could not read the policy state"
                actual != enabled -> "Policy readback differs from requested state"
                enabled && !context.getSystemService(UserManager::class.java).hasUserRestriction(key) ->
                    "Policy is set but is not effective"
                enabled && key == UserManager.DISALLOW_CONFIG_LOCATION &&
                    !context.getSystemService(LocationManager::class.java).isLocationEnabled ->
                    "Location Services did not turn on"
                else -> null
            }
        } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}" }
    }

    private fun locationEnabled(context: Context): String = try {
        if (context.getSystemService(LocationManager::class.java).isLocationEnabled) "Enabled" else "Disabled"
    } catch (e: Exception) {
        "Unavailable (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})"
    }

    private fun airplaneEnabled(context: Context): String = try {
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) "On" else "Off"
    } catch (e: Exception) {
        "Unavailable (${e.javaClass.simpleName}: ${e.message ?: "unknown error"})"
    }

    /** Apply once for each policy release; a manual test restore stays restored on later launches. */
    fun initializeConnectivityPolicies(context: Context) {
        if (prefs(context).getInt(POLICY_VERSION, 0) < CURRENT_POLICY_VERSION && isOwner(context)) {
            applyConnectivityPolicies(context, "First launch after policy update")
        }
    }

    fun applyConnectivityPolicies(context: Context, source: String) {
        val p = prefs(context)
        if (!isOwner(context)) {
            p.edit().putString(CONNECTIVITY_RESULT, "$source: skipped")
                .putString(CONNECTIVITY_ERROR, "App is not Device Owner").apply()
            return
        }
        val errors = mutableListOf<String>()
        try {
            if (!p.getBoolean(SNAPSHOT, false)) {
                val restrictions = dpm(context).getUserRestrictions(admin(context))
                val originalLocation = context.getSystemService(LocationManager::class.java).isLocationEnabled
                if (!p.edit().putBoolean(ORIGINAL_AIR, restrictions.getBoolean(UserManager.DISALLOW_AIRPLANE_MODE))
                        .putBoolean(ORIGINAL_LOCATION_RESTRICTION, restrictions.getBoolean(UserManager.DISALLOW_CONFIG_LOCATION))
                        .putBoolean(ORIGINAL_LOCATION_ENABLED, originalLocation)
                        .putBoolean(SNAPSHOT, true).commit()) {
                    throw IllegalStateException("Could not save original policy settings")
                }
            }
            p.edit().putInt(POLICY_VERSION, CURRENT_POLICY_VERSION).apply()
            try {
                dpm(context).addUserRestriction(admin(context), UserManager.DISALLOW_AIRPLANE_MODE)
            } catch (e: Exception) {
                errors.add("Airplane restriction: ${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                dpm(context).setLocationEnabled(admin(context), true)
            } catch (e: Exception) {
                errors.add("Enable Location: ${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                dpm(context).addUserRestriction(admin(context), UserManager.DISALLOW_CONFIG_LOCATION)
            } catch (e: Exception) {
                errors.add("Location restriction: ${e.javaClass.simpleName}: ${e.message}")
            }
            val air = restrictionState(context, UserManager.DISALLOW_AIRPLANE_MODE)
            val loc = restrictionState(context, UserManager.DISALLOW_CONFIG_LOCATION)
            val enabled = locationEnabled(context)
            if (air != "Enabled") errors.add("Airplane restriction readback: $air")
            if (loc != "Enabled") errors.add("Location restriction readback: $loc")
            if (enabled != "Enabled") errors.add("Location state readback: $enabled")
            p.edit().putString(CONNECTIVITY_RESULT, "$source: Airplane restriction $air; Location restriction $loc; Location $enabled")
                .putString(CONNECTIVITY_ERROR, if (errors.isEmpty()) "None" else errors.joinToString(" | ")).apply()
        } catch (e: Exception) {
            p.edit().putString(CONNECTIVITY_RESULT, "$source: could not save original settings; no new policies applied")
                .putString(CONNECTIVITY_ERROR, "${e.javaClass.simpleName}: ${e.message}").apply()
        }
    }

    fun restoreConnectivityPolicies(context: Context) {
        val p = prefs(context)
        if (!isOwner(context)) {
            p.edit().putString(CONNECTIVITY_RESULT, "Restore skipped")
                .putString(CONNECTIVITY_ERROR, "App is not Device Owner").apply()
            return
        }
        if (!p.getBoolean(SNAPSHOT, false)) {
            p.edit().putString(CONNECTIVITY_RESULT, "Restore unavailable: no saved settings")
                .putString(CONNECTIVITY_ERROR, "None").apply()
            return
        }
        val errors = mutableListOf<String>()
        try {
            if (!p.getBoolean(ORIGINAL_AIR, false))
                dpm(context).clearUserRestriction(admin(context), UserManager.DISALLOW_AIRPLANE_MODE)
        } catch (e: Exception) { errors.add("Airplane restriction: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            if (!p.getBoolean(ORIGINAL_LOCATION_RESTRICTION, false))
                dpm(context).clearUserRestriction(admin(context), UserManager.DISALLOW_CONFIG_LOCATION)
        } catch (e: Exception) { errors.add("Location restriction: ${e.javaClass.simpleName}: ${e.message}") }
        try {
            dpm(context).setLocationEnabled(admin(context), p.getBoolean(ORIGINAL_LOCATION_ENABLED, false))
        } catch (e: Exception) { errors.add("Location state: ${e.javaClass.simpleName}: ${e.message}") }
        val airExpected = p.getBoolean(ORIGINAL_AIR, false)
        val locExpected = p.getBoolean(ORIGINAL_LOCATION_RESTRICTION, false)
        val locationExpected = p.getBoolean(ORIGINAL_LOCATION_ENABLED, false)
        try {
            val restrictions = dpm(context).getUserRestrictions(admin(context))
            if (restrictions.getBoolean(UserManager.DISALLOW_AIRPLANE_MODE) != airExpected) errors.add("Airplane restriction readback differs")
            if (restrictions.getBoolean(UserManager.DISALLOW_CONFIG_LOCATION) != locExpected) errors.add("Location restriction readback differs")
            if (context.getSystemService(LocationManager::class.java).isLocationEnabled != locationExpected) errors.add("Location state readback differs")
        } catch (e: Exception) { errors.add("Restore readback: ${e.javaClass.simpleName}: ${e.message}") }
        val restored = errors.isEmpty()
        p.edit().putString(CONNECTIVITY_RESULT, "Restore: ${if (restored) "original policy settings restored" else "incomplete"}")
            .putString(CONNECTIVITY_ERROR, if (restored) "None" else errors.joinToString(" | "))
            .putBoolean(SNAPSHOT, !restored).apply()
    }

    fun applyRestriction(context: Context, source: String) {
        val before = mobileData(context)
        val editor = prefs(context).edit().putString(BEFORE, before)
        if (!isOwner(context)) {
            editor.putString(RESULT, "$source: skipped; app is not Device Owner")
                .putString(ERROR, "Device Owner provisioning is required")
                .apply()
            return
        }
        try {
            dpm(context).addUserRestriction(admin(context), UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            val state = restriction(context)
            editor.putString(RESULT, "$source: restriction $state; mobile data before: $before")
                .putString(ERROR, if (state == "Enabled") "None" else "Restriction could not be verified: $state")
                .apply()
        } catch (e: Exception) {
            editor.putString(RESULT, "$source: failed; mobile data before: $before")
                .putString(ERROR, "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}")
                .apply()
        }
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        val airRestriction = restrictionState(context, UserManager.DISALLOW_AIRPLANE_MODE)
        val airState = airplaneEnabled(context)
        val prevention = when {
            airRestriction == "Enabled" && airState == "Off" -> "Policy active; Samsung control still needs testing"
            airRestriction == "Enabled" && airState == "On" -> "FAILED: Airplane Mode is on despite restriction"
            else -> "INACTIVE: restriction is not enabled"
        }
        return """
            Device Owner: ${if (isOwner(context)) "Yes" else "No"}
            Airplane Mode prevention: $prevention
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            Mobile data: ${mobileData(context)}
            Mobile-network restriction: ${restriction(context)}
            Airplane Mode: $airState
            Airplane Mode restriction: $airRestriction
            Location Services: ${locationEnabled(context)}
            Location configuration restriction: ${restrictionState(context, UserManager.DISALLOW_CONFIG_LOCATION)}
            Original policy settings saved: ${if (p.getBoolean(SNAPSHOT, false)) "Yes" else "No"}
            Last Airplane/Location policy result: ${p.getString(CONNECTIVITY_RESULT, "Not applied yet")}
            Airplane/Location API errors: ${p.getString(CONNECTIVITY_ERROR, "None")}
            Mobile data before last application: ${p.getString(BEFORE, "Not checked")}
            Last policy result: ${p.getString(RESULT, "Not applied yet")}
            Errors: ${p.getString(ERROR, "None")}
        """.trimIndent()
    }
}
