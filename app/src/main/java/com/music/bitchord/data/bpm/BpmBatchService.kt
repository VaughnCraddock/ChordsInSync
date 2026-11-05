package com.music.bitchord.data.bpm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A batch BPM run: reads each queued file's existing tag, then analyzes the
 * ones that need it, row by row in [BpmJobs].
 *
 * The analysis window per file is short ([BpmAnalyzer] caps it), but a
 * folder of tracks is still not a thirty-second job, so this runs as a
 * foreground service of the dataSync type — the pattern the download and
 * sync services already set. The notification carries the count so far.
 *
 * Already-tagged files are **skipped, not re-analyzed**: their row lands as
 * ALREADY_TAGGED with the value they hold, and whether they join the save
 * is the results screen's include-tagged switch, not this service's call.
 * Nothing is written here at all — writes happen only when the user
 * confirms the results in the host dialog.
 */
class BpmBatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWith(done = 0, total = BpmJobs.rows.value.size)
        if (running) return START_NOT_STICKY
        running = true
        val myRun = BpmJobs.runId.value
        scope.launch {
            try {
                val queue = BpmJobs.rows.value
                queue.forEachIndexed { index, row ->
                    // A dismissed dialog that got re-asked starts a new run;
                    // this one has been superseded and stops touching rows.
                    if (BpmJobs.runId.value != myRun) return@launch
                    val uriString = row.uriString
                    if (uriString == null) {
                        // Queued without a file behind it: a result with a
                        // reason, like every other failure in this run.
                        BpmJobs.updateAt(index) {
                            it.copy(status = BpmRowStatus.FAILED, failure = BpmFailure.UNREADABLE)
                        }
                        return@forEachIndexed
                    }
                    BpmJobs.updateAt(index) { it.copy(status = BpmRowStatus.ANALYZING) }
                    val existing = BpmTagStore.readBpm(this@BpmBatchService, uriString)
                    if (existing != null) {
                        BpmJobs.updateAt(index) {
                            it.copy(
                                status = BpmRowStatus.ALREADY_TAGGED,
                                existingBpm = existing,
                                fieldText = BpmTags.formatBpmText(existing),
                            )
                        }
                    } else {
                        val estimate = BpmAnalyzer.analyze(this@BpmBatchService, uriString)
                        if (estimate != null) {
                            BpmJobs.updateAt(index) {
                                it.copy(
                                    status = BpmRowStatus.READY,
                                    detectedBpm = estimate.bpm,
                                    confidence = estimate.confidence,
                                    fieldText = BpmTags.formatBpmText(estimate.bpm),
                                )
                            }
                        } else {
                            BpmJobs.updateAt(index) {
                                it.copy(status = BpmRowStatus.FAILED, failure = BpmFailure.UNREADABLE)
                            }
                        }
                    }
                    startForegroundWith(done = index + 1, total = queue.size)
                }
            } finally {
                running = false
                if (BpmJobs.runId.value == myRun) BpmJobs.finish()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWith(done: Int, total: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.bpm_channel_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.bpm_notification_title))
            .setContentText(getString(R.string.bpm_notification_progress, done, total))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "bpm"
        private const val NOTIFICATION_ID = 0xB901

        /** Starts the run for the rows [BpmJobs.begin] has already queued. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BpmBatchService::class.java))
        }
    }
}
