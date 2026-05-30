package com.ihti.openclaw

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@Suppress("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AnyClaw"
        private const val PORT = 18789
        private const val GITHUB_ORG = "ihti228"
        private const val GITHUB_REPO = "anyclaw-ollama"
    }

    private lateinit var webView: WebView
    private lateinit var loadingOverlay: View
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var progressBar: ProgressBar

    private val serverManager by lazy { CodexServerManager(this) }
    private val handler = Handler(Looper.getMainLooper())

    // Track UI restoration
    private var uiInitialized = false
    private var pendingHtml: String? = null
    private var pendingUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        
        // Auto-start — no setup flow needed
        Thread {
            try {
                runSetupAndConnect()
            } catch (e: Exception) {
                Log.e(TAG, "Auto-start failed", e)
                runOnUiThread {
                    showError("Failed to start: ${e.message}")
                }
            }
        }.start()
    }

    private fun runSetupAndConnect() {
        // Step 1: Extract bootstrap
        if (!BootstrapInstaller.isBootstrapInstalled(this)) {
            updateStatus("Extracting environment…")
            BootstrapInstaller.install(this) { msg -> updateStatus(msg) }
        }
        updateStatus("Environment ready")

        // Step 2: Install proot
        if (!serverManager.isProotInstalled()) {
            updateStatus("Installing proot…")
            val prootOk = serverManager.installProot { msg -> updateDetail(msg) }
            if (!prootOk) {
                throw RuntimeException("Failed to install proot")
            }
        }
        updateStatus("proot ready")

        // Step 3: Install Node.js
        if (!serverManager.isNodeInstalled()) {
            updateStatus("Installing Node.js (first run)…", "This may take a few minutes")
            val nodeOk = serverManager.installNode { msg -> updateDetail(msg) }
            if (!nodeOk) {
                throw RuntimeException("Failed to install Node.js")
            }
        }
        updateStatus("Node.js ready")

        // Step 4: Start OpenClaw Gateway
        updateStatus("Starting OpenClaw…")
        serverManager.startOpenClawGateway()
        
        // Wait for server
        updateStatus("Waiting for server…")
        var attempts = 0
        while (attempts < 60) {
            if (serverManager.isServerHealthy()) {
                updateStatus("Server ready")
                break
            }
            Thread.sleep(1000)
            attempts++
        }
        
        if (attempts >= 60) {
            throw RuntimeException("Server failed to start")
        }

        // Connect to local gateway
        runOnUiThread {
            showLoading(false)
            webView.loadUrl("http://localhost:$PORT")
        }
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                Log.d("WebView", "${cm?.message()} — ${cm?.sourceId()}:${cm?.lineNumber()}")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://localhost:$PORT")) {
                    false // Let WebView handle it
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "Page finished: $url")
                if (!uiInitialized) {
                    uiInitialized = true
                    showLoading(false)
                }
                super.onPageFinished(view, url)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        window.setFlags(
            if (show) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
    }

    private fun updateStatus(message: String, detail: String = "") {
        runOnUiThread {
            statusText.text = message
            detailText.text = detail
            Log.d(TAG, message)
        }
    }

    private fun updateDetail(detail: String) {
        runOnUiThread { detailText.text = detail }
    }

    private fun showError(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message)
                .setPositiveButton(R.string.retry) { _, _ ->
                    Thread { runSetupAndConnect() }.start()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        serverManager.stopServer()
        super.onDestroy()
    }
}
