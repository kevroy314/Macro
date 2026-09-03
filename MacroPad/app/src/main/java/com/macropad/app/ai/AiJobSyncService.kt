package com.macropad.app.ai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.macropad.app.MacroPadApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the daemon while any job is outstanding.
 *
 * A foreground service rather than a worker because the interesting window is the
 * 30 seconds to few minutes right after you photograph a meal — WorkManager's
 * fifteen-minute floor is far too coarse for that. Its ongoing notification doubles
 * as the status indicator for outstanding jobs.
 */
class AiJobSyncService : Service() {

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MacroPadApplication
        startForegroundSafely(0, null)

        if (pollJob?.isActive != true) {
            pollJob = scope.launch {
                try {
                    var idleRounds = 0
                    while (isActive) {
                        val watched = app.repository.getWatchedAiJobs()
                        val busyThreads = app.repository.getBusyThreads().size
                        startForegroundSafely(
                            watched.count { it.isActive } + busyThreads,
                            watched.firstOrNull { it.isActive }?.let { job ->
                                if (watched.size == 1) job else null
                            }
                        )

                        // Estimates and planning replies share one poller: both are
                        // the same kind of "waiting on the daemon" work.
                        val stillWorking = try {
                            val jobsRunning = app.aiSyncManager.poll()
                            val threadsRunning = app.planningManager.poll()
                            jobsRunning || threadsRunning
                        } catch (e: Exception) {
                            true
                        }

                        if (!stillWorking) {
                            // One more pass catches a result that landed mid-poll.
                            if (++idleRounds >= 2) break
                        } else {
                            idleRounds = 0
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                } finally {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundSafely(activeCount: Int, singleJob: com.macropad.app.data.entity.AiJob?) {
        val notification = AiNotifications.buildForegroundNotification(this, activeCount, singleJob)
        try {
            ServiceCompat.startForeground(
                this,
                AiNotifications.ID_FOREGROUND,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            // Notifications denied, or a background-start restriction: keep polling
            // anyway for as long as the process lives.
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L

        /** Safe to call repeatedly; a running service just keeps polling. */
        fun start(context: Context) {
            val intent = Intent(context, AiJobSyncService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                // Can't start from the background on newer Android; the periodic
                // worker will pick the job up instead.
                AiPollWorker.ensureScheduled(context)
            }
        }
    }
}
