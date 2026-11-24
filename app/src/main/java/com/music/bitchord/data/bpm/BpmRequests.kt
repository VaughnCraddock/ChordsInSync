package com.music.bitchord.data.bpm

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A BPM ask from a menu: one song, or a folder's worth for the batch.
 *
 * Held in a flow rather than passed through the menus because the menus
 * close before the editor opens — the ask has to survive them. [BpmHost]
 * reads and clears it, exactly the arrangement local delete uses.
 */
sealed interface BpmRequest {
    data class Single(val song: Song) : BpmRequest

    /**
     * A folder run. [convertOnly] marks a conversion-led run (the folder
     * menu's "Convert Opus to AAC in folder"): rows that are not Opus are
     * skipped with their reason instead of analyzed, and Opus rows are
     * converted before anything else happens to them.
     */
    data class Batch(val songs: List<Song>, val folderName: String? = null, val convertOnly: Boolean = false) : BpmRequest
}

/** The one pending [BpmRequest], if any. Written by menus, read and cleared by the BPM host. */
object BpmRequests {

    val pending = MutableStateFlow<BpmRequest?>(null)

    fun ask(request: BpmRequest) {
        pending.value = request
    }

    fun clear() {
        pending.value = null
    }
}

/** Where one file stands in a batch run. */
enum class BpmRowStatus {
    QUEUED,
    ANALYZING,

    /** Analyzed (or valued by hand after a failed analysis): the field holds a value that can be saved. */
    READY,

    /** The file already had a BPM tag when the run reached it; it was not re-analyzed. */
    ALREADY_TAGGED,

    /** The container has no BPM writer; the row reports it and Save skips it. */
    UNSUPPORTED,

    /** Analysis (or the tag read) failed; [BpmRowState.failure] says why. A hand-entered value can still be saved. */
    FAILED,

    /** A conversion-led run had nothing to do for this file; [BpmRowState.failure] says why. Never saved. */
    SKIPPED,

    SAVING,

    /** Written and verified; [BpmRowState.savedValue] is what the file now holds. */
    SAVED,

    /** The write was attempted and refused; [BpmRowState.failure] says why. */
    SAVE_FAILED,
}

/**
 * One file's line in a batch run: what it had, what analysis found, and —
 * most importantly — [fieldText], the editable value that is exactly what
 * "Save all" writes when the time comes. The detected BPM only ever
 * *proposes* the field's initial content; from then on the field is the
 * user's, and no confidence figure anywhere argues with an override.
 */
data class BpmRowState(
    val song: Song,
    val status: BpmRowStatus = BpmRowStatus.QUEUED,
    val existingBpm: Double? = null,
    val detectedBpm: Double? = null,
    val confidence: Float? = null,
    val fieldText: String = "",
    val failure: BpmFailure? = null,
    val savedValue: Double? = null,
    /** The AAC sibling this row's Opus source was converted to, when it was: analysis and saving aim at it, never at the Opus original. */
    val convertedUriString: String? = null,
    /** The converted sibling's display name, for the row's "Converted to AAC" note. */
    val convertedName: String? = null,
) {
    val uriString: String? get() = song.localUri?.takeIf { it.isNotBlank() }

    /** Where reads, analysis and tag writes should aim: the converted AAC when there is one, else the file itself. */
    val targetUriString: String? get() = convertedUriString ?: uriString
}

/**
 * The live state of the current batch run, shared between
 * [BpmBatchService] (which does the reading and analyzing) and the host
 * dialog (which shows progress and results, and owns saving — saving needs
 * the Activity for the storage-consent dialogs).
 */
object BpmJobs {

    val rows = MutableStateFlow<List<BpmRowState>>(emptyList())
    val running = MutableStateFlow(false)
    val folderName = MutableStateFlow<String?>(null)

    /** Bumped by every [begin]; a service run that sees it change knows it has been superseded and stops. */
    val runId = MutableStateFlow(0)

    /** Whether already-tagged files take part in saving. Off by default: the run skips them unless asked. */
    val includeTagged = MutableStateFlow(false)

    /** Whether the current run is conversion-led (see [BpmRequest.Batch.convertOnly]). */
    val convertOnly = MutableStateFlow(false)

    fun begin(songs: List<Song>, name: String?, convertOnlyRun: Boolean = false) {
        runId.value++
        folderName.value = name
        includeTagged.value = false
        convertOnly.value = convertOnlyRun
        rows.value = songs.map { BpmRowState(song = it) }
        running.value = true
    }

    fun update(uriString: String, transform: (BpmRowState) -> BpmRowState) {
        val index = rows.value.indexOfFirst { it.uriString == uriString }
        if (index >= 0) updateAt(index, transform)
    }

    fun updateAt(index: Int, transform: (BpmRowState) -> BpmRowState) {
        rows.value = rows.value.mapIndexed { i, row ->
            if (i == index) transform(row) else row
        }
    }

    fun finish() {
        running.value = false
    }

    fun clear() {
        rows.value = emptyList()
        folderName.value = null
        convertOnly.value = false
        running.value = false
    }
}
