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

        // Allow the website's print button to trigger Android's native print dialog
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun triggerPrint() {
                runOnUiThread { printCurrentPage() }
            }
        }, "AndroidPrint")

        // Lets the page report its real content height, in CSS px, so we can resize
        // the WebView's native height to match instead of leaving a big blank gap
        // (or, on the flip side, clipping content) below/beyond the actual page.
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun reportHeight(cssPx: Float) {
                runOnUiThread {
                    if (cssPx <= 0f) return@runOnUiThread
                    val density = resources.displayMetrics.density
                    val px = (cssPx * density).toInt().coerceAtLeast(1)
                    val params = webView.layoutParams
                    if (params.height != px) {
                        params.height = px
                        webView.layoutParams = params
                    }
                }
            }
        }, "AndroidLayout")

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
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE

                val isLoginPage = url?.contains("login", ignoreCase = true) == true ||
                        url?.contains("account", ignoreCase = true) == true

                // Reduced values so pages appear closer to actual size, less empty space visible
                val desiredWidthValue = if (isLoginPage) 420 else 1024

                val js = """
        (function() {
            var meta = document.querySelector('meta[name="viewport"]');
            if (!meta) {
                meta = document.createElement('meta');
                meta.name = 'viewport';
                document.getElementsByTagName('head')[0].appendChild(meta);
            }
            var desiredWidth = $desiredWidthValue;
            var scale = window.screen.width / desiredWidth;
            meta.setAttribute('content', 'width=' + desiredWidth + ', initial-scale=' + scale + ', user-scalable=yes');
            window.scrollTo(0, 0);
            window.print = function() { AndroidPrint.triggerPrint(); };

            // --- Auto-height reporting -------------------------------------
            // Tell the native app how tall the page's real content is (in CSS
            // px, pre-scale) so it can size the WebView to match instead of
            // always filling the whole screen with blank space below.
            function reportHeight() {
                if (!window.AndroidLayout) return;
                var h = document.documentElement.scrollHeight;
                AndroidLayout.reportHeight(h);
            }

            reportHeight();
            window.addEventListener('load', reportHeight);
            window.addEventListener('resize', reportHeight);

            // Re-measure a few times after load: fonts, images, and the
            // sidebar's own JS can all change the page height shortly after
            // the 'load' event fires.
            [150, 400, 900, 1800].forEach(function (delay) {
                setTimeout(reportHeight, delay);
            });

            // Keep watching for content changes (e.g. sidebar flyouts opening,
            // dynamic tables loading) without spamming the bridge on every tick.
            if (window.MutationObserver) {
                var debounce = null;
                var observer = new MutationObserver(function () {
                    if (debounce) { clearTimeout(debounce); }
                    debounce = setTimeout(reportHeight, 200);
                });
                observer.observe(document.body, { childList: true, subtree: true, attributes: true });
            }
        })();
    """.trimIndent()
                view?.evaluateJavascript(js, null)
                view?.scrollTo(0, 0)
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