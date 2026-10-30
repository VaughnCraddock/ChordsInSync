package com.music.bitchord.data

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Deleting local music: which files a delete covers, and the pending
 * request the UI confirms before anything is touched.
 *
 * The selection half is pure string work so it is unit-testable; the
 * storage half lives in [LocalMediaDeleter], and the confirmation in the
 * `LocalDeleteHost` composable.
 *
 * **Local only, by design.** Nothing in the delete flow talks to the sync
 * engine: deleting a file on this phone never marks anything for remote
 * deletion, and a sync pair's index is never consulted or updated here.
 * Sync keeps its own no-delete-propagation default whatever happens here —
 * a file deleted locally is, to sync, simply a file that is gone, and the
 * planner's deletion-kept rule treats it exactly like any other.
 */
object LocalDelete {

    /** The distinct URIs of [songs]' files, in first-seen order — the set a deletion pass works through. */
    fun uriStringsFor(songs: List<Song>): List<String> =
        songs.mapNotNull { song -> song.localUri?.takeIf { it.isNotBlank() } }.distinct()

    /** The songs whose files sit directly in the folder at [folderPath] — a folder group's own tracks. */
    fun folderSongs(songs: List<Song>, folderPath: String): List<Song> =
        songs.filter { it.folderPath() == folderPath }
}

/**
 * A deletion its owner has asked for but not yet confirmed: the songs, and
 * the folder they came from when the ask was about a whole folder.
 *
 * Held in a flow rather than passed through the menus because the menus
 * close before the confirmation opens — the ask has to survive them.
 */
data class LocalDeleteRequest(
    val songs: List<Song>,
    /** The folder being deleted, when the ask was a folder's; null for a song's own delete. */
    val folderPath: String? = null,
    /** The folder's display name, for the confirmation's wording. */
    val folderName: String? = null,
)

/** The one pending [LocalDeleteRequest], if any. Written by menus, read and cleared by the delete host. */
object LocalDeleteRequests {

    val pending = MutableStateFlow<LocalDeleteRequest?>(null)

    fun ask(request: LocalDeleteRequest) {
        if (request.songs.isNotEmpty()) pending.value = request
    }

    fun clear() {
        pending.value = null
    }
}
