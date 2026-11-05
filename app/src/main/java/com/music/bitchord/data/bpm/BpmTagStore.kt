package com.music.bitchord.data.bpm

import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

/** Why a tag write did not land. The UI turns these into words; nothing is ever skipped silently. */
enum class BpmFailure {
    /** The container has no BPM writer (Ogg, WAV, …). Analysis still works on these. */
    UNSUPPORTED_FORMAT,

    /** A supported container whose tag layout this doesn't recognise — refused rather than guessed at. */
    UNRECOGNIZED_LAYOUT,

    /** Too large for the whole-file rewrite path (FLAC streams instead, and is exempt). */
    TOO_LARGE,

    /** Android would not let the app write the file, and offered no consent path. */
    PERMISSION_DENIED,

    /** The file could not be read at all. */
    UNREADABLE,

    /** Written, but the read-back did not show the value — reported, never claimed as saved. */
    VERIFY_FAILED,

    /** Anything else the storage layer answered with. */
    IO_ERROR,
}

/** The outcome of one [BpmTagStore.writeBpm] — every file ends in exactly one of these. */
sealed class BpmWriteOutcome {
    /** The tag now holds [value] — the value as read back after the write, which is the honest number. */
    data class Written(val value: Double) : BpmWriteOutcome()

    /** Android wants the owner's say-so first; show [sender], then call writeBpm again. */
    data class NeedsConsent(val sender: IntentSender) : BpmWriteOutcome()

    data class Failed(val reason: BpmFailure) : BpmWriteOutcome()
}

/**
 * Reads and writes BPM tags on the device's files, through the same
 * ContentResolver/MediaStore doors the app's other local-file work uses.
 *
 * The byte work is [BpmTags]; this object owns the Android half:
 *
 *  - **`file://`** — direct file writes, via a temp file in the same folder
 *    renamed over the original, then a media rescan so MediaStore's own
 *    listing stays in step.
 *  - **`content://`** — bytes out through `openOutputStream(uri, "wt")`.
 *    For a file this app doesn't own, Android answers with a
 *    [SecurityException]; on API 30+ that becomes a
 *    [MediaStore.createWriteRequest] consent (the write cousin of the
 *    createDeleteRequest the local delete flow uses), surfaced as
 *    [BpmWriteOutcome.NeedsConsent] for the UI to show — on API 29 a
 *    [android.app.RecoverableSecurityException] carries its own intent,
 *    used the same way. A denial is a result with a reason, never a
 *    silently skipped file.
 *  - **FLAC streams**: the metadata region is rebuilt from the head and the
 *    frames copied through a cache temp file, so a large FLAC never has to
 *    fit in memory twice. Other formats rewrite whole-file, capped at
 *    [MAX_WHOLE_FILE_BYTES].
 *
 * Every write is **verified by reading the tag back**; the value reported
 * as written is the value the read-back found (for MP4 that is the rounded
 * integer the `tmpo` atom holds, stated as-is).
 */
object BpmTagStore {

    private const val HEAD_BYTES = 16 * 1024 * 1024
    private const val MAX_WHOLE_FILE_BYTES = 96 * 1024 * 1024

