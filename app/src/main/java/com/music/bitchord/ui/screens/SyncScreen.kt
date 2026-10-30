package com.music.bitchord.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sync.SyncDirection
import com.music.bitchord.data.sync.SyncEngines
import com.music.bitchord.data.sync.SyncPair
import com.music.bitchord.data.sync.SyncRunResult
import com.music.bitchord.data.sync.SyncService
import com.music.bitchord.data.sync.SyncStore
import com.music.bitchord.data.sync.syncSanitisedName
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * The Sync page: engine switches, the home-network probe target, and the
 * pairs — each with its configured direction, what its last run did, and the
 * notes that explain why it might be waiting.
 *
 * Writing on this page is deliberately thin: the switches go to
 * [AppSettings], pairs to [SyncStore], and a Sync now tap starts
 * [SyncService]. What a run may actually do is the engine's business, gate
 * included — this page reports, it does not decide.
 */
@Composable
fun SyncScreen(contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engineSwitches by AppSettings.syncEngineEnabled.collectAsStateWithLifecycle()
    val pairs by SyncStore.pairs.collectAsStateWithLifecycle()
    val homeHost by AppSettings.syncHomeHost.collectAsStateWithLifecycle()
    val homePort by AppSettings.syncHomePort.collectAsStateWithLifecycle()

    // ── Add-pair state, for the device-folders engine ──
    var pendingTreeUri by remember { mutableStateOf<String?>(null) }
    var pendingTreeName by rememberSaveable { mutableStateOf("") }
    var pairName by rememberSaveable { mutableStateOf("") }
    var pairDirection by remember { mutableStateOf(SyncDirection.BIDIRECTIONAL) }
    var pairMirrorDeletes by rememberSaveable { mutableStateOf(false) }
    var removingPair by remember { mutableStateOf<SyncPair?>(null) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            // Read AND write, persisted: a picked folder the app may only
            // read is a library source, not a sync end. (The local-music
            // folder picker elsewhere takes read-only on purpose and is a
            // separate grant — this one does not touch it.)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            pendingTreeUri = uri.toString()
            pendingTreeName = DocumentFile.fromTreeUri(context, uri)?.name
                ?: uri.lastPathSegment?.substringAfterLast(':')
                ?: uri.toString()
            if (pairName.isBlank()) pairName = pendingTreeName
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.sync_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
        )
        Text(
            text = stringResource(R.string.sync_auto_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        )

        SectionHeader(stringResource(R.string.sync_engines))
        for (engineId in SyncEngines.all) {
            EngineRow(
                engineId = engineId,
                enabled = engineSwitches[engineId] ?: AppSettings.isSyncEngineEnabled(engineId),
                onEnabledChange = { AppSettings.setSyncEngineEnabled(engineId, it) },
            )
        }

        SyncthingCard()

        SectionHeader(stringResource(R.string.sync_home_section))
        var hostDraft by rememberSaveable(homeHost) { mutableStateOf(homeHost) }
        var portDraft by rememberSaveable(homePort) { mutableStateOf(homePort.toString()) }
        OutlinedTextField(
            value = hostDraft,
            onValueChange = { hostDraft = it },
            label = { Text(stringResource(R.string.sync_home_host)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = portDraft,
            onValueChange = { portDraft = it.filter { char -> char.isDigit() } },
            label = { Text(stringResource(R.string.sync_home_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        )
        Text(
            text = stringResource(R.string.sync_home_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )
        Button(
            onClick = {
                AppSettings.setSyncHomeHost(hostDraft)
                portDraft.toIntOrNull()?.let { AppSettings.setSyncHomePort(it) }
            },
            modifier = Modifier.padding(start = 20.dp, top = 10.dp),
        ) {
            Text(stringResource(R.string.save))
        }

        SectionHeader(stringResource(R.string.sync_pairs))
        if (pairs.isEmpty()) {
            Text(
                text = stringResource(R.string.sync_no_pairs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        for (pair in pairs) {
            PairCard(
                pair = pair,
                engineEnabled = AppSettings.isSyncEngineEnabled(pair.engineId),
                onSyncNow = { SyncService.syncNow(context, pair.id) },
                onToggleEnabled = {
                    SyncStore.updatePair(pair.copy(enabled = !pair.enabled))
                },
                onRemove = { removingPair = pair },
            )
        }

        val deviceEngineOn = AppSettings.isSyncEngineEnabled(SyncEngines.DEVICE_FOLDERS)
        if (SyncEngines.isAvailable(SyncEngines.DEVICE_FOLDERS) && deviceEngineOn) {
            Spacer(Modifier.height(16.dp))
            if (pendingTreeUri == null) {
                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                        )
                        treePicker.launch(intent)
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    Text(stringResource(R.string.sync_pick_folder))
                }
            } else {
                AddPairForm(
                    pickedFolderName = pendingTreeName,
                    name = pairName,
                    onNameChange = { pairName = it },
                    direction = pairDirection,
                    onDirectionChange = { pairDirection = it },
                    mirrorDeletes = pairMirrorDeletes,
                    onMirrorDeletesChange = { pairMirrorDeletes = it },
                    onCancel = { pendingTreeUri = null },
                    onCreate = {
                        val base = syncSanitisedName(pairName).ifBlank { syncSanitisedName(pendingTreeName) }
                            .ifBlank { "Folder" }
                        SyncStore.addPair(
                            SyncPair(
                                id = UUID.randomUUID().toString(),
                                engineId = SyncEngines.DEVICE_FOLDERS,
                                name = pairName.ifBlank { pendingTreeName },
                                remoteLocator = pendingTreeUri!!,
                                remoteLabel = pendingTreeName,
                                localFolder = uniqueLocalFolderName(base, pairs.map { it.localFolder }.toSet()),
                                direction = pairDirection,
                                enabled = true,
                                mirrorDeletes = pairMirrorDeletes,
                            ),
                        )
                        pendingTreeUri = null
                        pairName = ""
                        pairDirection = SyncDirection.BIDIRECTIONAL
                        pairMirrorDeletes = false
                    },
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    val pairToRemove = removingPair
    if (pairToRemove != null) {
        AlertDialog(
            onDismissRequest = { removingPair = null },
            title = { Text(stringResource(R.string.sync_remove_pair)) },
            text = { Text(stringResource(R.string.sync_remove_pair_message, pairToRemove.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SyncStore.removePair(pairToRemove.id)
                        removingPair = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { removingPair = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The first free local folder name for [base]: itself, then "base 2", "base 3", … */
private fun uniqueLocalFolderName(base: String, taken: Set<String>): String {
    var candidate = base
    var n = 2
    while (candidate in taken) {
        candidate = "$base $n"
        n++
    }
    return candidate
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun EngineRow(
    engineId: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    val name = stringResource(
        when (engineId) {
            SyncEngines.DEVICE_FOLDERS -> R.string.sync_engine_device_folders
            SyncEngines.DROPBOX -> R.string.sync_engine_dropbox
            SyncEngines.WEBDAV -> R.string.sync_engine_webdav
            else -> R.string.sync_engine_smb
        },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    if (SyncEngines.isAvailable(engineId)) R.string.sync_engine_available else R.string.sync_coming_soon,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (SyncEngines.isAvailable(engineId)) {
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun PairCard(
    pair: SyncPair,
    engineEnabled: Boolean,
    onSyncNow: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = pair.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.sync_remote_folder,
                stringResource(
                    when (pair.engineId) {
                        SyncEngines.DEVICE_FOLDERS -> R.string.sync_engine_device_folders
                        SyncEngines.DROPBOX -> R.string.sync_engine_dropbox
                        SyncEngines.WEBDAV -> R.string.sync_engine_webdav
                        else -> R.string.sync_engine_smb
                    },
                ),
                pair.remoteLabel,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.sync_direction_label,
                stringResource(
                    when (pair.direction) {
                        SyncDirection.BIDIRECTIONAL -> R.string.sync_direction_bidirectional
                        SyncDirection.UPLOAD_ONLY -> R.string.sync_direction_upload_only
                        SyncDirection.DOWNLOAD_ONLY -> R.string.sync_direction_download_only
                    },
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.sync_folder_on_device, pair.localFolder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = pairStatusText(pair, engineEnabled),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (pair.direction != SyncDirection.DOWNLOAD_ONLY && engineEnabled && pair.enabled) {
            Text(
                text = stringResource(R.string.sync_uploads_home_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            TextButton(
                onClick = onSyncNow,
                enabled = pair.enabled && engineEnabled,
            ) {
                Text(stringResource(R.string.sync_now_button))
            }
            TextButton(onClick = onToggleEnabled) {
                Text(stringResource(if (pair.enabled) R.string.pause else R.string.sync_resume))
            }
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.sync_remove_pair))
            }
        }
    }
}

/** What the pair's line should say: its switches first, then its last run. */
@Composable
private fun pairStatusText(pair: SyncPair, engineEnabled: Boolean): String {
    if (!engineEnabled) return stringResource(R.string.sync_paused_engine_off)
    if (!pair.enabled) return stringResource(R.string.sync_paused)
    val status = pair.status ?: return stringResource(R.string.sync_never_run)
    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(status.atMs))
    return when (status.result) {
        SyncRunResult.WAITING_HOME.name -> stringResource(R.string.sync_status_waiting_home)
        SyncRunResult.NEEDS_SIGN_IN.name -> stringResource(R.string.sync_status_needs_sign_in)
        SyncRunResult.ENGINE_OFF.name -> stringResource(R.string.sync_paused_engine_off)
        SyncRunResult.FAILED.name -> stringResource(R.string.sync_status_failed, whenText)
        SyncRunResult.PARTIAL.name ->
            stringResource(R.string.sync_status_partial, whenText, status.downloaded, status.uploaded)
        else -> if (status.downloaded + status.uploaded + status.conflicts + status.notes > 0) {
            stringResource(R.string.sync_status_changed, whenText, status.downloaded, status.uploaded)
        } else {
            stringResource(R.string.sync_status_ok, whenText)
        }
    }
}

@Composable
private fun AddPairForm(
    pickedFolderName: String,
    name: String,
    onNameChange: (String) -> Unit,
    direction: SyncDirection,
    onDirectionChange: (SyncDirection) -> Unit,
    mirrorDeletes: Boolean,
    onMirrorDeletesChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.sync_add_pair),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.sync_remote_folder, stringResource(R.string.sync_engine_device_folders), pickedFolderName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.sync_pair_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.sync_direction),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        for (option in SyncDirection.entries) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDirectionChange(option) },
            ) {
                RadioButton(selected = direction == option, onClick = { onDirectionChange(option) })
                Text(
                    text = stringResource(
                        when (option) {
                            SyncDirection.BIDIRECTIONAL -> R.string.sync_direction_bidirectional
                            SyncDirection.UPLOAD_ONLY -> R.string.sync_direction_upload_only
                            SyncDirection.DOWNLOAD_ONLY -> R.string.sync_direction_download_only
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sync_mirror_deletes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.sync_mirror_deletes_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = mirrorDeletes, onCheckedChange = onMirrorDeletesChange)
        }
        Row(Modifier.padding(top = 8.dp)) {
            Button(onClick = onCreate) {
                Text(stringResource(R.string.sync_create_pair))
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
/**
 * The Syncthing card: where downloads live, and why that folder matters.
 *
 * Downloads land in the shared Music/BitChord folder (that is
 * [com.music.bitchord.download.DownloadStore]'s home), and that folder is
 * this device's Syncthing location — music placed there is what gets
 * synced. The card states the placement only; how the folder is carried
 * (a companion Syncthing app today, possibly an embedded daemon later) is
 * deliberately not this page's business.
 */
@Composable
private fun SyncthingCard() {
    SectionHeader(stringResource(R.string.sync_syncthing_title))
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = "Music/BitChord",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.sync_syncthing_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
