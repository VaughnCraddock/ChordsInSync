package com.music.bitchord.ui.components

import android.app.Activity
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.LocalDelete
import com.music.bitchord.data.LocalDeleteRequest
import com.music.bitchord.data.LocalDeleteRequests
import com.music.bitchord.data.LocalMediaDeleter
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.launch
import java.io.File

/**
 * The one place a local delete is confirmed and carried out.
 *
 * Menus only *ask* (see [LocalDeleteRequests]); this host, sitting at the
 * root of the activity, owns the rest: an in-app confirmation that names
 * what will be removed — a song's title, or a folder's name and its song
 * count — and says plainly that the files are deleted from the phone; then
 * whatever consent dance the Android version requires (the API 30+ system
 * dialog, the API 29 per-file approvals), run through
 * [LocalMediaDeleter]; then the result, counted honestly.
 *
 * Nothing is ever deleted without the confirmation: there is no silent
 * path through here, and on API 30+ the system's own dialog stands behind
 * this one as well.
 */
@Composable
fun LocalDeleteHost(onDeleted: (List<Song>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request by LocalDeleteRequests.pending.collectAsStateWithLifecycle()

    // The pass in flight, held across the consent round trips below. The
    // confirmation request itself is cleared the moment it is confirmed —
    // what remains is work, not an ask.
    var active by remember { mutableStateOf<ActiveDelete?>(null) }
    var systemRequest by remember { mutableStateOf<IntentSender?>(null) }
    var consentRequest by remember { mutableStateOf<IntentSender?>(null) }

    val systemDeleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        val pass = active ?: return@rememberLauncherForActivityResult
        active = null
        scope.launch {
            // The system's dialog does not say which files it took; the
            // recount is the only honest answer.
            pass.deleted += LocalMediaDeleter.gone(context, pass.uriStrings)
            finishDelete(context, pass, onDeleted)
        }
    }
    LaunchedEffect(systemRequest) {
        val sender = systemRequest ?: return@LaunchedEffect
        systemRequest = null
        systemDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pass = active ?: return@rememberLauncherForActivityResult
        val consented = pass.consenting
        pass.consenting = null
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK && consented != null &&
                LocalMediaDeleter.retry(context, consented)
            ) {
                pass.deleted += consented
            }
            val next = pass.recoverable.removeFirstOrNull()
            if (next == null) {
                active = null
                finishDelete(context, pass, onDeleted)
            } else {
                pass.consenting = next.first
                consentRequest = next.second
            }
        }
    }
    LaunchedEffect(consentRequest) {
        val sender = consentRequest ?: return@LaunchedEffect
        consentRequest = null
        consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    val ask = request
    if (ask != null && active == null) {
        val folderPath = ask.folderPath
        AlertDialog(
            onDismissRequest = { LocalDeleteRequests.clear() },
            title = {
                Text(
                    stringResource(
                        if (folderPath != null) {
                            R.string.delete_folder_confirmation_title
                        } else {
                            R.string.delete_song_confirmation_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    if (folderPath != null) {
                        stringResource(
                            R.string.delete_folder_confirmation,
                            ask.folderName ?: folderPath.substringAfterLast('/'),
                            ask.songs.size,
                        )
                    } else {
                        stringResource(
                            R.string.delete_song_confirmation,
                            ask.songs.firstOrNull()?.title.orEmpty(),
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        LocalDeleteRequests.clear()
                        scope.launch {
                            val uriStrings = LocalDelete.uriStringsFor(ask.songs)
                            val outcome = LocalMediaDeleter.delete(context, uriStrings)
                            val pass = ActiveDelete(
                                request = ask,
                                uriStrings = uriStrings,
                                deleted = outcome.deletedUris.toMutableSet(),
                                recoverable = ArrayDeque<Pair<String, IntentSender>>().apply {
                                    addAll(outcome.recoverable)
                                },
                            )
                            when {
                                outcome.deleteRequest != null -> {
                                    active = pass
                                    systemRequest = outcome.deleteRequest
                                }
                                outcome.recoverable.isNotEmpty() -> {
                                    active = pass
                                    val first = pass.recoverable.removeFirst()
                                    pass.consenting = first.first
                                    consentRequest = first.second
                                }
                                else -> finishDelete(context, pass, onDeleted)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { LocalDeleteRequests.clear() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** A deletion pass between its confirmation and its result, while consent is gathered. */
private class ActiveDelete(
    val request: LocalDeleteRequest,
    val uriStrings: List<String>,
    val deleted: MutableSet<String>,
    val recoverable: ArrayDeque<Pair<String, IntentSender>>,
    var consenting: String? = null,
)

/**
 * A finished pass: records and folders tidied, the result said out loud,
 * and the lists told — through [onDeleted] — that their contents changed.
 */
private suspend fun finishDelete(
    context: Context,
    pass: ActiveDelete,
    onDeleted: (List<Song>) -> Unit,
) {
    val deletedSongs = pass.request.songs.filter { song ->
        song.localUri != null && song.localUri in pass.deleted
    }
    // A downloaded song whose file just went keeps a record in Downloads
    // pointing at nothing. Forget those records — Downloads.delete also
    // tries the file, which is already gone and simply reports so.
    val saved = Downloads.saved.value
    for (song in deletedSongs) {
        if (song.videoId in saved) Downloads.delete(context, song.videoId)
    }
    // The folder itself, once the delete has emptied it and the platform
    // lets us — a folder with anything left in it (a cover the scan never
    // counted, a subfolder) stays exactly as it is.
    pass.request.folderPath?.let { path ->
        val directory = File(path)
        if (directory.isDirectory && directory.list().isNullOrEmpty() && directory.delete()) {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
    }
    val failed = pass.request.songs.size - deletedSongs.size
    if (deletedSongs.size == 1) {
        Toast.makeText(context, R.string.deleted_one_song, Toast.LENGTH_SHORT).show()
    } else if (deletedSongs.isNotEmpty()) {
        Toast.makeText(
            context,
            context.getString(R.string.deleted_songs_result, deletedSongs.size),
            Toast.LENGTH_SHORT,
        ).show()
    }
    if (failed > 0) {
        Toast.makeText(
            context,
            context.getString(R.string.delete_failed_some, failed),
            Toast.LENGTH_SHORT,
        ).show()
    }
    if (deletedSongs.isNotEmpty()) onDeleted(deletedSongs)
}
