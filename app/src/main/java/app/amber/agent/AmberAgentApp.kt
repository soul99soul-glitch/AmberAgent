package app.amber.agent
import app.amber.agent.R

import android.app.Application
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import app.amber.common.android.appTempFolder
import app.amber.core.di.agentInfraModule
import app.amber.core.di.agentRuntimeModule
import app.amber.core.di.appModule
import app.amber.core.di.boardModule
import app.amber.core.di.chatModule
import app.amber.core.di.dataSourceModule
import app.amber.core.di.generationModule
import app.amber.core.di.toolExecutionModule
import app.amber.core.di.iCloudModule
import app.amber.core.di.jevModule
import app.amber.core.di.memoryModule
import app.amber.core.di.novelModule
import app.amber.core.di.repositoryModule
import app.amber.core.di.webMountModule
import app.amber.core.di.viewModelModule
import app.amber.core.di.workspaceModule
import app.amber.core.files.FilesManager
import app.amber.core.files.SkillManager
import app.amber.feature.cron.AgentCronManager
import app.amber.feature.reminder.ReminderScheduler
import app.amber.feature.novel.workspace.NovelWorkspaceRestoreBridge
import app.amber.feature.chat.impl.ChatEventProjector
import app.amber.feature.runtime.ColdStartRuntimeRecoveryGate
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.SettingsProviderRescue
import app.amber.core.settings.secret.SettingsSecretMigrator
import app.amber.core.nativepath.NativePathBootstrap
import app.amber.core.memory.dream.MemoryDreamScheduler
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.feature.board.collector.NotificationSignalCollector
import app.amber.feature.board.hotlist.HotListScheduler
import app.amber.feature.board.worker.BoardScheduler
import app.amber.core.service.ChatService
import app.amber.core.utils.CrashHandler
import app.amber.core.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "AmberAgentApp"
private const val DEFERRED_STARTUP_FALLBACK_DELAY_MS = 1_500L

class AmberAgentApp : Application() {
    private val postFirstFrameInitStarted = AtomicBoolean(false)

