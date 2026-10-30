package com.music.bitchord.data.sync

import com.music.bitchord.data.webdav.WebDavClient
import java.util.Locale

/** One thing a run should do, in the order the planner found it. */
data class SyncAction(
    val kind: Kind,
    /** The file, relative to the pair's root. */
    val path: String,
    /** For [Kind.DOWNLOAD_CONFLICT_COPY]: the name the preserved copy is written under. */
    val conflictName: String? = null,
    /** For [Kind.NOTE]: what there is to say about this file. */
    val note: SyncNote? = null,
) {
    enum class Kind {
        DOWNLOAD,
        UPLOAD,

        /** Both sides changed: the remote copy comes down under a new name, the local file is left exactly as it is. */
        DOWNLOAD_CONFLICT_COPY,

        /** Only ever planned when the pair mirrors deletions. */
        DELETE_REMOTE,

        /** Only ever planned when the pair mirrors deletions. */
        DELETE_LOCAL,

        /** Nothing moves; the run report says this instead. */
        NOTE,
    }
}

/** The anomalies a plan can note without moving a byte. */
enum class SyncNote {
    /** Deleted on the phone; the remote copy is being kept. */
    DELETED_ON_PHONE_KEPT,

    /** Deleted on the remote; the phone copy is being kept. */
    DELETED_ON_REMOTE_KEPT,

    /** Both sides changed and the direction leaves no way to preserve the remote copy; neither side is touched. */
    CONFLICT_KEPT,
}

/**
 * A finished plan: the [actions] to carry out, and the [settled] index
 * entries to record for files that needed no action because both sides
 * already agree about them.
 *
 * The distinction matters: a file whose transfer was merely *blocked* by
 * the direction (a local change on a download-only run) is not settled —
 * its index entry keeps the old agreed stamps, so a later run that may
 * upload will plan the upload then.
 */
data class SyncPlan(
    val actions: List<SyncAction>,
    val settled: Map<String, SyncIndexEntry>,
)

/**
 * The diff at the centre of the sync engine, and a pure function on purpose:
 * listings and the remembered index in, a plan out, no I/O and no clock of
 * its own — the conflict date is handed in — so every rule below is a unit
 * test rather than a hope.
 *
 * The rules, in the order they are applied per file:
 *
 *  - **Sync never deletes by default.** A file the index knows and one side
 *    no longer has is a deletion, and a deletion is *reported*, with the
 *    surviving copy kept — an unmounted card or a file-manager slip on one
 *    side must not become a loss on the other. Only a pair with
 *    [SyncPair.mirrorDeletes] on propagates a deletion, and only in the
 *    directions the pair otherwise allows: **upload-only never deletes
 *    remotely and download-only never deletes locally** unless mirroring is
 *    explicitly on for that pair. A one-way pair without mirroring treats a
 *    deletion on its target side as "missing" and restores the file, which
 *    is what a mirror is for.
 *  - A file only one side has, that the index has never seen, is new there
 *    and copies across — in the directions the pair allows. A direction that
 *    does not allow it is not an anomaly: the file is simply not this pair's
 *    business, and is skipped without a note.
 *  - A file both sides changed since the agreed state is a **conflict**, and
 *    conflicts keep both: the remote copy is downloaded under
 *    `name (conflict yyyy-MM-dd).ext` beside the local one, and the local
 *    file is left untouched for its owner to resolve. Newest-wins is not
 *    offered: a re-tag on the server and a re-rip on the phone have no
 *    meaningful "newest", and a music library is not the place to find out
 *    the hard way.
 *  - A file one side changed and the other did not copies across, again in
 *    the directions allowed.
 *
 * Files named like a preserved conflict copy are terminal artefacts of a
 * past disagreement: they are never uploaded, never mirrored, never planned
 * at all. See [isConflictCopy].
 */
object SyncPlanner {

