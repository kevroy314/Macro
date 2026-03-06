package com.macropad.app.sync

import android.content.Context
import androidx.work.*
import com.macropad.app.MacroPadApplication
import com.macropad.app.data.entity.ConflictResolution
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for daily Dropbox sync
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MacroPadApplication
        val dropboxManager = app.dropboxManager
        val repository = app.repository

        // Check if Dropbox is linked
        if (!dropboxManager.isLinked) {
            return Result.success() // Nothing to do
        }

        // Check if auto-sync is enabled
        val syncSettings = repository.getSyncSettings()
        if (!syncSettings.autoSyncEnabled) {
            return Result.success()
        }

        return try {
            // Get current local data
            val localBackup = repository.createBackup()

            // Try to download remote data first
            when (val downloadResult = dropboxManager.downloadBackup()) {
                is SyncResult.SuccessWithData -> {
                    // There's remote data - apply conflict resolution
                    val remoteBackup = downloadResult.backup

                    when (syncSettings.conflictResolution) {
                        ConflictResolution.LOCAL_WINS -> {
                            // Upload local data (overwrite remote)
                            dropboxManager.uploadBackup(localBackup)
                        }
                        ConflictResolution.REMOTE_WINS -> {
                            // Import remote data (overwrite local)
                            repository.importBackup(remoteBackup)
                        }
                        ConflictResolution.ASK_USER -> {
                            // For background sync with ASK_USER, default to LOCAL_WINS
                            // User will be prompted on manual sync
                            dropboxManager.uploadBackup(localBackup)
                        }
                    }
                }
                is SyncResult.NoRemoteData -> {
                    // No remote data - upload local
                    dropboxManager.uploadBackup(localBackup)
                }
                is SyncResult.Error -> {
                    // Error downloading - try to upload anyway
                    dropboxManager.uploadBackup(localBackup)
                }
                else -> {
                    dropboxManager.uploadBackup(localBackup)
                }
            }

            // Update last sync timestamp
            repository.saveSyncSettings(syncSettings.copy(lastSyncTimestamp = System.currentTimeMillis()))

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "dropbox_sync"

        /**
         * Schedule daily sync
         */
        fun scheduleDailySync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        /**
         * Cancel scheduled sync
         */
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /**
         * Trigger immediate sync
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }
    }
}
