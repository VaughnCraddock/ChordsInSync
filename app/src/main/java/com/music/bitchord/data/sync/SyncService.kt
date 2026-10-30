package com.music.bitchord.data.sync

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
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A manual "Sync now" run.
 *
 * Tapping Sync now is a decision made in the moment, so it bypasses the
 * scheduled run's charging-and-unmetered constraints — the owner is right
 * there watching it. What it does **not** bypass is the home gate: uploads
 * still only happen on the home network, exactly as the gate decides inside
 * [SyncEngine]. It runs as a foreground service of the dataSync type (the
 * download service's pattern) because a pair of music folders is not a
 * thirty-second job and the system should know what it is keeping alive.
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pairId = intent?.getStringExtra(EXTRA_PAIR_ID)
        startForegroundWith(pairId?.let { SyncStore.pair(it)?.name })
        if (running) return START_NOT_STICKY
        running = true
        scope.launch {
            try {
                val pairs = if (pairId != null) {
                    listOfNotNull(SyncStore.pair(pairId))
                } else {
                    SyncScheduler.selectRunnablePairs(SyncStore.pairs.value) { engineId ->
                        AppSettings.isSyncEngineEnabled(engineId)
                    }
                }
                val engine = SyncEngine(this@SyncService)
                for (pair in pairs) {
                    runCatching { engine.runPair(pair, manual = true) }
                }
            } finally {
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWith(pairName: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.sync_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(pairName ?: getString(R.string.sync_notification_all))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 0x8176
        private const val EXTRA_PAIR_ID = "pair_id"

        /** Runs one pair now, or every runnable pair when [pairId] is null. */
        fun syncNow(context: Context, pairId: String? = null) {
            val intent = Intent(context, SyncService::class.java).apply {
                if (pairId != null) putExtra(EXTRA_PAIR_ID, pairId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
