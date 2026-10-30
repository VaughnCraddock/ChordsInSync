package com.music.bitchord.data.sync

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * One run of one pair: list both sides, plan against the remembered index,
 * carry the plan out file by file, and record where it landed.
 *
 * The index commits **per file, as each file completes** — there is no
 * transaction across a network or a document provider, and pretending
 * otherwise would only mean a killed run starts over. A run that dies
 * halfway leaves the index describing exactly the files that made it, and
 * the next run's diff skips those by their stamps.
 *
 * The home gate is checked at the start and **again before every upload**:
 * a run that begins at home does not get to keep uploading from wherever
 * the phone has wandered to. The file in flight when the phone leaves
 * finishes — abandoning it would waste its bytes and, on a simple PUT,
 * could leave a truncated remote copy — and no new upload starts after it.
 */
class SyncEngine(
    context: Context,
    private val homeProbe: HomeProbe = AndroidHomeProbe(
        context,
        host = { AppSettings.syncHomeHost.value },
        port = { AppSettings.syncHomePort.value },
    ),
) {

    private val appContext = context.applicationContext

    suspend fun runPair(pair: SyncPair, manual: Boolean): SyncRunStatus {
        val status = runCatching { execute(pair) }
            .getOrElse { failure ->
                Log.w(TAG, "sync run failed for ${pair.name}: ${failure.message}")
                SyncRunStatus(
                    atMs = System.currentTimeMillis(),
                    result = SyncRunResult.FAILED.name,
                )
            }
        SyncStore.setStatus(pair.id, status)
        return status
    }

    private suspend fun execute(pair: SyncPair): SyncRunStatus = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (pair.engineId != SyncEngines.DEVICE_FOLDERS) {
            // Registered but not wired: no pair can exist for one yet, so
            // reaching here means state from a newer build. Say so plainly.
            return@withContext SyncRunStatus(now, SyncRunResult.FAILED.name)
        }

        val local = SyncLocalStore(appContext, pair.localFolder)
        val remote = SafTreeProvider(appContext, pair.remoteLocator)

        val engineEnabled = AppSettings.isSyncEngineEnabled(pair.engineId)
        // The probe is only worth its cost when an upload is even configured.
        val atHome = if (pair.direction == SyncDirection.DOWNLOAD_ONLY) {
            true
        } else {
            homeProbe.isHome()
        }
        val decision = HomeGate.decide(
            configured = pair.direction,
            pairEnabled = pair.enabled,
            engineEnabled = engineEnabled,
            atHome = atHome,
            remoteWritable = remote.canWrite(),
        )
        if (decision.effective == EffectiveDirection.PAUSED) {
            return@withContext SyncRunStatus(
                now,
                when (decision.block) {
                    DirectionBlock.ENGINE_DISABLED -> SyncRunResult.ENGINE_OFF.name
                    DirectionBlock.NOT_HOME -> SyncRunResult.WAITING_HOME.name
                    else -> SyncRunResult.FAILED.name
                },
            )
        }

        val localListing = local.list()
        val remoteListing = remote.list()
        val localByPath = localListing.associateBy { it.path }
        val remoteByPath = remoteListing.associateBy { it.path }

        val index = SyncStore.loadIndex(pair.id)
        val plan = SyncPlanner.plan(
            configured = pair.direction,
            effective = decision.effective,
            mirrorDeletes = pair.mirrorDeletes,
            local = localListing,
            remote = remoteListing,
            index = index,
            conflictDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE),
        )
        index.putAll(plan.settled)

        var downloaded = 0
        var uploaded = 0
        var conflicts = 0
        var failed = 0
        var notes = 0
        var uploadsStopped = false

        val actionPaths = plan.actions.mapTo(HashSet()) { it.path }
        for (action in plan.actions) {
            // Before any write to the remote, the gate is asked again. A
            // download in the same position needs no asking.
            if ((action.kind == SyncAction.Kind.UPLOAD || action.kind == SyncAction.Kind.DELETE_REMOTE) &&
                !uploadsStopped && !homeProbe.isHome()
            ) {
                uploadsStopped = true
                Log.i(TAG, "uploads stopped for ${pair.name}: left the home network")
            }
            try {
                when (action.kind) {
                    SyncAction.Kind.DOWNLOAD -> {
                        val source = remoteByPath.getValue(action.path)
                        val written = remote.read(action.path).use { stream ->
                            local.write(action.path, stream, source.stamp.size, source.stamp.mtimeMs)
                        }
                        check(written.stamp.size == source.stamp.size) { "Short write at ${action.path}" }
                        index[action.path] = SyncIndexEntry(
                            path = action.path,
                            localSize = written.stamp.size,
                            localMtimeMs = written.stamp.mtimeMs,
                            remoteSize = source.stamp.size,
                            remoteMtimeMs = source.stamp.mtimeMs,
                            remoteTag = source.stamp.tag,
                        )
                        downloaded++
                    }

                    SyncAction.Kind.UPLOAD -> {
                        if (uploadsStopped) continue
                        val source = localByPath.getValue(action.path)
                        val written = local.read(action.path).use { stream ->
                            remote.write(action.path, stream, source.stamp.size, source.stamp.mtimeMs)
                        }
                        index[action.path] = SyncIndexEntry(
                            path = action.path,
                            localSize = source.stamp.size,
                            localMtimeMs = source.stamp.mtimeMs,
                            remoteSize = written.stamp.size,
                            remoteMtimeMs = written.stamp.mtimeMs,
                            remoteTag = written.stamp.tag,
                        )
                        uploaded++
                    }

                    SyncAction.Kind.DOWNLOAD_CONFLICT_COPY -> {
                        val source = remoteByPath.getValue(action.path)
                        val target = action.conflictName ?: error("Conflict without a name at ${action.path}")
                        remote.read(action.path).use { stream ->
                            local.write(target, stream, source.stamp.size, source.stamp.mtimeMs)
                        }
                        // Both sides keep their own version; recording the
                        // stamps each side currently reports is what stops
                        // the same disagreement being re-fought — and
                        // re-copied — on every run from here.
                        val localNow = localByPath.getValue(action.path)
                        index[action.path] = SyncIndexEntry(
                            path = action.path,
                            localSize = localNow.stamp.size,
                            localMtimeMs = localNow.stamp.mtimeMs,
                            remoteSize = source.stamp.size,
                            remoteMtimeMs = source.stamp.mtimeMs,
                            remoteTag = source.stamp.tag,
                        )
                        conflicts++
                    }

                    SyncAction.Kind.DELETE_REMOTE -> {
                        if (uploadsStopped) continue
                        remote.delete(action.path)
                        index.remove(action.path)
                    }

                    SyncAction.Kind.DELETE_LOCAL -> {
                        local.delete(action.path)
                        index.remove(action.path)
                    }

                    SyncAction.Kind.NOTE -> notes++
                }
                SyncStore.saveIndex(pair.id, index)
            } catch (failure: Exception) {
                Log.w(TAG, "sync ${action.kind} failed at ${action.path} for ${pair.name}: ${failure.message}")
                failed++
            }
        }

        // Files the index knew that are now on neither side are forgotten:
        // there is nothing left to keep in step. A file a NOTE spoke for is
        // still on one side, so it never reaches this sweep.
        val gone = index.keys.filter { path ->
            path !in localByPath && path !in remoteByPath && path !in actionPaths
        }
        gone.forEach { index.remove(it) }
        if (gone.isNotEmpty()) SyncStore.saveIndex(pair.id, index)

        val transfers = downloaded + uploaded
        SyncRunStatus(
            atMs = System.currentTimeMillis(),
            result = when {
                failed > 0 && transfers == 0 && conflicts == 0 -> SyncRunResult.FAILED.name
                failed > 0 || conflicts > 0 || uploadsStopped -> SyncRunResult.PARTIAL.name
                else -> SyncRunResult.SUCCESS.name
            },
            downloaded = downloaded,
            uploaded = uploaded,
            conflicts = conflicts,
            failed = failed,
            notes = notes,
        )
    }

    private companion object {
        const val TAG = "SyncEngine"
    }
}
