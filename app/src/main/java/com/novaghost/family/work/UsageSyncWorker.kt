package com.novaghost.family.work

import android.content.Context
import androidx.work.*
import com.novaghost.family.admin.DeviceControl
import com.novaghost.family.core.Notifier
import com.novaghost.family.data.PolicyEngine
import com.novaghost.family.data.AlertKind
import com.novaghost.family.data.UsageCacheEntity
import com.novaghost.family.data.UsageRepo
import com.novaghost.family.nova
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun ensure(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UsageSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nova-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }
    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<UsageSyncWorker>().build())
    }
    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork("nova-sync") }
}

/**
 * Runs about every 15 minutes (WorkManager minimum). Limit/bedtime alerts therefore have up to
 * ~15 min granularity and may be delayed by battery optimisation. The UI says so.
 */
class UsageSyncWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val app = applicationContext.nova
        val prefs = app.prefs
        val room = prefs.roomId
        val dev = prefs.deviceId
        val paired = app.cloud.configured && room != null && dev != null
        if (!paired && !prefs.selfManaged) return Result.success()

        if (paired) {
            runCatching { app.cloud.fetchPolicy(room!!, dev!!) }.getOrNull()?.let { prefs.policy = it }
        }
        val repo = UsageRepo(applicationContext)
        val allowed = prefs.selfManaged || prefs.cUsage
        val usage = if (allowed) repo.today() else null
        val policy = prefs.policy
        val control = DeviceControl(applicationContext)
        val now = LocalDateTime.now()
        val nowMin = now.hour * 60 + now.minute

        if (usage != null) {
            app.db.dao().putUsage(UsageCacheEntity("local|${usage.date}", usage.toJson(), System.currentTimeMillis()))
            for (a in PolicyEngine.evaluate(policy, usage, nowMin, usage.date)) {
                val isBed = a.kind == AlertKind.BEDTIME_NOW || a.kind == AlertKind.BEDTIME_SOON || a.kind == AlertKind.STUDY_TIME
                val enabled = if (isBed) prefs.notifyBedtime else prefs.notifyLimits
                if (enabled && !prefs.wasAlerted(a.key)) {
                    Notifier.show(applicationContext, Notifier.CH_LIMITS, a.key.hashCode(), a.title, a.text)
                    prefs.markAlert(a.key)
                }
            }
            // Optional enforcement, only with the authorisation Android requires.
            val downtimeOn = policy.bedtimeEnabled && policy.downtimeLock && (prefs.selfManaged || prefs.cDowntime)
            if (downtimeOn && PolicyEngine.inWindow(nowMin, policy.bedtimeStart, policy.bedtimeEnd)) control.lockNow()
            if (control.isDeviceOwner()) {
                if (usage.gamingMin >= policy.gamingLimitMin && policy.gamingLimitMin > 0) {
                    val games = repo.gamePackages(usage)
                    if (control.setSuspended(games, true) > 0) prefs.suspended = games.joinToString(",")
                } else prefs.suspended?.takeIf { it.isNotBlank() }?.let {
                    control.setSuspended(it.split(","), false); prefs.suspended = null
                }
            }
        }

        if (paired) {
            val (bat, chg) = repo.battery()
            app.cloud.heartbeat(room!!, dev!!, bat, chg, prefs.consent)
            if (usage != null && prefs.cUsage) app.cloud.uploadUsage(room, dev, usage)
        }
        return Result.success()
    }
}
