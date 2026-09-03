package com.macropad.app.ai

import android.util.Log
import com.macropad.app.data.repository.MacroRepository
import com.macropad.app.net.AiCallResult
import com.macropad.app.net.AiClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps preset search keywords topped up.
 *
 * Runs in the background whenever the Presets screen opens: tagging a batch takes
 * the better part of a minute, which is fine for something nobody is waiting on,
 * and disastrous if it sat behind the search box instead.
 */
class PresetTagger(private val repository: MacroRepository) {

    private val mutex = Mutex()
    private var lastAttempt = 0L

    suspend fun refreshIfNeeded() {
        val settings = repository.getAiSettings()
        if (!settings.isConfigured) return

        // Don't retry a failing server on every visit to the screen.
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_INTERVAL_MS) return

        if (!mutex.tryLock()) return
        try {
            val untagged = repository.getUntaggedPresets()
            if (untagged.isEmpty()) return
            lastAttempt = now

            // A batch at a time: the whole list in one prompt would be slower to come
            // back and lose everything if it failed.
            untagged.chunked(BATCH_SIZE).forEach { batch ->
                when (val result = AiClient.tagPresets(settings, batch)) {
                    is AiCallResult.Failure -> {
                        Log.w(TAG, "preset tagging failed: ${result.message}")
                        return
                    }
                    is AiCallResult.Success -> {
                        result.value.forEach { (id, keywords) ->
                            id.toLongOrNull()?.let { presetId ->
                                repository.saveSearchTags(presetId, keywords)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "preset tagging error", e)
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        private const val TAG = "PresetTagger"
        private const val BATCH_SIZE = 25
        private const val RETRY_INTERVAL_MS = 5 * 60 * 1000L
    }
}
