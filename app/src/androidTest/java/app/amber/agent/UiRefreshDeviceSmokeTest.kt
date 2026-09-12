package app.amber.agent

import android.content.Context
import app.amber.agent.data.db.dao.HotListDAO
import app.amber.agent.data.db.entity.DeepReadCacheEntity
import app.amber.core.utils.JsonInstant
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationPhase
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadOutput
import app.amber.feature.board.hotlist.deepread.DeepReadSectionState
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.graphics.Region
import app.amber.feature.ui.pages.sessionhome.AmberContinuousShape
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import android.graphics.Color as PixelColor
import kotlin.math.abs
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.ai.core.ReasoningLevel
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import java.io.File
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Real-device navigation/screenshot canary for the refreshed Amber UI.
 *
 * Run this class with [AmberAgentUiSmokeTestRunner]. It intentionally never
 * sends a message or configures a provider, so no network/model credential is
 * needed. Screenshots are written below the target app's external files dir so
 * they can be pulled with adb after the test.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalUuidApi::class)
class UiRefreshDeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val preferences = targetContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val originalLaunchMode = preferences.getString(LAUNCH_START_MODE_PREF, null)
    private val originalColorMode = preferences.getString(COLOR_MODE_PREF, null)
    private val smokeConversationIds = listOf(
        Uuid.parse("00000000-0000-0000-0000-00000000a101"),
        Uuid.parse("00000000-0000-0000-0000-00000000a102"),
        Uuid.parse("00000000-0000-0000-0000-00000000a103"),
    )
    private val smokeConversationTitles = listOf(
        "UI smoke example 1",
        "UI smoke example 2",
        "UI smoke example 3",
    )
    private val insertedSmokeConversationIds = mutableSetOf<Uuid>()

    init {
        // RouteActivity reads these before composing. Keep the canary deterministic
        // without touching conversations, providers, or credentials.
        preferences.edit()
            .putString(LAUNCH_START_MODE_PREF, LaunchStartMode.HOME.name)
            .putString(COLOR_MODE_PREF, COLOR_MODE_LIGHT)
            .apply()
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    @After
    fun restoreUiPreferences() {
        preferences.edit().apply {
            if (originalLaunchMode == null) remove(LAUNCH_START_MODE_PREF)
            else putString(LAUNCH_START_MODE_PREF, originalLaunchMode)
            if (originalColorMode == null) remove(COLOR_MODE_PREF)
            else putString(COLOR_MODE_PREF, originalColorMode)
        }.apply()
    }

    @Test
    fun homeSettingsDisplayThemeAndEmptyChat() {
        assumeTrue(
            "Run with -PuiSmokeTest=true so the real AmberAgentApp is installed",
            targetContext.applicationContext is AmberAgentApp,
        )
        ActivityScenario.launch(RouteActivity::class.java).use {
            try {
                runSmokeFlow()
            } finally {
                deleteSeededConversations()
            }
        }
    }

    @Test
    fun chatModelParamsCancelAndSaveUseDurableSettings() {
        assumeTrue(
            "Run with -PuiSmokeTest=true so the real AmberAgentApp is installed",
            targetContext.applicationContext is AmberAgentApp,
        )
        val settingsStore: SettingsAggregator = getKoin().get()
        val original = runBlocking {
            settingsStore.settingsFlow.first { !it.init }
        }
        val cancelPrompt = "ui-smoke-cancel-prompt"
        val savedPrompt = "ui-smoke-saved-prompt"
        val savedReasoning = ReasoningLevel.entries.first { it != original.reasoningLevel }

        ActivityScenario.launch(RouteActivity::class.java).use {
            try {
                openChatModelPage()
                openChatModelParams()
                // The helper scrolls the form field into view for large-font devices;
                // this is intentionally a post-scroll parameter-sheet capture.
                capture("08-model-params-light")
                compose.onNode(hasSetTextAction()).performTextReplacement(cancelPrompt)
                closeSoftKeyboard()
                pressBack()
                compose.waitForIdle()
                runBlocking {
                    // Let a wrongly wired on-change save attempt settle before
                    // checking the durable source of truth.
                    delay(250)
                    val afterCancel = settingsStore.settingsFlow.value
                    assertEquals(original.systemPrompt, afterCancel.systemPrompt)
                    assertEquals(original.reasoningLevel, afterCancel.reasoningLevel)
                }

                openChatModelParams()
                compose.onNode(hasSetTextAction()).performTextReplacement(savedPrompt)
                compose.onNodeWithText(reasoningLabel(savedReasoning))
                    .performScrollTo()
                    .assertIsDisplayed()
                    .performClick()
                compose.onNodeWithText(targetContext.getString(R.string.common_save))
                    .assertIsDisplayed()
                    .performClick()
                runBlocking {
                    withTimeout(5_000) {
                        settingsStore.settingsFlow.first { settings ->
                            settings.systemPrompt == savedPrompt &&
                                settings.reasoningLevel == savedReasoning
                        }
                    }
                }
            } finally {
                runBlocking {
                    settingsStore.update(
                        settingsStore.settingsFlow.value.copy(
                            systemPrompt = original.systemPrompt,
                            reasoningLevel = original.reasoningLevel,
                        )
                    )
                }
            }
        }
    }

    @Test
    fun compactHomeCardAndCollapsedSearch() {
        assumeTrue(targetContext.applicationContext is AmberAgentApp)
        val dao: HotListDAO = getKoin().get()
        val topicId = "ui-home-compact-layout"
        val article = "A misalignment of incentives in modern systems"
        val now = System.currentTimeMillis()
        runBlocking {
            check(dao.getDeepRead(topicId) == null) { "Layout fixture already exists" }
            val output = DeepReadOutput(
                generationPhase = DeepReadGenerationPhase.IDLE,
                sectionStates = mapOf(
                    DeepReadGenerationStage.OVERVIEW to DeepReadSectionState(DeepReadSectionStatus.READY),
                ),
            )
            dao.upsertDeepRead(
                DeepReadCacheEntity(
                    topicId = topicId,
                    title = article,
                    outputJson = JsonInstant.encodeToString(DeepReadOutput.serializer(), output),
                    createdAt = now,
                    updatedAt = now,
                    expiresAt = now + 86_400_000L,
                    sourceUrl = "https://example.com/article",
                ),
            )
        }
        try {
            ActivityScenario.launch(RouteActivity::class.java).use {
                seedConversations()
                val repository: ConversationRepository = getKoin().get()
                runBlocking {
                    repeat(9) { index ->
                        val id = Uuid.parse("00000000-0000-0000-0000-00000000b10$index")
                        check(repository.getConversationSummaryById(id) == null)
                        repository.insertConversation(
                            Conversation.ofId(id).copy(title = "${index + 1}. 整理文章的关键观点和论证结构，给出下一步阅读建议"),
                        )
                        insertedSmokeConversationIds += id
                    }
                }
                compose.waitUntil(timeoutMillis = 15_000) {
                    compose.onAllNodesWithText(article, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(article, useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithContentDescription(targetContext.getString(R.string.session_home_hide_continue_candidate))
                    .assertDoesNotExist()
                compose.onNode(hasSetTextAction()).assertDoesNotExist()
                capture("09-home-compact-light")

                // The fixed search entry must work even when its former LazyColumn slot is off screen.
                compose.onNode(hasScrollAction()).performScrollToNode(hasText(smokeConversationTitles.first()))
                compose.onNodeWithContentDescription(targetContext.getString(R.string.history_page_search))
                    .performClick()
                compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextReplacement(smokeConversationTitles.first())
                compose.onNode(hasText(smokeConversationTitles.first()) and !hasSetTextAction()).assertIsDisplayed()
                compose.onNodeWithText(smokeConversationTitles[1]).assertDoesNotExist()
                capture("10-home-search-expanded")
                compose.onNodeWithContentDescription(targetContext.getString(R.string.parity_home_search_clear)).performClick()
                compose.onNodeWithContentDescription(targetContext.getString(R.string.parity_home_search_cancel)).performClick()
                compose.onNode(hasSetTextAction()).assertDoesNotExist()
                closeSoftKeyboard()

                compose.onNodeWithContentDescription(targetContext.getString(R.string.history_page_search)).assertIsDisplayed()
                capture("11-home-search-collapsed")
            }
        } finally {
            // A disposable emulator may keep these fixtures briefly for native-speed screen recording.
            if (InstrumentationRegistry.getArguments().getString("keepPreviewData") != "true") {
                runBlocking { dao.deleteDeepRead(topicId) }
                deleteSeededConversations()
            }
        }
    }

    @Test
    fun homeButtonsKeepPressedFeedbackInsideTheirVisibleShapes() {
        assumeTrue(targetContext.applicationContext is AmberAgentApp)
        ActivityScenario.launch(RouteActivity::class.java).use {
            val settings = compose.onNodeWithContentDescription(targetContext.getString(R.string.settings))
            val search = compose.onNodeWithContentDescription(targetContext.getString(R.string.history_page_search))
            val newChat = compose.onNodeWithContentDescription(targetContext.getString(R.string.chat_page_new_message))
            assertPressedShape(settings, "12-settings-pressed", visualHeightDp = 32f, visualWidthDp = 32f)
            assertPressedShape(search, "13-search-pressed", visualHeightDp = 32f)
            assertPressedShape(newChat, "14-new-chat-pressed", visualHeightDp = 40f, shape = AmberContinuousShape(44.dp))

            // The extra space outside the visible 32dp shape remains a working touch target.
            search.performTouchInput { click(Offset(center.x, 2f)) }
            compose.onNode(hasSetTextAction()).assertIsDisplayed()
            compose.onNodeWithContentDescription(targetContext.getString(R.string.parity_home_search_cancel)).performClick()
            closeSoftKeyboard()
            settings.performTouchInput { click(Offset(center.x, 2f)) }
            compose.onNodeWithText(targetContext.getString(R.string.setting_page_display_setting)).assertIsDisplayed()
            pressBack()
            newChat.performClick()
            compose.onNodeWithText(targetContext.getString(R.string.chat_page_hero_greeting)).assertIsDisplayed()
        }
    }

    private fun assertPressedShape(
        button: SemanticsNodeInteraction,
        screenshot: String,
        visualHeightDp: Float? = null,
        visualWidthDp: Float? = null,
        shape: Shape? = null,
    ) {
        button.assertIsDisplayed()
        capture("$screenshot-idle")
        val bounds = button.fetchSemanticsNode().boundsInRoot
        val density = targetContext.resources.displayMetrics.density
        val height = visualHeightDp?.times(density) ?: bounds.height
        val width = visualWidthDp?.times(density) ?: bounds.width
        val shapeRegion = shape?.let {
            val outline = it.createOutline(Size(width, height), LayoutDirection.Ltr, Density(density)) as Outline.Generic
            Region().apply {
                setPath(outline.path.asAndroidPath(), Region(0, 0, width.toInt(), height.toInt()))
            }
        }
        val radius = height / 2f
        val leftCircleX = bounds.center.x - width / 2f + radius
        val rightCircleX = bounds.center.x + width / 2f - radius
        val before = instrumentation.uiAutomation.takeScreenshot()
        var changedInside = 0
        var changedOutside = 0
        try {
            button.performTouchInput { down(center) }
            capture(screenshot)
            val pressed = instrumentation.uiAutomation.takeScreenshot()
            try {
                for (y in bounds.top.toInt() + 2 until bounds.bottom.toInt() - 2) {
                    for (x in bounds.left.toInt() + 2 until bounds.right.toInt() - 2) {
                        val old = before.getPixel(x, y)
                        val current = pressed.getPixel(x, y)
                        val delta = abs(PixelColor.red(old) - PixelColor.red(current)) +
                            abs(PixelColor.green(old) - PixelColor.green(current)) +
                            abs(PixelColor.blue(old) - PixelColor.blue(current))
                        if (delta <= 12) continue
                        if (shapeRegion != null) {
                            val localX = (x - bounds.center.x + width / 2f).toInt()
                            val localY = (y - bounds.center.y + height / 2f).toInt()
                            val samples = listOf(-2, 0, 2).flatMap { dy ->
                                listOf(-2, 0, 2).map { dx -> shapeRegion.contains(localX + dx, localY + dy) }
                            }
                            if (samples.none { it }) changedOutside++
                            if (samples.all { it }) changedInside++
                        } else {
                            val dx = x - x.toFloat().coerceIn(leftCircleX, rightCircleX)
                            val dy = y - bounds.center.y
                            val distanceSquared = dx * dx + dy * dy
                            if (distanceSquared > (radius + 2f) * (radius + 2f)) changedOutside++
                            if (distanceSquared < (radius - 2f) * (radius - 2f)) changedInside++
                        }
                    }
                }
            } finally {
                pressed.recycle()
            }
        } finally {
            button.performTouchInput { cancel() }
            before.recycle()
        }
        println("Pressed-shape check: $screenshot bounds=$bounds density=$density inside=$changedInside outside=$changedOutside")
        assertTrue("$screenshot must show actual press feedback", changedInside > 10)
        assertEquals("$screenshot feedback must stay within the visible shape", 0, changedOutside)
        SystemClock.sleep(400)
        compose.waitForIdle()
    }

    private fun openChatModelPage() {
        compose.onNodeWithContentDescription(targetContext.getString(R.string.settings))
            .assertIsDisplayed()
            .performClick()
        val modelsAndPrompts = targetContext.getString(R.string.setting_page_default_model)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(modelsAndPrompts))
        compose.onNodeWithText(modelsAndPrompts).assertIsDisplayed().performClick()
    }

    private fun openChatModelParams() {
        compose.onAllNodesWithText(targetContext.getString(R.string.setting_model_page_parameters))
            .onFirst()
            .performClick()
        compose.onNode(hasSetTextAction())
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun reasoningLabel(level: ReasoningLevel): String = targetContext.getString(
        when (level) {
            ReasoningLevel.OFF -> R.string.reasoning_off
            ReasoningLevel.AUTO -> R.string.reasoning_auto
            ReasoningLevel.LOW -> R.string.reasoning_light
            ReasoningLevel.MEDIUM -> R.string.reasoning_medium
            ReasoningLevel.HIGH -> R.string.reasoning_heavy
            ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
            ReasoningLevel.MAX -> R.string.reasoning_max
        },
    )

    private fun runSmokeFlow() {
        val settings = targetContext.getString(R.string.settings)
        val displaySettings = targetContext.getString(R.string.setting_page_display_setting)
        val displayTitle = targetContext.getString(R.string.setting_display_page_title)
        val light = targetContext.getString(R.string.setting_page_color_mode_light)
        val dark = targetContext.getString(R.string.setting_page_color_mode_dark)
        val back = targetContext.getString(R.string.back)

        compose.onNodeWithContentDescription(settings).assertIsDisplayed()
        capture("01-home-before-seed-light")

        seedConversations()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(smokeConversationTitles.first()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(smokeConversationTitles.first()).assertIsDisplayed()
        capture("02-home-with-sessions-light")

        compose.onNodeWithContentDescription(settings).performClick()
        compose.onNodeWithText(displaySettings).assertIsDisplayed()
        capture("03-settings-light")

        compose.onNodeWithText(displaySettings).performClick()
        compose.onNodeWithText(displayTitle).assertIsDisplayed()
        capture("04-display-light")

        compose.onNodeWithContentDescription(back).performClick()
        compose.onNodeWithText(displaySettings).assertIsDisplayed()

        // The selector is the real settings UI. Selecting the dark item causes
        // the route to be recreated, which also exercises theme application.
        compose.onNodeWithText(light).performClick()
        compose.onNodeWithText(dark).performClick()
        compose.onNodeWithText(displaySettings).assertIsDisplayed()
        capture("05-settings-dark")

        // Return to light so the final home/chat capture is easy to compare.
        compose.onNodeWithText(dark).performClick()
        compose.onNodeWithText(light).performClick()
        compose.onNodeWithText(displaySettings).assertIsDisplayed()
        capture("06-settings-light-after-toggle")

        compose.onNodeWithContentDescription(back).performClick()
        compose.onNodeWithContentDescription(settings).assertIsDisplayed()

        val newMessage = targetContext.getString(R.string.chat_page_new_message)
        compose.onNodeWithContentDescription(newMessage).performClick()
        compose.onNodeWithText(targetContext.getString(R.string.chat_page_hero_greeting)).assertIsDisplayed()
        val replySuggestion = targetContext.getString(R.string.amber_redesign_suggestion_reply)
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(replySuggestion).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(replySuggestion).assertIsDisplayed()
        capture("07-chat-empty-light")
    }

    private fun seedConversations() {
        val repository: ConversationRepository = getKoin().get()
        runBlocking {
            smokeConversationIds.forEachIndexed { index, id ->
                if (repository.getConversationSummaryById(id) != null) return@forEachIndexed
                val title = smokeConversationTitles[index]
                repository.insertConversation(Conversation.ofId(id).copy(title = title))
                if (repository.getConversationSummaryById(id)?.title == title) {
                    insertedSmokeConversationIds += id
                }
            }
        }
    }

    private fun deleteSeededConversations() {
        if (insertedSmokeConversationIds.isEmpty()) return
        runCatching {
            val repository: ConversationRepository = getKoin().get()
            runBlocking {
                insertedSmokeConversationIds.toList().forEach { id ->
                    repository.getConversationById(id)?.let { repository.deleteConversation(it) }
                }
            }
        }
        insertedSmokeConversationIds.clear()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // Compose's idling resource can settle before the next real Android
        // frame is presented (notably while ChatPage leaves its init spinner).
        SystemClock.sleep(400)
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val directory = targetContext.getExternalFilesDir(SCREENSHOT_DIRECTORY)
            ?: error("Target app has no external files directory")
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create screenshot directory: ${directory.absolutePath}"
        }
        val file = File(directory, "$name.png")
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Unable to encode screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        assertTrue("Screenshot was not written: ${file.absolutePath}", file.length() > 0L)
        println("UiRefreshDeviceSmokeTest screenshot=${file.absolutePath}")
    }

    private companion object {
        const val PREFERENCES_NAME = "amber_agent.preferences"
        const val COLOR_MODE_PREF = "colorMode"
        const val COLOR_MODE_LIGHT = "LIGHT"
        const val SCREENSHOT_DIRECTORY = "ui-refresh-smoke"
    }
}
