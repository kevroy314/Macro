package com.macropad.app.ai

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.macropad.app.MacroPadApplication
import java.util.concurrent.TimeUnit

/**
 * Backstop for [AiJobSyncService]: catches jobs that outlive the service, or that
 * finished while the app was swiped away.
 *
 * Fifteen minutes is WorkManager's floor — this is the safety net, not the main path.
 */
class AiPollWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MacroPadApplication ?: return Result.success()
        val settings = app.repository.getAiSettings()
        if (!settings.isConfigured) return Result.success()

        return try {
            val stillWorking = app.aiSyncManager.poll() or app.planningManager.poll()
            if (stillWorking) {
                // Hand back to the fast poller now that we're awake anyway.
                AiJobSyncService.start(applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "ai_poll_backstop"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<AiPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
