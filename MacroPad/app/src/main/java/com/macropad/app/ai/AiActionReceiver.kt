package com.macropad.app.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.macropad.app.MacroPadApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles the notification actions: inline answers, skipping a question, and
 * cancelling a running job from the ongoing notification.
 */
class AiActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val clientJobId = intent.getStringExtra(EXTRA_CLIENT_JOB_ID) ?: return
        val questionId = intent.getStringExtra(EXTRA_QUESTION_ID)
        val app = context.applicationContext as? MacroPadApplication ?: return
        val manager = app.aiSyncManager

        // The work outlives onReceive, so hold a wake lock via goAsync().
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_REPLY -> {
                        val reply = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(AiNotifications.KEY_REPLY)
                            ?.toString()
                            ?.trim()
                        if (!reply.isNullOrEmpty() && questionId != null) {
                            manager.recordAnswer(clientJobId, questionId, reply)
                        }
                    }
                    ACTION_SKIP -> manager.skipQuestions(clientJobId)
                    ACTION_CANCEL -> manager.cancelJob(clientJobId)
                }
            } catch (e: Exception) {
                // Nothing useful to show from a receiver; the job list carries the state.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.macropad.app.AI_REPLY"
        const val ACTION_SKIP = "com.macropad.app.AI_SKIP"
        const val ACTION_CANCEL = "com.macropad.app.AI_CANCEL"

        const val EXTRA_CLIENT_JOB_ID = "client_job_id"
        const val EXTRA_QUESTION_ID = "question_id"
    }
}
