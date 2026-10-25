package com.novaghost.family.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Encrypted local settings (AES-256 via Android Keystore). */
class Prefs(context: Context) {
    private val sp: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "nova_secure_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Keystore can fail on some devices/after restores. Only non-credential settings live here.
        Log.w("Prefs", "Encrypted prefs unavailable, using private plain prefs", e)
        context.getSharedPreferences("nova_prefs_fallback", Context.MODE_PRIVATE)
    }

    private fun str(k: String) = object : ReadWriteProperty<Any?, String?> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String? = sp.getString(k, null)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) {
            sp.edit().putString(k, value).apply()
        }
    }

    private fun bool(k: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(k, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(k, value).apply()
        }
    }

    var roomId by str("roomId")
    var deviceId by str("deviceId")
    var selfManaged by bool("selfManaged", false)

    var accent by str("accent")
    var reduceMotion by bool("reduceMotion", false)
    var largeText by bool("largeText", false)
    var highContrast by bool("highContrast", false)
    var notifyLimits by bool("notifyLimits", true)
    var notifyBedtime by bool("notifyBedtime", true)

    var cUsage by bool("cUsage", false)
    var cReminders by bool("cReminders", false)
    var cDowntime by bool("cDowntime", false)
    var cShare by bool("cShare", false)

    private var policyJson by str("policyJson")
    var suspended by str("suspendedPkgs")

    var policy: Policy
        get() {
            val j = policyJson ?: return Policy()
            return try {
                val o = JSONObject(j)
                Policy.fromMap(o.keys().asSequence().associateWith { k -> o.get(k) })
            } catch (e: Exception) {
                Policy()
            }
        }
        set(v) {
            policyJson = JSONObject(v.toMap()).toString()
        }

    var consent: Consent
        get() = Consent(cUsage, cReminders, cDowntime, cShare)
        set(c) {
            cUsage = c.usageReports; cReminders = c.reminders; cDowntime = c.downtime; cShare = c.screenShare
        }

    fun wasAlerted(key: String): Boolean = (sp.getStringSet("alerts", emptySet()) ?: emptySet()).contains(key)
    fun markAlert(key: String) {
        val day = key.substringBefore('|')
        val s = (sp.getStringSet("alerts", emptySet()) ?: emptySet()).filter { it.startsWith(day) }.toMutableSet()
        s.add(key)
        sp.edit().putStringSet("alerts", s).apply()
    }

    fun clearPairing() {
        sp.edit().remove("roomId").remove("deviceId").remove("alerts").remove("suspendedPkgs").apply()
        consent = Consent()
    }
}
