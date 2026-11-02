package app.amber.core.di

import app.amber.core.jev.AndroidJevUsageStore
import app.amber.core.jev.FileJevCalibrationStore
import app.amber.core.jev.JevCalibrationStore
import app.amber.core.jev.JevClient
import app.amber.core.jev.JevDecisionCoordinator
import app.amber.core.jev.JevMemoryReranker
import app.amber.core.jev.JevPolicy
import app.amber.core.jev.JevRuntime
import app.amber.core.jev.JevCouncilPoolRanker
import app.amber.core.jev.JevToolOutputProjector
import app.amber.core.jev.JevToolResultRetention
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
import java.io.File
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
    single { JevPolicy() }
    single<JevCalibrationStore> {
        FileJevCalibrationStore(File(androidContext().filesDir, "jev/calibration.jsonl"))
    }
    single<app.amber.core.jev.PerAppSuccessPathStore> {
        app.amber.core.jev.FilePerAppSuccessPathStore(
            File(androidContext().filesDir, "jev/per_app_success_paths.json"),
        )
    }
    single {
        JevRuntime(
            coordinator = get(),
            settingsProvider = { get<SettingsAggregator>().settingsFlow.value },
            policy = get(),
            calibration = get(),
        )
    }
    single { JevMemoryReranker(get()) }
    single<MemorySemanticReranker> { get<JevMemoryReranker>() }
    single { JevToolSemanticSearch(get()) }
    single { JevToolOutputProjector(get()) }
    single { JevToolResultRetention(get()) }
    single { app.amber.core.jev.JevAutoApprovalGate(get()) }
    single { app.amber.core.jev.JevApprovalTriage(get()) }
    single { app.amber.core.jev.JevCompletionCheck(get()) }
    single { app.amber.core.jev.JevSubagentIntent(get()) }
    single<app.amber.feature.subagent.SubAgentIntentAlignment> { get<app.amber.core.jev.JevSubagentIntent>() }
    single { app.amber.core.jev.JevScriptJudgment(get()) }
    single { JevCouncilPoolRanker(get()) }
    single<CouncilPoolRanker> { get<JevCouncilPoolRanker>() }
    single { JevWebGoalRunner(get()) }
    single {
        app.amber.core.jev.JevScreenGoalRunner(
            runtime = get(),
            // 只有宿主已有 MediaProjection 会话时才"顺手截一帧"；没有会话直接返回 null，
            // 绝不在目标循环里拉起授权 Activity。
            screenshotProvider = { reference ->
                val manager = get<app.amber.core.automation.ScreenCaptureManager>()
                if (!manager.hasActiveSession) {
                    app.amber.core.jev.ScreenshotAttempt.Skipped("no_capture_session")
                } else {
                    val captured = runCatching { manager.capture() }.getOrNull()
                    val bytes = captured?.file?.let { runCatching { it.readBytes() }.getOrNull() }
                    when {
                        captured == null -> app.amber.core.jev.ScreenshotAttempt.Skipped("capture_failed")
                        bytes == null -> app.amber.core.jev.ScreenshotAttempt.Skipped("read_failed")
                        else -> {
                            val isPng = bytes.size >= 2 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
                            // 面板原分辨率常超过 1568 每边上限：如实报 skipped，由回执暴露，不静默降级。
                            runCatching {
                                app.amber.core.jev.ScreenshotAttempt.Attached(
                                    app.amber.core.jev.JevImageAsset(
                                        reference = reference,
                                        format = if (isPng) "png" else "jpeg",
                                        data = bytes,
                                        widthPx = captured.width,
                                        heightPx = captured.height,
                                    ),
                                )
                            }.getOrElse {
                                app.amber.core.jev.ScreenshotAttempt.Skipped("image_edge_out_of_range")
                            }
                        }
                    }
                }
            },
            ocrEngine = getOrNull(),
            takeoverController = getOrNull(),
            successPathStore = get(),
        )
    }
}
