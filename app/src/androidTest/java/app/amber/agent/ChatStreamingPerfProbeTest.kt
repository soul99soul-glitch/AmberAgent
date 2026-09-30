package app.amber.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.OpenAIBrand
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.settings.Settings
import app.amber.core.settings.getCurrentChatModel
import app.amber.core.settings.prefs.SettingsAggregator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One opt-in emulator run through the real composer, provider and chat timeline. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalUuidApi::class)
class ChatStreamingPerfProbeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun scriptedOpenAiSseRendersAndPersistsFullReply() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Requires -e amberPerfProbe true", InstrumentationRegistry.getArguments().getString("amberPerfProbe") == "true")
        assumeTrue("Requires the real app runner (-PuiSmokeTest=true)", context.applicationContext is AmberAgentApp)
        val device = "${Build.MODEL} ${Build.FINGERPRINT}".lowercase()
        assumeTrue("Disposable fixture runs only on an emulator",
            device.contains("emulator") || device.contains("sdk_gphone") ||
                device.contains("android sdk built for") || device.contains("generic"))

        val settingsStore: SettingsAggregator = getKoin().get()
        val repository: ConversationRepository = getKoin().get()
        val service: ChatService = getKoin().get()
        val original = runBlocking { withTimeout(10_000) { settingsStore.settingsFlow.first { !it.init } } }
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val originalLaunchMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
        val conversationId = Uuid.random()
        val title = "l3-stream-probe-$conversationId"
        val mainModel = Model(modelId = "l3-stream-probe", displayName = "L3 local stream",
            type = ModelType.CHAT, contextWindowTokens = 64_000)
        val auxiliaryModel = Model(modelId = "l3-aux-probe", displayName = "L3 local auxiliary",
            type = ModelType.CHAT, contextWindowTokens = 16_000)
        val port = InstrumentationRegistry.getArguments().getString("amberPerfProbePort")?.toIntOrNull() ?: 18765
        val localBaseUrl = "http://10.0.2.2:$port/v1"
        val provider = ProviderSetting.OpenAI(
            name = "L3 scripted local only", models = listOf(mainModel, auxiliaryModel),
            apiKey = "l3-local-fixture", baseUrl = localBaseUrl,
            authMode = OpenAIAuthMode.API_KEY, brand = OpenAIBrand.GENERIC,
        )
        try {
            preferences.edit().putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name).apply()
            runBlocking {
                settingsStore.update(original.copy(
                    providers = original.providers + provider,
                    chatModelId = mainModel.id,
                    titleModelId = auxiliaryModel.id,
                    suggestionModelId = auxiliaryModel.id,
                    compressModelId = auxiliaryModel.id,
                    streamOutput = true,
                    maxTokens = 8_192,
                    enableWebSearch = false,
                    enabledMcpServerIds = emptySet(),
                    presetMessages = emptyList(),
                    regexes = emptyList(),
                    systemPrompt = "Local scripted streaming probe. Do not call tools.",
                    agentRuntime = original.agentRuntime.copy(
                        enableCoreMemory = false, enableShortTermMemory = false,
                        enableLongTermMemory = false, enableRecentChatsReference = false,
                    ),
                ))
                withTimeout(10_000) {
                    settingsStore.settingsFlow.first { active ->
                        active.getCurrentChatModel()?.id == mainModel.id &&
                            active.providers.any { candidate ->
                                candidate is ProviderSetting.OpenAI && candidate.id == provider.id &&
                                    candidate.baseUrl == localBaseUrl &&
                                    candidate.models.any { it.id == mainModel.id }
                            }
                    }
                }
                check(repository.getConversationSummaryById(conversationId) == null)
                repository.insertConversation(Conversation.ofId(conversationId).copy(title = title))
            }
            Log.i(TAG, "begin conversation=$conversationId expectedChars=$EXPECTED_CHARS")
            val intent = Intent(context, RouteActivity::class.java).apply {
                putExtra("conversationId", conversationId.toString())
            }
            ActivityScenario.launch<RouteActivity>(intent).use {
                compose.waitUntil(30_000) {
                    compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                    .onFirst().performTextInput(PROMPT)
                val send = context.getString(R.string.send)
                compose.onAllNodes(
                    hasClickAction() and (hasContentDescription(send) or
                        hasAnyDescendant(hasContentDescription(send))),
                    useUnmergedTree = true,
                ).onFirst().performClick()

                compose.waitUntil(90_000) {
                    replyText(service, conversationId).length in 2_500..10_000
                }
                capture(context, "l3-streaming")
                compose.waitUntil(120_000) {
                    val text = replyText(service, conversationId)
                    text.length == EXPECTED_CHARS && sha256(text) == EXPECTED_SHA256 &&
                        service.getGenerationJobStateFlow(conversationId).value?.isActive != true
                }
                val actual = replyText(service, conversationId)
                check(actual.length == EXPECTED_CHARS && sha256(actual) == EXPECTED_SHA256)
                check(actual.lineSequence().count { it.startsWith("| ") && it.contains("| phase-") } == 40)
                check(actual.lineSequence().count { it.startsWith("```") } == 6)
                runBlocking {
                    val stored = requireNotNull(repository.getConversationById(conversationId))
                    val persisted = stored.currentMessages.last().parts
                        .filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
                    check(persisted == actual) { "Final streamed reply did not reach durable conversation" }
                }
                val timeline = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).onFirst()
                timeline.performScrollToNode(hasText("L3_FINAL_TAIL_MARKER_EXACT_END", substring = true))
                compose.onNodeWithText("L3_FINAL_TAIL_MARKER_EXACT_END", substring = true,
                    useUnmergedTree = true).assertIsDisplayed()
                var swipes = 0
                for ((target, screenshot) in listOf(
                    hasText("data class StreamCheck", substring = true) to "l3-complete-code",
                    hasText("Row") to "l3-complete-table",
                )) {
                    val matches = compose.onAllNodes(target, useUnmergedTree = true)
                    fun targetIsDisplayed() = matches.fetchSemanticsNodes().indices.any {
                        matches[it].isDisplayed()
                    }
                    while (swipes < 40 && !targetIsDisplayed()) {
                        timeline.performTouchInput {
                            swipeDown(startY = height * 0.35f, endY = height * 0.65f, durationMillis = 600)
                        }
                        swipes++
                    }
                    val visible = targetIsDisplayed()
                    Log.i(TAG, "target=$screenshot swipes=$swipes visible=$visible bounds=${matches.fetchSemanticsNodes().map { it.boundsInRoot }}")
                    capture(context, if (visible) screenshot else "$screenshot-not-visible")
                    check(visible) { "Reply target is not visible: $screenshot" }
                }
                Log.i(TAG, "complete conversation=$conversationId chars=${actual.length} sha256=${sha256(actual)}")
            }
        } finally {
            try {
                runBlocking {
                    repository.getConversationById(conversationId)
                        ?.let { service.deleteConversation(it) }
                }
            } finally {
                runBlocking { settingsStore.update(original) }
                preferences.edit().apply {
                    if (originalLaunchMode == null) remove(LAUNCH_START_MODE_PREF)
                    else putString(LAUNCH_START_MODE_PREF, originalLaunchMode)
                }.apply()
            }
        }
    }

    private fun replyText(service: ChatService, id: Uuid): String =
        service.getConversationFlow(id).value.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("") { it.text }.orEmpty()

    private fun capture(context: Context, name: String) {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = checkNotNull(context.getExternalFilesDir("l3-streaming-probe"))
        check(directory.exists() || directory.mkdirs())
        val file = File(directory, "$name.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        check(file.length() > 0L)
        Log.i(TAG, "screenshot=${file.absolutePath}")
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "ChatStreamingPerfProbe"
        const val PREFERENCES_NAME = "amber_agent.preferences"
        const val LAUNCH_START_MODE_PREF = "launchStartMode"
        const val PROMPT = "L3_STREAM_MARKER_android_perf_20260929: return the scripted fixture exactly."
        const val EXPECTED_CHARS = 12_227
        const val EXPECTED_SHA256 = "b12c494d9c20d3f54d891f243076fd3695a31c57bccd6334cec10ed305595fe1"
    }
}
