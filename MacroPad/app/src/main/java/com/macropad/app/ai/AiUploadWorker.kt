package com.macropad.app.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.macropad.app.MacroPadApplication
import java.util.concurrent.TimeUnit

/**
 * Sends jobs that were created while the server was out of reach.
 *
 * This is what makes a LAN-only server usable: you photograph the meal at the
 * restaurant, and the job sits on the phone until it can be delivered — which for a
 * home server means when you walk back through the door.
 *
 * Kept separate from [AiPollWorker] on purpose. That one asks the daemon what it has
 * finished; this one is waiting on the network, and only this one wants to be woken
 * the moment connectivity changes.
 */
class AiUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MacroPadApplication ?: return Result.success()
        val settings = app.repository.getAiSettings()
        if (!settings.isConfigured) return Result.success()

        return try {
            val stillQueued = app.aiSyncManager.drainPendingUploads()
            if (stillQueued) {
                // Connectivity isn't reachability: the phone can be on cellular, or on
                // someone else's wifi, and still nowhere near a home server. Back off
                // and try again rather than reporting success.
                Result.retry()
            } else {
                // Something went through, so the daemon now has work in flight.
                AiJobSyncService.start(applicationContext)
                Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "ai_pending_uploads"

        /**
         * Queues a drain. Replaces any pending run so a burst of submissions results
         * in one attempt rather than one per meal.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AiUploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
