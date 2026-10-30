package com.music.bitchord.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The storage half of deleting local music: files out of MediaStore, with
 * whatever consent the Android version in front of us requires.
 *
 *  - **API 30+**: nothing is deleted by the call itself. The files go into
 *    one [MediaStore.createDeleteRequest] and the system's own dialog is
 *    the deletion — the caller shows it and re-counts afterwards with
 *    [gone]. This app is not the files' owner in MediaStore's eyes, so
 *    there is no silent path, by the platform's design.
 *  - **API 29**: a direct delete, except another app's files answer with a
 *    [android.app.RecoverableSecurityException] whose intent asks the owner
 *    to allow it. Those come back in [Outcome.recoverable]; the caller
 *    shows each in turn and [retry]s the file after an approval.
 *  - **Older, and `file://` paths on any version**: a plain delete — the
 *    world scoped storage replaced.
 *
 * Local only, like everything in this flow: see [LocalDelete].
 */
object LocalMediaDeleter {

    /** What one pass over a set of files did, and what it still needs consent for. */
    data class Outcome(
        /** URI strings confirmed deleted. */
        val deletedUris: Set<String> = emptySet(),
        /** API 30+: the system confirmation covering every content file at once. */
        val deleteRequest: IntentSender? = null,
        /** API 29: files waiting on their owner's consent, URI string to the intent that asks. */
        val recoverable: List<Pair<String, IntentSender>> = emptyList(),
    )

    suspend fun delete(context: Context, uriStrings: Collection<String>): Outcome =
        withContext(Dispatchers.IO) {
            val deleted = mutableSetOf<String>()
            val content = mutableListOf<Uri>()
            for (uriString in uriStrings) {
                val uri = Uri.parse(uriString)
                when (uri.scheme) {
                    "file" -> {
                        val path = uri.path
                        if (path != null && File(path).delete()) deleted += uriString
                    }
                    "content" -> content += uri
                    else -> Unit // Nothing this flow knows how to delete; it stays, and is counted as such.
                }
            }
            if (content.isEmpty()) return@withContext Outcome(deletedUris = deleted)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Outcome(
                    deletedUris = deleted,
                    deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, content).intentSender,
                )

                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    val pending = mutableListOf<Pair<String, IntentSender>>()
                    for (uri in content) {
                        try {
                            if (context.contentResolver.delete(uri, null, null) > 0) {
                                deleted += uri.toString()
                            }
                        } catch (denied: android.app.RecoverableSecurityException) {
                            pending += uri.toString() to denied.userAction.actionIntent.intentSender
                        } catch (denied: SecurityException) {
                            // Not ours to delete on this version, and no
                            // consent path was offered. It stays.
                        }
                    }
                    Outcome(deletedUris = deleted, recoverable = pending)
                }

                else -> {
                    for (uri in content) {
                        try {
                            if (context.contentResolver.delete(uri, null, null) > 0) {
                                deleted += uri.toString()
                            }
                        } catch (denied: SecurityException) {
                            // As above: it stays.
                        }
                    }
                    Outcome(deletedUris = deleted)
                }
            }
        }

    /** One file again, after its owner approved the consent intent for it (API 29). */
    suspend fun retry(context: Context, uriString: String): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    /** Which of [uriStrings] are actually gone — the recount after a system dialog has had its say. */
    suspend fun gone(context: Context, uriStrings: Collection<String>): Set<String> =
        withContext(Dispatchers.IO) {
            uriStrings.filter { uriString ->
                val uri = Uri.parse(uriString)
                when (uri.scheme) {
                    "file" -> uri.path?.let { !File(it).exists() } ?: true
                    else -> runCatching {
                        context.contentResolver.query(
                            uri,
                            arrayOf(MediaStore.MediaColumns._ID),
                            null,
                            null,
                            null,
                        )?.use { cursor -> !cursor.moveToFirst() } ?: true
                    }.getOrDefault(false)
                }
            }.toSet()
        }
}
