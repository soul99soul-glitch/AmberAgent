package me.rerere.rikkahub.di

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.providers.google.GoogleGeminiAuthStore
import me.rerere.ai.provider.providers.google.GoogleGeminiOAuthClient
import me.rerere.ai.provider.providers.openai.OpenAICodexAuthStore
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.agent.miniapp.MiniAppSearchBridge
import me.rerere.rikkahub.data.datastore.prefs.AgentPrefs
import me.rerere.rikkahub.data.datastore.prefs.AssistantPrefs
import me.rerere.rikkahub.data.datastore.prefs.ChatPrefs
import me.rerere.rikkahub.data.datastore.prefs.ExtensionPrefs
import me.rerere.rikkahub.data.datastore.prefs.ProviderPrefs
import me.rerere.rikkahub.data.datastore.prefs.SearchPrefs
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.datastore.prefs.SettingsProviderRescue
import me.rerere.rikkahub.data.datastore.prefs.UIPrefs
import me.rerere.rikkahub.data.datastore.settingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_18_19
import me.rerere.rikkahub.data.db.migrations.Migration_19_20
import me.rerere.rikkahub.data.db.migrations.Migration_20_21
import me.rerere.rikkahub.data.db.migrations.Migration_21_22
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_25_26
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_28_29
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.agent.runtime.AgentToolDispatcher
import me.rerere.rikkahub.data.agent.runtime.PermissionDecisionResolver
import me.rerere.rikkahub.data.sync.core.SyncArchiveManager
import me.rerere.rikkahub.data.sync.google.GoogleDriveAppDataClient
import me.rerere.rikkahub.data.sync.google.GoogleDriveSyncRepository
import me.rerere.rikkahub.data.sync.google.GoogleOAuthConfigGate
import me.rerere.rikkahub.data.sync.local.LocalBackupRepository
import me.rerere.search.SearchService
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        UIPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        SearchPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        AgentPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        ProviderPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        ChatPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        ExtensionPrefs(dataStore = get<Context>().settingsStore, scope = get())
    }

    single {
        AssistantPrefs(dataStore = get<Context>().settingsStore, scope = get())
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
            assistantPrefs = get(),
            scope = get(),
        )
    }

    single {
        SettingsProviderRescue(
            context = get(),
            settingsStore = get(),
            json = get(),
        )
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_18_19,
                Migration_19_20,
                Migration_20_21,
                Migration_21_22,
                Migration_22_23,
                Migration_25_26,
                Migration_26_27,
                Migration_27_28,
                Migration_28_29,
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

    single { TemplateTransformer(settingsStore = get()) }

    single { MiniAppSearchBridge(settingsStore = get()) }

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
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single { PermissionDecisionResolver() }

    single { AgentToolDispatcher(json = get(), permissionDecisionResolver = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            memoryRecallStore = get(),
            conversationRepo = get(),
            aiLoggingManager = get(),
            conversationContextEngine = get(),
            toolDispatcher = get(),
        )
    }

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
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor(remoteConfig = get()))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build().also { SearchService.init(it, get()) }
    }

    single {
        ProviderManager(client = get(), context = get())
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
            json = get(),
        )
    }

    single { LocalBackupRepository(context = get(), syncArchiveManager = get()) }

    single { GoogleOAuthConfigGate(context = get()) }

    single { GoogleDriveAppDataClient(httpClient = createGoogleDriveHttpClient(get()), json = get()) }

    single {
        GoogleDriveSyncRepository(
            context = get(),
            driveClient = get(),
            archiveManager = get(),
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
