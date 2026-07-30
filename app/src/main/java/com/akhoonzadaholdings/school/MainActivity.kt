package com.akhoonzadaholdings.school

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    private val desktopViewportWidth = 1280

    // --- File upload (<input type="file">) support ---
    // WebView doesn't handle file inputs on its own; without this callback +
    // launcher, tapping "Choose File" / "Upload Photo" on the site does
    // nothing at all — which is exactly the bug being fixed here.
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        // Registered once, before the WebView loads anything — required by
        // the Activity Result API (must be called unconditionally in onCreate,
        // not lazily inside onShowFileChooser).
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = filePathCallback
            filePathCallback = null

            if (callback == null) return@registerForActivityResult

            if (result.resultCode != RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val data = result.data
            val resultUris: Array<Uri> = when {
                // User picked from gallery/file manager: URI comes back in data.
                data?.dataString != null -> arrayOf(Uri.parse(data.dataString))
                // Multiple files selected (clipData) from a file manager.
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                // User took a photo with the camera: use the pre-created URI.
                cameraPhotoUri != null -> arrayOf(cameraPhotoUri!!)
                else -> emptyArray()
            }
            callback.onReceiveValue(resultUris)
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = desktopUserAgent
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = android.app.DownloadManager.Request(Uri.parse(url))
            request.setMimeType(mimetype)
            request.addRequestHeader("User-Agent", userAgent)
            request.setDescription("Downloading file...")
            request.setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype))
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            )
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            android.widget.Toast.makeText(this, "Downloading...", android.widget.Toast.LENGTH_SHORT).show()
        }

        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun triggerPrint() {
                runOnUiThread { printCurrentPage() }
            }
        }, "AndroidPrint")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            // This is the actual fix: without overriding this, file inputs on
            // the site (student profile photo, staff image, etc.) are inert.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Only one chooser can be in flight at a time; if a previous
                // one never resolved, cancel it cleanly instead of leaking it.
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val acceptTypes = fileChooserParams?.acceptTypes?.joinToString() ?: ""
                val wantsImage = acceptTypes.contains("image") || acceptTypes.isEmpty()

                // Gallery / file picker intent — always offered.
                val contentIntent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val intentsToShow = mutableListOf<Intent>()

                // Offer a direct camera-capture option too, but only when the
                // field accepts images and the device actually has a camera
                // and app (checked via PackageManager to avoid a crash on
                // devices/emulators with no camera app).
                if (wantsImage && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    val photoFile = createCameraOutputFile()
                    if (photoFile != null) {
                        cameraPhotoUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            photoFile
                        )
                        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                        // Only add it if a camera app can actually handle it.
                        if (cameraIntent.resolveActivity(packageManager) != null) {
                            intentsToShow.add(cameraIntent)
                        }
                    }
                }

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentIntent)
                    putExtra(Intent.EXTRA_TITLE, "Choose a file")
                    if (intentsToShow.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, intentsToShow.toTypedArray())
                    }
                }

                return try {
                    fileChooserLauncher.launch(chooserIntent)
                    true
                } catch (e: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE

                val isAuthPage = url?.contains("route=auth", ignoreCase = true) == true

                val js = if (isAuthPage) {
                    """
                    (function() {
                        var meta = document.querySelector('meta[name="viewport"]');
                        if (meta) {
                            meta.setAttribute('content', 'width=device-width, initial-scale=1.0, user-scalable=yes');
                        }
                    })();
                    """.trimIndent()
                } else {
                    """
                    (function() {
                        var meta = document.querySelector('meta[name="viewport"]');
                        if (!meta) {
                            meta = document.createElement('meta');
                            meta.name = 'viewport';
                            (document.head || document.documentElement).appendChild(meta);
                        }
                        meta.setAttribute(
                            'content',
                            'width=$desktopViewportWidth, initial-scale=1.0, user-scalable=yes'
                        );
                    })();
                    """.trimIndent()
                }
                view?.evaluateJavascript(js, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                val js = """
                    (function() {
                        window.print = function() { AndroidPrint.triggerPrint(); };
                    })();
                """.trimIndent()
                view?.evaluateJavascript(js, null)
            }
        }

        webView.loadUrl("https://school.akhoonzadaholdings.com/public/index.php?route=portal%2Findex")
    }

    /** Creates a private, app-scoped file for the camera to write the captured photo into. */
    private fun createCameraOutputFile(): File? {
        return try {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera_uploads")
            if (!dir.exists()) dir.mkdirs()
            File.createTempFile("upload_${System.currentTimeMillis()}_", ".jpg", dir)
        } catch (e: Exception) {
            null
        }
    }

    private fun printCurrentPage() {
        val printManager = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
        val jobName = "AKPSC Document"
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, printAdapter, android.print.PrintAttributes.Builder().build())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}