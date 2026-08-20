package com.akhoonzadaholdings.school

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/mubashirnoori/akpsc-app/main/version.json"

    // How long a failure Toast/message must stay visible before we let the
    // splash screen continue into MainActivity. Without this, a fast-failing
    // download (e.g. a bad URL, 404, redirect problem) can throw within a
    // fraction of a second, and the app jumps into MainActivity before the
    // Toast is even readable.
    private const val FAILURE_DISPLAY_TIME_MS = 2500L

    fun check(context: Context, onFinished: (updateAvailable: Boolean) -> Unit) {
        Thread {
            try {
                val connection = URL(VERSION_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                val latestVersionCode = json.getInt("versionCode")
                val latestVersionName = json.getString("versionName")
                val apkUrl = json.getString("apkUrl")
                val notes = json.optString("notes", "")

                android.util.Log.d("UpdateChecker", "version.json => versionCode=$latestVersionCode versionName=$latestVersionName apkUrl=$apkUrl")

                val currentVersionCode = context.packageManager
                    .getPackageInfo(context.packageName, 0).let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                            it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
                    }

                Handler(Looper.getMainLooper()).post {
                    if (latestVersionCode > currentVersionCode) {
                        showUpdateDialog(context, latestVersionName, notes, apkUrl, onFinished)
                    } else {
                        onFinished(false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UpdateChecker", "Version check failed: ${e.message}", e)
                Handler(Looper.getMainLooper()).post { onFinished(false) }
            }
        }.start()
    }

    private fun showUpdateDialog(
        context: Context,
        versionName: String,
        notes: String,
        apkUrl: String,
        onFinished: (Boolean) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("Version $versionName is available.\n\n$notes")
            .setPositiveButton("Download") { _, _ ->
                startDownload(context, apkUrl, onFinished)
            }
            .setNegativeButton("Later") { _, _ ->
                onFinished(true)
            }
            .setCancelable(false)
            .show()
    }

    private fun startDownload(context: Context, apkUrl: String, onFinished: (Boolean) -> Unit) {
        val dialogView = android.view.LayoutInflater.from(context)
            .inflate(R.layout.dialog_update_progress, null)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBarUpdate)
        val progressText = dialogView.findViewById<TextView>(R.id.progressText)

        val progressDialog = AlertDialog.Builder(context)
            .setTitle("Updating App")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        Thread {
            try {
                var currentUrl = apkUrl
                var connection: HttpURLConnection
                var redirectCount = 0

                while (true) {
                    val url = URL(currentUrl)
                    connection = url.openConnection() as HttpURLConnection
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.connect()

                    val code = connection.responseCode
                    android.util.Log.d("UpdateChecker", "Response code: $code for URL: $currentUrl")

                    if (code in 300..399) {
                        val newUrl = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (newUrl.isNullOrEmpty() || redirectCount > 5) {
                            throw Exception("Redirect failed at hop $redirectCount (last URL: $currentUrl)")
                        }
                        currentUrl = newUrl
                        redirectCount++
                    } else if (code == 200) {
                        break
                    } else {
                        val errorBody = try {
                            connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
                        } catch (_: Exception) { null }
                        throw Exception("Server returned HTTP $code for $currentUrl${if (errorBody != null) " — $errorBody" else ""}")
                    }
                }

                val contentType = connection.contentType
                android.util.Log.d("UpdateChecker", "Content-Type: $contentType")

                val fileLength = connection.contentLength
                android.util.Log.d("UpdateChecker", "Expected file size: $fileLength bytes")

                val outputFile = File(context.getExternalFilesDir(null), "update.apk")
                if (outputFile.exists()) outputFile.delete()

                var totalDownloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            totalDownloaded += count
                            output.write(buffer, 0, count)
                            if (fileLength > 0) {
                                val progress = (totalDownloaded * 100 / fileLength).toInt()
                                Handler(Looper.getMainLooper()).post {
                                    progressBar.progress = progress
                                    progressText.text = "Downloading update... $progress%"
                                }
                            }
                        }
                    }
                }

                android.util.Log.d("UpdateChecker", "Actually downloaded: $totalDownloaded bytes, file size on disk: ${outputFile.length()}, Content-Type was: $contentType")

                if (outputFile.length() < 100_000) {
                    val preview = try {
                        outputFile.readText().take(300)
                    } catch (_: Exception) { "(binary, can't preview)" }
                    throw Exception("Downloaded file too small (${outputFile.length()} bytes) — likely not a real APK. Preview: $preview")
                }

                Handler(Looper.getMainLooper()).post {
                    progressDialog.dismiss()
                    installApk(context, outputFile)
                    // Do NOT call onFinished() here. installApk() only *launches*
                    // the system install prompt — it doesn't wait for the user to
                    // tap "Install". Calling onFinished(true) right after used to
                    // immediately continue the splash flow into MainActivity while
                    // that prompt was still coming up, and MainActivity won the
                    // race for the foreground — so the install screen never got a
                    // chance to be seen or acted on. Nothing actually installed,
                    // the version never changed, and the same "update available"
                    // dialog reappeared on every launch. Finishing here instead
                    // leaves the system installer as the only thing in front; if
                    // the user completes the install, Android restarts the app
                    // fresh (back at the splash screen) on its own.
                    if (context is android.app.Activity) {
                        context.finish()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("UpdateChecker", "Download failed: ${e.message}", e)
                Handler(Looper.getMainLooper()).post {
                    progressDialog.dismiss()
                    android.widget.Toast.makeText(
                        context,
                        "Update failed: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                // Give the failure Toast real time on screen before letting the
                // splash screen move on — this is the fix for "bar shows for a
                // second, then the old app just opens": onFinished() used to
                // fire immediately here, racing past the Toast.
                Handler(Looper.getMainLooper()).postDelayed({
                    onFinished(true)
                }, FAILURE_DISPLAY_TIME_MS)
            }
        }.start()
    }

    private fun installApk(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}