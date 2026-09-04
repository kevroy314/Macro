package com.macropad.app.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.macropad.app.data.entity.AiProposal
import com.macropad.app.data.entity.AiThread
import com.macropad.app.data.entity.AiThreadMessage
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.net.AiCallResult
import com.macropad.app.net.AiClient
import com.macropad.app.net.ServerThread
import com.google.gson.Gson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Planning conversations: multi-turn threads that can see the day's numbers and
 * hand back entries to log.
 *
 * A thread is a Claude session on the daemon. The app keeps a local mirror of the
 * messages so old threads read instantly and offline, but the server is the source
 * of truth for their contents.
 */
class PlanningManager(
    private val context: Context,
    private val repository: MacroRepository
) {
    private val gson = Gson()
    private val mutex = Mutex()

    var onDataChanged: (suspend () -> Unit)? = null

    /** Starts a thread and sends its first message. Returns the local thread id. */
    suspend fun startThread(text: String, imageUris: List<Uri>): AiCallResult<String> {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) {
            return AiCallResult.Failure("Set up the AI server in Settings first")
        }

        val clientThreadId = UUID.randomUUID().toString()
        val images = storeImages(clientThreadId, imageUris)

        repository.saveThread(
            AiThread(clientThreadId = clientThreadId, status = AiThread.STATUS_SENDING)
        )
        // Show the message immediately; the server echo replaces it a moment later.
        repository.replaceThreadMessages(
            clientThreadId,
            listOf(
                AiThreadMessage(
                    id = "local-${UUID.randomUUID()}",
                    clientThreadId = clientThreadId,
                    role = AiThreadMessage.ROLE_USER,
                    text = text,
                    imageCount = images.size
                )
            )
        )

        val response = AiClient.createThread(
            settings = settings,
            clientThreadId = clientThreadId,
            text = text,
            images = images,
            contextJson = repository.buildPlanningContext()
        )
        return when (response) {
            is AiCallResult.Success -> {
                mutex.withLock { mergeThread(response.value, clientThreadId) }
                clearImages(clientThreadId)
                AiCallResult.Success(clientThreadId)
            }
            is AiCallResult.Failure -> {
                repository.saveThread(
                    AiThread(
                        clientThreadId = clientThreadId,
                        status = AiThread.STATUS_FAILED,
                        error = response.message
                    )
                )
                response
            }
        }
    }

    suspend fun sendMessage(
        clientThreadId: String,
        text: String,
        imageUris: List<Uri>
    ): AiCallResult<Unit> {
        val settings = repository.getAiSettings()
        val thread = repository.getThread(clientThreadId)
            ?: return AiCallResult.Failure("Thread not found")
        val serverThreadId = thread.serverThreadId
            ?: return AiCallResult.Failure("This thread never reached the server")
        if (!settings.isConfigured) {
            return AiCallResult.Failure("AI server not configured")
        }

        val images = storeImages(clientThreadId, imageUris)
        repository.saveThread(thread.copy(status = AiThread.STATUS_SENDING, error = null))

        // Optimistic echo so the message appears the instant it is sent.
        repository.replaceThreadMessages(
            clientThreadId,
            repository.getThreadMessages(clientThreadId) + AiThreadMessage(
                id = "local-${UUID.randomUUID()}",
                clientThreadId = clientThreadId,
                role = AiThreadMessage.ROLE_USER,
                text = text,
                imageCount = images.size
            )
        )

        val response = AiClient.sendThreadMessage(
            settings = settings,
            serverThreadId = serverThreadId,
            text = text,
            images = images,
            contextJson = repository.buildPlanningContext()
        )
        return when (response) {
            is AiCallResult.Success -> {
                mutex.withLock { mergeThread(response.value, clientThreadId) }
                clearImages(clientThreadId)
                AiCallResult.Success(Unit)
            }
            is AiCallResult.Failure -> {
                repository.saveThread(
                    thread.copy(status = AiThread.STATUS_FAILED, error = response.message)
                )
                response
            }
        }
    }

    /** Pulls thread state. Returns true while a reply is still being written. */
    suspend fun poll(): Boolean {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return false

        val since = repository.getLatestThreadServerUpdate()
        when (val response = AiClient.listThreads(settings, since)) {
            is AiCallResult.Failure -> {
                Log.w(TAG, "thread poll failed: ${response.message}")
                return repository.getBusyThreads().isNotEmpty()
            }
            is AiCallResult.Success -> mutex.withLock {
                response.value.threads.forEach { mergeThread(it, null) }
            }
        }
        return repository.getBusyThreads().isNotEmpty()
    }

    private suspend fun mergeThread(server: ServerThread, hint: String?) {
        val local = hint?.let { repository.getThread(it) }
            ?: server.clientThreadId?.let { repository.getThread(it) }
            ?: repository.getThreadByServerId(server.id)
            ?: AiThread(clientThreadId = server.clientThreadId ?: server.id)

        repository.saveThread(
            local.copy(
                serverThreadId = server.id,
                title = server.title.ifBlank { local.title },
                status = server.status,
            progress = server.progress,
                error = server.error,
                serverUpdatedAt = maxOf(local.serverUpdatedAt, server.updatedAt)
            )
        )

        if (server.messages.isNotEmpty()) {
            repository.replaceThreadMessages(
                local.clientThreadId,
                server.messages.map { message ->
                    AiThreadMessage(
                        id = message.id,
                        clientThreadId = local.clientThreadId,
                        role = message.role,
                        text = message.text,
                        imageCount = message.imageCount,
                        proposalsJson = gson.toJson(message.proposals),
                        createdAt = message.createdAt
                    )
                }
            )
        }
    }

    suspend fun cancel(clientThreadId: String) {
        val thread = repository.getThread(clientThreadId) ?: return
        val settings = repository.getAiSettings()
        val serverThreadId = thread.serverThreadId
        if (settings.isConfigured && serverThreadId != null) {
            AiClient.cancelThread(settings, serverThreadId)
        }
        repository.saveThread(thread.copy(status = AiThread.STATUS_IDLE))
    }

    suspend fun deleteThread(clientThreadId: String) {
        val thread = repository.getThread(clientThreadId) ?: return
        val settings = repository.getAiSettings()
        val serverThreadId = thread.serverThreadId
        repository.deleteThread(clientThreadId)
        clearImages(clientThreadId)
        if (settings.isConfigured && serverThreadId != null) {
            AiClient.deleteThread(settings, serverThreadId)
        }
    }

    /** Logs one proposal against today. */
    suspend fun applyProposal(
        messageId: String,
        index: Int,
        proposal: AiProposal,
        savePreset: Boolean
    ) {
        repository.applyProposal(proposal, savePreset)
        repository.markProposalApplied(messageId, index)
        onDataChanged?.invoke()
    }

    // ------------------------------------------------------------------- images

    private fun imageDir(clientThreadId: String): File =
        File(context.cacheDir, "planning/$clientThreadId").apply { mkdirs() }

    private fun storeImages(clientThreadId: String, uris: List<Uri>): List<File> {
        if (uris.isEmpty()) return emptyList()
        clearImages(clientThreadId)
        // Thread attachments are only needed for the one request that carries them —
        // the daemon keeps its own copy for the session.
        val outcome = AiImageStore.storeAll(context, "planning-$clientThreadId", uris)
        return outcome.files
    }

    private fun clearImages(clientThreadId: String) {
        AiImageStore.deleteJobFiles(context, "planning-$clientThreadId")
        imageDir(clientThreadId).deleteRecursively()
    }

    companion object {
        private const val TAG = "PlanningManager"
    }
}
