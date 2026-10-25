package com.novaghost.family

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.novaghost.family.core.NetworkMonitor
import com.novaghost.family.core.Notifier
import com.novaghost.family.core.Prefs
import com.novaghost.family.data.Cloud
import com.novaghost.family.data.NovaDb
import com.novaghost.family.work.SyncScheduler

class NovaApp : Application() {
    val prefs by lazy { Prefs(this) }
    val db by lazy { Room.databaseBuilder(this, NovaDb::class.java, "nova.db").build() }
    val cloud by lazy { Cloud(this) }
    val network by lazy { NetworkMonitor(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        if (prefs.selfManaged || (prefs.roomId != null && prefs.deviceId != null)) SyncScheduler.ensure(this)
    }
}

val Context.nova: NovaApp get() = applicationContext as NovaApp
