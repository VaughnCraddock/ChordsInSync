package com.music.bitchord.data.sync

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * The local end of a pair: the pair's own folder under
 * `Music/BitChordSync` in shared storage.
 *
 * Shared storage is the whole point (see [SyncPair]): files here are indexed
 * by MediaStore, so they appear in Local Music and group on the Folders tab
 * like any other music on the phone. The mechanics are the download store's,
 * narrowed to one fixed root: on API 29+ a MediaStore row written pending
 * and committed whole, below that a real file renamed out of its `.part`
 * name and handed to the media scanner.
 *
 * One honesty note: MediaStore's audio collection is the listing, so a file
 * MediaStore does not count as audio (a text file of lyrics notes, say) is
 * not seen here and does not sync. Both of this engine's ends are music
 * folders; the library has exactly the same blind spot.
 */
class SyncLocalStore(
    context: Context,
    /** The pair's directory name directly under `Music/BitChordSync`. */
    private val localFolder: String,
) : SyncProvider {

    private val appContext = context.applicationContext

    /** `Music/BitChordSync/<pair>` — the MediaStore relative path, trailing slash included. */
    private val root: String = "${Environment.DIRECTORY_MUSIC}/$SYNC_ROOT_FOLDER/$localFolder/"

    /** Path to content URI, from the last listing — how a read finds its file. */
    @Volatile
    private var uriByPath: Map<String, Uri> = emptyMap()

    override suspend fun list(): List<SyncEntry> = withContext(Dispatchers.IO) {
        val uris = mutableMapOf<String, Uri>()
        val entries = mutableListOf<SyncEntry>()
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$root%"),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val relative = (cursor.getString(pathCol).orEmpty().removePrefix(root)) +
                        cursor.getString(nameCol).orEmpty()
                    if (relative.isBlank()) continue
                    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(cursor.getLong(idCol).toString())
                        .build()
                    uris[relative] = uri
                    entries += SyncEntry(
                        relative,
                        SyncFileStamp(
                            size = cursor.getLong(sizeCol),
                            mtimeMs = cursor.getLong(modifiedCol) * 1000L,
                        ),
                    )
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "$SYNC_ROOT_FOLDER/$localFolder")
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("${base.absolutePath}/%"),
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val relative = cursor.getString(dataCol).orEmpty()
                        .removePrefix(base.absolutePath + "/")
                    if (relative.isBlank() || relative.startsWith("/")) continue
                    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon()
                        .appendPath(cursor.getLong(idCol).toString())
                        .build()
                    uris[relative] = uri
                    entries += SyncEntry(
                        relative,
                        SyncFileStamp(
                            size = cursor.getLong(sizeCol),
                            mtimeMs = cursor.getLong(modifiedCol) * 1000L,
                        ),
                    )
                }
            }
        }
        uriByPath = uris
        entries
    }

    override suspend fun read(path: String): InputStream = withContext(Dispatchers.IO) {
        val uri = uriByPath[path] ?: error("No local file at $path")
        appContext.contentResolver.openInputStream(uri) ?: error("Could not open $path")
    }

    override suspend fun write(path: String, source: InputStream, size: Long, mtimeMs: Long?): SyncEntry =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(path, source, mtimeMs)
            } else {
                writeViaFile(path, source, mtimeMs)
            }
        }

    private fun writeViaMediaStore(path: String, source: InputStream, mtimeMs: Long?): SyncEntry {
        val resolver = appContext.contentResolver
        val name = path.substringAfterLast('/')
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(name))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (directory.isEmpty()) root else "$root$directory/",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // Offered, not promised: whether the store keeps a written
            // date-modified is the store's call. The entry is read back
            // afterwards either way, and the read-back is what's recorded.
            if (mtimeMs != null) put(MediaStore.MediaColumns.DATE_MODIFIED, mtimeMs / 1000L)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create $path in Music")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.copyTo(out) }
                ?: error("Could not open $path for writing")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        uriByPath = uriByPath + (path to uri)
        return stat(path) ?: SyncEntry(path, SyncFileStamp(size = -1, mtimeMs = mtimeMs ?: 0L))
    }

    @Suppress("DEPRECATION")
    private fun writeViaFile(path: String, source: InputStream, mtimeMs: Long?): SyncEntry {
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "$SYNC_ROOT_FOLDER/$localFolder")
        val target = File(base, path)
        val folder = target.parentFile ?: error("No folder for $path")
        if (!folder.exists() && !folder.mkdirs()) error("Could not create ${folder.path}")
        val part = File(folder, ".${target.name}.part")
        part.delete()
        part.outputStream().use { out -> source.copyTo(out) }
        if (!part.renameTo(target)) {
            part.delete()
            error("Could not finish writing $path")
        }
        if (mtimeMs != null) runCatching { target.setLastModified(mtimeMs) }
        MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), null, null)
        return SyncEntry(path, SyncFileStamp(size = target.length(), mtimeMs = target.lastModified()))
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            val uri = uriByPath[path]
            if (uri != null) {
                appContext.contentResolver.delete(uri, null, null)
                uriByPath = uriByPath - path
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val base = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "$SYNC_ROOT_FOLDER/$localFolder",
                )
                val file = File(base, path)
                if (file.exists() && file.delete()) {
                    MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null)
                }
            }
        }
    }

    /** The entry for [path] as the store now holds it, re-queried rather than remembered. */
    private fun stat(path: String): SyncEntry? {
        val uri = uriByPath[path] ?: return null
        val resolver = appContext.contentResolver
        return runCatching {
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                SyncEntry(
                    path,
                    SyncFileStamp(
                        size = cursor.getLong(0),
                        mtimeMs = cursor.getLong(1) * 1000L,
                    ),
                )
            }
        }.onFailure { Log.w(TAG, "stat failed for $path: ${it.message}") }.getOrNull()
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "audio/*"
    }

    companion object {
        private const val TAG = "SyncLocalStore"

        /** The one folder under Music that sync pairs keep their local sides in. */
        const val SYNC_ROOT_FOLDER = "BitChordSync"
    }
}