    /**
     * Two stamps within this of each other count as the same moment. The
     * sides of a pair report mtimes at different precisions — MediaStore's
     * date-modified is whole seconds — so exact equality would see a change
     * in every file the engine itself just wrote.
     */
    const val MTIME_TOLERANCE_MS = 2_000L

    /** The mark every preserved conflict copy carries in its name. */
    const val CONFLICT_MARKER = " (conflict "

    fun isConflictCopy(path: String): Boolean = CONFLICT_MARKER in path.substringAfterLast('/')

    fun plan(
        configured: SyncDirection,
        effective: EffectiveDirection,
        mirrorDeletes: Boolean,
        local: List<SyncEntry>,
        remote: List<SyncEntry>,
        index: Map<String, SyncIndexEntry>,
        conflictDate: String,
    ): SyncPlan {
        if (effective == EffectiveDirection.PAUSED) return SyncPlan(emptyList(), emptyMap())
        val canDownload = effective == EffectiveDirection.DOWNLOAD_ONLY || effective == EffectiveDirection.BIDIRECTIONAL
        val canUpload = effective == EffectiveDirection.UPLOAD_ONLY || effective == EffectiveDirection.BIDIRECTIONAL

        // Conflict copies preserved on the phone are out of the game
        // entirely. The same name on the remote is just a file, and is
        // planned like any other — it lands as a local copy and is then
        // ignored by this same rule.
        val localByPath = local.filterNot { isConflictCopy(it.path) }.associateBy { it.path }
        val remoteByPath = remote.associateBy { it.path }
        val takenNames = localByPath.keys + remoteByPath.keys

        val builder = PlanBuilder(
            configured = configured,
            canDownload = canDownload,
            canUpload = canUpload,
            mirrorDeletes = mirrorDeletes,
            takenNames = takenNames,
            conflictDate = conflictDate,
        )
        val paths = (localByPath.keys + remoteByPath.keys + index.keys).toSortedSet()
        for (path in paths) {
            builder.planFile(path, localByPath[path], remoteByPath[path], index[path])
        }
        return SyncPlan(builder.actions, builder.settled)
    }

    private class PlanBuilder(
        private val configured: SyncDirection,
        private val canDownload: Boolean,
        private val canUpload: Boolean,
        private val mirrorDeletes: Boolean,
        private val takenNames: Set<String>,
        private val conflictDate: String,
    ) {
        val actions = mutableListOf<SyncAction>()
        val settled = mutableMapOf<String, SyncIndexEntry>()

        private fun note(path: String, note: SyncNote) {
            actions += SyncAction(SyncAction.Kind.NOTE, path, note = note)
        }

        private fun agree(path: String, local: SyncEntry, remote: SyncEntry) {
            settled[path] = SyncIndexEntry(
                path = path,
                localSize = local.stamp.size,
                localMtimeMs = local.stamp.mtimeMs,
                remoteSize = remote.stamp.size,
                remoteMtimeMs = remote.stamp.mtimeMs,
                remoteTag = remote.stamp.tag,
            )
        }

        private fun conflict(path: String, local: SyncEntry, remote: SyncEntry) {
            if (canDownload) {
                actions += SyncAction(
                    SyncAction.Kind.DOWNLOAD_CONFLICT_COPY,
                    path,
                    conflictName = conflictNameFor(path, conflictDate, takenNames),
                )
            } else {
                note(path, SyncNote.CONFLICT_KEPT)
            }
        }

        fun planFile(path: String, local: SyncEntry?, remote: SyncEntry?, agreed: SyncIndexEntry?) {
            if (agreed == null) {
                when {
                    local != null && remote != null -> {
                        // Never agreed, already present on both sides:
                        // identical stamps adopt silently; anything else is
                        // a conflict by another name.
                        if (stampsMatch(local.stamp, remote.stamp)) agree(path, local, remote) else conflict(path, local, remote)
                    }
                    local != null -> if (canUpload) actions += SyncAction(SyncAction.Kind.UPLOAD, path)
                    remote != null -> if (canDownload) actions += SyncAction(SyncAction.Kind.DOWNLOAD, path)
                }
                return
            }

            if (local == null && remote == null) {
                // Gone from both sides: nothing to move, nothing to report.
                // The engine drops the index entry.
                return
            }

            if (local == null) {
                // Deleted on the phone (or the card it lives on is away).
                when {
                    mirrorDeletes && canUpload -> actions += SyncAction(SyncAction.Kind.DELETE_REMOTE, path)
                    configured == SyncDirection.DOWNLOAD_ONLY && canDownload && remote != null ->
                        actions += SyncAction(SyncAction.Kind.DOWNLOAD, path)
                    else -> note(path, SyncNote.DELETED_ON_PHONE_KEPT)
                }
                return
            }

            if (remote == null) {
                // Deleted on the remote.
                when {
                    mirrorDeletes && canDownload -> actions += SyncAction(SyncAction.Kind.DELETE_LOCAL, path)
                    configured == SyncDirection.UPLOAD_ONLY && canUpload ->
                        actions += SyncAction(SyncAction.Kind.UPLOAD, path)
                    else -> note(path, SyncNote.DELETED_ON_REMOTE_KEPT)
                }
                return
            }

            val localChanged = stampChanged(local.stamp, agreed.localStamp())
            val remoteChanged = stampChanged(remote.stamp, agreed.remoteStamp())
            when {
                localChanged && remoteChanged -> {
                    // Both moved — unless they moved to the same thing.
                    if (stampsMatch(local.stamp, remote.stamp)) agree(path, local, remote) else conflict(path, local, remote)
                }
                remoteChanged -> if (canDownload) actions += SyncAction(SyncAction.Kind.DOWNLOAD, path)
                localChanged -> if (canUpload) actions += SyncAction(SyncAction.Kind.UPLOAD, path)
                else -> agree(path, local, remote) // Agreed and unchanged: the common case.
            }
        }
    }

