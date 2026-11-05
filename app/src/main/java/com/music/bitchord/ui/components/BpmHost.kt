package com.music.bitchord.ui.components

import android.app.Activity
import android.content.IntentSender
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.bpm.BpmAnalyzer
import com.music.bitchord.data.bpm.BpmBatchService
import com.music.bitchord.data.bpm.BpmFailure
import com.music.bitchord.data.bpm.BpmJobs
import com.music.bitchord.data.bpm.BpmRequest
import com.music.bitchord.data.bpm.BpmRequests
import com.music.bitchord.data.bpm.BpmRowState
import com.music.bitchord.data.bpm.BpmRowStatus
import com.music.bitchord.data.bpm.BpmTagStore
import com.music.bitchord.data.bpm.BpmTags
import com.music.bitchord.data.bpm.BpmWriteOutcome
import com.music.bitchord.data.bpm.TapTempo
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The one place BPM analysis is shown, edited and written to tags.
 *
 * Menus only *ask* (see [BpmRequests]); this host, sitting at the root of
 * the activity, owns the rest, in the same arrangement as [LocalDeleteHost]:
 *
 *  - **Single song**: the editor opens on the file's existing tag (if any),
 *    analysis runs and *proposes* a value, and the field — typed, halved,
 *    doubled or tapped in — is the only thing "Save to tags" ever writes.
 *    An existing tag is never overwritten without the user seeing the old
 *    value and pressing save themselves.
 *  - **Folder batch**: the foreground [BpmBatchService] reads and analyzes
 *    the queue (skipping already-tagged files); this dialog shows progress,
 *    then the results list — every row editable, every failure carrying its
 *    reason — and "Save all" writes exactly what the fields show at that
 *    moment. Nothing is written before that confirmation.
 *
 * Storage consent (the API 30+ write request, for files this app doesn't
 * own) is launched from here, because it needs the Activity; the write is
 * retried once the owner approves, exactly LocalDeleteHost's shape.
 *
 * Every Text names its color: on this app's dark surfaces the ambient
 * content color cannot be trusted (the lesson the engine-label fix taught).
 */