    /** The BPM in the file's own tag at [uriString], or null when untagged/unreadable. Fresh read every call. */
    suspend fun readBpm(context: Context, uriString: String): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val name = displayName(context, uri)
            when (uri.scheme) {
                "file" -> {
                    val file = uri.path?.let { File(it) } ?: return@runCatching null
                    if (!file.isFile) return@runCatching null
                    BpmTags.readBpm(readBounded(file), name)
                }
                "content" -> {
                    val head = context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                        ?: return@runCatching null
                    BpmTags.readBpm(head, name)
                }
                else -> null
            }
        }.getOrNull()
    }

    /** Writes [bpm] into the file's tag at [uriString], and verifies by reading it back. */
    suspend fun writeBpm(context: Context, uriString: String, bpm: Double): BpmWriteOutcome =
        withContext(Dispatchers.IO) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                ?: return@withContext BpmWriteOutcome.Failed(BpmFailure.UNREADABLE)
            val name = displayName(context, uri)
            val outcome = when (uri.scheme) {
                "file" -> writeToFile(context, uri, name, bpm)
                "content" -> writeToContent(context, uri, name, bpm)
                else -> BpmWriteOutcome.Failed(BpmFailure.UNSUPPORTED_FORMAT)
            }
            if (outcome !is BpmWriteOutcome.Written) return@withContext outcome
            // The verification is the claim: the saved value reported to the
            // user is the one the file provably holds now.
            val readBack = readBpm(context, uriString)
            val expected = expectedStoredValue(context, uriString, name, bpm)
            when {
                readBack == null -> BpmWriteOutcome.Failed(BpmFailure.VERIFY_FAILED)
                expected != null && kotlin.math.abs(readBack - expected) > 0.051 ->
                    BpmWriteOutcome.Failed(BpmFailure.VERIFY_FAILED)
                else -> BpmWriteOutcome.Written(readBack)
            }
        }

    /** What a successful write of [bpm] should read back as — MP4's integer atom rounds. */
    private suspend fun expectedStoredValue(context: Context, uriString: String, name: String?, bpm: Double): Double? {
        val format = runCatching {
            val uri = Uri.parse(uriString)
            val head = when (uri.scheme) {
                "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile }?.let { readBounded(it, 64 * 1024) }
                "content" -> context.contentResolver.openInputStream(uri)?.use { readBounded(it, 64 * 1024) }
                else -> null
            } ?: return@runCatching null
            BpmTags.formatOf(head, name)
        }.getOrNull() ?: return null
        return if (format == BpmFormat.MP4) {
            bpm.roundToInt().toDouble()
        } else {
            BpmTags.parseBpmText(BpmTags.formatBpmText(bpm))
        }
    }

    // ------------------------------------------------------------------ file://

    private fun writeToFile(context: Context, uri: Uri, name: String?, bpm: Double): BpmWriteOutcome {
        val file = uri.path?.let { File(it) }
            ?: return BpmWriteOutcome.Failed(BpmFailure.UNREADABLE)
        if (!file.isFile) return BpmWriteOutcome.Failed(BpmFailure.UNREADABLE)
        return try {
            val format = BpmTags.formatOf(readBounded(file, 64 * 1024), name)
            when {
                format == BpmFormat.UNSUPPORTED ->
                    BpmWriteOutcome.Failed(BpmFailure.UNSUPPORTED_FORMAT)
                format == BpmFormat.FLAC -> {
                    streamFlacRewrite(file, bpm, name) { temp ->
                        replaceFile(temp, file)
                    }
                }
                file.length() > MAX_WHOLE_FILE_BYTES ->
                    BpmWriteOutcome.Failed(BpmFailure.TOO_LARGE)
                else -> {
                    val bytes = file.readBytes()
                    val updated = BpmTags.withBpm(bytes, bpm, name)
                        ?: return BpmWriteOutcome.Failed(BpmFailure.UNRECOGNIZED_LAYOUT)
                    val temp = tempBeside(file)
                    temp.writeBytes(updated)
                    replaceFile(temp, file)
                    BpmWriteOutcome.Written(bpm)
                }
            }.also {
                if (it is BpmWriteOutcome.Written) {
                    MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
                }
            }
        } catch (denied: SecurityException) {
            BpmWriteOutcome.Failed(BpmFailure.PERMISSION_DENIED)
        } catch (error: Exception) {
            BpmWriteOutcome.Failed(BpmFailure.IO_ERROR)
        }
    }

    // --------------------------------------------------------------- content://

    private suspend fun writeToContent(context: Context, uri: Uri, name: String?, bpm: Double): BpmWriteOutcome {
        val resolver = context.contentResolver
        return try {
            val headStream = resolver.openInputStream(uri)
                ?: return BpmWriteOutcome.Failed(BpmFailure.UNREADABLE)
            val head = headStream.use { readBounded(it, 64 * 1024) }
            when (BpmTags.formatOf(head, name)) {
                BpmFormat.UNSUPPORTED -> BpmWriteOutcome.Failed(BpmFailure.UNSUPPORTED_FORMAT)
                BpmFormat.FLAC -> streamFlacRewrite(contentSource(context, uri), bpm, name) { temp ->
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        temp.inputStream().use { it.copyTo(out) }
                    } ?: throw java.io.IOException("no output stream for $uri")
                }
                else -> {
                    val bytes = resolver.openInputStream(uri)?.use { readBounded(it, MAX_WHOLE_FILE_BYTES + 1) }
                        ?: return BpmWriteOutcome.Failed(BpmFailure.UNREADABLE)
                    if (bytes.size > MAX_WHOLE_FILE_BYTES) {
                        return BpmWriteOutcome.Failed(BpmFailure.TOO_LARGE)
                    }
                    val updated = BpmTags.withBpm(bytes, bpm, name)
                        ?: return BpmWriteOutcome.Failed(BpmFailure.UNRECOGNIZED_LAYOUT)
                    resolver.openOutputStream(uri, "wt")?.use { it.write(updated) }
                        ?: return BpmWriteOutcome.Failed(BpmFailure.IO_ERROR)
                    BpmWriteOutcome.Written(bpm)
                }
            }
        } catch (denied: android.app.RecoverableSecurityException) {
            BpmWriteOutcome.NeedsConsent(denied.userAction.actionIntent.intentSender)
        } catch (denied: SecurityException) {
            consentOrFailure(context, uri)
        } catch (error: Exception) {
            BpmWriteOutcome.Failed(BpmFailure.IO_ERROR)
        }
    }

    /** API 30+ asks through createWriteRequest; older versions without a recoverable path simply refuse. */
    private fun consentOrFailure(context: Context, uri: Uri): BpmWriteOutcome =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                BpmWriteOutcome.NeedsConsent(
                    MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender,
                )
            }.getOrElse { BpmWriteOutcome.Failed(BpmFailure.PERMISSION_DENIED) }
        } else {
            BpmWriteOutcome.Failed(BpmFailure.PERMISSION_DENIED)
        }

    // ------------------------------------------------------------------- FLAC

    /** A re-readable source of a file's bytes, so the FLAC path can make its two passes. */
    private fun interface FlacSource {
        fun open(): InputStream
    }

    private fun contentSource(context: Context, uri: Uri): FlacSource = FlacSource {
        context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open $uri")
    }

    /**
     * Rebuilds [source]'s FLAC with [bpm] in its comment: head in, frames
     * streamed through a cache temp file, [deliver] moves the finished temp
     * onto the target. The temp is always cleaned up.
     */
    private fun streamFlacRewrite(
        source: FlacSource,
        bpm: Double,
        name: String?,
        deliver: (File) -> Unit,
    ): BpmWriteOutcome {
        val temp = File.createTempFile("bpm-flac-", ".tmp")
        return try {
            val head = source.open().use { readBounded(it, HEAD_BYTES) }
            val rewrite = BpmTags.flacRewrite(head, bpm)
                ?: return BpmWriteOutcome.Failed(BpmFailure.UNRECOGNIZED_LAYOUT)
            FileOutputStream(temp).use { out ->
                out.write(rewrite.metadata)
                source.open().use { input ->
                    skipFully(input, rewrite.audioStart.toLong())
                    input.copyTo(out)
                }
            }
            deliver(temp)
            BpmWriteOutcome.Written(bpm)
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun streamFlacRewrite(
        file: File,
        bpm: Double,
        name: String?,
        deliver: (File) -> Unit,
    ): BpmWriteOutcome = streamFlacRewrite(FlacSource { file.inputStream() }, bpm, name, deliver)

    // ------------------------------------------------------------------ helpers

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (input.read() == -1) {
                break
            } else {
                remaining -= 1
            }
        }
    }

    private fun tempBeside(file: File): File =
        File.createTempFile("bpm-", ".tmp", file.parentFile)

    /** Replaces [target] with [temp]: a rename where the filesystem allows, a copy where it doesn't. */
    private fun replaceFile(temp: File, target: File) {
        if (temp.renameTo(target)) return
        temp.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        temp.delete()
    }

    /** Reads a whole file, or its first [limit] bytes when it is bigger than that. */
    private fun readBounded(file: File, limit: Int = HEAD_BYTES): ByteArray {
        if (file.length() <= limit) return file.readBytes()
        return file.inputStream().use { readBounded(it, limit) }
    }

    private fun readBounded(input: InputStream, limit: Int = HEAD_BYTES): ByteArray {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val count = input.read(buffer, read, limit - read)
            if (count < 0) break
            read += count
        }
        return if (read == limit) buffer else buffer.copyOf(read)
    }

    /** The file's display name — the format fallback when magic bytes don't settle it. */
    private fun displayName(context: Context, uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).name }
        "content" -> runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
        else -> null
    }
}
