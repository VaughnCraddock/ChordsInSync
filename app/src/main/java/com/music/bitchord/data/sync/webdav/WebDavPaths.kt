package com.music.bitchord.data.sync.webdav

import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import java.net.URLDecoder

/**
 * The two directions of one mapping: a sync path (`Rock/Live/track.flac`,
 * relative to the pair's root, the [com.music.bitchord.data.sync.SyncEntry]
 * convention) and the URL of the same file under the pair's WebDAV root.
 *
 * Pure string work, built on [WebDavClient.joinUrl] so the encoding is the
 * one the rest of the app already speaks, and unit-tested as a round trip:
 * a name that goes out must come back as itself.
 */
object WebDavPaths {

    /** The pair's root URL with no trailing slash — the form paths hang off. */
    fun rootOf(rawUrl: String): String = WebDavConfig.normalizeUrl(rawUrl)

    /** [root] plus [path], each segment percent-encoded on its own. */
    fun urlForPath(root: String, path: String): String =
        path.split('/').filter { it.isNotEmpty() }
            .fold(rootOf(root)) { url, segment -> WebDavClient.joinUrl(url, segment) }

    /**
     * The sync path of [url] under [root], or null when [url] is not under
     * the root at all — a listing that wanders outside its pair's tree is
     * not this pair's business.
     */
    fun pathForUrl(root: String, url: String): String? {
        val rootNorm = rootOf(root)
        val candidate = url.trim()
        val prefix = "$rootNorm/"
        if (!candidate.startsWith(prefix, ignoreCase = true)) return null
        val relative = candidate.substring(prefix.length).trimEnd('/')
        if (relative.isBlank()) return null
        return relative.split('/').joinToString("/") { segment -> decode(segment) }
    }

    /**
     * The display path of a folder under [serverRoot]: `Music/Live`, or the
     * root's own last segment when the folder IS the root. What a pair calls
     * its remote folder on the Sync page.
     */
    fun labelFor(serverRoot: String, folderUrl: String): String {
        val relative = pathForUrl(serverRoot, folderUrl)
        val rootName = decode(rootOf(serverRoot).trimEnd('/').substringAfterLast('/'))
        return when {
            relative == null -> decode(folderUrl.trimEnd('/').substringAfterLast('/'))
            relative.isBlank() -> rootName
            rootName.isBlank() -> relative
            else -> "$rootName/$relative"
        }
    }

    private fun decode(segment: String): String = runCatching {
        URLDecoder.decode(segment, "UTF-8")
    }.getOrDefault(segment)
}
