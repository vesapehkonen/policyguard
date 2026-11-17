package org.example.devicepolicyguard

import android.content.Context
import android.telephony.PhoneNumberUtils
import org.json.JSONArray

object FamilyConfig {
    private const val PREFS = "family_fallback"
    val intervals = listOf(1, 5, 10, 15, 30)
    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun intervalMinutes(context: Context) = prefs(context).getInt("interval_minutes", 10)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun recipients(context: Context): List<String> = try {
        val values = JSONArray(prefs(context).getString("recipients", "[]"))
        (0 until values.length()).map { values.getString(it) }
    } catch (_: Exception) { emptyList() }

    fun normalizedNumber(input: String): String? {
        val number = PhoneNumberUtils.stripSeparators(input.trim())
        return number.takeIf { it.matches(Regex("\\+[1-9][0-9]{6,14}")) }
    }

    fun save(context: Context, recipients: List<String>, interval: Int): String? {
        if (interval !in intervals) return "Invalid SMS interval"
        if (recipients.isEmpty() || recipients.size > 5) return "Enter 1–5 recipients"
        val normalized = recipients.map { normalizedNumber(it) ?: return "Use international numbers such as +15551234567" }
        if (normalized.distinct().size != normalized.size) return "Duplicate recipients"
        val array = JSONArray()
        normalized.forEach(array::put)
        prefs(context).edit().putString("recipients", array.toString()).putInt("interval_minutes", interval).apply()
        return null
    }

    fun saveInterval(context: Context, interval: Int): String? {
        if (interval !in intervals) return "Invalid SMS interval"
        val p = prefs(context)
        val lastAttempt = p.getLong("attempt_time", 0)
        val editor = p.edit().putInt("interval_minutes", interval)
        if (lastAttempt > 0) editor.putLong("next_attempt_wall", lastAttempt + interval * 60_000L)
        return if (editor.commit()) null
            else "Could not save SMS interval"
    }

    fun saveRecipients(context: Context, recipients: List<String>): String? {
        if (recipients.size > 5) return "Maximum five recipients"
        if (recipients.isEmpty() && enabled(context)) return "Disable automatic SMS before removing the last recipient"
        val normalized = recipients.map { normalizedNumber(it) ?: return "Use international numbers such as +15551234567" }
        if (normalized.distinct().size != normalized.size) return "Duplicate recipient"
        val array = JSONArray()
        normalized.forEach(array::put)
        return if (prefs(context).edit().putString("recipients", array.toString()).commit()) null
            else "Could not save recipients"
    }
}
