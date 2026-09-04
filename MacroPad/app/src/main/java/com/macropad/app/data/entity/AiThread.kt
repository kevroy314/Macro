package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A planning conversation.
 *
 * Backed by a Claude session on the daemon that is resumed on every turn, so the
 * assistant remembers the thread without the app resending its history.
 */
@Entity(tableName = "ai_threads")
data class AiThread(
    @PrimaryKey
    val clientThreadId: String,
    val serverThreadId: String? = null,
    val title: String = "",
    val status: String = STATUS_IDLE,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val serverUpdatedAt: Long = 0,
    /** What the assistant is doing right now, while [isBusy]. Empty when idle. */
    val progress: String = "",
    /** Everything it has done, as a JSON array of {at, text}. Survives completion. */
    val steps: String = "[]"
) {
    val isBusy: Boolean
        get() = status == STATUS_RUNNING || status == STATUS_SENDING

    val displayTitle: String
        get() = title.ifBlank { "New plan" }

    companion object {
        /** Local-only: the message is on its way to the server. */
        const val STATUS_SENDING = "sending"
        const val STATUS_RUNNING = "running"
        const val STATUS_IDLE = "idle"
        const val STATUS_FAILED = "failed"
    }
}

/**
 * One turn in a thread. Mirrors the daemon exactly — the server is the source of
 * truth for a conversation, so these rows are replaced wholesale on each sync
 * rather than merged field by field.
 */
@Entity(
    tableName = "ai_thread_messages",
    indices = [Index("clientThreadId")]
)
data class AiThreadMessage(
    @PrimaryKey
    val id: String,
    val clientThreadId: String,
    val role: String,
    val text: String = "",
    val imageCount: Int = 0,
    /** JSON array of AiProposal. */
    val proposalsJson: String = "[]",
    /** JSON array of proposal indices already added to the day. */
    val appliedIndicesJson: String = "[]",
    /** The steps that produced this reply. JSON array of {at, text}. */
    val steps: String = "[]",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/** Food the assistant suggests logging, rendered as a tappable card. */
data class AiProposal(
    val name: String = "",
    val qty: String = "",
    val protein_g: Int = 0,
    val carbs_g: Int = 0,
    val fat_g: Int = 0,
    val calories: Int = 0,
    val note: String = ""
)
