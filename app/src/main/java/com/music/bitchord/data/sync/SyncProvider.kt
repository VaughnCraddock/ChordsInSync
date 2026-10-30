package com.music.bitchord.data.sync

import java.io.InputStream

/**
 * One side of a sync pair: a folder, somewhere, that can be listed, read
 * from and — where the side allows it — written to.
 *
 * Both ends of a device-folders pair implement this: the picked folder on
 * shared storage is the "remote", and the pair's folder under
 * `Music/BitChordSync` is the "local". The cloud engines to come implement
 * the same shape against their APIs, which is the whole point of drawing
 * the line here.
 *
 * Paths are relative to the pair's root, `/`-separated, with no leading
 * slash — [SyncEntry.path]'s own convention.
 */
interface SyncProvider {

    /** Every file under the root, recursively. Directories are structure, not entries. */
    suspend fun list(): List<SyncEntry>

    /** The bytes of the file at [path]. The caller closes the stream. */
    suspend fun read(path: String): InputStream

    /**
     * Stores [source] at [path], making whatever folders the path needs,
     * and returns the entry as this side sees it once stored — the stamp the
     * engine records as agreed, read back rather than assumed, because a
     * side's clock and precision are its own.
     *
     * [mtimeMs] is the moment the source file last changed, offered to sides
     * that can keep it. A side that cannot set a file's time ignores it and
     * its returned entry says the time it actually has.
     */
    suspend fun write(path: String, source: InputStream, size: Long, mtimeMs: Long?): SyncEntry

    /** Removes the file at [path]. Only ever called when a pair mirrors deletions. */
    suspend fun delete(path: String)
}

/**
 * Characters a folder name on shared storage cannot hold, and the whitespace
 * runs a name reads better without — the same treatment [com.music.bitchord.download.DownloadStore]
 * gives the files it names.
 */
internal fun syncSanitisedName(raw: String): String = raw
    .replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), " ")
    .replace(Regex("""\s+"""), " ")
    .trim()
    .trim('.')
