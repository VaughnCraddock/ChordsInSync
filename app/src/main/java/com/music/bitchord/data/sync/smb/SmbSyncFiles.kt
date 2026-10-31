package com.music.bitchord.data.sync.smb

import com.music.bitchord.data.smb.SmbConfig
import com.music.bitchord.data.sync.SyncEntry
import com.music.bitchord.data.sync.SyncFileStamp

/**
 * One row of a share directory listing, as the wire reported it — the thin
 * shape between SMBJ's `FileIdBothDirectoryInformation` and everything the
 * sync layer reasons about. Keeping the mapping from rows to sync entries
 * on this shape (not on SMBJ's types) is what makes it unit-testable on
 * the JVM: the provider's walk only has to translate the wire type into a
 * row, and every decision after that is here.
 */
data class SmbRawEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Last-write time, epoch milliseconds — the stamp SMB change detection lives on. */
    val lastWriteMs: Long,
)

object SmbSyncFiles {

    /** [prefix] joined with a child name, display form; a blank prefix means the pair root itself. */
    fun childPath(prefix: String, name: String): String =
        if (prefix.isBlank()) name else "$prefix/$name"

    /** A file row as a sync entry under [path]: size + last-write, and no tag — SMB offers nothing better. */
    fun toSyncEntry(path: String, row: SmbRawEntry): SyncEntry =
        SyncEntry(path, SyncFileStamp(size = row.size, mtimeMs = row.lastWriteMs))

    /** What one directory's rows split into: the subfolders to walk, and the audio files to sync. */
    data class Split(val directories: List<String>, val files: List<SyncEntry>)

    /**
     * Splits one directory's [rows] (its path within the pair is [prefix]).
     *
     * Audio only, like every other end of a pair: the phone side lists
     * through MediaStore's audio collection, and a file it cannot see could
     * never settle. The `.`/`..` rows a server includes about itself are
     * structure, not content.
     */
    fun split(rows: List<SmbRawEntry>, prefix: String): Split {
        val directories = mutableListOf<String>()
        val files = mutableListOf<SyncEntry>()
        for (row in rows) {
            if (row.name == "." || row.name == "..") continue
            val path = childPath(prefix, row.name)
            when {
                row.isDirectory -> directories += path
                SmbConfig.isAudioFile(row.name) -> files += toSyncEntry(path, row)
            }
        }
        return Split(directories, files)
    }
}
