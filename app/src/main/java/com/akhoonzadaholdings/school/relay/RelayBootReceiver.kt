package com.akhoonzadaholdings.school.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (RelayPrefs.isConfigured(context) && RelayPrefs.isEnabled(context)) {
            RelayForegroundService.start(context)
            RelayWatchdog.schedule(context)
        }
    }
}
