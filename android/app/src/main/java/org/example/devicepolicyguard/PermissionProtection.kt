package org.example.devicepolicyguard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Policy-fix only the permissions required for automatic family location SMS. */
object PermissionProtection {
    private val permissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.SEND_SMS,
    )

    private fun manager(context: Context) = context.getSystemService(DevicePolicyManager::class.java)
    private fun admin(context: Context) = ComponentName(context, GuardAdminReceiver::class.java)
    private fun label(permission: String) = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION -> "Precise Location"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate Location"
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Background Location"
        Manifest.permission.SEND_SMS -> "SMS"
        else -> permission
    }

    fun isProtected(context: Context): Boolean = try {
        PolicyStatus.isOwner(context) && permissions.all { permission ->
            manager(context).getPermissionGrantState(admin(context), context.packageName, permission) ==
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED &&
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    } catch (_: Exception) { false }

    fun summary(context: Context): String {
        if (!PolicyStatus.isOwner(context)) return "Unavailable: app is not Device Owner"
        return permissions.joinToString("\n") { permission ->
            try {
                val state = manager(context).getPermissionGrantState(admin(context), context.packageName, permission)
                val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                "${label(permission)}: ${if (granted) "granted" else "denied"}; " + when (state) {
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> "protected by Device Owner"
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED -> "denied by Device Owner"
                    else -> "user controlled"
                }
            } catch (e: Exception) { "${label(permission)}: unavailable (${e.javaClass.simpleName})" }
        }
    }

    /** DEFAULT releases policy control without revoking an existing grant. */
    fun setProtected(context: Context, protect: Boolean): String? {
        if (!PolicyStatus.isOwner(context)) return "Device Owner is required"
        val target = if (protect) DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        val errors = mutableListOf<String>()
        permissions.forEach { permission ->
            try {
                val changed = manager(context).setPermissionGrantState(admin(context), context.packageName, permission, target)
                val actual = manager(context).getPermissionGrantState(admin(context), context.packageName, permission)
                if (!changed || actual != target ||
                    (protect && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED))
                    errors.add("${label(permission)} did not match the requested state")
            } catch (e: Exception) { errors.add("${label(permission)}: ${e.javaClass.simpleName}: ${e.message}") }
        }
        if (errors.isEmpty()) return null
        if (protect) {
            // Avoid leaving a partly protected set after an unsuccessful enable.
            permissions.forEach { permission ->
                try {
                    manager(context).setPermissionGrantState(admin(context), context.packageName,
                        permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
                } catch (_: Exception) { /* The detailed readback remains visible in settings. */ }
            }
        }
        return errors.joinToString("; ")
    }
}
