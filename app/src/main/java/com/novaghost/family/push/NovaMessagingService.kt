package com.novaghost.family.push

import android.app.PendingIntent
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.novaghost.family.MainActivity
import com.novaghost.family.core.Notifier
import com.novaghost.family.data.InboxEntity
import com.novaghost.family.nova
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NovaMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val app = nova
        val r = app.prefs.roomId
        val d = app.prefs.deviceId
        if (app.cloud.configured && r != null && d != null) {
            scope.launch { runCatching { app.cloud.saveToken(r, d, token) } }
        }
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val app = nova
        val prefs = app.prefs
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        when (m.data["type"]) {
            "reminder" -> {
                if (!prefs.cReminders) return // child has not consented; nothing is shown
                val id = m.data["reminderId"] ?: return
                val text = m.data["message"] ?: return
                Notifier.show(this, Notifier.CH_REMINDERS, id.hashCode(), "Family reminder", text, open)
                scope.launch {
                    app.db.dao().put(InboxEntity(id, text, System.currentTimeMillis(), "Parent"))
                    val room = prefs.roomId
                    if (room != null && app.cloud.configured) runCatching { app.cloud.markDelivered(room, id) }
                }
            }
            "share_request" -> {
                if (!prefs.cShare) return
                Notifier.show(this, Notifier.CH_SHARE, 4242, "Screen sharing requested",
                    "A parent asked to view your screen. Open the app to allow or decline.", open)
            }
        }
    }
}
