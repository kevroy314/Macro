package com.macropad.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.macropad.app.MacroPadApplication
import com.macropad.app.net.AiCallResult
import java.util.concurrent.TimeUnit

/**
 * Takes a backup once a day, so recovering a lost phone doesn't depend on having
 * remembered to press a button.
 *
 * Only runs when the user has turned it on. It is off by default because it makes the
 * app talk to the server on its own schedule rather than in response to a tap.
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

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
