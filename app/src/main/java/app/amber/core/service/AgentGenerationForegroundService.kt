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
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import app.amber.core.utils.NotificationUtil
import java.util.concurrent.ConcurrentHashMap

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
                activeGenerations[conversationId] = title.ifBlank { getString(R.string.app_name) }
                startForegroundCompat(buildNotification(conversationId, title, content))
            }

            ACTION_STOP -> {
                val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
                activeGenerations.remove(conversationId)
                if (activeGenerations.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                } else {
                    val nextId = activeGenerations.keys.first()
                    startForegroundCompat(
                        buildNotification(
                            conversationId = nextId,
                            title = activeGenerations[nextId].orEmpty(),
                            content = getString(R.string.generation_keepalive_content),
                        )
                    )
                }
            }

            else -> {
                if (activeGenerations.isEmpty()) {
                    stopSelf(startId)
                } else {
                    val nextId = activeGenerations.keys.first()
                    startForegroundCompat(
                        buildNotification(
                            conversationId = nextId,
                            title = activeGenerations[nextId].orEmpty(),
                            content = getString(R.string.generation_keepalive_content),
                        )
                    )
                }
            }
        }
        return START_STICKY
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
        conversationId: String,
        title: String,
        content: String,
    ): Notification {
        return NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.amberagent_live_status_icon)
            .setContentTitle(title.ifBlank { getString(R.string.generation_keepalive_title) })
            .setContentText(content.ifBlank { getString(R.string.generation_keepalive_content) })
            .setContentIntent(buildLaunchPendingIntent(conversationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildLaunchPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId)
        }
        return PendingIntent.getActivity(
            this,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.GENERATION_KEEPALIVE_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.GENERATION_KEEPALIVE_STOP"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CONTENT = "content"
        private const val NOTIFICATION_ID = 4106
        private val activeGenerations = ConcurrentHashMap<String, String>()

        fun start(
            context: Context,
            conversationId: String,
            title: String,
            content: String,
        ): Boolean {
            if (!NotificationUtil.hasNotificationPermission(context)) return false
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
    }
}