@Composable
fun BpmHost() {
    val request by BpmRequests.pending.collectAsStateWithLifecycle()

    // The consent round trip: a write answers NeedsConsent, the system's
    // dialog runs, and the caller's callback hears the verdict either way.
    var pendingConsent by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var consentSender by remember { mutableStateOf<IntentSender?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val callback = pendingConsent
        pendingConsent = null
        callback?.invoke(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consentSender) {
        val sender = consentSender ?: return@LaunchedEffect
        consentSender = null
        consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }
    val requestConsent: (IntentSender, (Boolean) -> Unit) -> Unit = { sender, callback ->
        pendingConsent = callback
        consentSender = sender
    }

    when (val ask = request) {
        is BpmRequest.Single -> SingleBpmDialog(
            song = ask.song,
            requestConsent = requestConsent,
            onDismiss = { BpmRequests.clear() },
        )
        is BpmRequest.Batch -> BatchBpmDialog(
            request = ask,
            requestConsent = requestConsent,
            onDismiss = { BpmRequests.clear() },
        )
        null -> Unit
    }
}

/** The field's value as a writable BPM, or null: what the user typed is what gets saved, so it is parsed strictly. */
private fun parseBpmField(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }

@Composable
private fun failureText(failure: BpmFailure): String = stringResource(
    when (failure) {
        BpmFailure.UNSUPPORTED_FORMAT -> R.string.bpm_failure_unsupported
        BpmFailure.UNRECOGNIZED_LAYOUT -> R.string.bpm_failure_unrecognized
        BpmFailure.TOO_LARGE -> R.string.bpm_failure_too_large
        BpmFailure.PERMISSION_DENIED -> R.string.bpm_failure_permission
        BpmFailure.UNREADABLE -> R.string.bpm_failure_unreadable
        BpmFailure.VERIFY_FAILED -> R.string.bpm_failure_verify
        BpmFailure.IO_ERROR -> R.string.bpm_failure_io
    },
)

// ------------------------------------------------------------ single song

@Composable
private fun SingleBpmDialog(
    song: Song,
    requestConsent: (IntentSender, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriString = song.localUri ?: return

    var existing by remember { mutableStateOf<Double?>(null) }
    var detected by remember { mutableStateOf<Double?>(null) }
    var confidence by remember { mutableStateOf<Float?>(null) }
    var analyzing by remember { mutableStateOf(true) }
    var attempted by remember { mutableStateOf(false) }
    var fieldText by remember { mutableStateOf("") }
    var userEdited by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<BpmFailure?>(null) }

    fun analyze() {
        analyzing = true
        scope.launch {
            val estimate = BpmAnalyzer.analyze(context, uriString)
            analyzing = false
            attempted = true
            if (estimate != null) {
                detected = estimate.bpm
                confidence = estimate.confidence
                // The detected value fills the field only while the field is
                // still the app's to fill: no existing tag, and no typing yet.
                if (!userEdited && existing == null) {
                    fieldText = BpmTags.formatBpmText(estimate.bpm)
                }
            }
        }
    }

    LaunchedEffect(uriString) {
        existing = BpmTagStore.readBpm(context, uriString)
        existing?.let { fieldText = BpmTags.formatBpmText(it) }
        analyze()
    }

    fun save() {
        val value = parseBpmField(fieldText) ?: return
        saving = true
        failure = null
        scope.launch {
            when (val outcome = BpmTagStore.writeBpm(context, uriString, value)) {
                is BpmWriteOutcome.Written -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.bpm_saved, BpmTags.formatBpmText(outcome.value)),
                        Toast.LENGTH_SHORT,
                    ).show()
                    saving = false
                    onDismiss()
                }
                is BpmWriteOutcome.NeedsConsent -> {
                    saving = false
                    requestConsent(outcome.sender) { approved -> if (approved) save() }
                }
                is BpmWriteOutcome.Failed -> {
                    saving = false
                    failure = outcome.reason
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analyze_bpm), color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                BpmEditorBody(
                    existingBpm = existing,
                    detectedBpm = detected,
                    confidence = confidence,
                    analyzing = analyzing,
                    showNoTempo = attempted && detected == null,
                    onAnalyze = { analyze() },
                    fieldText = fieldText,
                    onFieldChange = { fieldText = it; userEdited = true },
                    failure = failure,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = parseBpmField(fieldText) != null && !saving) {
                Text(stringResource(R.string.bpm_save_to_tags))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ------------------------------------------------------------------ batch

@Composable
private fun BatchBpmDialog(
    request: BpmRequest.Batch,
    requestConsent: (IntentSender, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by BpmJobs.rows.collectAsStateWithLifecycle()
    val running by BpmJobs.running.collectAsStateWithLifecycle()
    val includeTagged by BpmJobs.includeTagged.collectAsStateWithLifecycle()
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        val songs = request.songs.filter { !it.localUri.isNullOrBlank() }
        BpmJobs.begin(songs, request.folderName)
        if (songs.isNotEmpty()) {
            BpmBatchService.start(context)
        } else {
            BpmJobs.finish()
        }
    }

    fun eligible(row: BpmRowState): Boolean {
        if (parseBpmField(row.fieldText) == null || row.uriString == null) return false
        return when (row.status) {
            BpmRowStatus.READY, BpmRowStatus.FAILED, BpmRowStatus.SAVE_FAILED -> true
            BpmRowStatus.ALREADY_TAGGED -> includeTagged
            else -> false
        }
    }

    suspend fun awaitConsent(sender: IntentSender): Boolean =
        suspendCancellableCoroutine { continuation ->
            requestConsent(sender) { approved -> if (continuation.isActive) continuation.resume(approved) }
        }

    suspend fun saveRow(row: BpmRowState, value: Double) {
        val uriString = row.uriString ?: return
        BpmJobs.update(uriString) { it.copy(status = BpmRowStatus.SAVING, failure = null) }
        var outcome = BpmTagStore.writeBpm(context, uriString, value)
        if (outcome is BpmWriteOutcome.NeedsConsent) {
            val approved = awaitConsent(outcome.sender)
            outcome = if (approved) {
                BpmTagStore.writeBpm(context, uriString, value)
            } else {
                BpmWriteOutcome.Failed(BpmFailure.PERMISSION_DENIED)
            }
        }
        when (outcome) {
            is BpmWriteOutcome.Written -> BpmJobs.update(uriString) {
                it.copy(status = BpmRowStatus.SAVED, savedValue = outcome.value, failure = null)
            }
            is BpmWriteOutcome.Failed -> BpmJobs.update(uriString) {
                it.copy(status = BpmRowStatus.SAVE_FAILED, failure = outcome.reason)
            }
            is BpmWriteOutcome.NeedsConsent -> BpmJobs.update(uriString) {
                it.copy(status = BpmRowStatus.SAVE_FAILED, failure = BpmFailure.PERMISSION_DENIED)
            }
        }
    }

    fun saveAll() {
        if (saving || running) return
        val targets = rows.filter { eligible(it) }
        if (targets.isEmpty()) return
        saving = true
        scope.launch {
            for (row in targets) {
                parseBpmField(row.fieldText)?.let { value -> saveRow(row, value) }
            }
            saving = false
        }
    }

    val doneCount = rows.count { it.status != BpmRowStatus.QUEUED && it.status != BpmRowStatus.ANALYZING }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analyze_bpm_in_folder), color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                request.folderName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (running) {
                    Text(
                        text = stringResource(R.string.bpm_notification_progress, doneCount, rows.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (rows.any { it.status == BpmRowStatus.ALREADY_TAGGED }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.bpm_batch_include_tagged),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = includeTagged,
                            onCheckedChange = { BpmJobs.includeTagged.value = it },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(rows) { index, row ->
                        BpmBatchRow(
                            row = row,
                            includeTagged = includeTagged,
                            onFieldChange = { text ->
                                row.uriString?.let { uri ->
                                    BpmJobs.update(uri) { it.copy(fieldText = text) }
                                }
                            },
                            onOpenEditor = { editingIndex = index },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { saveAll() },
                enabled = !running && !saving && rows.any { eligible(it) },
            ) {
                Text(stringResource(R.string.bpm_batch_save_all))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )

    // The full editor for one row: tap tempo, ÷2/×2 and re-analysis live
    // here; its Done writes the value back into the row's field, and the
    // batch's Save all is still the only thing that touches tags.
    val editIndex = editingIndex
    if (editIndex != null && editIndex in rows.indices) {
        val row = rows[editIndex]
        BpmRowEditorDialog(
            row = row,
            onDismiss = { editingIndex = null },
            onDone = { text ->
                row.uriString?.let { uri -> BpmJobs.update(uri) { it.copy(fieldText = text) } }
                editingIndex = null
            },
        )
    }
}

@Composable
private fun BpmBatchRow(
    row: BpmRowState,
    includeTagged: Boolean,
    onFieldChange: (String) -> Unit,
    onOpenEditor: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val editable = when (row.status) {
        BpmRowStatus.READY, BpmRowStatus.FAILED, BpmRowStatus.SAVE_FAILED -> true
        BpmRowStatus.ALREADY_TAGGED -> includeTagged
        else -> false
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = editable || row.status == BpmRowStatus.ALREADY_TAGGED) { onOpenEditor() }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = variant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editable) {
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = row.fieldText,
                    onValueChange = onFieldChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.bpm_field_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        when (row.status) {
            BpmRowStatus.QUEUED, BpmRowStatus.ANALYZING -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bpm_analyzing), style = MaterialTheme.typography.bodySmall, color = variant)
            }
            BpmRowStatus.READY -> Text(
                text = row.detectedBpm?.let { detected ->
                    stringResource(R.string.bpm_detected, BpmTags.formatBpmText(detected)) +
                        (row.confidence?.let { " · " + stringResource(R.string.bpm_confidence, (it * 100).toInt()) } ?: "")
                } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = variant,
            )
            BpmRowStatus.ALREADY_TAGGED -> Text(
                text = stringResource(R.string.bpm_status_tagged) +
                    (row.existingBpm?.let { ": " + BpmTags.formatBpmText(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = variant,
            )
            BpmRowStatus.UNSUPPORTED -> Text(
                text = stringResource(R.string.bpm_status_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = variant,
            )
            BpmRowStatus.FAILED -> Text(
                text = failureText(row.failure ?: BpmFailure.UNREADABLE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            BpmRowStatus.SAVING -> Text(
                text = stringResource(R.string.bpm_analyzing),
                style = MaterialTheme.typography.bodySmall,
                color = variant,
            )
            BpmRowStatus.SAVED -> Text(
                text = stringResource(R.string.bpm_status_saved) +
                    (row.savedValue?.let { ": " + BpmTags.formatBpmText(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = variant,
            )
            BpmRowStatus.SAVE_FAILED -> Text(
                text = failureText(row.failure ?: BpmFailure.IO_ERROR),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun BpmRowEditorDialog(
    row: BpmRowState,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detected by remember { mutableStateOf(row.detectedBpm) }
    var confidence by remember { mutableStateOf(row.confidence) }
    var analyzing by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(row.detectedBpm != null) }
    var fieldText by remember { mutableStateOf(row.fieldText) }
    var userEdited by remember { mutableStateOf(false) }

    fun analyze() {
        val uriString = row.uriString ?: return
        analyzing = true
        scope.launch {
            val estimate = BpmAnalyzer.analyze(context, uriString)
            analyzing = false
            attempted = true
            if (estimate != null) {
                detected = estimate.bpm
                confidence = estimate.confidence
                if (!userEdited) fieldText = BpmTags.formatBpmText(estimate.bpm)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.analyze_bpm), color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column {
                Text(
                    text = row.song.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                BpmEditorBody(
                    existingBpm = row.existingBpm,
                    detectedBpm = detected,
                    confidence = confidence,
                    analyzing = analyzing,
                    showNoTempo = attempted && detected == null,
                    onAnalyze = { analyze() },
                    fieldText = fieldText,
                    onFieldChange = { fieldText = it; userEdited = true },
                    failure = row.failure?.takeIf { row.status == BpmRowStatus.FAILED },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(fieldText) }, enabled = parseBpmField(fieldText) != null) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// ------------------------------------------------------------ shared editor

/**
 * The editor every BPM surface shares: what the tag holds, what analysis
 * found (with its confidence, as information — it never argues with an
 * override), the one editable field that decides what gets saved, the ÷2/×2
 * octave fixes, and tap tempo feeding the same field.
 */
@Composable
private fun BpmEditorBody(
    existingBpm: Double?,
    detectedBpm: Double?,
    confidence: Float?,
    analyzing: Boolean,
    showNoTempo: Boolean,
    onAnalyze: () -> Unit,
    fieldText: String,
    onFieldChange: (String) -> Unit,
    failure: BpmFailure?,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val tapTempo = remember { TapTempo() }
    var tapBpm by remember { mutableStateOf<Double?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        existingBpm?.let { existing ->
            Text(
                text = stringResource(R.string.bpm_existing_tag, BpmTags.formatBpmText(existing)),
                style = MaterialTheme.typography.bodyMedium,
                color = onSurface,
            )
        }
        when {
            analyzing -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.75.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bpm_analyzing), style = MaterialTheme.typography.bodyMedium, color = variant)
            }
            detectedBpm != null -> Text(
                text = stringResource(R.string.bpm_detected, BpmTags.formatBpmText(detectedBpm)) +
                    (confidence?.let { " · " + stringResource(R.string.bpm_confidence, (it * 100).toInt()) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = variant,
            )
            showNoTempo -> Text(
                text = stringResource(R.string.bpm_no_tempo_found),
                style = MaterialTheme.typography.bodyMedium,
                color = variant,
            )
        }
        TextButton(onClick = onAnalyze, enabled = !analyzing) {
            Text(stringResource(R.string.analyze_bpm))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = fieldText,
                onValueChange = onFieldChange,
                singleLine = true,
                label = { Text(stringResource(R.string.bpm_field_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = fieldText.isNotBlank() && parseBpmField(fieldText) == null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    parseBpmField(fieldText)?.let { onFieldChange(BpmTags.formatBpmText(it / 2)) }
                },
                enabled = parseBpmField(fieldText) != null,
            ) { Text("÷2", color = onSurface) }
            TextButton(
                onClick = {
                    parseBpmField(fieldText)?.let { onFieldChange(BpmTags.formatBpmText(it * 2)) }
                },
                enabled = parseBpmField(fieldText) != null,
            ) { Text("×2", color = onSurface) }
        }
        if (fieldText.isNotBlank() && parseBpmField(fieldText) == null) {
            Text(
                text = stringResource(R.string.bpm_invalid_value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Tap tempo: taps land on the calculator, the live value steadies
        // as they agree, and Use writes it into the same field as every
        // other source — from there the normal save path applies.
        Text(
            text = stringResource(R.string.bpm_tap_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = variant,
        )
        Button(
            onClick = { tapBpm = tapTempo.tap(System.currentTimeMillis()) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(stringResource(R.string.bpm_tap), style = MaterialTheme.typography.titleMedium)
        }
        tapBpm?.let { tapped ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = BpmTags.formatBpmText(tapped),
                    style = MaterialTheme.typography.titleMedium,
                    color = onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onFieldChange(BpmTags.formatBpmText(tapped)) }) {
                    Text(stringResource(R.string.bpm_tap_use, BpmTags.formatBpmText(tapped)))
                }
            }
        }

        failure?.let { reason ->
            Text(
                text = failureText(reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
