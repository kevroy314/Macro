package com.macropad.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One AI macro-estimation request.
 *
 * Keyed by [clientJobId], which the app generates before uploading — the row has to
 * exist while the photo is still being sent, and the same id makes a retried upload
 * idempotent on the server rather than logging the meal twice.
 */
@Entity(tableName = "ai_jobs", indices = [Index("status")])
data class AiJob(
    @PrimaryKey
    val clientJobId: String,
    val serverJobId: String? = null,
    val parentJobId: String? = null,

    val status: String = STATUS_PENDING_UPLOAD,
    val promptText: String = "",
    /** JSON array of absolute paths to the local copies of the uploaded photos. */
    val imagePaths: String = "[]",
    val thresholdMode: String = "percent",
    val thresholdValue: Float = 10f,

    /** Server's result payload, verbatim. Parsed lazily by the UI. */
    val resultJson: String? = null,
    val revision: Int = 0,
    val error: String? = null,

    /** What was actually added to the daily totals, so a revision can apply a delta. */
    val appliedProteinG: Int = 0,
    val appliedCarbsG: Int = 0,
    val appliedFatG: Int = 0,
    val appliedEntryId: Long? = null,
    val presetId: Long? = null,

    /** True once the user has answered or dismissed the outstanding questions. */
    val questionsResolved: Boolean = false,
    /**
     * Answers collected so far, as a JSON object of question id -> answer. Questions
     * arrive in pairs but are answered one notification at a time, so they are held
     * here until the set is complete and can be submitted in a single resume.
     */
    val pendingAnswersJson: String = "{}",
    /** Soft delete: the job stays in the log but its macros are out of the totals. */
    val excludedFromTotals: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Server-side updated_at of the last payload we merged, for incremental polling. */
    val serverUpdatedAt: Long = 0
) {
    val isActive: Boolean
        get() = status in ACTIVE_STATUSES

    val isAwaitingAnswer: Boolean
        get() = status == STATUS_NEEDS_INPUT && !questionsResolved

    companion object {
        const val STATUS_PENDING_UPLOAD = "pending_upload"
        const val STATUS_QUEUED = "queued"
        const val STATUS_RUNNING = "running"
        const val STATUS_NEEDS_INPUT = "needs_input"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_SUPERSEDED = "superseded"

        /** Statuses where the daemon still has work in flight. */
        val ACTIVE_STATUSES = setOf(STATUS_PENDING_UPLOAD, STATUS_QUEUED, STATUS_RUNNING)

        /**
         * Statuses that keep the poller alive. A job waiting on an answer is
         * deliberately *not* watched: nothing changes server-side until the answer is
         * sent, and sending it moves the job back to RUNNING. Watching it would leave
         * the foreground service polling forever for a question nobody answered.
         */
        val WATCHED_STATUSES = ACTIVE_STATUSES
    }
}

/** The agent's result, as parsed from [AiJob.resultJson]. */
data class AiResult(
    val items: List<AiResultItem> = emptyList(),
    val total: AiMacroTotal = AiMacroTotal(),
    val preset_name: String = "",
    val summary: String = "",
    val sources: List<String> = emptyList(),
    val follow_up_questions: List<AiQuestion> = emptyList()
)

data class AiResultItem(
    val name: String = "",
    val qty: String = "",
    val protein_g: Int = 0,
    val carbs_g: Int = 0,
    val fat_g: Int = 0,
    val confidence: String = "medium",
    val assumptions: String = ""
)

data class AiMacroTotal(
    val protein_g: Int = 0,
    val carbs_g: Int = 0,
    val fat_g: Int = 0,
    val calories: Int = 0
)

data class AiQuestion(
    val id: String = "",
    val question: String = "",
    val why: String = "",
    val est_calorie_swing: Double = 0.0
)

/**
 * A question the user has answered.
 *
 * The question text is stored alongside the answer because a revised estimate
 * replaces `follow_up_questions` with an empty list — without this, the record of
 * what was asked would disappear the moment it was answered.
 */
data class AiAnsweredQuestion(
    val id: String = "",
    val question: String = "",
    val answer: String = ""
)
