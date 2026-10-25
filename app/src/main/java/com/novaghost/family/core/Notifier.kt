package com.novaghost.family.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.novaghost.family.R

object Notifier {
    const val CH_LIMITS = "limits"
    const val CH_REMINDERS = "reminders"
    const val CH_SHARE = "share"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LIMITS, "Screen-time limits", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Family reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SHARE, "Screen sharing", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun show(ctx: Context, channel: String, id: Int, title: String, text: String,
             intent: android.app.PendingIntent? = null) {
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: nothing to show; UI surfaces this state.
        }
    }
}
