package org.example.devicepolicyguard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager

class AutoSmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = FamilyConfig.prefs(context)
        if (intent.getLongExtra("token", -1) != p.getLong("batch_token", -2)) return
        val index = intent.getStringExtra("index") ?: return
        val sent = intent.action == ACTION_SENT
        val key = if (sent) "seen_sent" else "seen_delivery"
        val seen = p.getStringSet(key, emptySet())!!.toMutableSet()
        if (!seen.add(index)) return
        val failure = resultCode != Activity.RESULT_OK
        val doneKey = if (sent) "sent_done" else "delivery_done"
        val failKey = if (sent) "sent_failures" else "delivery_failures"
        val done = p.getInt(doneKey, 0) + 1
        val failures = p.getInt(failKey, 0) + if (failure) 1 else 0
        val expected = p.getInt("expected_parts", 0)
        val errorText = if (failure) {
            val reason = when (resultCode) {
                SmsManager.RESULT_ERROR_NO_SERVICE -> "No SMS service"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
                SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS rate limit"
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Carrier failure"
                else -> "SMS code $resultCode"
            }
            "${if (sent) "Send" else "Delivery"} $index: $reason; "
        } else ""
        val errors = p.getString("batch_errors", "").orEmpty() + errorText
        val editor = p.edit().putStringSet(key, seen).putInt(doneKey, done).putInt(failKey, failures)
            .putString("batch_errors", errors)
        if (sent) {
            editor.putBoolean("batch_pending", done < expected)
                .putString("send_result", if (done == expected) {
                    if (failures == 0) {
                        editor.putLong("last_success_time", System.currentTimeMillis())
                        "All $expected SMS parts sent to carrier"
                    }
                    else "$failures/$expected parts failed: $errors"
                } else "$done/$expected SMS parts reported")
        } else {
            editor.putString("delivery_result", "$done/$expected parts delivered${if (failures > 0) "; $failures failed" else ""}; carrier reports may be missing")
        }
        if (failure) editor.putString("error", errors)
        editor.apply()
        if (sent && done == expected) EventLog.record(context,
            if (failures == 0) "Automatic SMS accepted by carrier" else "Automatic SMS send failed: $errors")
    }

    companion object {
        const val ACTION_SENT = "org.example.devicepolicyguard.AUTO_SMS_SENT"
        const val ACTION_DELIVERED = "org.example.devicepolicyguard.AUTO_SMS_DELIVERED"
    }
}
