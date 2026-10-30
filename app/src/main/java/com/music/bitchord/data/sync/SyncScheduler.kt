package com.music.bitchord.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.settings.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Which pairs a scheduled run may touch, and the scheduling itself.
 *
 * The automatic run is deliberately picky about its moment: **charging and
 * on unmetered Wi-Fi**, per Vaughn's rule. WorkManager holds the request
 * until both hold, so a pair can wait hours for its window quietly — that
 * waiting is WorkManager constraints doing their job, not a failure, and the
 * last-run status a pair shows says what the last *actual* run did.
 */
object SyncScheduler {

    private const val WORK_NAME = "sync_periodic"
    private const val INTERVAL_HOURS = 4L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        // KEEP: one standing request, re-asserted on every app start
        // without disturbing the schedule of the one already waiting. The
        // per-engine toggles cannot be expressed to WorkManager without a
        // request per engine, so enforcement lives where it is testable —
        // in [selectRunnablePairs] at the start of every run.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * The pairs a run may process: enabled, on an engine that is available
     * in this build and switched on. A pair whose engine is off is never in
     * the list — dormant means dormant, no network and no runs — and the UI
     * shows it as paused by its engine in the meantime.
     */
    fun selectRunnablePairs(
        pairs: List<SyncPair>,
        engineEnabled: (String) -> Boolean,
    ): List<SyncPair> = pairs.filter { pair ->
        pair.enabled && SyncEngines.isAvailable(pair.engineId) && engineEnabled(pair.engineId)
    }
}

/** The charging-plus-unmetered run: every runnable pair, in turn, each recording its own outcome. */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pairs = SyncScheduler.selectRunnablePairs(SyncStore.pairs.value) { engineId ->
            AppSettings.isSyncEngineEnabled(engineId)
        }
        val engine = SyncEngine(applicationContext)
        for (pair in pairs) {
            // One pair's failure is its own status line, never the run's:
            // the remaining pairs still get their window.
            runCatching { engine.runPair(pair, manual = false) }
                .onFailure { Log.w(TAG, "scheduled sync failed for ${pair.name}: ${it.message}") }
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
