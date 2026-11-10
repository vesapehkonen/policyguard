package org.example.devicepolicyguard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.text.InputType
import android.view.MotionEvent
import android.view.WindowManager
import android.graphics.Color
import android.graphics.Typeface
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/** All configuration and policy restoration controls require an administrator PIN. */
class AdminSettingsActivity : Activity() {
    private lateinit var column: LinearLayout
    private lateinit var message: TextView
    private lateinit var recipientRows: LinearLayout
    private var authenticated = false
    private var pendingEnable = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var lastInteraction = 0L
    private val timeout = object : Runnable {
        override fun run() {
            if (authenticated && !AppSession.isUnlocked()) showAuth()
            else if (authenticated) timeoutHandler.postDelayed(this, 10_000)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (authenticated && event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!AppSession.isUnlocked()) { showAuth(); return true }
            lastInteraction = SystemClock.elapsedRealtime()
            AppSession.touch()
        }
        return super.dispatchTouchEvent(event)
    }

    private fun unlock() {
        AppSession.unlock()
        authenticated = true
        lastInteraction = SystemClock.elapsedRealtime()
        timeoutHandler.removeCallbacks(timeout)
        timeoutHandler.postDelayed(timeout, 10_000)
        showSettings()
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
        if (AppSession.isUnlocked()) unlock() else showAuth()
    }

