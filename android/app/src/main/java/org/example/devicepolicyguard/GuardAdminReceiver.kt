package org.example.devicepolicyguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class GuardAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        PolicyStatus.applyRestriction(context, "Provisioning complete")
        PolicyStatus.applyConnectivityPolicies(context, "Provisioning complete")
    }
}
