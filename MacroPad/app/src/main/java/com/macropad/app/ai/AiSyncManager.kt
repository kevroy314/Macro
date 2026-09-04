package com.macropad.app.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.macropad.app.data.entity.AiAnsweredQuestion
import com.macropad.app.data.entity.AiJob
import com.macropad.app.data.entity.AiQuestion
import com.macropad.app.data.entity.AiResult
import com.macropad.app.data.entity.AiSettings
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.net.AiAnswer
import com.macropad.app.net.AiCallResult
import com.macropad.app.net.AiClient
import com.macropad.app.net.ServerJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Owns the app's side of the AI estimator: uploading jobs, polling the daemon,
 * merging results into the day's macros, and driving the notifications.
 *
 * Every mutation goes through [mutex] so a poll and a notification reply can't apply
 * the same result twice.
 */
class AiSyncManager(
    private val context: Context,
    private val repository: MacroRepository
) {
    private val gson = Gson()
    private val mutex = Mutex()

    /** Set by the UI so a freshly applied estimate refreshes the widgets. */
    var onDataChanged: (suspend () -> Unit)? = null

    // ------------------------------------------------------------------ submitting

    /**
     * Copies the photos locally, records the job, and uploads it.
     * The row exists before the upload starts, so a failure is visible and retryable
     * rather than silently losing the meal.
     */
    suspend fun submitJob(
        text: String,
        imageUris: List<Uri>,
        thresholdMode: String? = null,
        thresholdValue: Float? = null
    ): AiCallResult<AiJob> {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) {
            return AiCallResult.Failure("Set up the AI server in Settings first")
        }

        val clientJobId = UUID.randomUUID().toString()
        val outcome = AiImageStore.storeAll(context, clientJobId, imageUris)
        val storedPaths = outcome.files.map { it.absolutePath }
        if (imageUris.isNotEmpty() && storedPaths.isEmpty()) {
            AiImageStore.deleteJobFiles(context, clientJobId)
            val reason = outcome.error?.let { ": $it" }.orEmpty()
            return AiCallResult.Failure("Could not read the selected photos$reason")
        }

        val job = AiJob(
            clientJobId = clientJobId,
            status = AiJob.STATUS_PENDING_UPLOAD,
            promptText = text.trim(),
            imagePaths = gson.toJson(storedPaths),
            thresholdMode = thresholdMode ?: settings.thresholdMode.wireValue,
            thresholdValue = thresholdValue ?: settings.thresholdValue
        )
        repository.saveAiJob(job)

        return when (val result = uploadJob(job, settings)) {
            is AiCallResult.Success -> AiCallResult.Success(result.value)
            is AiCallResult.Failure -> {
                if (result.retryable) {
                    // The server is unreachable, not refusing. The photos are already
                    // on disk and clientJobId makes the eventual upload idempotent, so
                    // hold the job and send it when the network comes back — which for
                    // a LAN-only server means when you get home.
                    repository.saveAiJob(job.copy(error = result.message))
                    AiUploadWorker.enqueue(context)
                    AiCallResult.Success(repository.getAiJob(clientJobId) ?: job)
                } else {
                    repository.saveAiJob(
                        job.copy(status = AiJob.STATUS_FAILED, error = result.message)
                    )
                    result
                }
            }
        }
    }

    /**
     * Sends everything that has been waiting for the network.
     *
     * Returns true if anything is still queued afterwards, so the worker knows whether
     * to ask to be woken again.
     */
    suspend fun drainPendingUploads(): Boolean {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return false

        val pending = repository.getWatchedAiJobs()
            .filter { it.status == AiJob.STATUS_PENDING_UPLOAD && it.serverJobId == null }

        var triedRediscovery = false

        for (job in pending) {
            if (System.currentTimeMillis() - job.createdAt > MAX_QUEUE_AGE_MS) {
                // Weeks offline means the server isn't coming back on its own. Stop
                // retrying, but leave the row and its photos so it can be retried by
                // hand from the AI log.
                repository.saveAiJob(
                    job.copy(
                        status = AiJob.STATUS_FAILED,
                        error = "Waited too long for the server. Tap to try again."
                    )
                )
                continue
            }
            val current = repository.getAiSettings()
            when (val result = uploadJob(job, current)) {
                is AiCallResult.Success -> Unit
                is AiCallResult.Failure -> {
                    if (!result.retryable) {
                        repository.saveAiJob(
                            job.copy(status = AiJob.STATUS_FAILED, error = result.message)
                        )
                    } else if (!triedRediscovery) {
                        // Unreachable might just mean the router moved it. Look once
                        // per drain, then give this job one more go at the new address.
                        triedRediscovery = true
                        val moved = rediscoverServer()
                        if (moved != null && !moved.equals(current.baseUrl, ignoreCase = true)) {
                            uploadJob(job, repository.getAiSettings())
                        }
                    }
                }
            }
        }

        return repository.getWatchedAiJobs().any {
            it.status == AiJob.STATUS_PENDING_UPLOAD && it.serverJobId == null
        }
    }

    /**
     * Re-finds a home server that has moved, and stores its new address.
     *
     * Returns the new base URL, or null if nothing changed. A server behind a real
     * hostname is never touched: [LanDiscovery.isLocalAddress] is what stops a brief
     * outage from silently repointing the app at some machine on the local network.
     */
    suspend fun rediscoverServer(): String? {
        val settings = repository.getAiSettings()
        if (!LanDiscovery.isLocalAddress(settings.baseUrl)) return null

        val found = LanDiscovery.discover(context) ?: return null
        if (!found.baseUrl.equals(settings.baseUrl, ignoreCase = true)) {
            repository.saveAiSettings(settings.copy(serverUrl = found.baseUrl))
            Log.i(TAG, "server moved to ${found.baseUrl}")
        }
        // Returned even when unchanged, so "Find on network" can report honestly that
        // the server is there rather than claiming it found nothing.
        return found.baseUrl
    }

    /** Uploads (or re-uploads) a job that is still pending. */
    suspend fun uploadJob(job: AiJob, settings: AiSettings): AiCallResult<AiJob> {
        val images = pathsOf(job).map { java.io.File(it) }.filter { it.exists() }
        val response = AiClient.createJob(
            settings = settings,
            clientJobId = job.clientJobId,
            text = job.promptText,
            images = images,
            thresholdMode = job.thresholdMode,
            thresholdValue = job.thresholdValue
        )
        return when (response) {
            is AiCallResult.Failure -> response
            is AiCallResult.Success -> {
                val merged = mutex.withLock { mergeServerJob(response.value, job) }
                AiCallResult.Success(merged ?: job)
            }
        }
    }

    // -------------------------------------------------------------------- polling

    /**
     * Pulls anything that changed on the server and merges it.
     * Returns true while there is still work worth polling for.
     */
    suspend fun poll(): Boolean {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return false

        // Re-drive anything whose upload failed before the server ever saw it.
        drainPendingUploads()

        val since = repository.getLatestAiServerUpdate()
        when (val response = AiClient.listJobs(settings, since)) {
            is AiCallResult.Failure -> {
                Log.w(TAG, "poll failed: ${response.message}")
                // Keep polling: a transient network blip shouldn't abandon the job.
                return hasWorkOnServer()
            }
            is AiCallResult.Success -> {
                mutex.withLock {
                    response.value.jobs.forEach { mergeServerJob(it, null) }
                }
            }
        }
        return hasWorkOnServer()
    }

    /**
     * Whether the daemon still owes us an answer.
     *
     * Deliberately excludes jobs queued for upload. Those are waiting on the network,
     * not on the server, and letting them hold the five-second foreground poller open
     * would burn the battery all afternoon for a meal that cannot be sent until you
     * are home. [AiUploadWorker] picks those up when connectivity returns.
     */
    private suspend fun hasWorkOnServer(): Boolean =
        repository.getWatchedAiJobs().any { it.serverJobId != null }

    /**
     * Folds one server payload into the local job, applying macros and firing
     * notifications on the transitions that warrant them.
     */
    private suspend fun mergeServerJob(server: ServerJob, hint: AiJob?): AiJob? {
        val local = hint
            ?: server.clientJobId?.let { repository.getAiJob(it) }
            ?: repository.getAiJobByServerId(server.id)
            ?: return null

        val previousStatus = local.status
        val previousRevision = local.revision
        val resultJson = server.resultObject?.toString()

        var updated = local.copy(
            serverJobId = server.id,
            parentJobId = server.parentJobId ?: local.parentJobId,
            status = server.status,
            progress = server.progress,
            steps = gson.toJson(server.steps),
            revision = server.revision,
            error = server.error,
            resultJson = resultJson ?: local.resultJson,
            serverUpdatedAt = maxOf(local.serverUpdatedAt, server.updatedAt),
            updatedAt = System.currentTimeMillis()
        )
        repository.saveAiJob(updated)

        val result = repository.parseAiResult(updated)
        val settings = repository.getAiSettings()

        if (result != null && settings.autoApply && !updated.excludedFromTotals) {
            val isNew = updated.appliedEntryId == null
            val isRevision = !isNew && server.revision > previousRevision
            if (isNew || isRevision) {
                updated = if (isNew) {
                    repository.applyAiResult(updated, result)
                } else {
                    repository.reviseAiEntry(updated, result)
                }
                onDataChanged?.invoke()
            }
        }

        if (server.status != previousStatus) {
            onStatusChanged(updated, result, previousStatus)
        } else if (result != null && server.revision > previousRevision) {
            // A revision can land without a status change if more questions follow.
            maybeAskNextQuestion(updated, result)
        }
        return updated
    }

    private suspend fun onStatusChanged(job: AiJob, result: AiResult?, previousStatus: String) {
        when (job.status) {
            AiJob.STATUS_NEEDS_INPUT -> {
                if (result != null) maybeAskNextQuestion(job, result)
            }
            AiJob.STATUS_COMPLETED -> {
                AiNotifications.cancelAllForJob(context, job.clientJobId, result)
                if (result != null) AiNotifications.showResult(context, job, result)
                repository.saveAiJob(job.copy(questionsResolved = true))
            }
            AiJob.STATUS_FAILED -> {
                AiNotifications.showFailure(
                    context,
                    job,
                    job.error ?: "The estimate could not be completed"
                )
            }
            AiJob.STATUS_CANCELLED, AiJob.STATUS_SUPERSEDED -> {
                AiNotifications.cancelAllForJob(context, job.clientJobId, result)
            }
        }
    }

    /** Posts a notification for the first question the user hasn't answered yet. */
    private fun maybeAskNextQuestion(job: AiJob, result: AiResult) {
        if (job.questionsResolved) return
        val next = unansweredQuestions(job, result).firstOrNull() ?: return
        AiNotifications.showQuestion(
            context,
            job,
            next,
            unansweredQuestions(job, result).size
        )
    }

    /** Questions from [result] that this job has no answer for yet. */
    fun unansweredQuestions(job: AiJob, result: AiResult): List<AiQuestion> {
        val answered = answeredQuestions(job).map { it.id }.toSet()
        return result.follow_up_questions.filter { it.id !in answered }
    }

    // ------------------------------------------------------------------- answering

    /**
     * Stores one inline answer. Once every outstanding question has an answer they
     * are submitted together, so the agent resumes once rather than once per answer.
     */
    suspend fun recordAnswer(clientJobId: String, questionId: String, answer: String) {
        mutex.withLock {
            val job = repository.getAiJob(clientJobId) ?: return
            val result = repository.parseAiResult(job) ?: return

            val asked = result.follow_up_questions.firstOrNull { it.id == questionId }
            val answers = answeredQuestions(job).filterNot { it.id == questionId } +
                AiAnsweredQuestion(
                    id = questionId,
                    question = asked?.question.orEmpty(),
                    answer = answer
                )

            val updated = job.copy(pendingAnswersJson = gson.toJson(answers))
            repository.saveAiJob(updated)
            AiNotifications.cancelQuestion(context, clientJobId, questionId)

            // Hold the rest back until every question has an answer, so the agent
            // resumes once with the full picture instead of once per answer.
            val outstanding = unansweredQuestions(updated, result)
            if (outstanding.isNotEmpty()) {
                AiNotifications.showQuestion(context, updated, outstanding.first(), outstanding.size)
                return
            }
            submitAnswers(updated, result)
        }
    }

    /** "Keep estimate": stop asking and leave the current numbers alone. */
    suspend fun skipQuestions(clientJobId: String) {
        mutex.withLock {
            val job = repository.getAiJob(clientJobId) ?: return
            val result = repository.parseAiResult(job)
            AiNotifications.cancelAllForJob(context, clientJobId, result)

            val answers = answeredQuestions(job)
            if (answers.isNotEmpty() && result != null) {
                // Send what we have; a partial answer still sharpens the estimate.
                submitAnswers(job, result)
                return
            }
            repository.saveAiJob(
                job.copy(questionsResolved = true, status = AiJob.STATUS_COMPLETED)
            )
        }
    }

    private suspend fun submitAnswers(job: AiJob, result: AiResult) {
        val settings = repository.getAiSettings()
        val serverJobId = job.serverJobId
        if (!settings.isConfigured || serverJobId == null) return

        val payload = answeredQuestions(job).map {
            AiAnswer(questionId = it.id, answer = it.answer)
        }
        if (payload.isEmpty()) return

        // Mark it running straight away so the poller keeps watching.
        repository.saveAiJob(
            job.copy(status = AiJob.STATUS_RUNNING, questionsResolved = true)
        )

        when (val response = AiClient.answer(settings, serverJobId, payload)) {
            is AiCallResult.Success -> mergeServerJob(response.value, null)
            is AiCallResult.Failure -> repository.saveAiJob(
                job.copy(
                    status = AiJob.STATUS_NEEDS_INPUT,
                    questionsResolved = false,
                    error = response.message
                )
            )
        }
        AiJobSyncService.start(context)
    }

    // -------------------------------------------------------------- job management

    /**
     * Correct a finished estimate in place.
     *
     * Re-running throws away the conversation and pays for the research again. A
     * correction resumes the same session, so "I actually had one more of these"
     * costs a single turn and lands as a delta on what was already logged.
     */
    suspend fun correctJob(clientJobId: String, text: String): AiCallResult<AiJob> {
        val job = repository.getAiJob(clientJobId)
            ?: return AiCallResult.Failure("That estimate is gone")
        val settings = repository.getAiSettings()
        val serverJobId = job.serverJobId
        if (!settings.isConfigured || serverJobId == null) {
            return AiCallResult.Failure("This estimate can't be revised")
        }

        repository.saveAiJob(job.copy(status = AiJob.STATUS_RUNNING, error = null))
        AiJobSyncService.start(context)

        return when (val response = AiClient.correct(settings, serverJobId, text.trim())) {
            is AiCallResult.Success -> {
                val merged = mutex.withLock { mergeServerJob(response.value, job) }
                AiCallResult.Success(merged ?: job)
            }
            is AiCallResult.Failure -> {
                repository.saveAiJob(job.copy(status = AiJob.STATUS_COMPLETED, error = response.message))
                response
            }
        }
    }

    suspend fun cancelJob(clientJobId: String) {
        val job = repository.getAiJob(clientJobId) ?: return
        val settings = repository.getAiSettings()
        val serverJobId = job.serverJobId

        if (settings.isConfigured && serverJobId != null) {
            AiClient.cancel(settings, serverJobId)
        }
        repository.saveAiJob(job.copy(status = AiJob.STATUS_CANCELLED))
        AiNotifications.cancelAllForJob(context, clientJobId, repository.parseAiResult(job))
    }

    /** Edit and re-run: a new job on the server, linked to the old one. */
    suspend fun retryJob(
        clientJobId: String,
        newText: String,
        thresholdMode: String,
        thresholdValue: Float
    ): AiCallResult<AiJob> {
        val job = repository.getAiJob(clientJobId)
            ?: return AiCallResult.Failure("Job not found")
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return AiCallResult.Failure("AI server not configured")

        val serverJobId = job.serverJobId
        val newClientId = UUID.randomUUID().toString()

        // Carry the local photos across so the new job can be retried offline too.
        val newPaths = mutableListOf<String>()
        AiImageStore.listImages(context, clientJobId).forEachIndexed { index, file ->
            val target = java.io.File(
                AiImageStore.jobDir(context, newClientId),
                "image_${index + 1}.jpg"
            )
            runCatching { file.copyTo(target, overwrite = true) }
                .onSuccess { newPaths.add(target.absolutePath) }
        }

        val newJob = AiJob(
            clientJobId = newClientId,
            parentJobId = clientJobId,
            status = AiJob.STATUS_PENDING_UPLOAD,
            promptText = newText.trim(),
            imagePaths = gson.toJson(newPaths),
            thresholdMode = thresholdMode,
            thresholdValue = thresholdValue
        )
        repository.saveAiJob(newJob)

        val response = if (serverJobId != null) {
            AiClient.retry(
                settings = settings,
                serverJobId = serverJobId,
                clientJobId = newClientId,
                text = newJob.promptText,
                thresholdMode = thresholdMode,
                thresholdValue = thresholdValue
            )
        } else {
            AiClient.createJob(
                settings = settings,
                clientJobId = newClientId,
                text = newJob.promptText,
                images = newPaths.map { java.io.File(it) },
                thresholdMode = thresholdMode,
                thresholdValue = thresholdValue
            )
        }

        return when (response) {
            is AiCallResult.Success -> {
                mutex.withLock { mergeServerJob(response.value, newJob) }
                // Take the old estimate back out of the day before the new one lands.
                // Without this a re-run counts the same meal twice — the superseded
                // job keeps its entry and its contribution to the daily total, and
                // the replacement adds its own on top.
                repository.setAiJobExcluded(job, true)
                repository.saveAiJob(
                    repository.getAiJob(clientJobId)?.copy(
                        status = AiJob.STATUS_SUPERSEDED,
                        questionsResolved = true
                    ) ?: job.copy(
                        status = AiJob.STATUS_SUPERSEDED,
                        questionsResolved = true
                    )
                )
                onDataChanged?.invoke()
                AiNotifications.cancelAllForJob(context, clientJobId, repository.parseAiResult(job))
                AiCallResult.Success(repository.getAiJob(newClientId) ?: newJob)
            }
            is AiCallResult.Failure -> {
                repository.saveAiJob(
                    newJob.copy(status = AiJob.STATUS_FAILED, error = response.message)
                )
                response
            }
        }
    }

    /** Removes a finished job from the log, and its macros from the day. */
    suspend fun deleteJob(clientJobId: String) {
        val job = repository.getAiJob(clientJobId) ?: return
        val settings = repository.getAiSettings()
        val serverJobId = job.serverJobId
        AiNotifications.cancelAllForJob(context, clientJobId, repository.parseAiResult(job))

        repository.deleteAiJob(job)
        AiImageStore.deleteJobFiles(context, clientJobId)
        onDataChanged?.invoke()

        // Best effort: the local record is what the user sees.
        if (settings.isConfigured && serverJobId != null) {
            AiClient.deleteJob(settings, serverJobId)
        }
    }

    suspend fun setExcludedFromTotals(clientJobId: String, excluded: Boolean) {
        val job = repository.getAiJob(clientJobId) ?: return
        repository.setAiJobExcluded(job, excluded)
        onDataChanged?.invoke()
    }

    // ------------------------------------------------------------------- utilities

    fun pathsOf(job: AiJob): List<String> = try {
        gson.fromJson(job.imagePaths, object : TypeToken<List<String>>() {}.type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Questions this job has answers for, oldest first. */
    fun answeredQuestions(job: AiJob): List<AiAnsweredQuestion> {
        val raw = job.pendingAnswersJson
        if (raw.isBlank()) return emptyList()
        return try {
            gson.fromJson(raw, object : TypeToken<List<AiAnsweredQuestion>>() {}.type)
                ?: emptyList()
        } catch (e: Exception) {
            // Jobs answered before answers carried their question text stored a
            // plain id -> answer map.
            try {
                val legacy: Map<String, String> =
                    gson.fromJson(raw, object : TypeToken<Map<String, String>>() {}.type)
                        ?: emptyMap()
                legacy.map { (id, answer) -> AiAnsweredQuestion(id = id, answer = answer) }
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    fun questionsOf(job: AiJob): List<AiQuestion> =
        repository.parseAiResult(job)?.follow_up_questions ?: emptyList()

    /** Test connection button in Settings. */
    suspend fun testConnection(
        url: String,
        apiKey: String,
        certPin: String = ""
    ): AiCallResult<String> =
        when (val result = AiClient.health(url, apiKey, certPin)) {
            is AiCallResult.Failure -> result
            is AiCallResult.Success -> {
                val health = result.value
                if (health.ok) {
                    AiCallResult.Success("Connected · ${health.model}")
                } else {
                    AiCallResult.Failure("Server responded but is not ready")
                }
            }
        }

    companion object {
        private const val TAG = "AiSyncManager"

        /** How long a queued upload keeps retrying before it needs a human. */
        private const val MAX_QUEUE_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
