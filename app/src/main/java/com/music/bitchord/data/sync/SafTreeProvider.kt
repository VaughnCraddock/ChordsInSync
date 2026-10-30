package com.music.bitchord.data.sync

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * The far end of a device-folders pair: a folder on shared storage its owner
 * picked, held through a persisted read-and-write SAF grant.
 *
 * This is the reference engine — the one the whole sync core is proven
 * against before any cloud account is involved. Everything goes through
 * [DocumentFile], which is provider round-trips per file and not fast; for
 * a picked music folder that is the honest price of writing somewhere the
 * app does not own.
 *
 * A SAF tree cannot set a file's modification time, so [write] ignores the
 * offered mtime and the entry it returns carries the time the write itself
 * produced. The engine records that read-back as the agreed stamp, so the
 * pair still compares clean next run.
 */
class SafTreeProvider(
    context: Context,
    treeUri: String,
) : SyncProvider {

    private val appContext = context.applicationContext
    private val root: DocumentFile? = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUri))

    private fun tree(): DocumentFile =
        root?.takeIf { it.exists() } ?: error("The picked folder is no longer available")

    override suspend fun list(): List<SyncEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<SyncEntry>()
        fun walk(directory: DocumentFile, prefix: String) {
            for (child in directory.listFiles()) {
                val name = child.name ?: continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                when {
                    child.isDirectory -> walk(child, path)
                    child.isFile -> entries += SyncEntry(
                        path,
                        SyncFileStamp(size = child.length(), mtimeMs = child.lastModified()),
                    )
                }
            }
        }
        walk(tree(), "")
        entries
    }

    override suspend fun read(path: String): InputStream = withContext(Dispatchers.IO) {
        val file = find(path) ?: error("No file at $path in the picked folder")
        appContext.contentResolver.openInputStream(file.uri) ?: error("Could not open $path")
    }

    override suspend fun write(path: String, source: InputStream, size: Long, mtimeMs: Long?): SyncEntry =
        withContext(Dispatchers.IO) {
            val segments = path.split('/')
            var directory = tree()
            for (segment in segments.dropLast(1)) {
                val next = directory.findFile(segment)?.takeIf { it.isDirectory }
                    ?: directory.createDirectory(segment)
                    ?: error("Could not create folder $segment")
                directory = next
            }
            val name = segments.last()
            val existing = directory.findFile(name)?.takeIf { it.isFile }
            val file = existing ?: directory.createFile(mimeTypeFor(name), name)
                ?: error("Could not create $path in the picked folder")
            // "wt": write-and-truncate. A plain write over a longer old file
            // would leave its tail behind as part of the new one.
            appContext.contentResolver.openOutputStream(file.uri, "wt")?.use { out -> source.copyTo(out) }
                ?: error("Could not open $path for writing")
            val written = directory.findFile(name) ?: file
            SyncEntry(path, SyncFileStamp(size = written.length(), mtimeMs = written.lastModified()))
        }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            find(path)?.delete()
        }
    }

    /** Whether the persisted grant for this tree includes writing — the pair's writability fact. */
    fun canWrite(): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.isWritePermission && root != null && permission.uri == root.uri
        }

    private fun find(path: String): DocumentFile? {
        var current = root ?: return null
        val segments = path.split('/')
        for ((index, segment) in segments.withIndex()) {
            val next = current.findFile(segment) ?: return null
            if (index < segments.lastIndex && !next.isDirectory) return null
            current = next
        }
        return current
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}
