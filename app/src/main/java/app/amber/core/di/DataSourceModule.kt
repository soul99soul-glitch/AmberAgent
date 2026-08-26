package app.amber.core.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import app.amber.core.agent.store.AgentRuntimeDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.google.GoogleGeminiOAuthClient
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.providers.ClaudeProvider
import app.amber.ai.provider.providers.GoogleProvider
import app.amber.ai.provider.providers.OpenAIProvider
import app.amber.common.http.AcceptLanguageBuilder
import app.amber.agent.BuildConfig
import app.amber.core.ai.AIRequestInterceptor
import app.amber.core.ai.RequestLoggingInterceptor
import app.amber.core.ai.ChatRunCoordinator
import app.amber.core.ai.Generator
import app.amber.core.ai.tools.LocalTools
import app.amber.core.ai.transformers.TemplateTransformer
import app.amber.feature.miniapp.MiniAppAiBridge
import app.amber.feature.miniapp.MiniAppSearchBridge
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.NativePathPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.SettingsProviderRescue
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SettingsSecretMigrator
import app.amber.core.settings.secret.createAndroidSecretStore
import app.amber.core.nativepath.NativePathBootstrap
import app.amber.core.settings.settingsStore
import app.amber.agent.data.db.AppDatabase
import app.amber.agent.data.workspace.ArtifactRepository
import app.amber.agent.data.db.fts.MessageFtsManager
import app.amber.agent.data.db.fts.SimpleDictManager
import app.amber.core.ai.mcp.McpManager
import app.amber.feature.runtime.AgentToolDispatcher
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.runtime.defaultToolInvocationHooks
import app.amber.feature.jscell.JsCellRuntime
import app.amber.feature.jscell.JsCellStore
import app.amber.feature.jscell.QuickJsCellEngine
import app.amber.feature.recipe.RecipeRegistry
import app.amber.feature.runtime.PermissionDecisionResolver
import app.amber.feature.runtime.RoomRunTerminalStore
import app.amber.feature.runtime.RoomToolEffectLedger
import app.amber.feature.runtime.RunRecoveryService
import app.amber.feature.runtime.RunTerminalStore
import app.amber.feature.runtime.ToolEffectLedger
import app.amber.feature.home.ContinueCandidateAggregator
import app.amber.feature.home.ContinueCandidateSource
import app.amber.feature.home.ContinueDismissStore
import app.amber.feature.home.CouncilContinueSource
import app.amber.feature.home.DeepReadContinueSource
import app.amber.feature.home.ImageGenerationContinueSource
import app.amber.feature.home.MiniAppDraftContinueSource
import app.amber.feature.home.RoomContinueDismissStore
import app.amber.feature.ui.theme.SettingsAggregatorThemeStore
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.feature.ui.theme.ThemeSettingsStore
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.google.GoogleDriveAppDataClient
import app.amber.core.sync.google.GoogleDriveSyncRepository
import app.amber.core.sync.google.GoogleOAuthConfigGate
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.sync.provider.LocalFolderSyncProvider
import app.amber.core.sync.provider.PersistedFolderStore
import app.amber.core.sync.provider.WebDavSyncProvider
import app.amber.search.SearchService
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    // P1-01: 统一 SecretStore（Android Keystore AES/GCM 加密存储）
    single {
        createAndroidSecretStore(get<Context>())
    }

    single {
        SecretRedactor(secretStore = get())
    }

    single {
        SettingsSecretMigrator(
            dataStore = get<Context>().settingsStore,
            secretStore = get(),
            redactor = get(),
        )
    }

    single {
        UIPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        SearchPrefs(dataStore = get<Context>().settingsStore, scope = get(), secretStore = get())
    }

    single {
        AgentPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        ProviderPrefs(dataStore = get<Context>().settingsStore, scope = get(), secretStore = get())
    }

    single {
        ChatPrefs(dataStore = get<Context>().settingsStore, scope = get(), secretStore = get())
    }

    single {
        ExtensionPrefs(dataStore = get<Context>().settingsStore, scope = get(), secretStore = get())
    }

    single {
        NativePathPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    // Capability parity plan (Phase 0): per-capability feature flags, all default off.
    single {
        CapabilityFlags(dataStore = get<Context>().settingsStore)
    }

    // P2-01: capability policies + approval history (Settings DataStore).
    single {
        CapabilityPermissionStore(dataStore = get<Context>().settingsStore, json = get())
    }

    // P4-01: installed declarative recipe registry (Settings DataStore).
    single {
        RecipeRegistry(dataStore = get<Context>().settingsStore, json = get())
    }

    // P4-03: persistent JS cells (Settings DataStore) + runtime. Nested JS
    // tool calls resolve through a strict read-only whitelist — v1 admits
    // get_time_info only; write tools are never reachable from inside JS.
    single {
        JsCellStore(dataStore = get<Context>().settingsStore, json = get())
    }

    single {
        JsCellRuntime(
            store = get(),
            engine = QuickJsCellEngine(),
            nestedToolResolver = { name ->
                runCatching { get<LocalTools>().timeTool }
                    .getOrNull()
                    ?.takeIf { it.name == name }
            },
        )
    }

    single {
        NativePathBootstrap(
            prefs = get(),
            crashlytics = get(),
            remoteConfig = get(),
            scope = get(),
        )
    }

    single {
        SettingsAggregator(
            dataStore = get<Context>().settingsStore,
            uiPrefs = get(),
            searchPrefs = get(),
            agentPrefs = get(),
            providerPrefs = get(),
            chatPrefs = get(),
            extensionPrefs = get(),
            scope = get(),
            secretRedactor = get(),
        )
    }

    single {
        SettingsProviderRescue(
            context = get(),
            settingsStore = get(),
            json = get(),
            secretRedactor = get(),
        )
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "amber_agent")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                    // P8-04: 会话标题 FTS（每个会话一行，索引 title）。纯派生表，
                    // 与 message_fts 同一套增删/重命名/重建维护点同步（MessageFtsManager）。
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS conversation_title_fts USING fts5(
                            title,
                            conversation_id UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null
                        )
                    )
                    options
                }
            )))
            .build()
    }

    single {
        Room.databaseBuilder(get<Context>(), AgentRuntimeDatabase::class.java, "agent_runtime")
            .build()
    }

    single { get<AgentRuntimeDatabase>().agentRuntimeDao() }

    single { TemplateTransformer() }

    single { MiniAppSearchBridge(settingsStore = get()) }

    single { MiniAppAiBridge(context = get(), settingsStore = get(), providerCatalog = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().conversationCompactDao()
    }

    single {
        get<AppDatabase>().conversationContextEventDao()
    }

    single {
        get<AppDatabase>().continueCandidateDismissDao()
    }

    single {
        get<AppDatabase>().themePackageDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().memoryCandidateDao()
    }

    single {
        get<AppDatabase>().memoryEventDao()
    }

    single {
        get<AppDatabase>().memoryDreamPlanDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().messageStatsDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().feishuWatchedDocDao()
    }

    single {
        get<AppDatabase>().feishuDocSnapshotDao()
    }

    single {
        get<AppDatabase>().feishuDocChangeDao()
    }

    single {
        get<AppDatabase>().feishuDocDependencyDao()
    }

    single {
        get<AppDatabase>().boardSignalDao()
    }

    single {
        get<AppDatabase>().boardItemDao()
    }

    single {
        get<AppDatabase>().boardFocusRuleDao()
    }

    single {
        get<AppDatabase>().boardWeightDao()
    }

    single {
        get<AppDatabase>().dailyReviewDao()
    }

    single {
        get<AppDatabase>().hotListDao()
    }

    single {
        get<AppDatabase>().docSubscriptionDao()
    }

    single {
        get<AppDatabase>().docChangeLogDao()
    }

    single {
        get<AppDatabase>().miniAppDao()
    }

    single {
        get<AppDatabase>().miniAppGrantDao()
    }

    single {
        get<AppDatabase>().miniAppVersionDao()
    }

    single {
        get<AppDatabase>().miniAppAuditLogDao()
    }

    single {
        get<AppDatabase>().miniAppSharedDataDao()
    }

    single {
        get<AppDatabase>().toolEffectDao()
    }

    // P3-01: Workspace Artifact Registry (Room v11).
    single {
        get<AppDatabase>().artifactDao()
    }

    // P3-03: durable composer drafts written by the MiniApp host bridge.
    single {
        get<AppDatabase>().conversationDraftDao()
    }

    single {
        app.amber.feature.miniapp.ConversationDraftStore(
            dao = get(),
            conversationDao = get(),
        )
    }

    single {
        app.amber.feature.miniapp.MiniAppWorkspaceWriter(
            artifactRepository = get(),
        )
    }

    // P3-03: real write-back — draft persistence + (authorized) real send via
    // the production ChatService. The send lambda is the only chat coupling.
    single {
        val chatService: app.amber.core.service.ChatService = get()
        app.amber.feature.miniapp.MiniAppConversationWriter(
            draftStore = get(),
            sendMessage = { conversationId, parts ->
                val id = runCatching { kotlin.uuid.Uuid.parse(conversationId) }.getOrNull()
                if (id == null) false else chatService.sendMessage(id, parts)
            },
        )
    }

    single {
        ArtifactRepository(
            dao = get(),
            workspaceManager = get(),
            messageNodeDao = get(),
            conversationDao = get(),
        )
    }

    single {
        get<AppDatabase>().runTerminalDao()
    }

    // P6-01: resume cursor for server-side stored OpenAI Responses (Room,
    // schema v14) — keyed by runId, written write-ahead during streaming.
    single {
        get<AppDatabase>().runResumeDao()
    }

    single<app.amber.ai.provider.ResponseResumeStore> {
        app.amber.feature.runtime.RoomResponseResumeStore(dao = get())
    }

    // P6-01: resolves a run's stored response to provider + API for the
    // recovery worker and the Stop path. Strict official-endpoint gating.
    single<app.amber.feature.runtime.StoredResponseGateway> {
        app.amber.feature.runtime.OpenAIStoredResponseGateway(
            resumeStore = get(),
            settingsStore = get(),
            storedResponseApi = get(),
        )
    }

    // P6-01: Stop-path server cancel — awaited for a decidable outcome;
    // unconfirmed cancels keep the run WAITING_EXTERNAL (never pretend).
    single {
        app.amber.feature.runtime.StoredResponseStopCancel(
            gateway = get(),
            resumeStore = get(),
        )
    }

    // P4-02: persistent thread graph (Room, schema v13) — child thread nodes,
    // queued/delivered/persisted messages and terminal results. Written/read
    // only when the thread_graph_v2 capability flag is on.
    single {
        get<AppDatabase>().threadGraphDao()
    }

    single<app.amber.feature.subagent.ThreadGraphStore> {
        app.amber.feature.runtime.RoomThreadGraphStore(dao = get())
    }

    // P1-02: durable tool effect ledger (Room, same DB as conversations).
    single<ToolEffectLedger> {
        RoomToolEffectLedger(
            dao = get(),
            runTerminalDao = get(),
            json = get(),
        )
    }

    // P1-03: typed run terminal state (Room).
    single<RunTerminalStore> {
        RoomRunTerminalStore(dao = get())
    }

    // P1-02/P1-03: cold-start recovery — reconciles ledger effects and keeps
    // WAITING_USER runs resumable after process death. P6-01: also resolves
    // runs with a stored server-side OpenAI Response (status query + missing
    // event fetch + terminal settle) when the capability is on.
    single {
        RunRecoveryService(
            ledger = get(),
            runTerminalStore = get(),
            conversationRepo = get(),
            json = get(),
            storedResponseGateway = get(),
            capabilityFlags = get(),
            resumeStore = get(),
        )
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get(), appEventBus = get()) }

    single { PermissionDecisionResolver() }

    single {
        AgentToolDispatcher(
            json = get(),
            permissionDecisionResolver = get(),
            hooks = defaultToolInvocationHooks(),
        )
    }

    single {
        ChatRunCoordinator(
            context = get(),
            providerCatalog = get(),
            json = get(),
            memoryRepo = get(),
            memoryRecallStore = get(),
            conversationRepo = get(),
            aiLoggingManager = get(),
            conversationContextEngine = get(),
            toolDispatcher = get(),
            toolEffectLedger = get(),
            capabilityFlags = get(),
            capabilityPermissionStore = get(),
        )
    }
    single<Generator> { get<ChatRunCoordinator>() }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    requestBuilder.addHeader(HttpHeaders.UserAgent, "AmberAgent-Android/${BuildConfig.VERSION_NAME}")
                }

                chain.proceed(requestBuilder.build())
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (contentTypeHeader != null && contentTypeHeader.contains(";")) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor(get()))
            .addInterceptor(AIRequestInterceptor(remoteConfig = get()))
            .apply {
                // Logcat HTTP tracing is debug-only; logcat is readable by adb and
                // crash tooling, so credentials are redacted even there.
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                        redactHeader("Authorization")
                        redactHeader("Proxy-Authorization")
                        redactHeader("Cookie")
                        redactHeader("Set-Cookie")
                        redactHeader("x-api-key")
                        redactHeader("api-key")
                        redactHeader("x-goog-api-key")
                    })
                }
            }
            .build().also { SearchService.init(it, get()) }
    }

    single { OpenAIProvider(client = get(), context = get()) }
    single { GoogleProvider(client = get(), context = get()) }
    single { ClaudeProvider(client = get(), context = get()) }
    single<app.amber.ai.provider.providers.openai.StoredResponseApi> {
        get<OpenAIProvider>().storedResponses
    }
    single {
        ProviderCatalog(
            openAIProvider = get(),
            googleProvider = get(),
            claudeProvider = get(),
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single { OpenAICodexAuthStore(context = get()) }
    single { GoogleGeminiAuthStore(context = get()) }
    single { GoogleGeminiOAuthClient(httpClient = get(), authStore = get()) }

    single {
        SyncArchiveManager(
            context = get(),
            settingsStore = get(),
            database = get(),
            messageFtsManager = get(),
            filesManager = get(),
            webMountOAuthTokenStore = get(),
            openAICodexAuthStore = get(),
            googleGeminiAuthStore = get(),
            json = get(),
            nativePathPrefs = get(),
            secretRedactor = get(),
            deviceBoundBackupKey = get(),
        )
    }

    // P7-02：设备绑定加密的设备秘密（Keystore 保护的随机秘密，独立于凭据 SecretStore）。
    single { app.amber.core.sync.core.DeviceBoundBackupKey.createAndroid(get()) }

    // P7-03：存储占用分析与按时间清理会话。
    single { app.amber.core.storage.StorageAnalyzer(context = get(), database = get()) }
    single { app.amber.core.storage.SessionCleanupManager(context = get(), database = get()) }

    single { LocalBackupRepository(context = get(), syncArchiveManager = get()) }

    // P7-01 SyncProvider 抽象：WebDAV 与本地文件夹为无状态 provider（配置来自
    // settings，settings 层已按 reference 从 SecretStore rehydrate 凭据）。
    // Google Drive provider 需要会话，由 BackupVM 按需构造 GoogleDriveSyncProvider。
    single { PersistedFolderStore(context = get()) }
    single {
        WebDavSyncProvider(
            context = get(),
            settingsStore = get(),
            archiveManager = get(),
            json = get(),
            httpClient = get(),
        )
    }
    single {
        LocalFolderSyncProvider(
            context = get(),
            archiveManager = get(),
            folderStore = get(),
            json = get(),
        )
    }

    single { GoogleOAuthConfigGate(context = get()) }

    single { GoogleDriveAppDataClient(httpClient = createGoogleDriveHttpClient(get()), json = get()) }

    single {
        GoogleDriveSyncRepository(
            context = get(),
            driveClient = get(),
            archiveManager = get(),
        )
    }

    // P8-08 首页「继续」聚合：各域来源 + 聚合器。
    single<ContinueCandidateSource> {
        ImageGenerationContinueSource(
            runTerminalStore = get(),
            toolEffectLedger = get(),
            conversationDao = get(),
        )
    }
    single<ContinueCandidateSource> { CouncilContinueSource(conversationDao = get()) }
    single<ContinueCandidateSource> { DeepReadContinueSource(hotListDao = get()) }
    single<ContinueCandidateSource> { MiniAppDraftContinueSource(draftDao = get()) }

    single<ContinueDismissStore> { RoomContinueDismissStore(dao = get()) }

    single {
        ContinueCandidateAggregator(
            // Koin 不会把接口的多个定义自动收集成 List —— get<List<T>>()
            // 会因找不到 List 定义而抛 NoDefinitionFound；getAll<T>() 才是
            // 收集所有 ContinueCandidateSource 定义的写法。
            sources = getAll<ContinueCandidateSource>(),
            dismissStore = get(),
        )
    }

    // P8-09 主题库：导入包入库 + apply/remove + 失败回退。
    single<ThemeSettingsStore> { SettingsAggregatorThemeStore(aggregator = get()) }

    single {
        ThemePackageManager(
            dao = get<AppDatabase>().themePackageDao(),
            settingsStore = get(),
        )
    }
}

private fun createGoogleDriveHttpClient(context: Context): OkHttpClient {
    val acceptLang = AcceptLanguageBuilder.fromAndroid(context).build()
    return OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val requestBuilder = originalRequest.newBuilder()
                .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

            if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                requestBuilder.addHeader(HttpHeaders.UserAgent, "AmberAgent-Android/${BuildConfig.VERSION_NAME}")
            }

            chain.proceed(requestBuilder.build())
        }
        .build()
}
