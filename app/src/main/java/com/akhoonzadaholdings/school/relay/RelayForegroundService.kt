package com.akhoonzadaholdings.school.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.akhoonzadaholdings.school.MainActivity
import com.akhoonzadaholdings.school.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers

/**
 * Keeps polling sms_gateway.php for messages addressed to this device and sends them.
 * Runs as a foreground service (not WorkManager) because notification/OTP-type messages
 * need to go out within a couple seconds, not on a 15-minute minimum WorkManager interval.
 *
 * Only ever starts if RelayPrefs.isConfigured() && isEnabled() — see RelaySettingsActivity.
 */
class RelayForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private var lastStatus: String = "Waiting for messages…"
    private var sentCount = 0
    private var failedCount = 0

    companion object {
        const val CHANNEL_ID = "relay_service_channel"
        const val NOTIFICATION_ID = 4201
        const val POLL_INTERVAL_MS = 4_000L
        const val IDLE_POLL_INTERVAL_MS = 8_000L

        fun start(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RelayPrefs.isConfigured(this) || !RelayPrefs.isEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (pollJob?.isActive != true) {
            pollJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    // Swiping the app away from recents sends this even though the service itself is a
    // separate component from any activity — without it, MIUI/most OEM launchers treat a
    // swipe as "user wants this app gone" and kill the whole process, service included,
    // no matter that it's a foreground service. START_STICKY only covers the OS reclaiming
    // memory; it does not cover this case. Re-launching here is what makes the relay survive
    // a swipe instead of quietly going dark until the next reboot.
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (RelayPrefs.isConfigured(this) && RelayPrefs.isEnabled(this)) {
            start(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            if (!RelayPrefs.isEnabled(this) || !RelayPrefs.isConfigured(this)) {
                withContext(Dispatchers.Main) { stopSelf() }
                return
            }

            val endpoint = RelayPrefs.getEndpoint(this)
            val token = RelayPrefs.getToken(this)

            var gotAny = false
            try {
                val pending = GatewayApi.fetchPending(endpoint, token)
                for (msg in pending) {
                    gotAny = true
                    // Normally resolves in well under a second once the OS reports the send
                    // result. The timeout exists only for phones (Xiaomi/MIUI in particular)
                    // whose battery manager can silently suppress that callback even inside a
                    // foreground service — without this, one such message would hang this loop
                    // forever, never acking and never moving on to anything queued after it.
                    val result = withTimeoutOrNull(30_000L) {
                        SmsSender.send(this@RelayForegroundService, msg.to, msg.message)
                    } ?: SendResult.Unconfirmed("No delivery confirmation from the OS within 30s")
                    when (result) {
                        is SendResult.Sent -> {
                            sentCount++
                            lastStatus = "Sent to ${maskNumber(msg.to)}"
                            runCatching { GatewayApi.ack(endpoint, token, msg.id, sent = true) }
                        }
                        is SendResult.Unconfirmed -> {
                            // Ack as sent, not failed/retried: SmsManager already accepted the
                            // request with no error, so the SMS most likely did go out — the
                            // only thing missing is Android's confirmation of that. Retrying
                            // here would risk texting the same person the same message twice.
                            sentCount++
                            lastStatus = "Sent to ${maskNumber(msg.to)} (unconfirmed — check phone if unsure)"
                            runCatching { GatewayApi.ack(endpoint, token, msg.id, sent = true) }
                        }
                        is SendResult.Failed -> {
                            failedCount++
                            lastStatus = "Failed: ${result.reason}"
                            runCatching { GatewayApi.ack(endpoint, token, msg.id, sent = false, error = result.reason) }
                        }
                    }
                    updateNotification()
                }
            } catch (e: GatewayApi.GatewayException) {
                lastStatus = "Gateway error: ${e.message}"
                updateNotification()
            } catch (e: Exception) {
                lastStatus = "Connection error: ${e.message}"
                updateNotification()
            }

            delay(if (gotAny) POLL_INTERVAL_MS else IDLE_POLL_INTERVAL_MS)
        }
    }

    private fun maskNumber(number: String): String =
        if (number.length > 4) "•••" + number.takeLast(4) else number

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows relay status while this device is sending school SMS notifications"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val label = RelayPrefs.getLabel(this).ifBlank { "This device" }
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SMS relay active — $label")
            .setContentText("$lastStatus  ·  ${sentCount} sent, ${failedCount} failed")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}