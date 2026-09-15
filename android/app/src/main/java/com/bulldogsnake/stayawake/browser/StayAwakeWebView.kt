package com.bulldogsnake.stayawake.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.bulldogsnake.stayawake.Prefs
import com.bulldogsnake.stayawake.Site
import org.json.JSONObject

/**
 * A WebView configured for streaming sites: DRM (Widevine) enabled, autoplay allowed,
 * per-site desktop or mobile user agent, and a local start page with the service tiles.
 * The same class is used on the phone (sign-in) and on the car screen, so cookies and
 * logins are shared automatically.
 */
@SuppressLint("SetJavaScriptEnabled")
class StayAwakeWebView(context: Context, private val prefs: Prefs) : WebView(context) {

    interface Host {
        fun onOpenSite(site: Site)
        fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onHideCustomView()
        fun onRenderProcessGone()
        fun onUrlChanged(url: String)
    }

    var host: Host? = null

    private var desktop: Boolean? = null

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@StayAwakeWebView, true)
        }
        setBackgroundColor(0xFF000000.toInt())

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    START_SCHEME -> {
                        Site.fromId(uri.lastPathSegment)?.let { host?.onOpenSite(it) }
                        true
                    }
                    // Block intent://, vnd.youtube:, market: and friends; they would try to
                    // leave the browser and open the phone app instead.
                    else -> true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                host?.onUrlChanged(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                host?.onUrlChanged(url)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView render process gone (crashed=" + detail.didCrash() + ")")
                host?.onRenderProcessGone()
                return true
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // Widevine (EME) is what Netflix and Prime Video need; nothing else is granted.
                val granted = request.resources
                    .filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    .toTypedArray()
                if (granted.isNotEmpty()) request.grant(granted) else request.deny()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                val h = host
                if (h == null) callback.onCustomViewHidden() else h.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                host?.onHideCustomView()
            }
        }
    }

    fun loadStartPage() {
        setDesktop(false)
        loadUrl(START_URL)
    }

    fun openSite(site: Site) {
        val d = prefs.desktopMode(site)
        setDesktop(d)
        loadUrl(site.homeUrl(d))
    }

    /** Loads any URL, choosing the user agent from the site it belongs to. */
    fun openUrl(url: String) {
        if (url == START_URL) {
            loadStartPage()
            return
        }
        Site.fromUrl(url)?.let { setDesktop(prefs.desktopMode(it)) }
        loadUrl(url)
    }

    fun search(query: String) {
        val site = Site.fromUrl(url) ?: Site.YOUTUBE
        val d = prefs.desktopMode(site)
        setDesktop(d)
        loadUrl(site.searchUrl(query, d))
    }

    /**
     * Types text into whatever page element currently has focus (a login field, a search
     * box) and presses Enter. If nothing is focused, falls back to a site search.
     */
    fun typeText(text: String) {
        val js = TYPE_JS.replace("__TEXT__", JSONObject.quote(text))
        evaluateJavascript(js) { result ->
            if (result == null || result.contains("nofocus")) search(text)
        }
    }

    fun pauseAllVideo() {
        evaluateJavascript(PAUSE_JS, null)
    }

    private fun setDesktop(enabled: Boolean) {
        if (desktop == enabled) return
        desktop = enabled
        settings.userAgentString = if (enabled) UserAgents.DESKTOP else UserAgents.mobile(context)
        applyUserAgentMetadata(enabled)
    }

    /** Keeps User-Agent Client Hints consistent with the UA string (sites check both). */
    private fun applyUserAgentMetadata(desktop: Boolean) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
            val metadata = UserAgentMetadata.Builder()
                .setPlatform(if (desktop) "Windows" else "Android")
                .setPlatformVersion(if (desktop) "15.0.0" else Build.VERSION.RELEASE)
                .setArchitecture(if (desktop) "x86" else "arm")
                .setModel(if (desktop) "" else Build.MODEL)
                .setMobile(!desktop)
                .setBitness(64)
                .setWow64(false)
                .build()
            WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        } catch (t: Throwable) {
            Log.w(TAG, "User agent metadata not applied", t)
        }
    }

    companion object {
        private const val TAG = "StayAwakeWebView"
        const val START_SCHEME = "stayawake"
        const val START_URL = "file:///android_asset/start.html"

        private const val PAUSE_JS =
            "document.querySelectorAll('video').forEach(function(v){try{v.pause();}catch(e){}});"

        private val TYPE_JS = """
            (function(text) {
              var el = document.activeElement;
              if (!el) return 'nofocus';
              var tag = (el.tagName || '').toUpperCase();
              var isField = tag === 'INPUT' || tag === 'TEXTAREA';
              if (!isField && !el.isContentEditable) return 'nofocus';
              if (isField) {
                var proto = tag === 'INPUT' ? window.HTMLInputElement.prototype : window.HTMLTextAreaElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) desc.set.call(el, text); else el.value = text;
              } else {
                el.textContent = text;
              }
              el.dispatchEvent(new Event('input', {bubbles: true}));
              el.dispatchEvent(new Event('change', {bubbles: true}));
              var opts = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true};
              el.dispatchEvent(new KeyboardEvent('keydown', opts));
              el.dispatchEvent(new KeyboardEvent('keypress', opts));
              el.dispatchEvent(new KeyboardEvent('keyup', opts));
              return 'typed';
            })(__TEXT__);
        """.trimIndent()
    }
}
