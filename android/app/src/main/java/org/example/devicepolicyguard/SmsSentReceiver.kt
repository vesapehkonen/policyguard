package org.example.devicepolicyguard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

/** Receives one carrier send result per SMS part, even if the screen is closed. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = intent.getLongExtra("token", -1)
        val part = intent.getIntExtra("part", -1)
        val expected = prefs.getInt("expected", 0)
        if (token != prefs.getLong("token", -2) || part !in 0 until expected) return
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        if (!seen.add(part.toString())) return
        val failures = prefs.getInt("failures", 0) + if (resultCode == Activity.RESULT_OK) 0 else 1
        val error = when (resultCode) {
            Activity.RESULT_OK -> ""
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular SMS service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Cellular radio is off"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS send limit exceeded"
            SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "Number blocked by fixed dialing"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> if (intent.getBooleanExtra("noDefault", false)) "No default SMS SIM" else "Generic carrier failure"
            else -> "SMS error code $resultCode"
        }
        val details = prefs.getString("details", "").orEmpty() +
            if (error.isEmpty()) "" else "Part ${part + 1}: $error (radio code ${intent.getIntExtra("errorCode", 0)}). "
        val complete = seen.size == expected
        val editor = prefs.edit().putStringSet("seen", seen).putInt("failures", failures).putString("details", details)
            .putBoolean("pending", !complete)
            .putString("result", if (complete) {
                if (failures == 0) "Sent to carrier ($expected part${if (expected == 1) "" else "s"}); recipient delivery is not confirmed"
                else "SMS failed: $details"
            } else "Sending SMS: ${seen.size}/$expected parts reported")
        if (complete && failures == 0) editor.putLong("last_success_time", System.currentTimeMillis())
        editor.apply()
        if (complete) EventLog.record(context,
            if (failures == 0) "Manual test SMS accepted by carrier" else "Manual test SMS failed: $details")
    }

    companion object { const val PREFS = "family_sms_test" }
}
