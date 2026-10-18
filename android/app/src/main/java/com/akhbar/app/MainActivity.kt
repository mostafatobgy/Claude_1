package com.akhbar.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/** Shows the news web app; article links open in the phone's browser. */
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serverHost = Uri.parse(BuildConfig.SERVER_URL).host

        val swipe = SwipeRefreshLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == serverHost) return false
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    return true
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    swipe.isRefreshing = false
                }
            }
        }
        swipe.addView(webView)
        swipe.setOnRefreshListener { webView.reload() }
        // Only allow pull-to-refresh when the page is scrolled to the top.
        swipe.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
        setContentView(swipe)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) webView.loadUrl(BuildConfig.SERVER_URL)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }
}