    private val backgroundSchedulers by lazy {
        AppBackgroundSchedulers(
            scope = get<AppScope>(),
            settings = { get<SettingsAggregator>() },
            cron = { get<AgentCronManager>() },
            reminders = { get<ReminderScheduler>() },
            memory = { get<MemoryDreamScheduler>() },
            notificationCollector = { get<NotificationSignalCollector>() },
            boardScheduler = { get<BoardScheduler>() },
            hotList = { get<HotListScheduler>() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        ensureFirebaseInitialized()
        startKoin {
            androidLogger()
            androidContext(this@AmberAgentApp)
            modules(appModule, chatModule, memoryModule, iCloudModule, webMountModule, agentRuntimeModule, agentInfraModule, boardModule, workspaceModule, novelModule, viewModelModule, dataSourceModule, generationModule, toolExecutionModule, repositoryModule, jevModule)
            if (SyncArchiveManager.hasInterruptedFileRestore(this@AmberAgentApp)) {
                // Eager singletons start after this block; workers must also wait
                // until files match the last committed database snapshot.
                koin.get<NovelWorkspaceRestoreBridge>()
                runBlocking(Dispatchers.IO) {
                    koin.get<SyncArchiveManager>().recoverInterruptedRestore()
                }
                // Archive restore may have replaced persisted theme settings; force
                // the next cold start to re-probe the minimal appearance snapshot.
                clearStartupThemeSnapshotCache()
            }
            workManagerFactory()
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Keep the cold-start runtime recovery barrier eager so agent run admission
        // never waits for UI first-frame callbacks.
        recoverInterruptedAgentRuns()
        if (StandaloneSurfaces.current == StandaloneSurfaces.NOVEL) {
            recoverInterruptedNovelJobs()
        }
        if (StandaloneSurfaces.current == StandaloneSurfaces.DEEP_READ) {
            seedStandaloneDefaults()
        }

        // Move Remote Config + NativePath switch installation off the main thread;
        // every native switch falls back safely to JVM until installed.
        initRemoteConfigAndNativePathsAsync()

        // Attach best-effort app-level cleanup for singleton services that own process lifecycle observers.
        registerChatServiceCleanup()

        // Non-first-frame warmups and background schedulers run once after the first
        // drawn frame, with a bounded fallback so non-Activity process launches
        // (FCM, WorkManager, services) still execute them reliably.
        scheduleDeferredStartupFallback()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    /**
     * Called once after [RouteActivity] draws its first frame to kick off
     * non-critical background warmups without competing with cold-start rendering.
     */
    internal fun notifyFirstFrameRendered() {
        runDeferredStartupOnce()
    }

    private fun scheduleDeferredStartupFallback() {
        get<AppScope>().launch(Dispatchers.Default) {
            delay(DEFERRED_STARTUP_FALLBACK_DELAY_MS)
            runDeferredStartupOnce()
        }
    }

    private fun runDeferredStartupOnce() {
        if (!postFirstFrameInitStarted.compareAndSet(false, true)) return

        // delete temp files
        deleteTempFiles()

        // sync upload files to DB
        syncManagedFiles()

        if (StandaloneSurfaces.allowsNovel && StandaloneSurfaces.current != StandaloneSurfaces.NOVEL) {
            recoverInterruptedNovelJobs()
        }

        if (StandaloneSurfaces.allowsAgentFeatures) {
            // Resolve the heavy ChatService/bubble dependency graph off the main thread,
            // then start the bubble controller loop on Main.immediate.
            startTaskBubbleControllerDeferred()

            // install bundled agent skills
            installBuiltinSkills()

            // Reschedule persisted mobile cron tasks after app startup.
            backgroundSchedulers.rescheduleCronTasks()
            // Reminder owner is durable and must be reattached after every process start.
            backgroundSchedulers.rescheduleReminders()
        }

        // Migrate the persisted shape before cached-settings rescue can write it back.
        migrateAndRescueSettings()

        if (StandaloneSurfaces.allowsAgentFeatures) {
            // Keep Daydream background review aligned with memory settings.
            backgroundSchedulers.syncMemoryDreamTasks()
        }

        if (StandaloneSurfaces.allowsBoard) {
            // Start Today Board notification collector if enabled.
            backgroundSchedulers.startBoardNotificationCollector()

            // ProcessLifecycleOwner observer registration must run on the main thread.
            get<AppScope>().launch(Dispatchers.Main.immediate) {
                backgroundSchedulers.syncTodayBoardScheduler()
            }
            backgroundSchedulers.syncHotListScheduler()
        }

        // Increment launch count after first frame so its DataStore write does not
        // trigger an immediate second full-settings decode during cold start.
        incrementLaunchCount()
    }

    private fun initRemoteConfigAndNativePathsAsync() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FirebaseRemoteConfig>().apply {
                    setConfigSettingsAsync(remoteConfigSettings {
                        minimumFetchIntervalInSeconds = 1800
                    })
                    setDefaultsAsync(R.xml.remote_config_defaults)
                    fetchAndActivate()
                }
            }.onFailure { Log.w(TAG, "FirebaseRemoteConfig init failed", it) }

            // Install the existing user-pref and Remote Config switches off the main thread.
            // Report installation failures so packaged JNI availability is not mistaken for activation.
            runCatching { get<NativePathBootstrap>().install() }
                .onFailure {
                    Log.e(TAG, "NativePathBootstrap.install failed; native path stays disabled", it)
                    runCatching { Firebase.crashlytics.recordException(it) }
                }
        }
    }

    private fun startTaskBubbleControllerDeferred() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val controller = get<app.amber.feature.bubble.AgentTaskBubbleController>()
                withContext(Dispatchers.Main.immediate) {
                    controller.start()
                }
            }.onFailure { Log.w(TAG, "start task bubble controller failed", it) }
        }
    }

    private fun clearStartupThemeSnapshotCache() {
        getSharedPreferences(STARTUP_THEME_PREFS_NAME, MODE_PRIVATE).edit {
            remove(STARTUP_THEME_SNAPSHOT_KNOWN_PREF)
            remove(STARTUP_THEME_REQUIRES_CUSTOM_DISPLAY_PREF)
        }
    }

    /**
     * The standalone deep-read app IS the board — seed `todayBoard.enabled` on
     * first launch so its home surface, manual refresh and schedulers all work
     * without a settings detour. One-shot: a later user toggle is respected.
     */
    private fun seedStandaloneDefaults() {
        if (StandaloneSurfaces.current != StandaloneSurfaces.DEEP_READ) return
        val prefs = getSharedPreferences("standalone_defaults", MODE_PRIVATE)
        if (prefs.getBoolean("board_enabled_seeded", false)) return
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val store = get<SettingsAggregator>()
                store.settingsFlow.first { !it.init }
                store.update { current ->
                    current.copy(
                        agentRuntime = current.agentRuntime.copy(
                            todayBoard = current.agentRuntime.todayBoard.copy(enabled = true),
                        ),
                    )
                }
            }.onSuccess {
                prefs.edit().putBoolean("board_enabled_seeded", true).apply()
            }.onFailure { Log.e(TAG, "standalone board seed failed", it) }
        }
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

    private fun recoverInterruptedNovelJobs() {
        get<AppScope>().launch(Dispatchers.IO) {
            val repository = get<app.amber.feature.novelworkspace.NovelWorkspaceProjectRepository>()
            val controller = get<app.amber.feature.novel.workspace.NovelWorkspaceGhostwriteController>()
            for (project in repository.listProjects()) {
                try {
                    controller.reconcile(repository.projectDirectory(project.id))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Novel job recovery deferred for ${project.id}", error)
                }
            }
        }
    }

    private fun recoverInterruptedAgentRuns() {
        val recoveryGate = get<ColdStartRuntimeRecoveryGate>()
        get<AppScope>().launch(Dispatchers.IO) {
            AppRuntimeRecovery(
                gate = recoveryGate,
                recoverDurableRuns = {
                    val flags = get<app.amber.core.settings.CapabilityFlags>()
                    if (
                        flags.isEnabled(app.amber.core.settings.Capability.DurableToolEffects) &&
                        flags.isEnabled(app.amber.core.settings.Capability.TypedRunTerminal)
                    ) {
                        get<app.amber.feature.runtime.RunRecoveryService>().recover()
                    }
                },
                replayChatEvents = {
                    get<ChatEventProjector>().replayUnfinished()
                    get<app.amber.core.service.LocalAsyncTaskCoordinator>().recoverInterruptedTasks()
                },
                recoverJsCells = {
                    val flags = get<app.amber.core.settings.CapabilityFlags>()
                    if (flags.isEnabled(app.amber.core.settings.Capability.JSCellRuntime)) {
                        get<app.amber.feature.jscell.JsCellRuntime>().recoverFromColdStart()
                    }
                },
                reportFailure = { message, error -> Log.w(TAG, message, error) },
            ).recover()
        }
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

    private fun migrateAndRescueSettings() {
        get<AppScope>().launch(Dispatchers.IO) {
            val migrationVersion = runCatching { get<SettingsSecretMigrator>().migrateIfNeeded() }
                .onFailure {
                    Log.e(TAG, "Secret migration failed; legacy plaintext kept (rerunnable)", it)
                }
                .getOrNull()
            if (migrationVersion == SettingsSecretMigrator.MIGRATION_FAILED) {
                Log.w(TAG, "Secret migration deferred; checking cached provider rescue without altering legacy data")
            }
            runCatching { get<SettingsProviderRescue>().rescueIfNeeded() }
                .onFailure { Log.e(TAG, "Settings rescue failed", it) }
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

    /**
     * Keep the BoardScheduler aligned with user settings. Also wires foreground
     * compensation: when the app returns to the foreground after a long gap (>= the
     * configured threshold), trigger a one-shot board run so users don't see stale
     * content after leaving the app for a while.
     */

    override fun onTerminate() {
        cleanupChatService()
        super.onTerminate()
        get<AppScope>().cancel()
    }

    private fun ensureFirebaseInitialized() {
        if (FirebaseApp.getApps(this).isNotEmpty()) return
        if (!BuildConfig.FIREBASE_LOCAL_FALLBACK_ALLOWED) {
            val message = "Firebase config is missing for ${BuildConfig.APPLICATION_ID}; release builds require a real google-services client."
            Log.e(TAG, message)
            error(message)
        }
        val options = FirebaseOptions.Builder()
            .setApplicationId("1:000000000000:android:0000000000000000000000")
            .setApiKey("local-firebase-disabled")
            .setProjectId("amberagent-local")
            .build()
        FirebaseApp.initializeApp(this, options)
        Log.w(TAG, "FirebaseApp initialized with local fallback options; google-services config is missing for this package.")
    }
}

typealias AppScope = app.amber.core.infra.AppScope
