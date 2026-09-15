package com.bulldogsnake.stayawake.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import com.bulldogsnake.stayawake.Prefs
import com.bulldogsnake.stayawake.Site
import com.bulldogsnake.stayawake.browser.StayAwakeWebView

/**
 * Full-screen browser on the phone. Sign in to a service here once; the car screen uses
 * the same WebView cookie jar, so it is signed in too.
 */
class LoginActivity : Activity(), StayAwakeWebView.Host {

    private lateinit var root: FrameLayout
    private var webView: StayAwakeWebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
        val site = Site.fromId(intent.getStringExtra(EXTRA_SITE)) ?: Site.YOUTUBE
        createWebView().openSite(site)
    }

    private fun createWebView(): StayAwakeWebView {
        val wv = StayAwakeWebView(this, Prefs(this)).apply { host = this@LoginActivity }
        root.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        webView = wv
        return wv
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            onHideCustomView()
            return
        }
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView?.let { root.removeView(it); it.destroy() }
        webView = null
        super.onDestroy()
    }

    override fun onOpenSite(site: Site) {
        webView?.openSite(site)
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        onHideCustomView()
        customView = view
        customViewCallback = callback
        root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        webView?.visibility = View.INVISIBLE
    }

    override fun onHideCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        webView?.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onRenderProcessGone() {
        val url = webView?.url
        webView?.let { root.removeView(it); it.destroy() }
        val wv = createWebView()
        if (url != null) wv.openUrl(url) else wv.loadStartPage()
    }

    override fun onUrlChanged(url: String) {}

    companion object {
        private const val EXTRA_SITE = "site"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        fun intent(context: Context, site: Site): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_SITE, site.id)
    }
}
