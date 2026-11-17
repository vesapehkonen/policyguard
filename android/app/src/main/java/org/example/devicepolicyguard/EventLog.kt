package org.example.devicepolicyguard

import android.content.Context
import org.json.JSONArray
import java.text.DateFormat
import java.util.Date

/** Small local history. Entries contain status only, never phone numbers or coordinates. */
object EventLog {
    private const val NAME = "activity_history"
    @Synchronized fun record(context: Context, description: String) {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val entries = try { JSONArray(prefs.getString("events", "[]")) } catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        next.put("${DateFormat.getDateTimeInstance().format(Date())}  $description")
        for (i in 0 until minOf(entries.length(), 29)) next.put(entries.optString(i))
        prefs.edit().putString("events", next.toString()).apply()
    }
    fun summary(context: Context): String {
        val entries = try { JSONArray(context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getString("events", "[]")) } catch (_: Exception) { JSONArray() }
        return if (entries.length() == 0) "No activity yet" else
            (0 until entries.length()).joinToString("\n\n") { entries.optString(it) }
    }
}
