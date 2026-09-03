package com.macropad.app.sync

import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.net.AiCallResult

/**
 * Moving a phone off Dropbox and onto the daemon's backup, without a moment where
 * the data lives in neither place.
 *
 * The order matters: read Dropbox, merge only what's missing locally, then push the
 * combined result to the server. Nothing is deleted from Dropbox and nothing local is
 * overwritten, so a failed migration leaves you exactly where you started and can be
 * run again.
 */
class DropboxMigration(
    private val repository: MacroRepository,
    private val dropbox: DropboxManager,
    private val serverBackup: ServerBackup
) {

    data class Preview(
        val plan: BackupMerge.Plan,
        val description: String,
        val backup: BackupData
    ) {
        /** A v3 Dropbox file has daily totals but no per-entry history to bring over. */
        val entriesUnavailable: Boolean get() = !backup.hasEntryHistory
    }

    /** Read Dropbox and work out what it would add. Changes nothing. */
    suspend fun preview(): AiCallResult<Preview> {
        if (!dropbox.isLinked) {
            return AiCallResult.Failure("This phone isn't linked to Dropbox")
        }
        val backup = when (val result = dropbox.downloadBackup()) {
            is SyncResult.SuccessWithData -> result.backup
            is SyncResult.NoRemoteData ->
                return AiCallResult.Failure("There's no backup in Dropbox to migrate")
            is SyncResult.Error -> return AiCallResult.Failure(result.message)
            is SyncResult.Success -> return AiCallResult.Failure("Unexpected Dropbox response")
        }

        val plan = BackupMerge.plan(
            local = repository.snapshotForMerge(),
            remote = repository.snapshotOf(backup)
        )
        return AiCallResult.Success(
            Preview(
                plan = plan,
                description = BackupMerge.describe(plan, repository.snapshotOf(backup)),
                backup = backup
            )
        )
    }

    /**
     * Merge the gaps, then upload everything to the server.
     *
     * The upload is the step that can fail on a home network, so it goes last: by then
     * the merged data is already safe in the local database.
     */
    suspend fun migrate(preview: Preview): AiCallResult<String> {
        preview.backup.targets?.let { repository.saveTarget(it) }
        repository.applyMergePlan(preview.plan)

        return when (val upload = serverBackup.backUpNow()) {
            is AiCallResult.Failure -> AiCallResult.Failure(
                "Merged into this phone, but the upload failed: ${upload.message}. " +
                    "Your data is safe here — tap Back up now to retry."
            )
            is AiCallResult.Success -> AiCallResult.Success(
                buildString {
                    append("Migrated. ")
                    if (preview.plan.daysToAdd.isNotEmpty()) {
                        append("${preview.plan.daysToAdd.size} day(s) recovered from Dropbox. ")
                    }
                    append("${upload.value.days} days are now backed up to your server.")
                }
            )
        }
    }
}
