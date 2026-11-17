package org.example.devicepolicyguard

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Bounded, ADB-readable operational log. Do not put recipients or coordinates here. */
object DiagnosticLog {
    private const val TAG = "PolicyGuard"
    private const val MAX_BYTES = 1_000_000L
    private const val ARCHIVES = 3

    @Synchronized fun record(context: Context, event: String) {
        Log.i(TAG, event)
        try {
            val directory = context.getExternalFilesDir(null) ?: return
            val current = File(directory, "diagnostic.log")
            if (current.length() >= MAX_BYTES) {
                File(directory, "diagnostic.log.$ARCHIVES").delete()
                for (index in ARCHIVES - 1 downTo 1)
                    File(directory, "diagnostic.log.$index").renameTo(File(directory, "diagnostic.log.${index + 1}"))
                current.renameTo(File(directory, "diagnostic.log.1"))
            }
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            current.appendText("${format.format(Date())}  $event\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not write diagnostic log", e)
        }
    }
}
