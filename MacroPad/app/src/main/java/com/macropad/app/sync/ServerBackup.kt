package com.macropad.app.sync

import com.google.gson.Gson
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.net.AiCallResult
import com.macropad.app.net.AiClient
import com.macropad.app.net.ServerBackupMeta

/**
 * Backup and restore through the user's own AI daemon.
 *
 * An alternative to Dropbox rather than a replacement: a Dropbox app registration
 * caps how many accounts can link to it, which a household sharing one build runs
 * into immediately. The daemon has no such cap, already authenticates each person
 * separately, and keeps their backup private to them.
 */
class ServerBackup(private val repository: MacroRepository) {

    private val gson = Gson()

    suspend fun meta(): AiCallResult<ServerBackupMeta> {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return notConfigured()
        return AiClient.backupMeta(settings)
    }

    suspend fun backUpNow(): AiCallResult<ServerBackupMeta> {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return notConfigured()
        val payload = gson.toJson(repository.createBackup())
        return AiClient.uploadBackup(settings, payload)
    }

    /**
     * Brings down the server's backup and fills in whatever this phone is missing.
     * Days already here are kept as they are. Returns the number of days added.
     */
    suspend fun restore(): AiCallResult<Int> {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return notConfigured()

        return when (val result = AiClient.downloadBackup(settings)) {
            is AiCallResult.Failure -> result
            is AiCallResult.Success -> try {
                val backup = gson.fromJson(result.value, BackupData::class.java)
                    ?: return AiCallResult.Failure("The stored backup is unreadable")
                val plan = repository.importBackup(backup)
                AiCallResult.Success(plan.daysToAdd.size)
            } catch (e: Exception) {
                AiCallResult.Failure("The stored backup is unreadable: ${e.message}")
            }
        }
    }

    private fun notConfigured(): AiCallResult.Failure =
        AiCallResult.Failure("Set up the AI server in Settings first")
}
