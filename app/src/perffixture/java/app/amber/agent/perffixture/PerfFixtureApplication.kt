package app.amber.agent.perffixture

import android.app.Application
import android.content.Context
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ProviderCatalog
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.TextGenerationParams
import app.amber.ai.provider.TextModelGateway
import app.amber.ai.ui.MessageChunk
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessageChoice
import app.amber.ai.ui.UIMessagePart
import app.amber.core.di.agentInfraModule
import app.amber.core.di.agentRuntimeModule
import app.amber.core.di.appModule
import app.amber.core.di.boardModule
import app.amber.core.di.chatModule
import app.amber.core.di.dataSourceModule
import app.amber.core.di.generationModule
import app.amber.core.di.iCloudModule
import app.amber.core.di.jevModule
import app.amber.core.di.memoryModule
import app.amber.core.di.novelModule
import app.amber.core.di.repositoryModule
import app.amber.core.di.toolExecutionModule
import app.amber.core.di.viewModelModule
import app.amber.core.di.webMountModule
import app.amber.core.di.workspaceModule
import app.amber.core.utils.DatabaseUtil
import app.amber.feature.ui.utils.ChatPerfImageModelOverride
import app.amber.feature.ui.utils.ChatPerfTrace
import app.amber.agent.createNotificationChannel
import app.amber.feature.runtime.ColdStartRuntimeRecoveryGate
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CancellationException
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Independent perf-fixture host. The package is app.amber.agent.perffixture
 * and every mutable path lives under a fixture-only namespace. The production
 * ChatPage → ChatVM → Room path is unchanged; only provider + fixture data are
 * replaced.
 */
class PerfFixtureApplication : Application() {
    private val fixtureRoot: File
        get() = File(super.getFilesDir(), "chat-history-perf-fixture").apply { mkdirs() }

    override fun getFilesDir(): File = File(fixtureRoot, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(fixtureRoot, "cache").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(fixtureRoot, "no-backup").apply { mkdirs() }
    override fun getDatabasePath(name: String): File =
        File(fixtureRoot, "databases/$name").also { it.parentFile?.mkdirs() }

    // No SharedPreferences name prefix: the package itself is isolated
    // (app.amber.agent.perffixture). Activity ContextImpl and Application must
    // resolve the same file across process restarts; a prefix applied only by
    // Application made the launcher/RouteActivity read a different file.

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApplicationId("1:000000000000:android:0000000000000000000000")
                    .setApiKey("local-firebase-disabled")
                    .setProjectId("amberagent-local")
                    .build(),
            )
        }
        FirebaseApp.getInstance().setDataCollectionDefaultEnabled(false)
        startKoin {
            androidContext(this@PerfFixtureApplication)
            modules(
                appModule, chatModule, memoryModule, iCloudModule, webMountModule,
                agentRuntimeModule, agentInfraModule, boardModule, workspaceModule, novelModule,
                viewModelModule, dataSourceModule, generationModule, toolExecutionModule,
                repositoryModule, jevModule,
                module {
                    single { PerfFixtureTextGateway() }
                    single {
                        ProviderCatalog(
                            get(), get(), get(), get(),
                            openAITextGateway = get<PerfFixtureTextGateway>(),
                        )
                    }
                },
            )
            workManagerFactory()
        }.koin.get<ColdStartRuntimeRecoveryGate>().complete()
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)
        createNotificationChannel()
        // Fixture-only URL -> local file resolver. Production never sets this.
        ChatPerfImageModelOverride.resolve = { url ->
            PerfFixtureScenarios.localAssetPath(this, url)
        }
    }
}

/** Scripted provider for S7 only; never performs network I/O. */
internal object PerfFixtureStreamSpec {
    const val CHUNK_CHARS = 12
    const val DELAY_MS = 33L
    val reply = "S7 scripted streaming answer. " + "bounded-fixture-chunk ".repeat(180)
}

class PerfFixtureTextGateway : TextModelGateway<ProviderSetting.OpenAI> {
    val calls = AtomicInteger()

    @Volatile var delayMs: Long = PerfFixtureStreamSpec.DELAY_MS
    @Volatile var reply: String = PerfFixtureStreamSpec.reply
    @Volatile var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        providerSetting.models

    override suspend fun complete(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = chunk("Perf fixture auxiliary", final = true)

    override suspend fun stream(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        calls.incrementAndGet()
        val runId = ChatPerfTrace.currentRunId() ?: "unknown"
        val text = reply
        var emitted = false
        try {
        for (piece in text.chunked(PerfFixtureStreamSpec.CHUNK_CHARS)) {
            delay(delayMs)
            if (!emitted) {
                emitted = true
                android.util.Log.i("AmberPerfFixture", "s7-first-chunk runId=$runId")
            }
            emit(chunk(piece, final = false))
        }
        gate?.await()
        emit(chunk("", final = true))
        ChatPerfTrace.fixtureStreamSettled(runId)
        android.util.Log.i("AmberPerfFixture", "s7-settled runId=$runId")
        } catch (cancelled: CancellationException) {
            ChatPerfTrace.failFixtureStream(runId, "S7 stream cancelled")
            throw cancelled
        }
    }

    private fun chunk(text: String, final: Boolean) = MessageChunk(
        id = "perf-fixture-script",
        model = "perf-fixture-script",
        choices = listOf(
            UIMessageChoice(
                0,
                delta = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(text)),
                ),
                message = null,
                finishReason = if (final) "stop" else null,
            ),
        ),
    )
}
