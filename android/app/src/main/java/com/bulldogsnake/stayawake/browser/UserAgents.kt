package com.bulldogsnake.stayawake.browser

import android.content.Context
import android.webkit.WebSettings

object UserAgents {
    /** Desktop Chrome on Windows. Netflix and Prime Video serve their web players to this. */
    const val DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /** The stock WebView UA with the "wv" marker removed so sites treat it as mobile Chrome. */
    fun mobile(context: Context): String =
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+(\\.\\d+)* "), "")
}
