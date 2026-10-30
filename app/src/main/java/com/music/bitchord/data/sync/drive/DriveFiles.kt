package com.music.bitchord.data.sync.drive

import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * One file or folder as Drive's REST API describes it, reduced to what the
 * sync engine reasons about.
 *
 * [md5Checksum] is the change stamp the planner wants most: Drive computes
 * a real MD5 of every binary file's content, so a file whose bytes moved is
 * known to have moved however the clocks on either side feel about it. It
 * is absent for Google Docs files — which also have no stable [size] to
 * speak of and cannot be byte-synced at all; [DriveTree] leaves them out.
 */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedTimeMs: Long,
    val md5Checksum: String?,
    val parents: List<String>,
    val trashed: Boolean,
) {
    val isFolder: Boolean get() = mimeType == DriveApi.FOLDER_MIME_TYPE
}

/**
 * Drive's JSON, read tolerantly by hand.
 *
 * The shapes here are small and stable (a file resource, a page of them, an
 * error), and reading them by navigation — rather than by generated
 * serializer — means a field Google adds or renames degrades one file's
 * stamp instead of failing a whole listing. Every function in here is pure:
 * the listing-to-entries mapping is a unit test, not a hope.
 */
object DriveJson {

    private val json = Json { ignoreUnknownKeys = true }

    /** One `files.get` / `files.create` response body: a single file resource, or null if it does not parse as one. */
    fun parseFile(body: String): DriveFile? =
        runCatching { fileFrom(json.parseToJsonElement(body).jsonObject) }.getOrNull()

    /** One `files.list` page: the files on it and the token for the next, if there is one. */
    fun parseListPage(body: String): Pair<List<DriveFile>, String?> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val files = root["files"]?.jsonArray?.mapNotNull { element ->
            runCatching { fileFrom(element.jsonObject) }.getOrNull()
        } ?: emptyList()
        val next = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
        files to next
    }.getOrDefault(emptyList<DriveFile>() to null)

    /** The machine-readable reason inside a Drive error body (`rateLimitExceeded`, …), if it carries one. */
    fun errorReason(body: String): String? = runCatching {
        val error = json.parseToJsonElement(body).jsonObject["error"]?.jsonObject ?: return null
        error["errors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: error["status"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun fileFrom(obj: JsonObject): DriveFile? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: return null
        return DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            // Drive reports size as a string, and omits it for folders.
            size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            modifiedTimeMs = obj["modifiedTime"]?.jsonPrimitive?.contentOrNull
                ?.let { DriveTime.parseMs(it) } ?: 0L,
            md5Checksum = obj["md5Checksum"]?.jsonPrimitive?.contentOrNull,
            parents = obj["parents"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            trashed = obj["trashed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        )
    }
}

/** Drive's RFC 3339 timestamps, both ways. Parsing accepts the fractional-second forms Drive actually sends. */
object DriveTime {

    private val parser: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .appendPattern("[XXX][X]")
        .toFormatter()

    fun parseMs(text: String): Long? = runCatching {
        Instant.from(parser.parse(text)).toEpochMilli()
    }.getOrNull()

    /** The form uploads stamp onto a file: whole seconds, UTC, `Z`. Drive accepts it and echoes it back. */
    fun formatMs(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMs))
}

/**
 * A flat pile of [DriveFile]s — everything under a pair's root, gathered
 * folder by folder — resolved into the paths the sync engine speaks.
 *
 * Resolution walks down from the root by parent links rather than trusting
 * any single file's say-so, which buys three properties for free: a trashed
 * file never resolves (listings already filter trashed, but a file trashed
 * mid-walk stays out), a Google Docs file never becomes an entry (it is
 * skipped, not listed — there are no bytes to sync), and a file whose
 * parent chain never reaches the root is not the pair's business.
 */
object DriveTree {

    /** One resolved file or folder: its path under the root, and the Drive file behind it. */
    data class Resolved(val path: String, val file: DriveFile)

    fun resolve(files: List<DriveFile>, rootId: String): List<Resolved> {
        val childrenByParent = HashMap<String, MutableList<DriveFile>>()
        for (file in files) {
            if (file.trashed) continue
            for (parent in file.parents) {
                childrenByParent.getOrPut(parent) { mutableListOf() } += file
            }
        }
        val resolved = mutableListOf<Resolved>()
        // Breadth-first, with a visited set: Drive folders form a tree in
        // practice, but a shared or shortcut loop must not hang a sync run.
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(rootId to "")
        visited += rootId
        while (queue.isNotEmpty()) {
            val (folderId, prefix) = queue.removeFirst()
            for (child in childrenByParent[folderId].orEmpty()) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                when {
                    child.isFolder -> {
                        resolved += Resolved(path, child)
                        if (visited.add(child.id)) queue.add(child.id to path)
                    }
                    child.mimeType.startsWith(GOOGLE_APPS_MIME_PREFIX) -> Unit // No bytes to sync.
                    else -> resolved += Resolved(path, child)
                }
            }
        }
        return resolved
    }

    /** The sync listing for a pair rooted at [rootId]: files only, folders are structure. */
    fun entries(files: List<DriveFile>, rootId: String): List<SyncEntry> =
        resolve(files, rootId)
            .filter { !it.file.isFolder }
            .map { resolved ->
                SyncEntry(
                    path = resolved.path,
                    stamp = SyncFileStamp(
                        size = resolved.file.size,
                        mtimeMs = resolved.file.modifiedTimeMs,
                        tag = resolved.file.md5Checksum,
                    ),
                )
            }

    private const val GOOGLE_APPS_MIME_PREFIX = "application/vnd.google-apps."
}

/** What a failed Drive call means for the run — the difference between "wait", "sign in" and "give up". */
enum class DriveFailure {
    /** The token was refused: only an interactive sign-in can mint a usable one. */
    NEEDS_SIGN_IN,

    /** Quota or a server wobble: the same call, a little later, stands a fair chance. */
    RETRYABLE,

    /** The request itself is wrong for this file or account; repeating it changes nothing. */
    FATAL,
}

/**
 * Drive's failure vocabulary, kept pure so the mapping is a test table.
 *
 * 401 is a credential problem (an expired token is re-minted before this is
 * consulted — see [DriveApi]). A 403 is only retryable when Drive says the
 * reason is rate limiting; the same status carries permission failures that
 * no amount of waiting fixes, and hammering those is how a sync engine gets
 * an account flagged.
 */
object DriveErrors {

    fun classify(httpCode: Int, reason: String?): DriveFailure = when {
        httpCode == 401 -> DriveFailure.NEEDS_SIGN_IN
        httpCode == 429 -> DriveFailure.RETRYABLE
        httpCode in 500..599 -> DriveFailure.RETRYABLE
        httpCode == 403 && reason in RATE_LIMIT_REASONS -> DriveFailure.RETRYABLE
        else -> DriveFailure.FATAL
    }

    /**
     * How long to wait before attempt [attempt] (0-based) is retried: a
     * doubling ladder from one second, capped, with the server's own
     * Retry-After honoured when it asks for longer — also capped, because a
     * sync run that waits forever is a sync run that never reports.
     */
    fun backoffMs(attempt: Int, retryAfterSeconds: Long? = null): Long {
        val ladder = minOf(30_000L, 1_000L shl attempt.coerceIn(0, 5))
        val server = (retryAfterSeconds ?: 0L) * 1_000L
        return maxOf(ladder, minOf(server, 60_000L))
    }

    private val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
}
