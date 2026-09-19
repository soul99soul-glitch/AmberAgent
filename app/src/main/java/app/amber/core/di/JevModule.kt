package app.amber.core.di

import app.amber.core.jev.AndroidJevUsageStore
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevMemoryReranker
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevCouncilPoolRanker
import app.amber.core.jev.JevToolOutputProjector
import app.amber.core.jev.JevToolSemanticSearch
import app.amber.core.jev.MemorySemanticReranker
import app.amber.core.jev.JevWebGoalRunner
import app.amber.core.jev.OkHttpJevTransport
import app.amber.feature.modelcouncil.CouncilPoolRanker
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.secret.SecretDescriptor
import app.amber.core.settings.secret.SecretStore
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/** Jev 判断服务的 Key 存放位置；真实值只进 SecretStore，Settings 仅留掩码。 */
val JevApiKeyDescriptor = SecretDescriptor(scope = "jev", ownerId = "default", fieldName = "apiKey")

val jevModule = module {
    single {
        // 独立于长超时的全局聊天客户端：判断服务需要紧凑超时兜底
        //（真正的 deadline 由协调器的 withTimeout 施加并取消 Call）。
        val chatClient = get<OkHttpClient>()
        JevClient(
            OkHttpJevTransport(
                chatClient.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .writeTimeout(3, TimeUnit.SECONDS)
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build(),
            ),
        )
    }
    single {
        JevDecisionCoordinator(
            client = get(),
            apiKeyProvider = { get<SecretStore>().read(JevApiKeyDescriptor) },
            usageStore = AndroidJevUsageStore(androidContext()),
        )
    }
    single { JevRuntime(coordinator = get(), settingsProvider = { get<SettingsAggregator>().settingsFlow.value }) }
    single { JevMemoryReranker(get()) }
    single<MemorySemanticReranker> { get<JevMemoryReranker>() }
    single { JevToolSemanticSearch(get()) }
    single { JevToolOutputProjector(get()) }
    single { JevCouncilPoolRanker(get()) }
    single<CouncilPoolRanker> { get<JevCouncilPoolRanker>() }
    single { JevWebGoalRunner(get()) }
    single { app.amber.core.jev.JevScreenGoalRunner(get()) }
}
