package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.di.agentInfraModule
import me.rerere.rikkahub.di.agentRuntimeModule
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.boardModule
import me.rerere.rikkahub.di.chatModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.iCloudModule
import me.rerere.rikkahub.di.memoryModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.webMountModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.di.workspaceModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.agent.cron.AgentCronManager
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.prefs.SettingsProviderRescue
import me.rerere.rikkahub.data.memory.dream.MemoryDreamScheduler
import me.rerere.rikkahub.data.agent.board.collector.NotificationSignalCollector
import me.rerere.rikkahub.data.agent.board.hotlist.HotListScheduler
import me.rerere.rikkahub.data.agent.board.worker.BoardScheduler
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update_v3"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID = "screen_capture"
const val MEMORY_NOTIFICATION_CHANNEL_ID = "memory_tasks"
const val FEISHU_DOC_CHANGE_CHANNEL_ID = "feishu_doc_change"
const val BOARD_NOTIFICATION_CHANNEL_ID = "today_board"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, chatModule, memoryModule, iCloudModule, webMountModule, agentRuntimeModule, agentInfraModule, boardModule, workspaceModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // delete temp files
        deleteTempFiles()

        // sync upload files to DB
        syncManagedFiles()

        // install bundled agent skills
        installBuiltinSkills()

        // Init remote config
        get<FirebaseRemoteConfig>().apply {
            setConfigSettingsAsync(remoteConfigSettings {
                minimumFetchIntervalInSeconds = 1800
            })
            setDefaultsAsync(R.xml.remote_config_defaults)
            fetchAndActivate()
        }

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Reschedule persisted mobile cron tasks after app startup.
        rescheduleCronTasks()

        // Repair provider settings if the prefs-split startup race persisted defaults.
        rescueProviderSettingsIfNeeded()

        // Keep Daydream background review aligned with memory settings.
        syncMemoryDreamTasks()

        // Start Today Board notification collector if enabled.
        startBoardNotificationCollector()

        // Sync Today Board scheduler with settings + foreground-compensation hook.
        syncTodayBoardScheduler()
        syncHotListScheduler()

        // Attach best-effort app-level cleanup for singleton services that own process lifecycle observers.
        registerChatServiceCleanup()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun registerChatServiceCleanup() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) {
                    cleanupChatService()
                }
            }
        )
    }

    private fun cleanupChatService() {
        runCatching { get<ChatService>().cleanup() }
            .onFailure { Log.w(TAG, "cleanupChatService failed", it) }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsAggregator>()
                // [M1.1.8d-3-fix] filterNot { init } 等价于 SettingsStore.settingsFlowRaw.first()
                // 的冷流挂起语义；Aggregator 的 settingsFlow 是 MutableStateFlow(dummy()) ，
                // 不过滤会立刻拿到 dummy 然后 update() 被 dummy guard 拒绝（launchCount 永 0）。
                val current = store.settingsFlow.filterNot { it.init }.first()
                store.updateLaunchCount(current.launchCount + 1)
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlow.filterNot { it.init }.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun rescueProviderSettingsIfNeeded() {
        get<AppScope>().launch(Dispatchers.IO) {
            get<SettingsProviderRescue>().rescueIfNeeded()
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun installBuiltinSkills() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<SkillManager>().installBuiltinSkillsIfMissing()
            }.onFailure {
                Log.e(TAG, "installBuiltinSkills failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsAggregator>().settingsFlow.filterNot { it.init }.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun rescheduleCronTasks() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<AgentCronManager>().rescheduleAll()
            }.onFailure {
                Log.e(TAG, "rescheduleCronTasks failed", it)
            }
        }
    }

    private fun syncMemoryDreamTasks() {
        get<AppScope>().launch(Dispatchers.IO) {
            val scheduler = get<MemoryDreamScheduler>()
            val settingsStore = get<SettingsAggregator>()
            settingsStore.settingsFlow
                .map { it.agentRuntime.memoryWorker }
                .distinctUntilChanged()
                .collect {
                    runCatching {
                        scheduler.sync()
                    }.onFailure { error ->
                        Log.e(TAG, "syncMemoryDreamTasks failed", error)
                    }
                }
        }
    }

    private fun startBoardNotificationCollector() {
        get<AppScope>().launch(Dispatchers.IO) {
            val settingsStore = get<SettingsAggregator>()
            val collector = get<NotificationSignalCollector>()
            settingsStore.settingsFlow
                .map { it.agentRuntime.todayBoard.enabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        collector.start()
                    } else {
                        collector.stop()
                    }
                }
        }
    }

    /**
     * Keep the BoardScheduler aligned with user settings. Also wires foreground
     * compensation: when the app returns to the foreground after a long gap (>= the
     * configured threshold), trigger a one-shot board run so users don't see stale
     * content after leaving the app for a while.
     */
    private fun syncTodayBoardScheduler() {
        get<AppScope>().launch(Dispatchers.IO) {
            val settingsStore = get<SettingsAggregator>()
            val scheduler = get<BoardScheduler>()
            settingsStore.settingsFlow
                .map { it.agentRuntime.todayBoard }
                .distinctUntilChanged()
                .collect {
                    runCatching { scheduler.sync(settingsStore.settingsFlow.value) }
                        .onFailure { Log.e(TAG, "syncTodayBoardScheduler failed", it) }
                }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : LifecycleEventObserver {
                private val lastForegroundCompensationMs = java.util.concurrent.atomic.AtomicLong(0L)

                override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                    if (event != Lifecycle.Event.ON_START) return
                    get<AppScope>().launch(Dispatchers.IO) {
                        runCatching {
                            val board = get<SettingsAggregator>().settingsFlow.value.agentRuntime.todayBoard
                            if (!board.enabled) return@runCatching
                            // FOREGROUND_ONLY users explicitly chose no background work —
                            // respect that even for app-start compensation.
                            if (board.backgroundStrategy ==
                                me.rerere.rikkahub.data.agent.board.TodayBoardBackgroundStrategy.FOREGROUND_ONLY
                            ) return@runCatching
                            val now = System.currentTimeMillis()
                            val last = lastForegroundCompensationMs.get()
                            if (now - last < board.foregroundCompensationGapMs) return@runCatching
                            // CAS guards against two rapid ON_START events passing the gap check.
                            if (!lastForegroundCompensationMs.compareAndSet(last, now)) return@runCatching
                            get<BoardScheduler>().runOnce()
                            // Also trigger doc radar check on foreground return
                            runCatching { get<me.rerere.rikkahub.data.agent.office.radar.DocRadar>().runOnce() }
                        }.onFailure { Log.e(TAG, "foreground compensation failed", it) }
                    }
                }
            },
        )
    }

    private fun syncHotListScheduler() {
        get<AppScope>().launch(Dispatchers.IO) {
            val settingsStore = get<SettingsAggregator>()
            val scheduler = get<HotListScheduler>()
            settingsStore.settingsFlow
                .map { it.agentRuntime.todayBoard }
                .distinctUntilChanged()
                .collect {
                    runCatching { scheduler.sync(settingsStore.settingsFlow.value) }
                        .onFailure { Log.e(TAG, "syncHotListScheduler failed", it) }
                }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_DEFAULT
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val screenCaptureChannel = NotificationChannelCompat
            .Builder(SCREEN_CAPTURE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_screen_capture))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(screenCaptureChannel)

        val memoryChannel = NotificationChannelCompat
            .Builder(MEMORY_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_memory_tasks))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(memoryChannel)

        val feishuDocChangeChannel = NotificationChannelCompat
            .Builder(FEISHU_DOC_CHANGE_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(getString(R.string.notification_channel_feishu_doc_change))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(feishuDocChangeChannel)

        val boardChannel = NotificationChannelCompat
            .Builder(BOARD_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("今日看板")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(boardChannel)
    }

    override fun onTerminate() {
        cleanupChatService()
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
