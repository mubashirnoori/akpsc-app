package com.akhoonzadaholdings.school.relay

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Safety net for OEMs (Xiaomi/MIUI, Oppo, Vivo, Huawei…) that kill the relay's
 * foreground service anyway, even after the user grants the standard Android
 * "ignore battery optimizations" exemption — that API is a *hint*, not a
 * guarantee, and several of these vendors run their own separate memory
 * cleaner on top of stock Android that isn't bound by it.
 *
 * This does not replace RelayForegroundService's own poll loop (still every
 * 4–8s while alive, and unaffected by this). It's a periodic check, roughly
 * every 15 minutes — the shortest interval AlarmManager's inexact repeating
 * API supports without needing the SCHEDULE_EXACT_ALARM permission — that
 * relaunches the service if it's supposed to be running but isn't. It won't
 * shrink an outage to zero, but it turns "dead until someone notices and
 * reopens Settings" into "dead for at most ~15 minutes."
 */
object RelayWatchdog {

    private const val REQUEST_CODE = 4202

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = pendingIntent(context)
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            pendingIntent
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RelayWatchdogReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

class RelayWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // start() itself already no-ops safely if the relay isn't configured/enabled
        // (see RelayForegroundService.onStartCommand), and is harmless to call even
        // if the service happens to still be alive — it just re-confirms foreground
        // status and leaves the existing poll loop running rather than restarting it.
        if (RelayPrefs.isConfigured(context) && RelayPrefs.isEnabled(context)) {
            RelayForegroundService.start(context)
        }
    }
}
