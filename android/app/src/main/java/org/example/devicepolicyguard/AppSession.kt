package org.example.devicepolicyguard

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock

/** In-memory unlock only. Closing the app UI or two minutes of inactivity locks it. */
object AppSession {
    private const val TIMEOUT_MS = 120_000L
    private var lastTouch = 0L

    fun unlock() { lastTouch = SystemClock.elapsedRealtime() }
    fun touch() { if (isUnlocked()) lastTouch = SystemClock.elapsedRealtime() }
    fun lock() { lastTouch = 0L }
    fun isUnlocked(): Boolean {
        if (lastTouch == 0L) return false
        if (SystemClock.elapsedRealtime() - lastTouch >= TIMEOUT_MS) {
            lock()
            return false
        }
        return true
    }
}

class GuardApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
    }
    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) AppSession.lock()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
