package com.music.bitchord.data.sync.drive

import android.webkit.MimeTypeMap
import com.music.bitchord.data.Http
import com.music.bitchord.data.sync.SyncNeedsSignInException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A Drive call that failed in a way retrying will not fix; [httpCode] 0 means the failure never reached Drive. */
class DriveApiException(
    val httpCode: Int,
    val reason: String?,
    message: String?,
) : Exception(message ?: "Drive request failed ($httpCode${reason?.let { ", $it" } ?: ""})")

/** The account behind a token, from `about.get` — the fallback identity when Play services offers none. */
data class DriveUser(val email: String, val displayName: String?)

/**
 * Drive REST v3 over the app's shared OkHttp client, with the sync
 * engine's manners: a token fetched lazily and refreshed once on a 401,
 * bounded backoff on quota and server errors (the [DriveErrors] table
 * decides what is worth retrying), and no request ever retried past the
 * point where its body cannot be replayed — every body this class builds
 * is bytes it holds, which is exactly why small uploads are buffered and
 * large ones move in buffered chunks.
 *
 * The token itself is not this class's business: [freshToken] is asked
 * whenever one is needed, and asking again is how a refresh happens.
 */
class DriveApi(
    private val freshToken: suspend () -> String,
) {

    private val client = Http.client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedToken: String? = null

    private suspend fun token(forceRefresh: Boolean): String {
        if (forceRefresh) cachedToken = null
        cachedToken?.let { return it }
        return freshToken().also { cachedToken = it }
    }

    // ── Reads ────────────────────────────────────────────────────────────

    /** The connected account, for the identity [DriveAuth] stores at connect time. */
    suspend fun aboutUser(): DriveUser = jsonCall(
        build = { token ->
            Request.Builder()
                .url(apiUrl("/about", mapOf("fields" to "user(displayName,emailAddress)")))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        },
        read = { body ->
            val user = (Json.parseToJsonElement(body) as? JsonObject)?.get("user") as? JsonObject
            val email = (user?.get("emailAddress") as? JsonPrimitive)?.content
            val name = (user?.get("displayName") as? JsonPrimitive)?.content
            DriveUser(email ?: throw DriveApiException(0, null, "Drive did not say which account this is"), name)
        },
    )

    /** Every child of [folderId], folders and files alike, across all pages. */
    suspend fun listChildren(folderId: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val params = mutableMapOf(
                "q" to "'$folderId' in parents and trashed = false",
                "fields" to "nextPageToken,files($FILE_FIELDS)",
                "pageSize" to "1000",
                "spaces" to "drive",
            )
            if (pageToken != null) params["pageToken"] = pageToken
            val page = jsonCall(
                build = { token ->
                    Request.Builder()
                        .url(apiUrl("/files", params))
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build()
                },
                read = { body -> DriveJson.parseListPage(body) },
            )
            files += page.first
            pageToken = page.second
        } while (pageToken != null)
        return files
    }

    /** One file's current metadata — the stamps an upload reads back rather than assumes. */
    suspend fun getFile(fileId: String): DriveFile = jsonCall(
        build = { token ->
            Request.Builder()
                .url(apiUrl("/files/$fileId", mapOf("fields" to FILE_FIELDS)))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        },
        read = { body -> DriveJson.parseFile(body) ?: throw DriveApiException(0, null, "Unparsable file resource") },
    )

    /**
     * The bytes of [fileId], streamed: the returned stream owns the
     * response, and closing it closes both. Nothing is buffered — a FLAC
     * album crosses the phone at the pace the local store writes it.
     */
    suspend fun download(fileId: String): InputStream {
        val response = executeWithRetry { token ->
            Request.Builder()
                .url(apiUrl("/files/$fileId", mapOf("alt" to "media")))
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        }
        val body = response.body ?: run {
            response.close()
            throw DriveApiException(0, null, "Drive returned no body for a download")
        }
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                response.close()
                super.close()
            }
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────

    /** Creates a folder; [parentId] null puts it at the Drive root. */
    suspend fun createFolder(name: String, parentId: String?): DriveFile {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            if (parentId != null) put("parents", buildJsonArray { add(JsonPrimitive(parentId)) })
        }
        return jsonCall(
            build = { token ->
                Request.Builder()
                    .url(apiUrl("/files", mapOf("fields" to FILE_FIELDS)))
                    .header("Authorization", "Bearer $token")
                    .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
            read = { body -> DriveJson.parseFile(body) ?: throw DriveApiException(0, null, "Unparsable folder resource") },
        )
    }

    /** Moves a file to Drive's trash — recoverable, and the only way this engine ever deletes. */
    suspend fun trash(fileId: String) {
        val metadata = buildJsonObject { put("trashed", true) }
        jsonCall(
            build = { token ->
                Request.Builder()
                    .url(apiUrl("/files/$fileId", mapOf("fields" to "id")))
                    .header("Authorization", "Bearer $token")
                    .patch(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
            read = { Unit },
        )
    }

    /**
     * Stores [source] as [name] under [parentId], or as a new revision of
     * [existingId] when the file is already known, stamping [mtimeMs] onto
     * the remote copy so the next comparison is exact. Returns the file's
     * metadata read back from Drive afterwards — MD5 included — never the
     * caller's assumptions about what landed.
     *
     * Small files go in one multipart request, buffered whole so the
     * request can be replayed on a retry. Large files go through a
     * resumable session in [CHUNK_BYTES] chunks, each chunk likewise held
     * until Drive acknowledges it.
     */
    suspend fun upload(
        existingId: String?,
        name: String,
        parentId: String?,
        source: InputStream,
        size: Long,
        mtimeMs: Long?,
    ): DriveFile {
        val metadata = buildJsonObject {
            if (existingId == null) {
                put("name", name)
                if (parentId != null) put("parents", buildJsonArray { add(JsonPrimitive(parentId)) })
            }
            if (mtimeMs != null) put("modifiedTime", DriveTime.formatMs(mtimeMs))
        }
        val uploaded = if (size < RESUMABLE_THRESHOLD_BYTES) {
            val bytes = withContext(Dispatchers.IO) { source.readBytes() }
            uploadMultipart(existingId, metadata, bytes, mimeTypeFor(name))
        } else {
            uploadResumable(existingId, metadata, source, size, mimeTypeFor(name))
        }
        return getFile(uploaded.id)
    }

    private suspend fun uploadMultipart(
        existingId: String?,
        metadata: JsonObject,
        bytes: ByteArray,
        mimeType: String,
    ): DriveFile {
        val body = MultipartBody.Builder()
            .setType(MULTIPART_RELATED)
            .addPart(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        return jsonCall(
            build = { token ->
                val url = uploadUrl(
                    if (existingId == null) "/files" else "/files/$existingId",
                    mapOf("uploadType" to "multipart", "fields" to FILE_FIELDS),
                )
                val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
                if (existingId == null) builder.post(body) else builder.patch(body)
                builder.build()
            },
            read = { responseBody ->
                DriveJson.parseFile(responseBody)
                    ?: throw DriveApiException(0, null, "Unparsable upload response")
            },
        )
    }

    private suspend fun uploadResumable(
        existingId: String?,
        metadata: JsonObject,
        source: InputStream,
        size: Long,
        mimeType: String,
    ): DriveFile {
        val sessionUri = initiateResumable(existingId, metadata, size, mimeType)
        // The window: buffer[0] is always the byte at `committed`. A chunk
        // Drive acknowledges only in part slides the window instead of
        // re-reading the source — a one-shot stream cannot rewind, and the
        // unacknowledged tail is still right here.
        val buffer = ByteArray(CHUNK_BYTES)
        var bufferLength = 0
        var committed = 0L
        var exhausted = false
        while (committed < size) {
            if (bufferLength == 0 && !exhausted) {
                var read = 0
                while (read < buffer.size) {
                    val n = withContext(Dispatchers.IO) { source.read(buffer, read, buffer.size - read) }
                    if (n < 0) {
                        exhausted = true
                        break
                    }
                    read += n
                }
                bufferLength = read
                if (read == 0) break
            }
            val endExclusive = minOf(committed + bufferLength, size)
            val outcome = putChunk(
                sessionUri = sessionUri,
                bytes = buffer,
                length = (endExclusive - committed).toInt(),
                start = committed,
                endExclusive = endExclusive,
                total = size,
            )
            when (outcome) {
                is ChunkOutcome.Complete -> return outcome.file
                is ChunkOutcome.Incomplete -> {
                    val advance = (outcome.committed - committed).toInt()
                    require(advance in 0..bufferLength) { "Drive acknowledged bytes outside the sent window" }
                    System.arraycopy(buffer, advance, buffer, 0, bufferLength - advance)
                    bufferLength -= advance
                    committed = outcome.committed
                }
            }
        }
        throw DriveApiException(0, null, "Upload ended without Drive confirming the file")
    }

    private sealed interface ChunkOutcome {
        data class Complete(val file: DriveFile) : ChunkOutcome
        data class Incomplete(val committed: Long) : ChunkOutcome
    }

    private suspend fun initiateResumable(
        existingId: String?,
        metadata: JsonObject,
        size: Long,
        mimeType: String,
    ): String {
        val response = executeWithRetry { token ->
            val url = uploadUrl(
                if (existingId == null) "/files" else "/files/$existingId",
                mapOf("uploadType" to "resumable"),
            )
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", mimeType)
                .header("X-Upload-Content-Length", size.toString())
            val body = metadata.toString().toRequestBody(JSON_MEDIA_TYPE)
            if (existingId == null) builder.post(body) else builder.patch(body)
            builder.build()
        }
        return response.use {
            it.header("Location")
                ?: throw DriveApiException(it.code, null, "Resumable initiation returned no session URI")
        }
    }

    /** Sends one buffered window of a resumable upload, retrying the window itself on transient failures. */
    private suspend fun putChunk(
        sessionUri: String,
        bytes: ByteArray,
        length: Int,
        start: Long,
        endExclusive: Long,
        total: Long,
    ): ChunkOutcome {
        var attempts = 0
        var authRetried = false
        while (true) {
            val currentToken = token(forceRefresh = false)
            val body = bytes.toRequestBody(null, 0, length)
            val request = Request.Builder()
                .url(sessionUri)
                .header("Authorization", "Bearer $currentToken")
                .header("Content-Range", "bytes $start-${endExclusive - 1}/$total")
                .put(body)
                .build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            try {
                when {
                    response.code == 200 || response.code == 201 -> {
                        val text = response.body?.string().orEmpty()
                        val file = DriveJson.parseFile(text)
                            ?: throw DriveApiException(0, null, "Unparsable upload completion")
                        return ChunkOutcome.Complete(file)
                    }
                    response.code == 308 -> {
                        val rangeEnd = response.header("Range")
                            ?.substringAfterLast('=')?.substringAfterLast('-')?.toLongOrNull()
                        if (rangeEnd != null) return ChunkOutcome.Incomplete(rangeEnd + 1)
                        return sessionStatus(sessionUri, total)
                    }
                    else -> {
                        val errorBody = response.body?.string().orEmpty()
                        val reason = DriveJson.errorReason(errorBody)
                        when (DriveErrors.classify(response.code, reason)) {
                            DriveFailure.NEEDS_SIGN_IN -> {
                                if (authRetried) throw SyncNeedsSignInException("Drive refused the refreshed token")
                                authRetried = true
                                token(forceRefresh = true)
                            }
                            DriveFailure.RETRYABLE -> {
                                if (attempts >= MAX_RETRIES) {
                                    throw DriveApiException(response.code, reason, "Chunk upload kept failing: $errorBody")
                                }
                                delay(DriveErrors.backoffMs(attempts, retryAfterSeconds(response)))
                                attempts++
                            }
                            DriveFailure.FATAL ->
                                throw DriveApiException(response.code, reason, errorBody.ifBlank { null })
                        }
                    }
                }
            } finally {
                response.close()
            }
        }
    }

    /** Asks a resumable session how much it holds — the answer to a 308 that did not say. */
    private suspend fun sessionStatus(sessionUri: String, total: Long): ChunkOutcome {
        val currentToken = token(forceRefresh = false)
        val request = Request.Builder()
            .url(sessionUri)
            .header("Authorization", "Bearer $currentToken")
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        return response.use {
            when (it.code) {
                200, 201 -> ChunkOutcome.Complete(
                    DriveJson.parseFile(it.body?.string().orEmpty())
                        ?: throw DriveApiException(0, null, "Unparsable upload completion"),
                )
                308 -> {
                    val rangeEnd = it.header("Range")
                        ?.substringAfterLast('=')?.substringAfterLast('-')?.toLongOrNull()
                    ChunkOutcome.Incomplete((rangeEnd ?: -1L) + 1)
                }
                else -> throw DriveApiException(it.code, null, "Session status query failed")
            }
        }
    }

    // ── Request plumbing ─────────────────────────────────────────────────

    /**
     * Runs a request whose body can be replayed, applying the token and
     * the retry table, and hands back the successful response — ownership
     * included: the caller closes it. Errors leave as [DriveApiException],
     * or as [SyncNeedsSignInException] when even a refreshed token is
     * refused, which no retry here can fix.
     */
    private suspend fun executeWithRetry(build: (String) -> Request): Response {
        var attempts = 0
        var authRetried = false
        while (true) {
            val currentToken = token(forceRefresh = false)
            val response = withContext(Dispatchers.IO) { client.newCall(build(currentToken)).execute() }
            if (response.isSuccessful) return response
            val errorBody = response.body?.string().orEmpty()
            val reason = DriveJson.errorReason(errorBody)
            val failure = DriveErrors.classify(response.code, reason)
            val code = response.code
            val retryAfter = retryAfterSeconds(response)
            response.close()
            when (failure) {
                DriveFailure.NEEDS_SIGN_IN -> {
                    if (authRetried) throw SyncNeedsSignInException("Drive refused the refreshed token")
                    authRetried = true
                    token(forceRefresh = true)
                }
                DriveFailure.RETRYABLE -> {
                    if (attempts >= MAX_RETRIES) {
                        throw DriveApiException(code, reason, "Drive kept failing: $errorBody")
                    }
                    delay(DriveErrors.backoffMs(attempts, retryAfter))
                    attempts++
                }
                DriveFailure.FATAL -> throw DriveApiException(code, reason, errorBody.ifBlank { null })
            }
        }
    }

    /** A call whose whole answer is its body: runs it, reads it (off the caller's thread), closes it. */
    private suspend fun <T> jsonCall(build: (String) -> Request, read: (String) -> T): T =
        withContext(Dispatchers.IO) {
            executeWithRetry(build).use { response -> read(response.body?.string().orEmpty()) }
        }

    private fun retryAfterSeconds(response: Response): Long? =
        response.header("Retry-After")?.toLongOrNull()

    private fun apiUrl(path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val builder = "$API_BASE$path".toHttpUrl().newBuilder()
        for ((key, value) in params) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private fun uploadUrl(path: String, params: Map<String, String> = emptyMap()): HttpUrl {
        val builder = "$UPLOAD_BASE$path".toHttpUrl().newBuilder()
        for ((key, value) in params) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

        private const val API_BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE_FIELDS = "id,name,mimeType,size,modifiedTime,md5Checksum,parents,trashed"

        /** Bodies are replayed from memory on a retry; a body this class cannot hold, it does not send this way. */
        private const val MAX_RETRIES = 3

        /** At or above this size an upload uses a resumable session instead of one buffered request. */
        const val RESUMABLE_THRESHOLD_BYTES = 8L * 1024 * 1024

        /** Resumable chunk size: 8 MiB, a whole multiple of Drive's required 256 KiB. */
        private const val CHUNK_BYTES = 8 * 1024 * 1024

        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()
    }
}
