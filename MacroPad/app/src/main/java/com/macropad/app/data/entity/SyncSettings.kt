package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sync settings for Dropbox integration
 */
@Entity(tableName = "sync_settings")
data class SyncSettings(
    @PrimaryKey
    val id: Int = 1, // Singleton pattern

    // Conflict resolution strategy
    val conflictResolution: ConflictResolution = ConflictResolution.LOCAL_WINS,

    // Auto-sync enabled
    val autoSyncEnabled: Boolean = false,

    // Last successful sync timestamp
    val lastSyncTimestamp: Long = 0
)

enum class ConflictResolution {
    LOCAL_WINS,      // Local data always wins (default)
    REMOTE_WINS,     // Remote/Dropbox data always wins
    ASK_USER         // Prompt user to choose
}
