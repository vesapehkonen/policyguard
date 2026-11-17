package org.example.devicepolicyguard

import android.app.Activity
import android.os.Bundle

class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PolicyStatus.applyRestriction(this, "Policy compliance")
        PolicyStatus.initializeConnectivityPolicies(this)
        setResult(RESULT_OK)
        finish()
    }
}
