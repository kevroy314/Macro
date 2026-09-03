package com.macropad.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.macropad.app.MacroPadApplication
import com.macropad.app.net.AiCallResult
import java.util.concurrent.TimeUnit

/**
 * Keeps the server's copy close behind the phone's.
 *
 * Two triggers, doing different jobs. [afterChange] runs shortly after you actually log
 * something, so what is on the server is minutes old rather than up to a day old — a
 * daily timer alone means a phone lost at 11pm costs you the whole day. [sync] keeps a
 * daily run as the floor, which covers the case where the debounced upload never got a
 * reachable server, and the case where nothing changed but the last upload failed.
 *
 * Both collapse into one queued job, so a burst of edits is one upload.
 */
class ServerBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MacroPadApplication ?: return Result.success()
        val settings = app.repository.getAiSettings()

        if (!settings.isConfigured || !settings.autoBackup) {
            // Turned off since this was scheduled. Stop asking.
            cancel(applicationContext)
            return Result.success()
        }

        // A server that is only reachable at home will be unreachable most of the day.
        // That is expected, not an error, so retry rather than reporting failure.
        return when (val result = app.serverBackup.backUpNow()) {
            is AiCallResult.Success -> {
                app.repository.saveAiSettings(
                    settings.copy(lastAutoBackupAt = System.currentTimeMillis())
                )
                Result.success()
            }
            is AiCallResult.Failure ->
                if (result.retryable) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "server_backup_daily"
        private const val CHANGE_WORK_NAME = "server_backup_after_change"

        /** Long enough that logging a meal is one upload, short enough to matter. */
        private const val DEBOUNCE_MINUTES = 15L

        /** Starts or stops the daily backup to match the setting. */
        fun sync(context: Context, enabled: Boolean) {
            if (!enabled) {
                cancel(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<ServerBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP, so toggling other settings doesn't reset the daily clock.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Queue a backup because the data changed.
         *
         * Debounced rather than immediate: logging a meal is several writes in a row,
         * and the widgets write too. Each change pushes the upload out again, so a
         * flurry of edits settles into a single backup once you stop.
         */
        fun afterChange(context: Context) {
            val request = OneTimeWorkRequestBuilder<ServerBackupWorker>()
                .setInitialDelay(DEBOUNCE_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                CHANGE_WORK_NAME,
                // REPLACE is the debounce: the pending upload is rescheduled, not added to.
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(CHANGE_WORK_NAME)
        }
    }
}
