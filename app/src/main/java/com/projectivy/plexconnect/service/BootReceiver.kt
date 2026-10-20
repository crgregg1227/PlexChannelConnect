package com.projectivy.plexconnect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.projectivy.plexconnect.data.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = AppPreferences(context)
        if (prefs.isConfigured()) {
            SyncWorker.schedulePeriodicSync(context)
            PlexRealtimeSyncService.startService(context, forceFullSync = true)
        }
    }
}
