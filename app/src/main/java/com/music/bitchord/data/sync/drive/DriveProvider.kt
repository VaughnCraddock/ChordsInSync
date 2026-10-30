package com.music.bitchord.data.sync.drive

import android.content.Context
import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp
import com.music.bitchord.data.sync.SyncProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * The far end of a Google Drive pair: any folder in the owner's Drive,
 * addressed by the folder id the add-pair browser stored as the pair's
 * remote locator (with its display path kept as the pair's label).
 *
 * Everything the engine needs is a [DriveApi] call away; the only state
 * here is the path-to-id maps a listing builds, because Drive addresses
 * files by id and the engine by path. A listing runs before any read or
 * write in a sync run, so the maps are warm when they are used — and a
 * write updates them, so a file uploaded early in a run is addressable for
 * the rest of it.
 *
 * The grant is the full Drive scope (Vaughn's choice — see [DriveAuth]),
 * but this provider's behaviour stays narrow: the walk starts at the pair
 * root and goes down, never up or sideways, so a run only ever touches
 * the subtree its pair points at.
 */
class DriveProvider(
    context: Context,
    private val pairRootId: String,
) : SyncProvider {

    private val appContext = context.applicationContext
    private val api = DriveApi { DriveAuth.accessToken(appContext) }

    @Volatile
    private var idByPath: Map<String, String> = emptyMap()

    @Volatile
    private var folderIdByPath: Map<String, String> = mapOf("" to pairRootId)

    override suspend fun list(): List<SyncEntry> = withContext(Dispatchers.IO) {
        val all = mutableListOf<DriveFile>()
        val seen = hashSetOf(pairRootId)
        val queue = ArrayDeque<String>()
        queue.add(pairRootId)
        while (queue.isNotEmpty()) {
            val folderId = queue.removeFirst()
            val children = api.listChildren(folderId)
            all += children
            for (child in children) {
                if (child.isFolder && seen.add(child.id)) queue.add(child.id)
            }
        }
        val resolved = DriveTree.resolve(all, pairRootId)
        idByPath = resolved.associate { it.path to it.file.id }
        folderIdByPath = mapOf("" to pairRootId) +
            resolved.filter { it.file.isFolder }.associate { it.path to it.file.id }
        DriveTree.entries(all, pairRootId)
    }

    override suspend fun read(path: String): InputStream {
        val id = idByPath[path] ?: error("No Drive file at $path")
        return api.download(id)
    }

    override suspend fun write(path: String, source: InputStream, size: Long, mtimeMs: Long?): SyncEntry {
        val segments = path.split('/')
        val parentId = ensureFolders(segments.dropLast(1))
        val file = api.upload(
            existingId = idByPath[path],
            name = segments.last(),
            parentId = parentId,
            source = source,
            size = size,
            mtimeMs = mtimeMs,
        )
        idByPath = idByPath + (path to file.id)
        return SyncEntry(path, SyncFileStamp(size = file.size, mtimeMs = file.modifiedTimeMs, tag = file.md5Checksum))
    }

    override suspend fun delete(path: String) {
        val id = idByPath[path] ?: return
        api.trash(id)
        idByPath = idByPath - path
    }

    /** Walks (and where missing, creates) the folder chain for [segments] under the pair root; returns the deepest folder's id. */
    private suspend fun ensureFolders(segments: List<String>): String {
        var parentId = pairRootId
        var prefix = ""
        for (segment in segments) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            val known = folderIdByPath[prefix]
            if (known != null) {
                parentId = known
                continue
            }
            val created = api.createFolder(segment, parentId)
            folderIdByPath = folderIdByPath + (prefix to created.id)
            parentId = created.id
        }
        return parentId
    }
}

/**
 * What the Sync page's Drive add-pair flow browses: the owner's whole
 * Drive, one folder level at a time, starting at My Drive. Kept beside
 * the provider because it is the same account and the same API — the
 * difference is only that a person, not the engine, is driving. The
 * folder the person is standing in when they create the pair becomes the
 * pair's remote root; creating a new folder goes in the folder being
 * browsed, so a fresh sync folder can be made exactly where it belongs.
 */
object DriveBrowser {

    /** One folder in a listing. The navigation path is the page's stack of these. */
    data class FolderOption(val id: String, val name: String)

    /** Drive's own alias for the My Drive root folder, valid anywhere a folder id is. */
    const val MY_DRIVE_ID = "root"

    /** The subfolders of [parentId], by name — one level of the browser. */
    suspend fun listFolders(context: Context, parentId: String): List<FolderOption> {
        val api = DriveApi { DriveAuth.accessToken(context.applicationContext) }
        return api.listChildren(parentId)
            .filter { it.isFolder && !it.trashed }
            .sortedBy { it.name.lowercase() }
            .map { FolderOption(it.id, it.name) }
    }

    /** A new folder inside [parentId] — the folder the browser is standing in. */
    suspend fun createFolder(context: Context, parentId: String, name: String): FolderOption {
        val api = DriveApi { DriveAuth.accessToken(context.applicationContext) }
        val created = api.createFolder(name, parentId)
        return FolderOption(created.id, created.name)
    }
}
