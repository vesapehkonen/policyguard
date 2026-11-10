package org.example.devicepolicyguard

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Detailed diagnostics are deliberately separate from the main actions. */
class HistoryActivity : Activity() {
    private lateinit var column: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (!AppSession.isUnlocked()) finish()
            else handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onResume() {
        super.onResume()
        if (!AppSession.isUnlocked()) { finish(); return }
        render()
        handler.postDelayed(check, 10_000)
    }
    override fun onPause() {
        handler.removeCallbacks(check)
        super.onPause()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!AppSession.isUnlocked()) { finish(); return true }
            AppSession.touch()
        }
        return super.dispatchTouchEvent(event)
    }
    private fun section(title: String, body: String) {
        column.addView(TextView(this).apply { text = title; textSize = 20f; setPadding(0, 24, 0, 8) })
        column.addView(TextView(this).apply { text = body; textSize = 16f })
    }
    private fun render() {
        column.removeAllViews()
        column.addView(TextView(this).apply { text = "Activity and details"; textSize = 28f })
        column.addView(Button(this).apply { text = "Back"; setOnClickListener { finish() } })
        column.addView(Button(this).apply { text = "Refresh"; setOnClickListener { render() } })
        section("Recent activity", EventLog.summary(this))
        section("Automatic sharing", FallbackStatus.summary(this))
        section("Permission control", PermissionProtection.summary(this))
        val manual = getSharedPreferences(SmsSentReceiver.PREFS, MODE_PRIVATE)
        fun time(key: String): String {
            val value = manual.getLong(key, 0)
            return if (value == 0L) "None" else DateFormat.getDateTimeInstance().format(Date(value))
        }
        section("Manual test", "Last attempt: ${time("attempt_time")}\nLast carrier send: ${time("last_success_time")}\n${manual.getString("result", "No test SMS sent yet")}")
        section("Device policy details", PolicyStatus.summary(this))
    }
}
