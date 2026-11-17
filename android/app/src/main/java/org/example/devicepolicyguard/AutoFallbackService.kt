package org.example.devicepolicyguard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Visible opt-in service. Timing is best effort; Android may delay or stop background work. */
class AutoFallbackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var offlineSince = 0L
    private var finding = false
    private var signal: CancellationSignal? = null
    private var gpsTimeout: Runnable? = null
    private var lastInternetAvailable: Boolean? = null
    private var defaultNetwork: Network? = null
    private var networkCallbackRegistered = false
    private var monitoringStarted = false
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == defaultNetwork) onNetworkChanged(
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == null || network == defaultNetwork) {
                defaultNetwork = null
                onNetworkChanged(false)
            }
        }
    }
    private val offlineTimer = Runnable { onNetworkChanged(FallbackStatus.hasValidatedInternet(this)) }
    // Only used if Android rejects callback registration.
    private val fallbackPoll = object : Runnable {
        override fun run() {
            onNetworkChanged(FallbackStatus.hasValidatedInternet(this@AutoFallbackService))
            handler.postDelayed(this, 15_000)
        }
    }
    private val p by lazy { FamilyConfig.prefs(this) }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "interval_minutes" || key == "batch_pending") {
            handler.post { if (offlineSince != 0L) evaluateOffline() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.record(this, "Service created; automatic SMS enabled=${FamilyConfig.enabled(this)}")
        val channel = NotificationChannel(CHANNEL, "Family policy guard",
            NotificationManager.IMPORTANCE_LOW)
        val warningChannel = NotificationChannel(WARNING_CHANNEL, "Internet connection",
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(channel)
            createNotificationChannel(warningChannel)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.record(this, "Service cannot start: Precise Location permission missing; no SMS")
            p.edit().putString("error", "Precise Location permission missing; service not started").apply()
            stopWithoutLocation()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.record(this, "Service cannot start: Background Location permission missing; no SMS")
            p.edit().putString("error", "Background Location permission missing; service not started")
                .putString("service_status", "Stopped: background Location permission missing").apply()
            stopWithoutLocation()
            return
        }
        try {
            startForeground(NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            DiagnosticLog.record(this, "Foreground service could not start: ${e.javaClass.simpleName}: ${e.message}; no SMS")
            p.edit().putString("error", "Foreground service could not start: ${e.javaClass.simpleName}: ${e.message}")
                .putBoolean("enabled", false).apply()
            stopWithoutLocation()
            return
        }
        status("Running with visible notification")
    }

    private fun stopWithoutLocation() {
        // Satisfy startForegroundService's deadline even when a permission was revoked
        // between the caller's check and service creation.
        try {
            startForeground(NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } catch (e: Exception) {
            p.edit().putString("error", "Could not start paused service: ${e.javaClass.simpleName}: ${e.message}").apply()
        }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLog.record(this, "Service start command; startId=$startId restart=${intent == null}")
        if (!FamilyConfig.enabled(this) ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.record(this, "Service stopped: sharing disabled or Location permission missing; no SMS")
            stopSelf()
            return START_NOT_STICKY
        }
        FallbackWatchdogReceiver.schedule(this)
        if (!monitoringStarted) {
            monitoringStarted = true
            p.registerOnSharedPreferenceChangeListener(preferenceListener)
            try {
                connectivity.registerDefaultNetworkCallback(networkCallback, handler)
                networkCallbackRegistered = true
            } catch (e: Exception) {
                EventLog.record(this, "Network callback unavailable: ${e.javaClass.simpleName}; using polling")
                handler.post(fallbackPoll)
            }
            onNetworkChanged(FallbackStatus.hasValidatedInternet(this))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DiagnosticLog.record(this, "Service destroyed; automatic SMS enabled=${FamilyConfig.enabled(this)}")
        if (networkCallbackRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        if (monitoringStarted) p.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        handler.removeCallbacksAndMessages(null)
        signal?.cancel()
        getSystemService(NotificationManager::class.java).cancel(WARNING_NOTIFICATION_ID)
        val detail = if (FamilyConfig.enabled(this) &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            "Stopped: background Location permission missing" else "Stopped; automatic restart scheduled if enabled"
        if (FamilyConfig.enabled(this) &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
            FallbackWatchdogReceiver.schedule(this, 60_000L)
        status(detail)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.record(this, "App task removed; service restart check scheduled")
        if (FamilyConfig.enabled(this)) FallbackWatchdogReceiver.schedule(this, 60_000L)
        super.onTaskRemoved(rootIntent)
    }

    private fun notification(): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Family policy guard active")
            .setContentIntent(launch)
            .setOngoing(true)
            .build()
    }

    private fun status(text: String) {
        if (p.getString("service_status", null) != text)
            p.edit().putString("service_status", text).apply()
    }

    private fun internetWarning(): Notification {
        val openSettings = PendingIntent.getActivity(this, 1,
            Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, WARNING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("No internet")
            .setContentText("Connect to Wi-Fi or mobile data")
            .setContentIntent(openSettings)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_manage),
                "Internet settings", openSettings).build())
            .setOngoing(true)
            .setFlag(Notification.FLAG_NO_CLEAR, true)
            .build()
    }

    private fun updateInternetWarning(available: Boolean) {
        if (lastInternetAvailable == available) return
        lastInternetAvailable = available
        val manager = getSystemService(NotificationManager::class.java)
        try {
            if (available) manager.cancel(WARNING_NOTIFICATION_ID)
            else manager.notify(WARNING_NOTIFICATION_ID, internetWarning())
        } catch (e: Exception) {
            EventLog.record(this, "Internet warning notification failed: ${e.javaClass.simpleName}")
        }
    }

    private fun onNetworkChanged(internetAvailable: Boolean) {
        if (!FamilyConfig.enabled(this)) { stopSelf(); return }
        if (lastInternetAvailable != internetAvailable)
            DiagnosticLog.record(this, if (internetAvailable) "Validated internet available; no automatic SMS"
                else "Validated internet unavailable; offline SMS timer active")
        updateInternetWarning(internetAvailable)
        if (internetAvailable) {
            handler.removeCallbacks(offlineTimer)
            offlineSince = 0L
            p.edit().remove("offline_since_wall").apply()
            if (finding) {
                finding = false
                signal?.cancel()
                signal = null
                gpsTimeout?.let(handler::removeCallbacks)
                gpsTimeout = null
            }
            status("Internet available; SMS paused")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (offlineSince == 0L) {
            val wall = System.currentTimeMillis()
            val saved = p.getLong("offline_since_wall", 0L)
            val firstDelayMs = minOf(FamilyConfig.intervalMinutes(this), 2) * 60_000L
            val elapsed = if (saved > 0L) (wall - saved).coerceIn(0L, firstDelayMs) else 0L
            offlineSince = now - elapsed
            if (saved == 0L) p.edit().putLong("offline_since_wall", wall).apply()
            DiagnosticLog.record(this, "Offline wait started; restored elapsed=${elapsed / 1000}s")
            val delayMinutes = minOf(FamilyConfig.intervalMinutes(this), 2)
            status("Internet unavailable; waiting $delayMinutes minute${if (delayMinutes == 1) "" else "s"} before first attempt")
        }
        evaluateOffline()
    }

    private fun scheduleOfflineCheck(delayMs: Long, reason: String) {
        handler.removeCallbacks(offlineTimer)
        DiagnosticLog.record(this, "No SMS yet: $reason; next check in ${delayMs.coerceAtLeast(1_000L) / 1000}s")
        handler.postDelayed(offlineTimer, delayMs.coerceAtLeast(1_000L))
    }

    private fun evaluateOffline() {
        handler.removeCallbacks(offlineTimer)
        if (offlineSince == 0L || !FamilyConfig.enabled(this)) return
        if (FallbackStatus.hasValidatedInternet(this)) {
            onNetworkChanged(true)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val firstDelayMs = minOf(FamilyConfig.intervalMinutes(this), 2) * 60_000L
        if (now - offlineSince < firstDelayMs) {
            scheduleOfflineCheck(firstDelayMs - (now - offlineSince), "initial offline wait")
            return
        }
        if (finding) {
            DiagnosticLog.record(this, "No SMS yet: waiting for fresh GPS fix")
            return
        }
        if (p.getBoolean("batch_pending", false)) {
            val age = System.currentTimeMillis() - p.getLong("attempt_time", 0)
            if (age < 180_000) {
                scheduleOfflineCheck(180_000 - age, "waiting for carrier result")
                return
            }
            p.edit().putBoolean("batch_pending", false)
                .putString("send_result", "Carrier result timed out; delivery unknown").apply()
            DiagnosticLog.record(this, "Carrier result timed out; delivery unknown")
        }
        val intervalMs = FamilyConfig.intervalMinutes(this) * 60_000L
        val wall = System.currentTimeMillis()
        // Persist the next attempt before doing any work to avoid a flood after process restart.
        val persistedNext = p.getLong("next_attempt_wall", 0)
        if (wall < persistedNext) {
            scheduleOfflineCheck(persistedNext - wall, "configured SMS interval")
            return
        }
        val saved = p.edit().putLong("attempt_time", wall).putLong("next_attempt_wall", wall + intervalMs)
            .putString("send_result", "Obtaining fresh GPS fix").putString("error", "None").commit()
        if (!saved) {
            fail("Could not save attempt state; no SMS sent")
            scheduleOfflineCheck(60_000, "attempt state could not be saved")
            return
        }
        requestFix()
        if (!finding) evaluateOffline()
    }

    private fun requestFix() {
        DiagnosticLog.record(this, "Automatic SMS attempt: checking permissions, SIM recipients, and GPS")
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            fail("Precise Location permission missing; no SMS sent"); return
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            fail("SMS permission missing; no SMS sent"); return
        }
        if (FamilyConfig.recipients(this).isEmpty()) {
            fail("No recipients configured; no SMS sent"); return
        }
        val manager = getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            fail("GPS/Location disabled; no SMS sent"); return
        }
        finding = true
        DiagnosticLog.record(this, "Requesting fresh GPS fix; 90s timeout")
        status("Offline; waiting for fresh GPS fix")
        val started = SystemClock.elapsedRealtimeNanos()
        val current = CancellationSignal()
        signal = current
        val timeout = Runnable {
            if (signal === current && finding) {
                finding = false
                signal = null
                gpsTimeout = null
                current.cancel()
                fail("No fresh GPS fix in 90 seconds; no SMS sent")
                scheduleOfflineCheck(15_000, "GPS timed out")
            }
        }
        gpsTimeout = timeout
        handler.postDelayed(timeout, 90_000)
        try {
            manager.getCurrentLocation(LocationManager.GPS_PROVIDER, current, mainExecutor) { fix ->
                handler.removeCallbacks(timeout)
                if (signal !== current) return@getCurrentLocation
                finding = false
                signal = null
                gpsTimeout = null
                try {
                    if (FallbackStatus.hasValidatedInternet(this)) {
                        onNetworkChanged(true)
                        return@getCurrentLocation
                    }
                    if (fix == null || fix.elapsedRealtimeNanos < started) {
                        fail("No fresh GPS fix; no SMS sent")
                        return@getCurrentLocation
                    }
                    p.edit().putLong("fix_time", fix.time)
                        .putString("fix_summary", String.format(Locale.US, "%.6f, %.6f ±%.0f m",
                            fix.latitude, fix.longitude, fix.accuracy)).apply()
                    DiagnosticLog.record(this, "Fresh GPS fix received; preparing SMS")
                    sendBatch(fix)
                } finally {
                    if (offlineSince != 0L) evaluateOffline()
                }
            }
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            finding = false
            signal = null
            gpsTimeout = null
            fail("GPS error: ${e.javaClass.simpleName}: ${e.message}; no SMS sent")
            evaluateOffline()
        }
    }

    private fun fail(reason: String) {
        p.edit().putString("send_result", "No SMS sent")
            .putString("error", reason).apply()
        EventLog.record(this, "Automatic SMS: $reason")
        status("Offline; $reason")
    }

    private fun sentIntent(token: Long, index: String, delivered: Boolean): PendingIntent {
        val kind = if (delivered) "delivered" else "sent"
        val action = if (delivered) AutoSmsResultReceiver.ACTION_DELIVERED else AutoSmsResultReceiver.ACTION_SENT
        val intent = Intent(this, AutoSmsResultReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("policyguard://auto/$token/$kind/$index")
            putExtra("token", token)
            putExtra("index", index)
        }
        return PendingIntent.getBroadcast(this, index.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun sendBatch(fix: Location) {
        if (FallbackStatus.hasValidatedInternet(this)) {
            DiagnosticLog.record(this, "Internet returned before SMS send; no SMS")
            status("Internet returned; SMS cancelled")
            return
        }
        val subId = SmsManager.getDefaultSmsSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            fail("No default SMS SIM; set one in Android SIM settings"); return
        }
        val recipients = FamilyConfig.recipients(this)
        val sms = try { getSystemService(SmsManager::class.java).createForSubscriptionId(subId) }
        catch (e: Exception) { fail("SMS SIM unavailable: ${e.message}"); return }
        val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(fix.time))
        val coordinates = String.format(Locale.US, "%.6f,%.6f", fix.latitude, fix.longitude)
        val link = "https://www.google.com/maps/search/?api=1&query=${Uri.encode(coordinates)}"
        val body = "Family location sharing\nTime: $time\nAccuracy: ±${fix.accuracy.toInt()} m\nBattery: ${BatteryStatus.percentage(this)}\n$link"
        val plans = try { recipients.map { it to sms.divideMessage(body) } }
        catch (e: Exception) { fail("Could not divide SMS: ${e.message}"); return }
        val expected = plans.sumOf { it.second.size }
        if (expected == 0) { fail("Empty SMS; no message sent"); return }
        val token = SystemClock.elapsedRealtimeNanos()
        val saved = p.edit().putLong("batch_token", token).putInt("expected_parts", expected)
            .putInt("sent_done", 0).putInt("sent_failures", 0)
            .putInt("delivery_done", 0).putInt("delivery_failures", 0)
            .putStringSet("seen_sent", emptySet()).putStringSet("seen_delivery", emptySet())
            .putString("batch_errors", "").putBoolean("batch_pending", true)
            .putString("send_result", "Sending to ${recipients.size} recipients")
            .putString("delivery_result", "Awaiting carrier reports").commit()
        if (!saved) { fail("Could not save SMS batch state; no SMS sent"); return }
        EventLog.record(this, "Automatic SMS requested for ${recipients.size} recipients")
        plans.forEachIndexed { recipientIndex, (destination, parts) ->
            val sent = ArrayList<PendingIntent>()
            val delivered = ArrayList<PendingIntent>()
            parts.indices.forEach { part ->
                val index = "$recipientIndex-$part"
                sent.add(sentIntent(token, index, false))
                delivered.add(sentIntent(token, index, true))
            }
            try {
                if (parts.size == 1)
                    sms.sendTextMessage(destination, null, body, sent[0], delivered[0])
                else
                    sms.sendMultipartTextMessage(destination, null, parts, sent, delivered)
            } catch (e: Exception) {
                DiagnosticLog.record(this, "SMS request failed for recipient ${recipientIndex + 1}: ${e.javaClass.simpleName}: ${e.message}")
                val old = p.getString("batch_errors", "").orEmpty()
                val done = p.getInt("sent_done", 0) + parts.size
                p.edit().putInt("sent_done", done)
                    .putInt("sent_failures", p.getInt("sent_failures", 0) + parts.size)
                    .putBoolean("batch_pending", done < expected)
                    .putString("batch_errors", old + "Recipient ${recipientIndex + 1}: ${e.javaClass.simpleName}: ${e.message}; ")
                    .putString("send_result", "$done/$expected parts reported, send error")
                    .putString("error", "SMS send failed: ${e.javaClass.simpleName}: ${e.message}")
                    .apply()
            }
        }
        status("Offline; SMS send attempted to ${recipients.size} recipients")
    }

    companion object {
        private const val CHANNEL = "family_location_sharing"
        private const val NOTIFICATION_ID = 102
        private const val WARNING_CHANNEL = "internet_connection"
        private const val WARNING_NOTIFICATION_ID = 103
    }
}
