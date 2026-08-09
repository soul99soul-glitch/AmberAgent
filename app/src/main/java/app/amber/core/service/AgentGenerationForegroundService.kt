package app.amber.core.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.amber.agent.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import app.amber.agent.R
import app.amber.agent.RouteActivity
import app.amber.core.utils.NotificationUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

class AgentGenerationForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                if (conversationId.isBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
                val generation = ActiveGeneration(
                    ownerKey = chatGenerationOwnerKey(conversationId),
                    title = title.ifBlank { getString(R.string.app_name) },
                    destination = GenerationDestination.Chat(conversationId),
                )
                activeGenerations[generation.ownerKey] = generation
                startForegroundCompat(buildNotification(generation, title, content))
            }

            ACTION_START_NOVEL -> {
                val projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty()
                val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
                val leaseToken = intent.getStringExtra(EXTRA_LEASE_TOKEN).orEmpty()
                if (projectId.isBlank() || runId.isBlank() || leaseToken.isBlank()) {
                    restoreForegroundOrStop(startId)
                    return START_NOT_STICKY
                }
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
                val generation = ActiveGeneration(
                    ownerKey = novelGenerationOwnerKey(runId),
                    leaseToken = leaseToken,
                    title = title.ifBlank { getString(R.string.app_name) },
                    destination = GenerationDestination.Novel(projectId = projectId, runId = runId),
                )
                activeGenerations[generation.ownerKey] = generation
                startForegroundCompat(buildNotification(generation, title, content))
            }

            ACTION_STOP -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                activeGenerations.removeChatGeneration(conversationId)
                restoreForegroundOrStop(startId)
            }

            ACTION_STOP_NOVEL -> {
                val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
                val leaseToken = intent.getStringExtra(EXTRA_LEASE_TOKEN).orEmpty()
                activeGenerations.removeNovelGenerationIfLeaseMatches(
                    runId = runId,
                    leaseToken = leaseToken,
                    leaseTokenOf = ActiveGeneration::leaseToken,
                )
                restoreForegroundOrStop(startId)
            }

            else -> restoreForegroundOrStop(startId)
        }
        return START_STICKY
    }

    private fun restoreForegroundOrStop(startId: Int) {
        val next = activeGenerations.values.firstOrNull()
        if (next == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return
        }
        startForegroundCompat(
            buildNotification(
                generation = next,
                title = next.title,
                content = getString(R.string.generation_keepalive_content),
            )
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        generation: ActiveGeneration,
        title: String,
        content: String,
    ): Notification {
        return NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(title.ifBlank { getString(R.string.generation_keepalive_title) })
            .setContentText(content.ifBlank { getString(R.string.generation_keepalive_content) })
            .setContentIntent(buildLaunchPendingIntent(generation))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildLaunchPendingIntent(generation: ActiveGeneration): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            when (val destination = generation.destination) {
                is GenerationDestination.Chat -> putExtra("conversationId", destination.conversationId)
                is GenerationDestination.Novel -> {
                    putExtra(RouteActivity.EXTRA_OPEN_NOVEL_PROJECT_ID, destination.projectId)
                    putExtra(RouteActivity.EXTRA_OPEN_NOVEL_RUN_ID, destination.runId)
                }
            }
        }
        return PendingIntent.getActivity(
            this,
            generation.ownerKey.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private data class ActiveGeneration(
        val ownerKey: String,
        val leaseToken: String = ownerKey,
        val title: String,
        val destination: GenerationDestination,
    )

    private sealed interface GenerationDestination {
        data class Chat(val conversationId: String) : GenerationDestination

        data class Novel(
            val projectId: String,
            val runId: String,
        ) : GenerationDestination
    }

    companion object {
        private const val ACTION_START = "app.amber.agent.action.GENERATION_KEEPALIVE_START"
        private const val ACTION_STOP = "app.amber.agent.action.GENERATION_KEEPALIVE_STOP"
        private const val ACTION_START_NOVEL = "app.amber.agent.action.NOVEL_GENERATION_KEEPALIVE_START"
        private const val ACTION_STOP_NOVEL = "app.amber.agent.action.NOVEL_GENERATION_KEEPALIVE_STOP"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_RUN_ID = "run_id"
        private const val EXTRA_LEASE_TOKEN = "lease_token"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CONTENT = "content"
        private const val NOTIFICATION_ID = 4106
        private val activeGenerations = ConcurrentHashMap<String, ActiveGeneration>()

        fun start(
            context: Context,
            conversationId: String,
            title: String,
            content: String,
        ): Boolean {
            if (!NotificationUtil.canShowNotification(context, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)) {
                return false
            }
            val intent = Intent(context, AgentGenerationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse { error ->
                Log.w("GenerationKeepAlive", "Unable to start generation foreground service", error)
                false
            }
        }

        fun stop(context: Context, conversationId: String) {
            val intent = Intent(context, AgentGenerationForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            runCatching {
                context.startService(intent)
            }.onFailure { error ->
                Log.w("GenerationKeepAlive", "Unable to stop generation foreground service", error)
            }
        }

        fun startNovel(
            context: Context,
            projectId: String,
            runId: String,
            leaseToken: String,
            title: String,
            content: String,
        ): Boolean {
            if (projectId.isBlank() || runId.isBlank() || leaseToken.isBlank()) return false
            if (!NotificationUtil.canShowNotification(context, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)) {
                return false
            }
            val intent = Intent(context, AgentGenerationForegroundService::class.java).apply {
                action = ACTION_START_NOVEL
                putExtra(EXTRA_PROJECT_ID, projectId)
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(EXTRA_LEASE_TOKEN, leaseToken)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CONTENT, content)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.getOrElse { error ->
                Log.w("GenerationKeepAlive", "Unable to start novel generation foreground service", error)
                false
            }
        }

        fun stopNovel(context: Context, runId: String, leaseToken: String) {
            if (runId.isBlank() || leaseToken.isBlank()) return
            val intent = Intent(context, AgentGenerationForegroundService::class.java).apply {
                action = ACTION_STOP_NOVEL
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(EXTRA_LEASE_TOKEN, leaseToken)
            }
            runCatching {
                context.startService(intent)
            }.onFailure { error ->
                Log.w("GenerationKeepAlive", "Unable to stop novel generation foreground service", error)
            }
        }
    }
}

internal fun chatGenerationOwnerKey(conversationId: String): String = "chat:$conversationId"

internal fun novelGenerationOwnerKey(runId: String): String = "novel:$runId"

internal fun <T> MutableMap<String, T>.removeChatGeneration(conversationId: String): T? =
    remove(chatGenerationOwnerKey(conversationId))

internal fun <T> ConcurrentMap<String, T>.removeNovelGenerationIfLeaseMatches(
    runId: String,
    leaseToken: String,
    leaseTokenOf: (T) -> String,
): Boolean {
    if (runId.isBlank() || leaseToken.isBlank()) return false
    val ownerKey = novelGenerationOwnerKey(runId)
    while (true) {
        val current = get(ownerKey) ?: return false
        if (leaseTokenOf(current) != leaseToken) return false
        if (remove(ownerKey, current)) return true
    }
}
