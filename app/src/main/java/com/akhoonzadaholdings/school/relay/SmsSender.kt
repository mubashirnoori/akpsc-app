package com.akhoonzadaholdings.school.relay

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Outcome of trying to send one message. */
sealed class SendResult {
    object Sent : SendResult()
    data class Failed(val reason: String) : SendResult()
    /** SmsManager accepted the send with no exception, but the OS never reported back
     *  whether it actually went out (seen on some OEM battery managers). Treated as a
     *  probable success rather than retried, since retrying risks sending the same SMS
     *  twice if it did in fact go out the first time. */
    data class Unconfirmed(val reason: String) : SendResult()
}

/**
 * Wraps SmsManager so callers get a real "did it actually go out" answer instead of
 * just "the send call didn't throw" — sendTextMessage returning without an exception
 * only means the OS accepted the request, not that the carrier delivered it.
 */
object SmsSender {

    private const val ACTION_SENT = "com.akhoonzadaholdings.school.relay.SMS_SENT"
    private var requestSeq = 0

    suspend fun send(context: Context, to: String, message: String): SendResult =
        suspendCancellableCoroutine { cont ->
            val appContext = context.applicationContext
            val requestId = requestSeq++
            val action = "$ACTION_SENT.$requestId"

            var resumed = false

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (resumed) return
                    resumed = true
                    try {
                        appContext.unregisterReceiver(this)
                    } catch (_: Exception) {
                    }
                    val result: SendResult = when (resultCode) {
                        android.app.Activity.RESULT_OK -> SendResult.Sent
                        SmsManager.RESULT_ERROR_NO_SERVICE -> SendResult.Failed("No cell service")
                        SmsManager.RESULT_ERROR_RADIO_OFF -> SendResult.Failed("Airplane mode / radio off")
                        SmsManager.RESULT_ERROR_NULL_PDU -> SendResult.Failed("Null PDU")
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> SendResult.Failed("Generic failure")
                        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> SendResult.Failed("Send limit exceeded")
                        else -> SendResult.Failed("SMS send failed (code $resultCode)")
                    }
                    if (cont.isActive) cont.resume(result)
                }
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, IntentFilter(action))
            }

            cont.invokeOnCancellation {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (_: Exception) {
                }
            }

            // Android 14+ (API 34) refuses a mutable PendingIntent built from an
            // implicit intent — SmsManager needs it mutable to attach the delivery
            // result, so the intent must be made explicit to this app instead.
            fun explicitIntent() = Intent(action).setPackage(appContext.packageName)

            try {
                val smsManager: SmsManager =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        appContext.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }

                val parts = smsManager.divideMessage(message)
                if (parts.size <= 1) {
                    val sentPI = PendingIntent.getBroadcast(appContext, requestId, explicitIntent(), flags)
                    smsManager.sendTextMessage(to, null, message, sentPI, null)
                } else {
                    // Multi-part: one PendingIntent per part, but we only need to know the
                    // outcome once — the receiver already unregisters itself after the first
                    // callback, so subsequent parts' callbacks are simply ignored if they land.
                    val sentIntents = ArrayList<PendingIntent>(parts.size)
                    for (i in parts.indices) {
                        sentIntents.add(PendingIntent.getBroadcast(appContext, requestId * 100 + i, explicitIntent(), flags))
                    }
                    smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, null)
                }
            } catch (e: Exception) {
                if (!resumed) {
                    resumed = true
                    try {
                        appContext.unregisterReceiver(receiver)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) cont.resume(SendResult.Failed(e.message ?: "Send threw an exception"))
                }
            }
        }
}