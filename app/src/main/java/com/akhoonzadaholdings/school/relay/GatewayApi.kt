package com.akhoonzadaholdings.school.relay

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** One queued message returned by the server, waiting to be sent from this device. */
data class PendingMessage(
    val id: Int,
    val to: String,
    val message: String,
    val purpose: String
)

/**
 * Thin client for api/sms_gateway.php. No external HTTP library needed —
 * this is two endpoints, both small JSON payloads.
 */
object GatewayApi {

    private const val TIMEOUT_MS = 20_000

    class GatewayException(message: String, val httpCode: Int = -1) : Exception(message)

    /** GET ?action=pending — up to 20 messages addressed to this device, already marked "sending" server-side. */
    @Throws(GatewayException::class)
    fun fetchPending(endpoint: String, token: String): List<PendingMessage> {
        val url = URL(appendQuery(endpoint, "action=pending"))
        val conn = openConnection(url, "GET", token)
        try {
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code != 200) {
                throw GatewayException(errorMessage(body, "Server returned $code"), code)
            }
            val json = JSONObject(body)
            val arr: JSONArray = json.optJSONArray("messages") ?: JSONArray()
            val result = ArrayList<PendingMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                result.add(
                    PendingMessage(
                        id = m.optInt("id"),
                        to = m.optString("to"),
                        message = m.optString("message"),
                        purpose = m.optString("purpose")
                    )
                )
            }
            return result
        } finally {
            conn.disconnect()
        }
    }

    /** POST {"action":"ack","id":..,"status":"sent"|"failed","error":".."} */
    @Throws(GatewayException::class)
    fun ack(endpoint: String, token: String, id: Int, sent: Boolean, error: String? = null) {
        val url = URL(endpoint)
        val conn = openConnection(url, "POST", token)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        val payload = JSONObject().apply {
            put("action", "ack")
            put("id", id)
            put("status", if (sent) "sent" else "failed")
            if (!sent && !error.isNullOrBlank()) put("error", error.take(255))
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

        try {
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code != 200) {
                throw GatewayException(errorMessage(body, "Server returned $code"), code)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Quick reachability/auth check — used by the setup screen's "Test connection" button. */
    @Throws(GatewayException::class)
    fun healthCheck(endpoint: String, token: String): String {
        val url = URL(endpoint)
        val conn = openConnection(url, "GET", token)
        try {
            val code = conn.responseCode
            val body = readBody(conn, code)
            if (code != 200) {
                throw GatewayException(errorMessage(body, "Server returned $code"), code)
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: URL, method: String, token: String): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("X-Gateway-Token", token)
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private fun errorMessage(body: String, fallback: String): String {
        return try {
            JSONObject(body).optString("error", fallback)
        } catch (e: Exception) {
            fallback
        }
    }

    private fun appendQuery(url: String, query: String): String =
        if (url.contains("?")) "$url&$query" else "$url?$query"
}
