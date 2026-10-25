package com.novaghost.family.core

import org.json.JSONArray
import org.json.JSONObject

enum class Role { PARENT, CHILD }

data class Policy(
    val dailyGoalMin: Int = 180,
    val gamingLimitMin: Int = 60,
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: Int = 21 * 60 + 30,
    val bedtimeEnd: Int = 7 * 60,
    val studyEnabled: Boolean = false,
    val studyStart: Int = 16 * 60,
    val studyEnd: Int = 18 * 60,
    val downtimeLock: Boolean = false
) {
    fun toMap(): Map<String, Any> = mapOf(
        "dailyGoalMin" to dailyGoalMin, "gamingLimitMin" to gamingLimitMin,
        "bedtimeEnabled" to bedtimeEnabled, "bedtimeStart" to bedtimeStart, "bedtimeEnd" to bedtimeEnd,
        "studyEnabled" to studyEnabled, "studyStart" to studyStart, "studyEnd" to studyEnd,
        "downtimeLock" to downtimeLock
    )

    companion object {
        fun fromMap(m: Map<String, Any?>?): Policy {
            val d = Policy()
            if (m == null) return d
            fun i(k: String, def: Int) = (m[k] as? Number)?.toInt() ?: def
            fun b(k: String, def: Boolean) = (m[k] as? Boolean) ?: def
            return Policy(
                i("dailyGoalMin", d.dailyGoalMin), i("gamingLimitMin", d.gamingLimitMin),
                b("bedtimeEnabled", d.bedtimeEnabled), i("bedtimeStart", d.bedtimeStart), i("bedtimeEnd", d.bedtimeEnd),
                b("studyEnabled", d.studyEnabled), i("studyStart", d.studyStart), i("studyEnd", d.studyEnd),
                b("downtimeLock", d.downtimeLock)
            )
        }
    }
}

/** What the CHILD has agreed to share. Only the child device can change these. */
data class Consent(
    val usageReports: Boolean = false,
    val reminders: Boolean = false,
    val downtime: Boolean = false,
    val screenShare: Boolean = false
) {
    fun toMap(): Map<String, Any> = mapOf(
        "usageReports" to usageReports, "reminders" to reminders,
        "downtime" to downtime, "screenShare" to screenShare
    )

    companion object {
        fun fromMap(m: Map<String, Any?>?): Consent = Consent(
            m?.get("usageReports") as? Boolean ?: false, m?.get("reminders") as? Boolean ?: false,
            m?.get("downtime") as? Boolean ?: false, m?.get("screenShare") as? Boolean ?: false
        )
    }
}

data class AppUsage(val packageName: String, val label: String, val minutes: Int, val category: String)

data class DailyUsage(
    val date: String,
    val totalMin: Int,
    val gamingMin: Int,
    val entertainmentMin: Int,
    val topApps: List<AppUsage>,
    val updatedAt: Long
) {
    fun toMap(): Map<String, Any> = mapOf(
        "date" to date, "totalMin" to totalMin, "gamingMin" to gamingMin, "entertainmentMin" to entertainmentMin,
        "topApps" to topApps.map {
            mapOf("pkg" to it.packageName, "label" to it.label, "min" to it.minutes, "cat" to it.category)
        }
    )

    fun toJson(): String {
        val o = JSONObject()
        o.put("date", date).put("total", totalMin).put("gaming", gamingMin)
            .put("ent", entertainmentMin).put("updated", updatedAt)
        val arr = JSONArray()
        topApps.forEach {
            arr.put(JSONObject().put("p", it.packageName).put("l", it.label).put("m", it.minutes).put("c", it.category))
        }
        o.put("apps", arr)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): DailyUsage? = try {
            val o = JSONObject(s)
            val arr = o.optJSONArray("apps") ?: JSONArray()
            val apps = (0 until arr.length()).map {
                val a = arr.getJSONObject(it)
                AppUsage(a.getString("p"), a.getString("l"), a.getInt("m"), a.getString("c"))
            }
            DailyUsage(o.getString("date"), o.getInt("total"), o.getInt("gaming"), o.getInt("ent"), apps, o.getLong("updated"))
        } catch (e: Exception) {
            null
        }
    }
}

data class RoomInfo(val id: String, val name: String, val ownerUid: String)
data class DeviceInfo(
    val id: String, val name: String, val childUid: String,
    val battery: Int, val charging: Boolean, val lastSeen: Long, val consent: Consent
)
data class ReminderItem(
    val id: String, val deviceId: String, val message: String,
    val scheduledAt: Long, val status: String, val createdAt: Long, val deliveredAt: Long
)
data class AuditItem(val id: String, val actorUid: String, val action: String, val target: String, val at: Long)
data class Invite(val code: String, val expiresAt: Long)
data class JoinResult(val roomId: String, val deviceId: String, val roomName: String)
data class ShareInfo(val status: String, val session: String?)
data class LocalPerms(
    val usageAccess: Boolean = false, val notifications: Boolean = false,
    val adminActive: Boolean = false, val deviceOwner: Boolean = false
)

enum class Freshness { LIVE, DELAYED, UNAVAILABLE }

object Fmt {
    fun minutes(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    fun clock(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)

    fun freshness(updatedAt: Long, now: Long): Freshness = when {
        updatedAt <= 0L -> Freshness.UNAVAILABLE
        now - updatedAt <= 20 * 60_000L -> Freshness.LIVE
        else -> Freshness.DELAYED
    }

    fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0L) return "never"
        val m = ((now - ts) / 60_000L).coerceAtLeast(0)
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 1440 -> "${m / 60} h ago"
            else -> "${m / 1440} d ago"
        }
    }
}

object InvitePayload {
    const val PREFIX = "novaghost://join?code="
    private val CODE = Regex("^[A-Z0-9]{8}$")
    fun encode(code: String) = PREFIX + code

    /** Accepts a scanned QR payload or a typed code; returns a normalized code or null. */
    fun parse(raw: String): String? {
        val t = raw.trim()
        val body = if (t.startsWith(PREFIX)) t.removePrefix(PREFIX) else t
        val norm = body.uppercase().replace("-", "").replace(" ", "")
        return if (CODE.matches(norm)) norm else null
    }
}
