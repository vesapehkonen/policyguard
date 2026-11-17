package org.example.devicepolicyguard

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.MotionEvent
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var policyStatus: TextView
    private lateinit var locationStatus: TextView
    private lateinit var root: LinearLayout
    private lateinit var number: EditText
    private lateinit var sendButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private var cancellation: CancellationSignal? = null
    private var busy = false
    private var pendingAction: String? = null
    private var lastFix: Location? = null
    private var locationMessage = "No location requested yet"
    private var sendDestination: String? = null
    private var sendSimId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private val prefs by lazy { getSharedPreferences(SmsSentReceiver.PREFS, Context.MODE_PRIVATE) }
    private var showingHome = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (showingHome && !AppSession.isUnlocked()) { showAuth(); return true }
            AppSession.touch()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        if (AppSession.isUnlocked()) showHome() else showAuth()
    }

    private fun showAuth() {
        showingHome = false
        handler.removeCallbacks(poll)
        cancellation?.cancel()
        cancellation = null
        busy = false
        root.removeAllViews()
        root.addView(TextView(this).apply { text = "Device Policy Guard"; textSize = 28f })
        root.addView(TextView(this).apply {
            text = "Private family location and device policy information"
            textSize = 16f
        })
        val first = EditText(this).apply {
            hint = if (AdminAuth.isSet(this@MainActivity)) "Administrator PIN" else "Create PIN (6–12 digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        root.addView(first)
        val confirm = if (!AdminAuth.isSet(this)) EditText(this).apply {
            hint = "Confirm PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        } else null
        if (confirm != null) root.addView(confirm)
        val error = TextView(this).apply { textSize = 16f }
        root.addView(error)
        root.addView(Button(this).apply {
            text = if (confirm == null) "Unlock" else "Create PIN and unlock"
            setOnClickListener {
                val pin = first.text.toString().toCharArray()
                val check = confirm?.text?.toString()?.toCharArray()
                val result = if (check != null && !pin.contentEquals(check)) "PINs do not match"
                    else if (check != null) AdminAuth.set(this@MainActivity, pin)
                    else AdminAuth.verify(this@MainActivity, pin)
                pin.fill('\u0000'); check?.fill('\u0000')
                first.text.clear(); confirm?.text?.clear()
                if (result == null) {
                    AppSession.unlock()
                    showHome()
                    resumeHome()
                } else error.text = result
            }
        })
        root.addView(TextView(this).apply {
            text = "Forgot PIN? Recovery requires a trusted, signed in-place update. Device Owner restrictions remain active."
        })
    }

    private fun showHome() {
        showingHome = true
        root.removeAllViews()
        val column = root
        val pad = (20 * resources.displayMetrics.density).toInt()
        column.addView(TextView(this).apply { text = "Device Policy Guard"; textSize = 28f })
        column.addView(TextView(this).apply { text = "Family location sharing and device protection"; textSize = 16f })
        column.addView(TextView(this).apply { text = "CURRENT STATUS"; textSize = 18f; setPadding(0, pad, 0, 0) })
        policyStatus = TextView(this).apply { textSize = 16f }
        policyStatus.setPadding(pad, pad, pad, pad)
        policyStatus.background = GradientDrawable().apply {
            setColor(Color.rgb(238, 244, 249)); cornerRadius = pad.toFloat()
        }
        column.addView(policyStatus)
        column.addView(Button(this).apply { text = "Refresh status"; isAllCaps = false; setOnClickListener { refresh() } })
        column.addView(TextView(this).apply { text = "SEND A TEST LOCATION SMS"; textSize = 18f; setPadding(0, pad, 0, 0) })
        column.addView(TextView(this).apply { text = "Enter a recipient, then tap Send test SMS. A fresh GPS fix and cellular SMS service are required." })
        column.addView(TextView(this).apply { text = "Test recipient" })
        number = EditText(this).apply {
            hint = "+15551234567"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setText(prefs.getString("parent_number", "")?.ifEmpty { FamilyConfig.recipients(this@MainActivity).firstOrNull().orEmpty() })
        }
        column.addView(number)
        sendButton = Button(this).apply {
            text = "Send test SMS"
            isAllCaps = false
            setOnClickListener { beginLocation(true) }
        }
        column.addView(sendButton)
        column.addView(Button(this).apply {
            text = "Check current location without sending"
            isAllCaps = false
            setOnClickListener { beginLocation(false) }
        })
        locationStatus = TextView(this).apply { textSize = 16f }
        locationStatus.setPadding(pad, pad, pad, pad)
        locationStatus.background = GradientDrawable().apply {
            setColor(Color.rgb(238, 244, 249)); cornerRadius = pad.toFloat()
        }
        column.addView(locationStatus)
        column.addView(Button(this).apply {
            text = "Settings and restrictions"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, AdminSettingsActivity::class.java)) }
        })
        column.addView(Button(this).apply {
            text = "Activity and detailed status"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        })
        PolicyStatus.initializeConnectivityPolicies(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!AppSession.isUnlocked()) {
            showAuth()
            return
        }
        if (!showingHome) showHome()
        resumeHome()
    }

    private fun resumeHome() {
        if (!number.hasFocus()) number.setText(prefs.getString("parent_number", "")?.ifEmpty {
            FamilyConfig.recipients(this).firstOrNull().orEmpty()
        })
        if (FamilyConfig.enabled(this) && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            FamilyConfig.prefs(this).edit()
                .putString("service_status", "Paused: background Location permission missing")
                .putString("error", "Grant Location access 'Allow all the time' in Administrator settings")
                .apply()
        } else if (FamilyConfig.enabled(this)) {
            try { startForegroundService(Intent(this, AutoFallbackService::class.java)) }
            catch (e: Exception) {
                FamilyConfig.prefs(this).edit()
                    .putString("service_status", "Could not restart: ${e.javaClass.simpleName}: ${e.message}").apply()
            }
        }
        refresh()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        if (busy) {
            cancellation?.cancel()
            cancellation = null
            busy = false
            locationMessage = "Location request cancelled when the app was left. No SMS was sent."
        }
        super.onPause()
    }

    override fun onDestroy() {
        cancellation?.cancel()
        super.onDestroy()
    }

    private fun has(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun beginLocation(send: Boolean) {
        if (!AppSession.isUnlocked()) { showAuth(); return }
        if (busy || (send && prefs.getBoolean("pending", false))) return
        lastFix = null
        if (send) {
            val raw = FamilyConfig.normalizedNumber(number.text.toString())
            if (raw == null) {
                locationMessage = "Enter an international number such as +15551234567."
                refresh(); return
            }
            prefs.edit().putString("parent_number", raw).apply()
            if (!has(Manifest.permission.SEND_SMS)) {
                pendingAction = "send"
                requestPermissions(arrayOf(Manifest.permission.SEND_SMS), 101)
                return
            }
            val simId = SmsManager.getDefaultSmsSubscriptionId()
            if (simId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                locationMessage = "No default SMS SIM. Set one in Android SIM settings, then try again."
                refresh(); return
            }
            sendDestination = raw
            sendSimId = simId
        }
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingAction = if (send) "send" else "get"
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 100)
            return
        }
        requestFreshFix(send)
    }

    private fun requestFreshFix(send: Boolean) {
        val manager = getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationMessage = "Location/GPS is off. Enable Location and try again."
            refresh(); return
        }
        busy = true
        locationMessage = "Waiting for a fresh GPS fix (up to 90 seconds)…"
        refresh()
        val started = SystemClock.elapsedRealtimeNanos()
        val signal = CancellationSignal()
        cancellation = signal
        val timeout = Runnable {
            if (cancellation === signal && busy) {
                busy = false
                cancellation = null
                signal.cancel()
                locationMessage = "No fresh GPS fix within 90 seconds. Try outdoors with a clear sky. No SMS was sent."
                refresh()
            }
        }
        handler.postDelayed(timeout, 90_000)
        try {
            manager.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, mainExecutor) { fix ->
                handler.removeCallbacks(timeout)
                if (cancellation !== signal) return@getCurrentLocation
                cancellation = null
                busy = false
                if (fix == null || fix.elapsedRealtimeNanos < started) {
                    locationMessage = "GPS did not produce a fresh fix. No SMS was sent."
                    refresh(); return@getCurrentLocation
                }
                lastFix = fix
                locationMessage = "Fresh GPS fix obtained."
                refresh()
                if (send) sendSms(fix)
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            cancellation = null
            busy = false
            locationMessage = "GPS error: ${e.message ?: e.javaClass.simpleName}. No SMS was sent."
            refresh()
        }
    }

    private fun sendSms(fix: Location) {
        if (!AppSession.isUnlocked()) { locationMessage = "Session expired; no SMS sent"; return }
        val simId = sendSimId
        if (simId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            locationMessage = "Default SMS SIM unavailable. No SMS was sent."
            refresh(); return
        }
        val destination = sendDestination ?: return
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(fix.time))
        val coordinates = String.format(Locale.US, "%.6f,%.6f", fix.latitude, fix.longitude)
        val link = "https://www.google.com/maps/search/?api=1&query=${Uri.encode(coordinates)}"
        val message = "Family location test\nTime: $time\nAccuracy: ±${fix.accuracy.toInt()} m\nBattery: ${BatteryStatus.percentage(this)}\n$link"
        try {
            val sms = getSystemService(SmsManager::class.java).createForSubscriptionId(simId)
            val parts = sms.divideMessage(message)
            val token = SystemClock.elapsedRealtimeNanos()
            val intents = ArrayList<PendingIntent>()
            parts.indices.forEach { part ->
                val intent = Intent(this, SmsSentReceiver::class.java).apply {
                    action = "org.example.devicepolicyguard.SMS_SENT"
                    data = Uri.parse("policyguard://sms/$token/$part")
                    putExtra("token", token)
                    putExtra("part", part)
                }
                intents.add(PendingIntent.getBroadcast(this, part, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            prefs.edit().putLong("token", token).putInt("expected", parts.size)
                .putLong("attempt_time", System.currentTimeMillis())
                .putStringSet("seen", emptySet()).putInt("failures", 0).putString("details", "")
                .putLong("send_started", SystemClock.elapsedRealtime())
                .putBoolean("pending", true).putString("result", "Sending SMS: waiting for carrier result")
                .commit()
            if (parts.size == 1) sms.sendTextMessage(destination, null, message, intents[0], null)
            else sms.sendMultipartTextMessage(destination, null, parts, intents, null)
            EventLog.record(this, "Manual test SMS requested")
            locationMessage = "SMS requested via Android's default SMS SIM."
        } catch (e: Exception) {
            prefs.edit().putBoolean("pending", false)
                .putString("result", "SMS could not start: ${e.message ?: e.javaClass.simpleName}").apply()
            locationMessage = "SMS send error: ${e.message ?: e.javaClass.simpleName}"
            EventLog.record(this, "Manual test SMS could not start: ${e.javaClass.simpleName}")
        }
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!AppSession.isUnlocked()) { pendingAction = null; showAuth(); return }
        when (requestCode) {
            100 -> {
                val action = pendingAction
                pendingAction = null
                if (has(Manifest.permission.ACCESS_FINE_LOCATION)) beginLocation(action == "send")
                else locationMessage = "Precise location permission denied; a fresh GPS fix is required. No SMS was sent."
            }
            101 -> {
                pendingAction = null
                if (has(Manifest.permission.SEND_SMS)) beginLocation(true)
                else locationMessage = "SMS permission denied. No SMS was sent."
            }
        }
        refresh()
    }

    private fun refresh() {
        if (!showingHome || !::policyStatus.isInitialized) return
        if (!AppSession.isUnlocked()) { showAuth(); return }
        if (prefs.getBoolean("pending", false) &&
            SystemClock.elapsedRealtime() - prefs.getLong("send_started", 0) > 180_000) {
            prefs.edit().putBoolean("pending", false)
                .putString("result", "No carrier result after 3 minutes. Delivery is unknown; check the recipient before retrying.")
                .apply()
        }
        val p = FamilyConfig.prefs(this)
        policyStatus.text = """
            Device Owner: ${if (PolicyStatus.isOwner(this)) "Active" else "Unavailable"}
            Mobile-network restriction: ${PolicyStatus.restriction(this)}
            Internet: ${if (FallbackStatus.hasValidatedInternet(this)) "Connected" else "No validated connection"}
            Automatic sharing: ${FallbackStatus.sharingState(this)}
            Sharing permissions: ${if (PermissionProtection.isProtected(this)) "Protected by Device Owner" else "Managed in Android Settings"}
            Last error: ${p.getString("error", "None")}
        """.trimIndent()
        val fix = lastFix
        locationStatus.text = """
            Location: $locationMessage
            ${fix?.let { "Fix: ${DateFormat.getDateTimeInstance().format(Date(it.time))}, ±${it.accuracy.toInt()} m\n${String.format(Locale.US, "%.6f, %.6f", it.latitude, it.longitude)}" } ?: "No current fix"}
            Test SMS: ${prefs.getString("result", "Not sent yet")}
        """.trimIndent()
        sendButton.isEnabled = !busy && !prefs.getBoolean("pending", false)
    }

    private fun formatTime(value: Long): String =
        if (value == 0L) "None" else DateFormat.getDateTimeInstance().format(Date(value))
}