    /** Whether [stamp] has moved from [agreed] by size, by content tag, or by clock beyond tolerance. */
    internal fun stampChanged(stamp: SyncFileStamp, agreed: SyncFileStamp): Boolean {
        if (stamp.size != agreed.size) return true
        val stampTag = stamp.tag
        val agreedTag = agreed.tag
        if (stampTag != null && agreedTag != null) return stampTag != agreedTag
        return kotlin.math.abs(stamp.mtimeMs - agreed.mtimeMs) > MTIME_TOLERANCE_MS
    }

    /** Whether two sides' stamps describe the same file, as far as either can tell. */
    internal fun stampsMatch(a: SyncFileStamp, b: SyncFileStamp): Boolean = !stampChanged(a, b)

    /**
     * The name a preserved copy of [path] is written under: the same folder,
     * the same stem, ` (conflict yyyy-MM-dd)` before the extension. When
     * that name is already taken — an earlier disagreement about the same
     * track — the upload pipeline's numbering settles it
     * ([WebDavClient.resolveNumberedName]: `name (1).ext`, `name (2).ext`, …).
     */
    fun conflictNameFor(path: String, conflictDate: String, taken: Set<String>): String {
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = path.substringAfterLast('/')
        val stem = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        val base = "$stem (conflict $conflictDate)"
        val takenFileNames = taken.map { it.substringAfterLast('/') }.toSet()
        val resolved = if (extension.isEmpty()) {
            numbered(base, takenFileNames)
        } else {
            WebDavClient.resolveNumberedName(base, extension, takenFileNames)
        }
        return if (directory.isEmpty()) resolved else "$directory/$resolved"
    }

    /** [WebDavClient.resolveNumberedName] for extensionless names, which it would leave with a trailing dot. */
    private fun numbered(base: String, takenFileNames: Set<String>): String {
        val lowered = takenFileNames.map { it.lowercase(Locale.ROOT) }.toSet()
        var candidate = base
        var n = 1
        while (candidate.lowercase(Locale.ROOT) in lowered && n < 1000) {
            candidate = "$base ($n)"
            n++
        }
        return candidate
    }
}
