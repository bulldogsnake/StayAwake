package com.bulldogsnake.stayawake

import android.net.Uri

/** The streaming services offered on the car start page. */
enum class Site(
    val id: String,
    val label: String,
    /** Netflix and Prime Video only play in a browser that looks like desktop Chrome. */
    val defaultDesktop: Boolean,
) {
    YOUTUBE("youtube", "YouTube", false),
    NETFLIX("netflix", "Netflix", true),
    PRIME("prime", "Prime Video", true);

    fun homeUrl(desktop: Boolean): String = when (this) {
        YOUTUBE -> if (desktop) "https://www.youtube.com/" else "https://m.youtube.com/"
        NETFLIX -> "https://www.netflix.com/browse"
        PRIME -> "https://www.primevideo.com/"
    }

    fun searchUrl(query: String, desktop: Boolean): String {
        val q = Uri.encode(query)
        return when (this) {
            YOUTUBE ->
                if (desktop) "https://www.youtube.com/results?search_query=$q"
                else "https://m.youtube.com/results?search_query=$q"
            NETFLIX -> "https://www.netflix.com/search?q=$q"
            PRIME -> "https://www.primevideo.com/search/ref=atv_nb_sr?phrase=$q"
        }
    }

    private fun matchesHost(host: String): Boolean = when (this) {
        YOUTUBE -> host.endsWith("youtube.com") || host == "youtu.be"
        NETFLIX -> host.endsWith("netflix.com")
        PRIME -> host.endsWith("primevideo.com") || host.contains("amazon.")
    }

    companion object {
        fun fromId(id: String?): Site? = entries.firstOrNull { it.id == id }

        fun fromUrl(url: String?): Site? {
            if (url.isNullOrBlank()) return null
            val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return null
            return entries.firstOrNull { it.matchesHost(host) }
        }
    }
}
