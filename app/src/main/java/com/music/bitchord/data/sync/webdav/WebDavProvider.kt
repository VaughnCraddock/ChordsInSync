package com.music.bitchord.data.sync.webdav

import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp
import com.music.bitchord.data.sync.SyncProvider
import com.music.bitchord.data.sync.SyncUnreachableException
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The far end of a WebDAV pair: one folder on one server, walked with the
 * app's own hand-rolled DAV client — the same PROPFIND/PUT/MKCOL stack the
 * library view and the upload pipeline already proved, now asked for the
 * three properties sync lives on (`getcontentlength`, `getlastmodified`,
 * `getetag`).
 *
 * Per-pair by construction: the server and its credential arrive with the
 * pair, not from the library view's single global configuration. The
 * single-server [com.music.bitchord.data.webdav.WebDavAuth] shortcut is
 * deliberately not touched.
 *
 * Listings carry audio files only, matching the local end: MediaStore's
 * audio collection is all the phone side can see, and a format neither end
 * can list could never settle in the index — it would be noted as deleted
 * on every run forever.
 *
 * A server that cannot be reached at all (no route, no DNS, a connect that
 * times out) throws [SyncUnreachableException]: away from home that is a
 * pair's normal state, not a failure. A server that *answers* wrongly —
 * 401, 500 — throws its answer, and the run records a failure as it should.
 */
class WebDavProvider(
    rootUrl: String,
    private val username: String,
    private val password: String,
) : SyncProvider {

    private val root: String = WebDavPaths.rootOf(rootUrl)

    override suspend fun list(): List<SyncEntry> = guardUnreachable {
        val entries = mutableListOf<SyncEntry>()
        val dirs = ArrayDeque<String>()
        dirs.add(root)
        val visited = HashSet<String>()
        while (dirs.isNotEmpty()) {
            val dir = dirs.removeFirst()
            if (!visited.add(dir.lowercase())) continue
            val children = WebDavClient.listDirectory(dir, username, password).getOrThrow()
            for (child in children) {
                if (child.isCollection) {
                    if (!visited.contains(child.url.lowercase())) dirs.add(child.url)
                } else if (WebDavConfig.isAudioFile(child.displayName)) {
                    val path = WebDavPaths.pathForUrl(root, child.url) ?: continue
                    entries += SyncEntry(path, stampOf(child))
                }
            }
        }
        entries
    }

    override suspend fun read(path: String): InputStream = guardUnreachable {
        WebDavClient.getFile(WebDavPaths.urlForPath(root, path), username, password).getOrThrow()
    }

    override suspend fun write(path: String, source: InputStream, size: Long, mtimeMs: Long?): SyncEntry =
        guardUnreachable {
            // Create the folder chain first: a PUT into a collection that
            // does not exist is a 409 on most servers.
            val segments = path.split('/').filter { it.isNotEmpty() }
            var dir = root
            for (segment in segments.dropLast(1)) {
                dir = WebDavClient.joinUrl(dir, segment)
                WebDavClient.ensureCollection(dir, username, password).getOrThrow()
            }
            val fileUrl = WebDavPaths.urlForPath(root, path)
            WebDavClient.putFile(
                fileUrl = fileUrl,
                stream = source,
                contentLength = size,
                mimeType = mimeTypeFor(segments.last()),
                username = username,
                password = password,
                overwrite = true,
            ).getOrThrow()
            // Read the stamp back: the server's clock and its etag are the
            // truth the index records, not the ones the phone hoped for.
            val stored = WebDavClient.stat(fileUrl, username, password).getOrNull()
            if (stored != null) {
                SyncEntry(path, stampOf(stored))
            } else {
                SyncEntry(path, SyncFileStamp(size = size, mtimeMs = mtimeMs ?: 0L))
            }
        }

    override suspend fun delete(path: String) {
        guardUnreachable {
            WebDavClient.delete(WebDavPaths.urlForPath(root, path), username, password).getOrThrow()
        }
    }

    private fun stampOf(entry: WebDavClient.Entry): SyncFileStamp = SyncFileStamp(
        size = entry.size ?: 0L,
        mtimeMs = entry.lastModifiedMs ?: 0L,
        tag = entry.etag,
    )

    private fun mimeTypeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            "wma" -> "audio/x-ms-wma"
            "aif", "aiff" -> "audio/aiff"
            "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }

    /**
     * Runs [block], translating "the server cannot be reached at all" into
     * [SyncUnreachableException]. OkHttp surfaces transport failures as
     * [IOException]s of a few specific shapes; everything else (a server's
     * own error answer, a refused credential) keeps its identity.
     */
    private suspend fun <T> guardUnreachable(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (failure: IOException) {
                if (failure.isUnreachable()) throw SyncUnreachableException(failure.message)
                throw failure
            }
        }

    private fun Throwable.isUnreachable(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is ConnectException || cause is UnknownHostException ||
                cause is SocketTimeoutException
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }
}

/**
 * The one-time writability probe, run at pair creation with the user's
 * knowledge (SYNC-RESEARCH §7.3): write one tiny file into the pair's
 * folder, then delete it.
 *
 * Returns true when the write landed, false when the server refused the
 * write itself (403/405/501 — a read-only server's answer; rclone's
 * `--read-only` answers 403), and null when the probe could not reach a
 * verdict (unreachable, an unrelated server error) — the pair is then
 * created as writable and per-file results will tell the truth later,
 * rather than a guess being frozen into the pair.
 */
object WebDavWritabilityProbe {

    private const val PROBE_NAME = ".bitchord-sync-probe"

    suspend fun probe(rootUrl: String, username: String, password: String): Boolean? {
        val root = WebDavPaths.rootOf(rootUrl)
        val probeUrl = WebDavClient.joinUrl(root, PROBE_NAME)
        val put = WebDavClient.putFile(
            fileUrl = probeUrl,
            stream = "bitchord sync probe".byteInputStream(),
            contentLength = 19,
            mimeType = "application/octet-stream",
            username = username,
            password = password,
            overwrite = true,
        )
        return put.fold(
            onSuccess = {
                // Best-effort tidy: a probe file left behind on a server
                // that took the write but refuses deletes is a wart, not a
                // reason to call the server read-only.
                WebDavClient.delete(probeUrl, username, password)
                true
            },
            onFailure = { failure ->
                val code = Regex("""\d{3}""").find(failure.message.orEmpty())?.value?.toIntOrNull()
                if (code == 403 || code == 405 || code == 501) false else null
            },
        )
    }
}
