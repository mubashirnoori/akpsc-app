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
                e.printStackTrace()
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
                val url = URL(apkUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                val fileLength = connection.contentLength
                val outputFile = File(context.getExternalFilesDir(null), "update.apk")

                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(4096)
                        var total = 0L
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            total += count
                            output.write(buffer, 0, count)
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                Handler(Looper.getMainLooper()).post {
                                    progressBar.progress = progress
                                    progressText.text = "Downloading update... $progress%"
                                }
                            }
                        }
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    progressDialog.dismiss()
                    installApk(context, outputFile)
                    onFinished(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    progressDialog.dismiss()
                    onFinished(true)
                }
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