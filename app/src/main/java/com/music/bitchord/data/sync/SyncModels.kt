package com.music.bitchord.data.sync

import kotlinx.serialization.Serializable

/**
 * Which way a sync pair is allowed to move files, as its owner configured it.
 *
 * What a run may actually do can be narrower — the home gate and a read-only
 * remote both clamp this before a planner ever sees it (see [HomeGate]).
 * Persisted by name inside [SyncPair].
 */
@Serializable
enum class SyncDirection {
    /** Both ways: new and changed files travel whichever way they moved. */
    BIDIRECTIONAL,

    /** Phone to remote only. The remote is never read for content. */
    UPLOAD_ONLY,

    /** Remote to phone only. The remote is never written to, not even a folder. */
    DOWNLOAD_ONLY,
}

/**
 * One folder on the far side of a pair, kept in step with one folder on this
 * phone. The local side of every pair lives under `Music/BitChordSync` in
 * shared storage — app-private storage is invisible to the media scanner, so
 * anything synced there would never appear in Local Music or the Folders tab.
 *
 * Deliberately free of secrets: an engine's tokens and passwords live in
 * `AuthStore`, never in here, so this record can sit in plain preferences
 * beside the download records it is modelled on.
 */
@Serializable
data class SyncPair(
    /** Stable id, a UUID string minted at creation. */
    val id: String,
    /** Which engine owns the far side — a [SyncEngines] id. */
    val engineId: String,
    /** The name its owner gave it; also what its local folder was named from. */
    val name: String,
    /**
     * Where the far side is, in the engine's own terms: a SAF tree URI for
     * device folders, a provider folder id or path for the engines to come.
     */
    val remoteLocator: String,
    /** The far folder's name, for showing rather than for addressing. */
    val remoteLabel: String = "",
    /**
     * This pair's directory name directly under `Music/BitChordSync`. Fixed
     * at creation: renaming a pair must not move its files, so the folder
     * keeps the name it was born with.
     */
    val localFolder: String,
    val direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
    /** A paused pair keeps every setting and simply never runs. */
    val enabled: Boolean = true,
    /**
     * Whether a file deleted on one side is deleted on the other. Off — the
     * default, and the reason is in the planner — a deletion is reported and
     * the surviving copy is kept.
     */
    val mirrorDeletes: Boolean = false,
    /** The last finished run, or null if the pair has never run. */
    val status: SyncRunStatus? = null,
)

/** How the last run of a pair ended, with the counts a status line needs. */
@Serializable
data class SyncRunStatus(
    /** When the run finished, epoch milliseconds. */
    val atMs: Long,
    /** A [SyncRunResult] name. */
    val result: String,
    val downloaded: Int = 0,
    val uploaded: Int = 0,
    val conflicts: Int = 0,
    val failed: Int = 0,
    /** Deletions kept and conflicts preserved, counted so a status line can say they happened. */
    val notes: Int = 0,
)

/** The outcomes a run can end in, by name — see [SyncRunStatus.result]. */
enum class SyncRunResult {
    /** Everything the plan called for happened. */
    SUCCESS,

    /** Transfers happened, but some files failed or a conflict was preserved. */
    PARTIAL,

    /** The run could not be carried out at all. */
    FAILED,

    /** Upload work exists but the phone is not on the home network. */
    WAITING_HOME,

    /** The pair's engine is switched off, so the run was not attempted. */
    ENGINE_OFF,
}

/** What one side says about one file: how big it is, when it last changed. */
data class SyncFileStamp(
    val size: Long,
    val mtimeMs: Long,
    /**
     * A content stamp where the side offers one (an etag, a revision, a
     * hash). Beats comparing clocks when both sides of a comparison have it;
     * null where the side has nothing of the kind, as both of this phase's
     * endpoints are.
     */
    val tag: String? = null,
)

/** One file in a recursive listing, named relative to the pair's root. */
data class SyncEntry(
    /** `/`-separated, no leading slash: `Rock/Live/track.flac`. */
    val path: String,
    val stamp: SyncFileStamp,
)

/**
 * What the engine remembers of one file from the last run that agreed on it:
 * the stamps both sides reported *after* that run. Diffing against this —
 * rather than comparing the two sides' clocks against each other — is what
 * makes change detection sane across devices whose clocks disagree, and it
 * is what tells a deletion (the file was here, now it is not) from a file
 * that simply has not been copied yet.
 */
@Serializable
data class SyncIndexEntry(
    val path: String,
    val localSize: Long,
    val localMtimeMs: Long,
    val remoteSize: Long,
    val remoteMtimeMs: Long,
    val remoteTag: String? = null,
) {
    fun localStamp(): SyncFileStamp = SyncFileStamp(localSize, localMtimeMs)

    fun remoteStamp(): SyncFileStamp = SyncFileStamp(remoteSize, remoteMtimeMs, remoteTag)
}

/**
 * The engines a pair can belong to. Only [DEVICE_FOLDERS] is wired in this
 * phase; the rest are registered so the settings UI can list them as coming
 * soon and so a pair created later needs no new plumbing here. The order is
 * the build order Vaughn picked: device folders (the reference engine),
 * then Google Drive, OneDrive, Dropbox, then the home servers.
 */
object SyncEngines {
    const val DEVICE_FOLDERS = "DEVICE_FOLDERS"
    const val GOOGLE_DRIVE = "GOOGLE_DRIVE"
    const val ONEDRIVE = "ONEDRIVE"
    const val DROPBOX = "DROPBOX"
    const val WEBDAV = "WEBDAV"
    const val SMB = "SMB"

    /** Every engine id, in display order. */
    val all: List<String> = listOf(DEVICE_FOLDERS, GOOGLE_DRIVE, ONEDRIVE, DROPBOX, WEBDAV, SMB)

    /** Engines with a working provider behind them in this build. */
    private val available: Set<String> = setOf(DEVICE_FOLDERS)

    fun isAvailable(engineId: String): Boolean = engineId in available
}
