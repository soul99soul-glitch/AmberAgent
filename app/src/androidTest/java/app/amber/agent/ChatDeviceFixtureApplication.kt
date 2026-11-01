package app.amber.agent

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
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
import app.amber.core.di.*
import app.amber.feature.runtime.ColdStartRuntimeRecoveryGate
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** Separate files/DB/preferences namespace; never opens the owner's saved model or conversation stores. */
class ChatDeviceFixtureApplication : Application() {
    private val fixtureRoot: File get() = File(super.getFilesDir(), "chat-device-fixture").apply { mkdirs() }
    override fun getFilesDir(): File = File(fixtureRoot, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(fixtureRoot, "cache").apply { mkdirs() }
    override fun getNoBackupFilesDir(): File = File(fixtureRoot, "no-backup").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(fixtureRoot, "databases/$name").also { it.parentFile?.mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences("chat-device-fixture-$name", mode)

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(this, FirebaseOptions.Builder()
                .setApplicationId("1:000000000000:android:0000000000000000000000")
                .setApiKey("local-firebase-disabled").setProjectId("amberagent-local").build())
        }
        FirebaseApp.getInstance().setDataCollectionDefaultEnabled(false)
        if (GlobalContext.getOrNull() != null) stopKoin()
        startKoin {
            androidContext(this@ChatDeviceFixtureApplication)
            modules(appModule, chatModule, memoryModule, iCloudModule, webMountModule,
                agentRuntimeModule, agentInfraModule, boardModule, workspaceModule, novelModule,
                viewModelModule, dataSourceModule, generationModule, toolExecutionModule,
                repositoryModule, jevModule,
                module {
                    single { FixtureTextGateway() }
                    single { ProviderCatalog(get(), get(), get(), get(), openAITextGateway = get<FixtureTextGateway>()) }
                })
            workManagerFactory()
        }.koin.get<ColdStartRuntimeRecoveryGate>().complete()
        createNotificationChannel()
    }
}

/**
 * Second instrumentation in the test APK. [newApplication] always installs
 * [ChatDeviceFixtureApplication]. Select with
 * `am instrument …/app.amber.agent.ChatDeviceFixtureRunner`.
 */
class ChatDeviceFixtureRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        context?.let { appContext ->
            TestReminderReceiverIsolation.installNoOpReminderKoin(appContext)
            TestReminderReceiverIsolation.disable(appContext)
        }
        Log.i("AmberS9Runner", "newApplication app=${ChatDeviceFixtureApplication::class.java.name}")
        return super.newApplication(cl, ChatDeviceFixtureApplication::class.java.name, context)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        try {
            TestReminderReceiverIsolation.restore(targetContext)
        } finally {
            super.finish(resultCode, results)
        }
    }
}

/** Only the provider boundary is scripted; production CAS, run ownership and persistence remain real. */
class FixtureTextGateway : TextModelGateway<ProviderSetting.OpenAI> {
    val calls = AtomicInteger()
    @Volatile var delayMs = 33L
    @Volatile var reply = ""
    @Volatile var beforeFinish: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    @Volatile var finishGateReached = false
    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = providerSetting.models
    override suspend fun complete(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): MessageChunk =
        chunk("Device regression auxiliary", final = true)
    override suspend fun stream(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<MessageChunk> = flow {
        calls.incrementAndGet()
        val response = reply
        for (piece in response.chunked(12)) {
            delay(delayMs)
            emit(chunk(piece, final = false))
        }
        beforeFinish?.let { gate ->
            finishGateReached = true
            gate.await()
        }
        emit(chunk("", final = true))
    }
    private fun chunk(text: String, final: Boolean) = MessageChunk(
        id = "chat-device-script", model = "chat-device-script",
        choices = listOf(UIMessageChoice(0,
            delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text))),
            message = null, finishReason = if (final) "stop" else null)),
    )
}
