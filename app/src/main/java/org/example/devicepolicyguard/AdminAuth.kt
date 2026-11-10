package org.example.devicepolicyguard

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** The stored verifier is a salted HMAC. Its secret key stays in Android Keystore. */
object AdminAuth {
    private const val ALIAS = "device_policy_guard_admin_pin"
    private const val PREFS = "admin_auth"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isSet(context: Context) = prefs(context).contains("verifier")
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) throw IllegalStateException("Administrator key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
    }
    private fun digest(salt: ByteArray, pin: CharArray, createKey: Boolean): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key(createKey))
        mac.update(salt)
        val bytes = ByteArray(pin.size) { pin[it].code.toByte() }
        return try { mac.doFinal(bytes) } finally { bytes.fill(0) }
    }
    fun set(context: Context, pin: CharArray): String? {
        if (pin.size !in 6..12 || pin.any { it !in '0'..'9' }) return "Use a 6–12 digit administrator PIN"
        return try {
            val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val verifier = digest(salt, pin, true)
            val saved = prefs(context).edit()
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("verifier", Base64.encodeToString(verifier, Base64.NO_WRAP))
                .putInt("failures", 0).putLong("locked_until", 0).commit()
            if (saved) null else "Credential storage failed"
        } catch (e: Exception) { "Credential storage failed: ${e.javaClass.simpleName}" }
    }
    fun verify(context: Context, pin: CharArray): String? {
        val p = prefs(context)
        if (SystemClock.elapsedRealtime() < p.getLong("locked_until", 0)) return "Too many attempts; wait 60 seconds"
        return try {
            val salt = Base64.decode(p.getString("salt", ""), Base64.DEFAULT)
            val expected = Base64.decode(p.getString("verifier", ""), Base64.DEFAULT)
            val valid = MessageDigest.isEqual(expected, digest(salt, pin, false))
            if (valid) {
                p.edit().putInt("failures", 0).putLong("locked_until", 0).apply()
                null
            } else {
                val failures = p.getInt("failures", 0) + 1
                p.edit().putInt("failures", failures)
                    .putLong("locked_until", if (failures >= 5) SystemClock.elapsedRealtime() + 60_000 else 0).apply()
                "Incorrect administrator PIN"
            }
        } catch (e: Exception) { "Credential verification failed: ${e.javaClass.simpleName}" }
    }
}
