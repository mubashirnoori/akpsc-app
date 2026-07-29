package com.akhoonzadaholdings.school

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    // The width (in CSS px) we tell the page it's rendering at. Your
    // nav-rail.css switches to mobile mode below 991.98px, so anything
    // comfortably above that forces the desktop layout to kick in.
    private val desktopViewportWidth = 1280

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Push content below the status bar (WiFi/notification area)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainLayout)) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, 0)
            insets
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

        // Security: don't let the WebView read arbitrary local files or mix
        // http/https content — neither is needed for this site.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Handle file downloads (Save PDF, etc.)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            request.setMimeType(mimetype)
            request.addRequestHeader("User-Agent", userAgent)
            request.setDescription("Downloading file...")
            request.setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype))
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS,
                android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            )
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            android.widget.Toast.makeText(this, "Downloading...", android.widget.Toast.LENGTH_SHORT).show()
        }

        // Print bridge — only exposes a zero-argument method, so the risk
        // from any injected/malicious page script calling it stays minimal.
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
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE

                // Login/account pages are a centered card sized for a narrow screen —
                // they aren't part of the nav-rail dashboard layout, so forcing the
                // desktop width on them pushes their vertically-centered content below
                // the fold. Only force desktop width on the actual dashboard/portal pages.
                val isAuthPage = url?.contains("route=auth", ignoreCase = true) == true

                val js = if (isAuthPage) {
                    // Let the login page use its own natural mobile viewport — don't
                    // touch it at all.
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