    override fun onStop() {
        authenticated = false
        timeoutHandler.removeCallbacks(timeout)
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        if (AppSession.isUnlocked()) unlock() else showAuth()
    }

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine(true)
    }
    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 16f }
    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 19f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.rgb(24, 68, 105))
        val gap = (20 * resources.displayMetrics.density).toInt()
        setPadding(0, gap, 0, gap / 3)
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }
    private fun reset(title: String) {
        column.removeAllViews()
        column.addView(TextView(this).apply { text = title; textSize = 24f })
        message = label("")
        column.addView(message)
    }
    private fun showAuth() {
        AppSession.lock()
        authenticated = false
        reset("Administrator settings")
        column.addView(label("Family location sharing configuration and policy restoration require the administrator PIN."))
        if (!AdminAuth.isSet(this)) {
            val first = pinField("New PIN (6–12 digits)")
            val second = pinField("Confirm new PIN")
            column.addView(first); column.addView(second)
            column.addView(button("Create administrator PIN") {
                val a = first.text.toString().toCharArray()
                val b = second.text.toString().toCharArray()
                val error = if (!a.contentEquals(b)) "PINs do not match" else AdminAuth.set(this, a)
                a.fill('\u0000'); b.fill('\u0000')
                first.text.clear(); second.text.clear()
                if (error == null) unlock() else message.text = error
            })
        } else {
            val field = pinField("Administrator PIN")
            column.addView(field)
            column.addView(button("Unlock settings") {
                val pin = field.text.toString().toCharArray()
                val error = AdminAuth.verify(this, pin)
                pin.fill('\u0000'); field.text.clear()
                if (error == null) unlock() else message.text = error
            })
            column.addView(button("Forgot PIN? Recovery options") {
                AlertDialog.Builder(this)
                    .setTitle("Administrator PIN recovery")
                    .setMessage("There is no PIN bypass. Keep this device and its signing key secure. A trusted administrator can prepare a signed in-place maintenance update to set a new PIN after verifying physical custody. Device Owner restrictions stay active throughout; never clear app data, uninstall, or factory-reset as a PIN recovery step.")
                    .setPositiveButton("OK", null).show()
            })
        }
    }

    private fun renderRecipients() {
        recipientRows.removeAllViews()
        val saved = FamilyConfig.recipients(this)
        if (saved.isEmpty()) recipientRows.addView(label("No automatic SMS recipients yet"))
        saved.forEachIndexed { index, number ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label(number), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Edit") { editRecipient(index) })
            row.addView(button("Remove") {
                AlertDialog.Builder(this)
                    .setTitle("Remove recipient?")
                    .setMessage(number)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        if (!authenticated || !AppSession.isUnlocked()) { showAuth(); return@setPositiveButton }
                        val updated = FamilyConfig.recipients(this).toMutableList().apply { removeAt(index) }
                        val error = FamilyConfig.saveRecipients(this, updated)
                        message.text = error ?: "Recipient removed"
                        if (error == null) renderRecipients()
                    }.show()
            })
            recipientRows.addView(row)
        }
    }

    private fun editRecipient(index: Int? = null) {
        val saved = FamilyConfig.recipients(this)
        if (index == null && saved.size >= 5) { message.text = "Maximum five recipients"; return }
        val field = EditText(this).apply {
            hint = "+15551234567"
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            if (index != null) setText(saved[index])
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (index == null) "Add recipient" else "Edit recipient")
            .setMessage("International number, for example +15551234567")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!authenticated || !AppSession.isUnlocked()) { dialog.dismiss(); showAuth(); return@setOnClickListener }
                val updated = FamilyConfig.recipients(this).toMutableList()
                if (index == null) updated.add(field.text.toString()) else updated[index] = field.text.toString()
                val error = FamilyConfig.saveRecipients(this, updated)
                if (error == null) {
                    message.text = "Recipient saved"
                    EventLog.record(this, "Automatic SMS recipient list updated")
                    renderRecipients()
                    dialog.dismiss()
                } else field.error = error
            }
        }
        dialog.show()
    }

    private fun showSettings() {
        if (!authenticated || !AppSession.isUnlocked()) { showAuth(); return }
        reset("Administrator settings")
        column.addView(section("Family location sharing"))
        column.addView(label("Automatic sharing sends a fresh location by SMS only while validated internet is unavailable. An ongoing notification identifies sharing."))
        column.addView(label("Automatic SMS: ${if (FamilyConfig.enabled(this)) "Enabled" else "Disabled"}"))
        column.addView(label("SMS interval while offline"))
        val interval = Spinner(this)
        interval.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("1 minute", "5 minutes", "10 minutes", "15 minutes", "30 minutes"))
        interval.setSelection(FamilyConfig.intervals.indexOf(FamilyConfig.intervalMinutes(this)).coerceAtLeast(0))
        interval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!authenticated || !AppSession.isUnlocked()) return
                val chosen = FamilyConfig.intervals[position]
                if (chosen == FamilyConfig.intervalMinutes(this@AdminSettingsActivity)) return
                val error = FamilyConfig.saveInterval(this@AdminSettingsActivity, chosen)
                message.text = error ?: "SMS interval saved: $chosen minute${if (chosen == 1) "" else "s"}"
                if (error != null) interval.setSelection(FamilyConfig.intervals.indexOf(FamilyConfig.intervalMinutes(this@AdminSettingsActivity)))
                else EventLog.record(this@AdminSettingsActivity, "Automatic SMS interval changed to $chosen minutes")
            }
        }
        column.addView(interval)
        column.addView(label("Recipients (international format, up to five)"))
        recipientRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(recipientRows)
        renderRecipients()
        column.addView(button("+ Add recipient") { editRecipient() })
        val fallbackSwitch = Switch(this).apply { text = "Automatic offline location SMS"; isChecked = FamilyConfig.enabled(this@AdminSettingsActivity) }
        fallbackSwitch.setOnCheckedChangeListener { _, checked ->
            if (!authenticated) { showAuth(); return@setOnCheckedChangeListener }
            if (checked) enableFallback()
            else {
                val saved = FamilyConfig.prefs(this).edit().putBoolean("enabled", false).commit()
                if (saved) {
                    stopService(Intent(this, AutoFallbackService::class.java))
                    EventLog.record(this, "Automatic SMS disabled")
                    message.text = "Automatic SMS fallback disabled"
                } else {
                    fallbackSwitch.isChecked = true
                    message.text = "Could not save fallback setting"
                }
            }
        }
        column.addView(fallbackSwitch)
        column.addView(button("Open Android app permissions") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        })
        column.addView(section("Permission protection"))
        column.addView(label("Protect SMS and Location permissions from changes in Android Settings. Turn off here to release control."))
        val permissionSwitch = Switch(this).apply {
            text = "Protect sharing permissions"
            isChecked = PermissionProtection.isProtected(this@AdminSettingsActivity)
            isEnabled = PolicyStatus.isOwner(this@AdminSettingsActivity)
        }
        val permissionReadback = label(PermissionProtection.summary(this))
        var updatingPermissions = false
        permissionSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingPermissions) return@setOnCheckedChangeListener
            if (!authenticated || !AppSession.isUnlocked()) { showAuth(); return@setOnCheckedChangeListener }
            val error = PermissionProtection.setProtected(this, checked)
            updatingPermissions = true
            permissionSwitch.isChecked = PermissionProtection.isProtected(this)
            updatingPermissions = false
            permissionReadback.text = PermissionProtection.summary(this)
            message.text = error ?: if (checked) "Sharing permissions protected by Device Owner"
                else "Sharing permissions are user controlled again; existing grants remain"
            EventLog.record(this, if (error == null) "Sharing permission protection ${if (checked) "enabled" else "disabled"}"
                else "Sharing permission protection failed")
            if (error == null && checked && FamilyConfig.enabled(this)) {
                try { startForegroundService(Intent(this, AutoFallbackService::class.java)) }
                catch (e: Exception) { message.text = "Permissions protected, but service did not start: ${e.javaClass.simpleName}" }
            }
        }
        column.addView(permissionSwitch)
        column.addView(permissionReadback)
        column.addView(section("Device restrictions"))
        policySwitch("Restrict mobile-network configuration", UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
        policySwitch("Restrict Airplane Mode", UserManager.DISALLOW_AIRPLANE_MODE)
        policySwitch("Keep Location on and restrict changes", UserManager.DISALLOW_CONFIG_LOCATION)
        column.addView(section("Security"))
        column.addView(button("Change administrator PIN") { showChangePin() })
    }

    private fun policySwitch(title: String, key: String) {
        val control = Switch(this).apply { text = title }
        val readback = label("Android policy: ${PolicyStatus.restrictionReadback(this, key)}")
        val state = PolicyStatus.adminRestriction(this, key)
        control.isEnabled = state != null
        control.isChecked = state == true
        var updating = false
        control.setOnCheckedChangeListener { _, checked ->
            if (updating) return@setOnCheckedChangeListener
            if (!authenticated) { showAuth(); return@setOnCheckedChangeListener }
            val error = PolicyStatus.setRestriction(this, key, checked)
            val actual = PolicyStatus.adminRestriction(this, key)
            updating = true
            control.isChecked = actual == true
            control.isEnabled = actual != null
            updating = false
            readback.text = "Android policy: ${PolicyStatus.restrictionReadback(this, key)}"
            message.text = error ?: "$title: ${if (actual == true) "enabled" else "disabled"} (verified)"
            EventLog.record(this, "$title: ${error ?: if (actual == true) "enabled" else "disabled"}")
        }
        column.addView(control)
        column.addView(readback)
        if (state == null) column.addView(label("$title: policy state unavailable; Device Owner required"))
    }

    private fun enableFallback() {
        if (FamilyConfig.recipients(this).isEmpty()) {
            showSettings()
            message.text = "Add at least one recipient before enabling automatic SMS"
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            message.text = "Grant Location access 'Allow all the time' in Android app settings before enabling background sharing"
            showSettings()
            message.text = "Grant Location access 'Allow all the time' in Android app settings before enabling background sharing"
            return
        }
        val missing = arrayListOf<String>()
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.SEND_SMS)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (missing.isNotEmpty()) {
            pendingEnable = true
            requestPermissions(missing.toTypedArray(), 201)
            return
        }
        try {
            if (!FamilyConfig.prefs(this).edit().putBoolean("enabled", true).commit()) {
                message.text = "Could not save fallback setting"; return
            }
            startForegroundService(Intent(this, AutoFallbackService::class.java))
            EventLog.record(this, "Automatic SMS enabled")
            showSettings()
            message.text = "Automatic SMS fallback requested; check service status below"
        } catch (e: Exception) {
            FamilyConfig.prefs(this).edit().putBoolean("enabled", false).apply()
            showSettings()
            message.text = "Could not start fallback: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 201 && pendingEnable) {
            pendingEnable = false
            if (authenticated && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                enableFallback()
            else if (::message.isInitialized) {
                if (authenticated) showSettings()
                message.text = "Unlock again and grant SMS and precise Location permissions; fallback remains disabled"
            }
        }
    }

    private fun showChangePin() {
        if (!authenticated) return
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val old = pinField("Current PIN")
        val next = pinField("New PIN (6–12 digits)")
        val confirm = pinField("Confirm new PIN")
        fields.addView(old); fields.addView(next); fields.addView(confirm)
        AlertDialog.Builder(this).setTitle("Change administrator PIN").setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Change") { _, _ ->
                if (!authenticated || SystemClock.elapsedRealtime() - lastInteraction >= 120_000) {
                    showAuth()
                    return@setPositiveButton
                }
                val oldPin = old.text.toString().toCharArray()
                val newPin = next.text.toString().toCharArray()
                val confirmation = confirm.text.toString().toCharArray()
                val error = AdminAuth.verify(this, oldPin)
                    ?: if (!newPin.contentEquals(confirmation)) "New PINs do not match" else AdminAuth.set(this, newPin)
                oldPin.fill('\u0000'); newPin.fill('\u0000'); confirmation.fill('\u0000')
                message.text = error ?: "Administrator PIN changed"
            }.show()
    }
}
