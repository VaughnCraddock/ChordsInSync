package com.music.bitchord.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sync.SyncDirection
import com.music.bitchord.data.sync.SyncEngines
import com.music.bitchord.data.sync.SyncPair
import com.music.bitchord.data.sync.SyncRunResult
import com.music.bitchord.data.sync.SyncService
import com.music.bitchord.data.sync.SyncStore
import com.music.bitchord.data.sync.drive.DriveAuth
import com.music.bitchord.data.sync.drive.DriveBrowser
import com.music.bitchord.data.sync.drive.DriveSetup
import com.music.bitchord.data.sync.syncSanitisedName
import kotlinx.coroutines.launch
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

    // ── Google Drive: connection state and the connect flow ──
    val authStore = remember { AuthStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var driveEmail by remember { mutableStateOf(authStore.driveAccountEmail) }
    var driveBusy by remember { mutableStateOf(false) }
    var driveConnectFailed by remember { mutableStateOf(false) }
    var disconnectingDrive by remember { mutableStateOf(false) }

    // The second half of a connect that needed Play services' own screen:
    // the intent comes back here, and its token finishes the connect.
    val driveResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            scope.launch {
                try {
                    when (val outcome = DriveAuth.outcomeFromIntent(context, data)) {
                        is DriveAuth.InteractiveOutcome.Token ->
                            driveEmail = DriveAuth.finishConnect(context, outcome)
                        else -> driveConnectFailed = true
                    }
                } catch (failure: Exception) {
                    driveConnectFailed = true
                }
                driveBusy = false
            }
        } else {
            // Backing out of the picker or consent screen is not a failure.
            if (result.resultCode != Activity.RESULT_CANCELED) driveConnectFailed = true
            driveBusy = false
        }
    }

    fun connectDrive() {
        scope.launch {
            driveBusy = true
            driveConnectFailed = false
            try {
                when (val outcome = DriveAuth.authorizeInteractive(context)) {
                    is DriveAuth.InteractiveOutcome.Token ->
                        driveEmail = DriveAuth.finishConnect(context, outcome)
                    is DriveAuth.InteractiveOutcome.Resolution -> {
                        // The launcher callback owns driveBusy from here.
                        driveResolutionLauncher.launch(
                            IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build(),
                        )
                        return@launch
                    }
                    is DriveAuth.InteractiveOutcome.Failure -> driveConnectFailed = true
                }
            } catch (failure: Exception) {
                driveConnectFailed = true
            }
            driveBusy = false
        }
    }

    // ── Add-pair state, for the Google Drive engine ──
    // The browser stands in one folder of the owner's Drive; the stack is
    // the path from My Drive down to it, and wherever it stands when the
    // pair is created is the pair's remote root.
    var driveFormOpen by remember { mutableStateOf(false) }
    var drivePathStack by remember { mutableStateOf<List<DriveBrowser.FolderOption>>(emptyList()) }
    var driveBrowseFolders by remember { mutableStateOf<List<DriveBrowser.FolderOption>?>(null) }
    var driveBrowseFailed by remember { mutableStateOf(false) }
    var driveNewFolderName by rememberSaveable { mutableStateOf("") }
    var drivePairName by rememberSaveable { mutableStateOf("") }
    var drivePairDirection by remember { mutableStateOf(SyncDirection.BIDIRECTIONAL) }
    var drivePairMirrorDeletes by rememberSaveable { mutableStateOf(false) }
    var driveCreateFailed by remember { mutableStateOf(false) }
    val driveCurrentFolderId = drivePathStack.lastOrNull()?.id ?: DriveBrowser.MY_DRIVE_ID
    val driveMyDriveLabel = stringResource(R.string.sync_drive_my_drive)
    val driveCurrentPathLabel =
        (listOf(driveMyDriveLabel) + drivePathStack.map { it.name }).joinToString(" / ")

    LaunchedEffect(driveFormOpen, driveCurrentFolderId) {
        if (driveFormOpen) {
            driveBrowseFolders = null
            driveBrowseFailed = false
            runCatching { DriveBrowser.listFolders(context, driveCurrentFolderId) }
                .onSuccess { driveBrowseFolders = it }
                .onFailure { driveBrowseFailed = true }
        }
    }

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

        // ── Google Drive account ──
        Column(Modifier.padding(horizontal = 20.dp)) {
            val email = driveEmail
            if (email == null) {
                Text(
                    text = stringResource(R.string.sync_drive_not_connected),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = stringResource(R.string.sync_drive_scope_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = { connectDrive() },
                    enabled = !driveBusy,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(stringResource(R.string.sync_drive_connect))
                }
            } else {
                Text(
                    text = stringResource(R.string.sync_drive_connected_as, email),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                TextButton(
                    onClick = { disconnectingDrive = true },
                    enabled = !driveBusy,
                ) {
                    Text(stringResource(R.string.disconnect))
                }
            }
            if (driveConnectFailed) {
                Text(
                    text = stringResource(R.string.sync_drive_connect_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        DriveSetupCard()

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

        val driveEngineOn = AppSettings.isSyncEngineEnabled(SyncEngines.GOOGLE_DRIVE)
        if (driveEmail != null && driveEngineOn) {
            Spacer(Modifier.height(16.dp))
            if (!driveFormOpen) {
                Button(
                    onClick = {
                        drivePathStack = emptyList()
                        driveFormOpen = true
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    Text(stringResource(R.string.sync_drive_add_pair))
                }
            } else {
                DriveAddPairForm(
                    currentPathLabel = driveCurrentPathLabel,
                    canGoUp = drivePathStack.isNotEmpty(),
                    onNavigateUp = { drivePathStack = drivePathStack.dropLast(1) },
                    folders = driveBrowseFolders,
                    foldersFailed = driveBrowseFailed,
                    onFolderOpen = { option -> drivePathStack = drivePathStack + option },
                    newFolderName = driveNewFolderName,
                    onNewFolderNameChange = { driveNewFolderName = it },
                    onCreateFolder = {
                        scope.launch {
                            driveBusy = true
                            driveCreateFailed = false
                            runCatching {
                                DriveBrowser.createFolder(
                                    context,
                                    driveCurrentFolderId,
                                    syncSanitisedName(driveNewFolderName),
                                )
                            }.onSuccess { created ->
                                // Step into the new folder: it is almost
                                // always the folder the pair is being made
                                // for, and standing in it is one tap to fix.
                                driveNewFolderName = ""
                                drivePathStack = drivePathStack + created
                            }.onFailure {
                                driveCreateFailed = true
                            }
                            driveBusy = false
                        }
                    },
                    name = drivePairName,
                    onNameChange = { drivePairName = it },
                    direction = drivePairDirection,
                    onDirectionChange = { drivePairDirection = it },
                    mirrorDeletes = drivePairMirrorDeletes,
                    onMirrorDeletesChange = { drivePairMirrorDeletes = it },
                    createFailed = driveCreateFailed,
                    creating = driveBusy,
                    onCancel = { driveFormOpen = false },
                    onCreate = {
                        val folderName = drivePathStack.lastOrNull()?.name ?: driveMyDriveLabel
                        val base = syncSanitisedName(drivePairName)
                            .ifBlank { syncSanitisedName(folderName) }
                            .ifBlank { "Folder" }
                        SyncStore.addPair(
                            SyncPair(
                                id = UUID.randomUUID().toString(),
                                engineId = SyncEngines.GOOGLE_DRIVE,
                                name = drivePairName.ifBlank { folderName },
                                remoteLocator = driveCurrentFolderId,
                                remoteLabel = driveCurrentPathLabel,
                                localFolder = uniqueLocalFolderName(base, pairs.map { it.localFolder }.toSet()),
                                direction = drivePairDirection,
                                enabled = true,
                                mirrorDeletes = drivePairMirrorDeletes,
                            ),
                        )
                        driveFormOpen = false
                        drivePathStack = emptyList()
                        driveNewFolderName = ""
                        drivePairName = ""
                        drivePairDirection = SyncDirection.BIDIRECTIONAL
                        drivePairMirrorDeletes = false
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

    if (disconnectingDrive) {
        AlertDialog(
            onDismissRequest = { disconnectingDrive = false },
            title = { Text(stringResource(R.string.sync_drive_setup_title)) },
            text = { Text(stringResource(R.string.sync_drive_disconnect_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        disconnectingDrive = false
                        scope.launch {
                            DriveAuth.disconnect(context)
                            driveEmail = null
                            driveFormOpen = false
                            drivePathStack = emptyList()
                            driveBrowseFolders = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { disconnectingDrive = false }) {
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
            SyncEngines.GOOGLE_DRIVE -> R.string.sync_engine_google_drive
            SyncEngines.ONEDRIVE -> R.string.sync_engine_onedrive
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
                        SyncEngines.GOOGLE_DRIVE -> R.string.sync_engine_google_drive
                        SyncEngines.ONEDRIVE -> R.string.sync_engine_onedrive
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
 * The Drive registration card: what the owner's own Google Cloud project
 * needs, and the two values it asks for — read off the installed build at
 * runtime by [DriveSetup], because the dev build and any future release
 * differ in both and a copied-from-docs value would register the wrong app
 * with complete confidence.
 */
@Composable
private fun DriveSetupCard() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val packageName = remember { DriveSetup.packageName(context) }
    val sha1 = remember { DriveSetup.sha1Fingerprint(context) }
    SectionHeader(stringResource(R.string.sync_drive_setup_title))
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.sync_drive_setup_steps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SetupValue(
            label = stringResource(R.string.sync_drive_package_label),
            value = packageName,
            onCopy = { clipboard.setText(AnnotatedString(packageName)) },
        )
        if (sha1 != null) {
            SetupValue(
                label = stringResource(R.string.sync_drive_sha1_label),
                value = sha1,
                onCopy = { clipboard.setText(AnnotatedString(sha1)) },
            )
        }
    }
}

@Composable
private fun SetupValue(label: String, value: String, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = onCopy) {
            Text(stringResource(R.string.sync_drive_copy))
        }
    }
}

/**
 * The Drive pair form: browse the owner's Drive from My Drive down to the
 * folder this pair should sync with — the folder being stood in when the
 * pair is created is the remote side — then the same name / direction /
 * mirror choices the device-folders form offers. A new folder can be made
 * in the folder being browsed. Listings are loaded by the page, which
 * also owns navigation and creation; this composable only renders.
 */
@Composable
private fun DriveAddPairForm(
    currentPathLabel: String,
    canGoUp: Boolean,
    onNavigateUp: () -> Unit,
    folders: List<DriveBrowser.FolderOption>?,
    foldersFailed: Boolean,
    onFolderOpen: (DriveBrowser.FolderOption) -> Unit,
    newFolderName: String,
    onNewFolderNameChange: (String) -> Unit,
    onCreateFolder: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    direction: SyncDirection,
    onDirectionChange: (SyncDirection) -> Unit,
    mirrorDeletes: Boolean,
    onMirrorDeletesChange: (Boolean) -> Unit,
    createFailed: Boolean,
    creating: Boolean,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = stringResource(R.string.sync_drive_add_pair),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.sync_drive_folder_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.sync_drive_remote_folder, currentPathLabel),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (canGoUp) {
            TextButton(onClick = onNavigateUp) {
                Text(stringResource(R.string.sync_drive_up))
            }
        }
        when {
            foldersFailed -> Text(
                text = stringResource(R.string.sync_drive_load_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            folders == null -> Text(
                text = stringResource(R.string.sync_drive_loading_folders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            folders.isEmpty() -> Text(
                text = stringResource(R.string.sync_drive_empty_folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> for (option in folders) {
                Text(
                    text = option.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFolderOpen(option) }
                        .padding(vertical = 10.dp),
                )
            }
        }
        OutlinedTextField(
            value = newFolderName,
            onValueChange = onNewFolderNameChange,
            label = { Text(stringResource(R.string.sync_drive_new_folder_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        if (newFolderName.isNotBlank()) {
            TextButton(onClick = onCreateFolder, enabled = !creating) {
                Text(stringResource(R.string.sync_drive_create_folder))
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.sync_pair_name)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
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
        if (createFailed) {
            Text(
                text = stringResource(R.string.sync_drive_create_folder_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp)) {
            Button(onClick = onCreate, enabled = !creating) {
                Text(stringResource(R.string.sync_create_pair))
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}
