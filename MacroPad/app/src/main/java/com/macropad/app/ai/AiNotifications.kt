package com.macropad.app.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.macropad.app.MainActivity
import com.macropad.app.R
import com.macropad.app.data.entity.AiJob
import com.macropad.app.data.entity.AiQuestion
import com.macropad.app.data.entity.AiResult

/**
 * Notifications for AI estimation jobs.
 *
 * The follow-up notification is the whole point of the feature's ergonomics: the
 * answer is typed inline, from the shade, without opening the app.
 */
object AiNotifications {

    const val CHANNEL_PROGRESS = "ai_progress"
    const val CHANNEL_QUESTIONS = "ai_questions"
    const val CHANNEL_RESULTS = "ai_results"

    const val KEY_REPLY = "ai_reply_text"

    /** Stable id for the foreground service's ongoing notification. */
    const val ID_FOREGROUND = 4200

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "AI jobs in progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while a macro estimate is being worked out" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUESTIONS,
                "AI follow-up questions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "A quick question that would sharpen an estimate" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULTS,
                "AI estimates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Finished estimates added to your day" }
        )
    }

    // ------------------------------------------------------------------ builders

    /** Ongoing notification for the polling service; doubles as the status indicator. */
    fun buildForegroundNotification(
        context: Context,
        activeCount: Int,
        singleJob: AiJob?
    ): Notification {
        val title = when {
            activeCount <= 0 -> "Finishing up"
            activeCount == 1 -> "Estimating macros…"
            else -> "Estimating macros ($activeCount jobs)"
        }
        // Prefer what it is actually doing right now. A notification that says
        // "Researching what you logged" for two minutes looks identical to a hang,
        // which is how it gets reported.
        val text = singleJob?.progress?.takeIf { it.isNotBlank() }
            ?: singleJob?.promptText?.takeIf { it.isNotBlank() }
            ?: "Researching what you logged"

        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // Progress lines and the reply itself run past one line.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context, MainActivity.ROUTE_AI_LOG))

        if (singleJob != null) {
            builder.addAction(
                0,
                "Cancel",
                broadcast(
                    context,
                    AiActionReceiver.ACTION_CANCEL,
                    singleJob.clientJobId,
                    null,
                    requestCode = singleJob.clientJobId.hashCode()
                )
            )
        }
        return builder.build()
    }

    /** One question at a time, answered inline. */
    fun showQuestion(context: Context, job: AiJob, question: AiQuestion, remaining: Int) {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Type your answer")
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            0,
            "Answer",
            broadcast(
                context,
                AiActionReceiver.ACTION_REPLY,
                job.clientJobId,
                question.id,
                requestCode = (job.clientJobId + question.id).hashCode()
            )
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()

        val skipAction = NotificationCompat.Action.Builder(
            0,
            "Keep estimate",
            broadcast(
                context,
                AiActionReceiver.ACTION_SKIP,
                job.clientJobId,
                question.id,
                requestCode = (job.clientJobId + question.id + "skip").hashCode()
            )
        ).build()

        val subtitle = buildString {
            append(job.promptText.takeIf { it.isNotBlank() } ?: "AI estimate")
            if (remaining > 1) append(" · $remaining questions")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(question.question)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(question.question))
            .setSubText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.ROUTE_AI_LOG))
            .addAction(replyAction)
            .addAction(skipAction)
            .build()

        notify(context, questionNotificationId(job.clientJobId, question.id), notification)
    }

    fun showResult(context: Context, job: AiJob, result: AiResult) {
        val total = result.total
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(result.preset_name.ifBlank { "Estimate added" })
            .setContentText(
                "${total.calories} kcal · ${total.protein_g}p ${total.carbs_g}c ${total.fat_g}f"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.summary))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.ROUTE_AI_LOG))
            .build()
        notify(context, resultNotificationId(job.clientJobId), notification)
    }

    fun showFailure(context: Context, job: AiJob, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Estimate failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.ROUTE_AI_LOG))
            .build()
        notify(context, resultNotificationId(job.clientJobId), notification)
    }

    fun cancelQuestion(context: Context, clientJobId: String, questionId: String) {
        NotificationManagerCompat.from(context)
            .cancel(questionNotificationId(clientJobId, questionId))
    }

    fun cancelAllForJob(context: Context, clientJobId: String, result: AiResult?) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(resultNotificationId(clientJobId))
        result?.follow_up_questions?.forEach {
            manager.cancel(questionNotificationId(clientJobId, it.id))
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; the in-app job list still shows everything.
        }
    }

    private fun questionNotificationId(clientJobId: String, questionId: String): Int =
        ("q:$clientJobId:$questionId").hashCode()

    private fun resultNotificationId(clientJobId: String): Int = ("r:$clientJobId").hashCode()

    private fun openAppIntent(context: Context, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, route)
        }
        return PendingIntent.getActivity(
            context,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun broadcast(
        context: Context,
        action: String,
        clientJobId: String,
        questionId: String?,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, AiActionReceiver::class.java).apply {
            this.action = action
            putExtra(AiActionReceiver.EXTRA_CLIENT_JOB_ID, clientJobId)
            questionId?.let { putExtra(AiActionReceiver.EXTRA_QUESTION_ID, it) }
        }
        // Inline reply needs a mutable PendingIntent so the system can attach the text.
        val flags = if (action == AiActionReceiver.ACTION_REPLY) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
