package com.novaghost.family.data

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.BatteryManager
import android.os.Process
import com.novaghost.family.core.AppUsage
import com.novaghost.family.core.DailyUsage
import java.time.LocalDate
import java.time.ZoneId

/** Pure computation, unit-tested. */
object UsageMath {
    const val RESUMED = 1   // MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
    const val PAUSED = 2    // MOVE_TO_BACKGROUND / ACTIVITY_PAUSED

    data class Ev(val pkg: String, val type: Int, val time: Long)

    /** Foreground milliseconds per package from a time-ordered event list. */
    fun foreground(events: List<Ev>, now: Long): Map<String, Long> {
        val open = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        for (e in events) {
            when (e.type) {
                RESUMED -> if (!open.containsKey(e.pkg)) open[e.pkg] = e.time
                PAUSED -> {
                    val s = open.remove(e.pkg)
                    if (s != null) total[e.pkg] = (total[e.pkg] ?: 0L) + (e.time - s).coerceAtLeast(0L)
                }
            }
        }
        for ((p, s) in open) total[p] = (total[p] ?: 0L) + (now - s).coerceAtLeast(0L)
        return total
    }
}

class UsageRepo(private val ctx: Context) {
    private val pm = ctx.packageManager

    fun hasAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private val home: String? by lazy {
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }

    private fun info(pkg: String): ApplicationInfo? = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { null }

    private fun classify(i: ApplicationInfo?): String {
        if (i == null) return "other"
        @Suppress("DEPRECATION")
        val flagGame = (i.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        return when {
            i.category == ApplicationInfo.CATEGORY_GAME || flagGame -> "game"
            i.category == ApplicationInfo.CATEGORY_VIDEO || i.category == ApplicationInfo.CATEGORY_AUDIO ||
                i.category == ApplicationInfo.CATEGORY_SOCIAL -> "entertainment"
            else -> "other"
        }
    }

    fun forDate(date: LocalDate): DailyUsage? {
        if (!hasAccess()) return null
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        if (end <= start) return null
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, end)
        val list = ArrayList<UsageMath.Ev>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageMath.RESUMED || e.eventType == UsageMath.PAUSED) {
                list.add(UsageMath.Ev(e.packageName, e.eventType, e.timeStamp))
            }
        }
        val ms = UsageMath.foreground(list, end)
        val apps = ms.filter { it.key != ctx.packageName && it.key != home && it.key != "com.android.systemui" }
            .map { (pkg, t) ->
                val i = info(pkg)
                val label = try { if (i != null) pm.getApplicationLabel(i).toString() else pkg } catch (x: Exception) { pkg }
                AppUsage(pkg, label, (t / 60_000L).toInt(), classify(i))
            }
            .filter { it.minutes >= 1 }
            .sortedByDescending { it.minutes }
        return DailyUsage(
            date = date.toString(),
            totalMin = apps.sumOf { it.minutes },
            gamingMin = apps.filter { it.category == "game" }.sumOf { it.minutes },
            entertainmentMin = apps.filter { it.category == "entertainment" }.sumOf { it.minutes },
            topApps = apps.take(8),
            updatedAt = now
        )
    }

    fun today(): DailyUsage? = forDate(LocalDate.now())

    /** Last 7 days, oldest first. Days the system no longer retains events for come back empty. */
    fun week(): List<DailyUsage> {
        val today = LocalDate.now()
        return (6 downTo 0).mapNotNull { forDate(today.minusDays(it.toLong())) }
    }

    fun gamePackages(u: DailyUsage): List<String> = u.topApps.filter { it.category == "game" }.map { it.packageName }

    fun battery(): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(if (level < 0) -1 else level * 100 / scale, charging)
    }
}
