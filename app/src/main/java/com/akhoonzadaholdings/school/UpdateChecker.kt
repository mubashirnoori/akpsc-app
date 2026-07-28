package com.akhoonzadaholdings.school

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/mubashirnoori/akpsc-app/main/version.json"

    fun check(context: Context) {
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
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                            it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode
                    }

                if (latestVersionCode > currentVersionCode) {
                    Handler(Looper.getMainLooper()).post {
                        showUpdateDialog(context, latestVersionName, notes, apkUrl)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun showUpdateDialog(context: Context, versionName: String, notes: String, apkUrl: String) {
        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("Version $versionName is available.\n\n$notes")
            .setPositiveButton("Download") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }
}