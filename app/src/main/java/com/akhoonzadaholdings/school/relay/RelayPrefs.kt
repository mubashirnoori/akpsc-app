package com.akhoonzadaholdings.school.relay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Local device config for the SMS relay: which endpoint/token this phone uses,
 * and whether the relay is turned on. Stored encrypted since the token is a
 * bearer credential — anyone with it can send SMS through this device's slot.
 *
 * This is per-device config only. It does not identify which staff member the
 * device belongs to; that assignment lives server-side in sms_gateway_devices
 * and is what the admin panel controls.
 */
object RelayPrefs {

    private const val FILE_NAME = "relay_secure_prefs"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LABEL = "label"

    private const val DEFAULT_ENDPOINT =
        "https://school.akhoonzadaholdings.com/api/sms_gateway.php"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getEndpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT

    fun getToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, "") ?: ""

    fun getLabel(context: Context): String =
        prefs(context).getString(KEY_LABEL, "") ?: ""

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /** True once a token has actually been entered — gates whether the relay can start at all. */
    fun isConfigured(context: Context): Boolean = getToken(context).isNotBlank()

    fun save(context: Context, endpoint: String, token: String, label: String, enabled: Boolean) {
        prefs(context).edit()
            .putString(KEY_ENDPOINT, endpoint.ifBlank { DEFAULT_ENDPOINT })
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_LABEL, label.trim())
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Wipes stored endpoint/token/label and turns the relay off — used by "Forget this device". */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
