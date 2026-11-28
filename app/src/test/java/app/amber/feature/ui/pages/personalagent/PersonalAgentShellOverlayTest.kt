package app.amber.feature.ui.pages.personalagent

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.core.context.CompactLifecycleState
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.ui.components.ai.ComposerContextPopoverContent
import app.amber.feature.ui.components.ai.ComposerContextSnapshot
import app.amber.feature.ui.components.ai.ComposerFocusPopover
import app.amber.feature.ui.components.ai.ComposerFocusToolsRow
import app.amber.feature.ui.components.ai.ComposerThinkingPopoverContent
import app.amber.feature.ui.components.ai.resolveComposerReasoningState
import app.amber.feature.ui.pages.chat.ChatShelfPanelContent
import app.amber.feature.ui.pages.chat.ShelfItem
import app.amber.feature.ui.pages.chat.ShelfItemKind
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.buildAmberTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.amber.feature.ui.components.ai.ComposerFocusToolTouchHeight
import app.amber.feature.ui.components.ai.ComposerFocusToolVisualHeight
import kotlin.math.abs
import androidx.test.core.app.ApplicationProvider
import app.amber.feature.prompts.AgentPersonaAvatarStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PersonalAgentShellOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun topChrome_rightButton_togglesArtifactShelfInsteadOfProactiveCenter() {
        stopKoin()
        startKoin {
            modules(module { single { AgentPersonaAvatarStore(ApplicationProvider.getApplicationContext()) } })
        }
        try {
            var toggled = 0
            compose.setContent {
                PersonalAgentTopChrome(
                    topicLabel = "Amber",
                    generating = false,
                    shelfOpen = false,
                    shelfItemCount = 3,
                    onOpenWorkspace = {},
                    onOpenAgentSoul = {},
                    onTopicClick = {},
                    onStop = {},
                    onToggleShelf = { toggled++ },
                )
            }

            compose.onNodeWithTag("personal-agent-artifact-shelf").assertIsDisplayed().performClick()
            assertEquals(1, toggled)
        } finally {
            stopKoin()
        }
    }

    @Test
    fun shelfPanel_rendersEmptyStateAndCloseAction_andRealItems() {
        var closed = 0
        var opened: ShelfItem? = null
        val sampleItem = ShelfItem(
            kind = ShelfItemKind.FILE,
            label = "architecture.md",
            target = "file:///tmp/architecture.md",
            nodeId = Uuid.random(),
            messageId = "m1",
        )
        compose.setContent {
            var items by remember { mutableStateOf<List<ShelfItem>>(emptyList()) }
            Column {
                ChatShelfPanelContent(
                    items = items,
                    onOpen = { opened = it },
                    onClose = {
                        closed++
                        items = listOf(sampleItem)
                    },
                )
            }
        }

        compose.onNodeWithTag("chat-artifact-shelf-empty").assertIsDisplayed()
        compose.onNodeWithTag("chat-artifact-shelf-close").performClick()
        assertEquals(1, closed)

        compose.onNodeWithText("architecture.md").assertIsDisplayed().performClick()
        assertEquals(sampleItem, opened)
    }

    @Test
    fun focusTools_reasoningStateHandlesMissingUnsupportedAndSupportedModels() {
        val noModelState = resolveComposerReasoningState(model = null, currentLevel = ReasoningLevel.AUTO)
        assertFalse(noModelState.hasModel)
        assertFalse(noModelState.available)

        val nonReasoningModel = Model(modelId = "gpt-4o-mini", displayName = "GPT-4o mini", abilities = emptyList())
        val unsupportedState = resolveComposerReasoningState(nonReasoningModel, ReasoningLevel.HIGH)
        assertTrue(unsupportedState.hasModel)
        assertFalse(unsupportedState.available)

        val reasoningModel = Model(
            modelId = "gpt-5",
            displayName = "GPT-5",
            abilities = listOf(ModelAbility.REASONING),
        )
        val supportedState = resolveComposerReasoningState(reasoningModel, ReasoningLevel.HIGH)
        assertTrue(supportedState.available)
        assertTrue(supportedState.isActiveLevel)
        assertTrue(supportedState.levels.contains(ReasoningLevel.HIGH))
    }

    @Test
    fun focusTools_contextSnapshot_hasZeroFabricatedValuesWhenEmpty_andComputesRealStats() {
        val empty = ComposerContextSnapshot(
            messageCount = 0,
            usedTokens = 0,
            windowTokens = 200_000,
            hasModel = false,
        )
        assertEquals(0f, empty.fillFraction, 0.0001f)
        assertEquals("0 / 200K", empty.occupancyText)
        assertEquals("—", empty.cacheHitRateText)
        assertNull(empty.tokensPerSecond)

        val populated = ComposerContextSnapshot(
            messageCount = 6,
            usedTokens = 50_000,
            windowTokens = 200_000,
            lastTurnPromptTokens = 10_000,
            lastTurnCompletionTokens = 600,
            lastTurnCachedTokens = 4_000,
            lastTurnElapsedMs = 10_000L,
            hasModel = true,
        )
        assertEquals(0.25f, populated.fillFraction, 0.0001f)
        assertEquals("50K / 200K", populated.occupancyText)
        assertEquals("40%", populated.cacheHitRateText)
        assertEquals(60.0, populated.tokensPerSecond ?: 0.0, 0.1)
    }

    @Test
    fun focusTools_rowAndPopovers_selectReasoningAndRenderContextStats() {
        val model = Model(
            modelId = "claude-sonnet-4.5",
            displayName = "Claude Sonnet 4.5",
            abilities = listOf(ModelAbility.REASONING),
        )
        var pickedLevel: ReasoningLevel? = null
        var compacted = 0
        val reasoningState = resolveComposerReasoningState(model, ReasoningLevel.AUTO)
        val snapshot = ComposerContextSnapshot(
            messageCount = 4,
            usedTokens = 24_000,
            windowTokens = 200_000,
            lastTurnPromptTokens = 12_000,
            lastTurnCompletionTokens = 300,
            lastTurnCachedTokens = 6_000,
            lastTurnElapsedMs = 5_000L,
            hasModel = true,
        )

        compose.setContent {
            var activePopover by remember { mutableStateOf<ComposerFocusPopover?>(null) }
            Column {
                ComposerFocusToolsRow(
                    model = model,
                    onOpenModelPicker = {},
                    reasoningState = reasoningState,
                    onSelectReasoningLevel = { _, level -> pickedLevel = level },
                    contextSnapshot = snapshot,
                    compactLifecycleState = CompactLifecycleState.idle(),
                    onCompactContext = { compacted++ },
                    activePopover = activePopover,
                    onActivePopoverChange = { activePopover = it },
                )
                ComposerThinkingPopoverContent(
                    model = model,
                    reasoningState = reasoningState,
                    onSelectLevel = { pickedLevel = it },
                )
                ComposerContextPopoverContent(
                    snapshot = snapshot,
                    compacting = false,
                    onCompactContext = { compacted++ },
                )
            }
        }

        compose.onNodeWithTag("chat-composer-model-picker").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer-thinking-button").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer-context-button").assertIsDisplayed()

        compose.onNodeWithTag("chat-composer-thinking-option-high").performClick()
        assertEquals(ReasoningLevel.HIGH, pickedLevel)

        compose.onNodeWithText("24K / 200K").assertIsDisplayed()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithTag("chat-composer-context-compact").performClick()
        assertEquals(1, compacted)
    }

    @Test
    fun bottomBar_selectionState_togglesAndSwitchesAcrossTabs() {
        assertEquals(PersonalAgentModeTab.TASKS, nextModeTabOnTap(PersonalAgentModeTab.CHAT, PersonalAgentModeTab.TASKS))
        assertEquals(PersonalAgentModeTab.CENTER, nextModeTabOnTap(PersonalAgentModeTab.TASKS, PersonalAgentModeTab.CENTER))
        assertEquals(PersonalAgentModeTab.CHAT, nextModeTabOnTap(PersonalAgentModeTab.CENTER, PersonalAgentModeTab.CENTER))
        assertEquals(PersonalAgentModeTab.CHAT, nextModeTabOnTap(PersonalAgentModeTab.TASKS, PersonalAgentModeTab.CHAT))

        val bounds = mapOf(
            PersonalAgentModeTab.CHAT to (0f to 60f),
            PersonalAgentModeTab.TASKS to (62f to 122f),
            PersonalAgentModeTab.CENTER to (124f to 184f),
        )
        assertEquals(PersonalAgentModeTab.CHAT, resolveScrubbedModeTab(20f, bounds))
        assertEquals(PersonalAgentModeTab.TASKS, resolveScrubbedModeTab(90f, bounds))
        assertEquals(PersonalAgentModeTab.CENTER, resolveScrubbedModeTab(160f, bounds))

        // Simulate a portable theme where raised == surface and ink2 == ink.
        val flatTokens = buildAmberTokens(AmberBase.LIGHT, Color(0xFFB83B2A)).let {
            it.copy(raised = it.surface, ink2 = it.ink)
        }
        compose.setContent {
            CompositionLocalProvider(LocalAmberTokens provides flatTokens) {
                var activeTab by remember { mutableStateOf(PersonalAgentModeTab.CHAT) }
                PersonalAgentBottomBar(
                    selectedTab = activeTab,
                    activeTaskCount = 2,
                    selectedRole = null,
                    cloud = MacGatewayRepository.State(loaded = true),
                    mac = MacGatewayRepository.State(loaded = true),
                    onSelectChat = { activeTab = PersonalAgentModeTab.CHAT },
                    onOpenTasks = { activeTab = nextModeTabOnTap(activeTab, PersonalAgentModeTab.TASKS) },
                    onOpenCenter = { activeTab = nextModeTabOnTap(activeTab, PersonalAgentModeTab.CENTER) },
                    onScrubTab = { activeTab = it },
                    onSelectHost = {},
                    onOpenGatewayPairing = {},
                )
            }
        }

        compose.onNodeWithTag("personal-agent-tab-chat").assertIsSelected()
        compose.onNodeWithTag("personal-agent-tab-tasks").assertIsNotSelected().performClick()
        compose.onNodeWithTag("personal-agent-tab-tasks").assertIsSelected()
        compose.onNodeWithTag("personal-agent-tab-chat").assertIsNotSelected()

        compose.onNodeWithTag("personal-agent-tab-center").performClick()
        compose.onNodeWithTag("personal-agent-tab-center").assertIsSelected()
        compose.onNodeWithTag("personal-agent-tab-tasks").assertIsNotSelected()

        // Tapping the active Center tab collapses back to Chat.
        compose.onNodeWithTag("personal-agent-tab-center").performClick()
        compose.onNodeWithTag("personal-agent-tab-chat").assertIsSelected()

        // Verify compact 34dp capsules (28dp segment pills + 3dp track padding) and 8dp bar top padding.
        val tabBounds = compose.onNodeWithTag("personal-agent-tab-chat").getUnclippedBoundsInRoot()
        val hostBounds = compose.onNodeWithTag("personal-agent-host-pill").getUnclippedBoundsInRoot()
        val barBounds = compose.onNodeWithTag("personal-agent-bottom-bar").getUnclippedBoundsInRoot()
        assertTrue("expected 28dp tab pill, got ${tabBounds.height}", abs(tabBounds.height.value - 28f) <= 1f)
        assertTrue("expected 34dp host pill, got ${hostBounds.height}", abs(hostBounds.height.value - 34f) <= 1f)
        assertTrue("expected 42dp bar (8dp top + 34dp pills), got ${barBounds.height}", abs(barBounds.height.value - 42f) <= 1f)
    }

    @Test
    fun focusToolsRow_alignsVisualHeightAndCenterlineAcrossStatesAndLargeFont() {
        var currentModel by mutableStateOf<Model?>(null)
        var fontScale by mutableStateOf(1f)
        val longModel = Model(
            modelId = "claude-opus-ultra-extended-preview-20261127",
            displayName = "Claude Opus Ultra Extended Long Display Name 2026",
            abilities = listOf(ModelAbility.REASONING),
        )
        val snapshot = ComposerContextSnapshot(
            messageCount = 2,
            usedTokens = 8_000,
            windowTokens = 200_000,
            lastTurnPromptTokens = 4_000,
            lastTurnCompletionTokens = 200,
            lastTurnCachedTokens = 1_000,
            lastTurnElapsedMs = 2_000L,
            hasModel = true,
        )

        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale = fontScale),
            ) {
                ComposerFocusToolsRow(
                    model = currentModel,
                    onOpenModelPicker = {},
                    reasoningState = resolveComposerReasoningState(currentModel, ReasoningLevel.AUTO),
                    onSelectReasoningLevel = { _, _ -> },
                    contextSnapshot = snapshot,
                    compactLifecycleState = CompactLifecycleState.idle(),
                    onCompactContext = {},
                    activePopover = null,
                    onActivePopoverChange = {},
                )
            }
        }

        fun assertDefaultAlignment() {
            val modelTouch = compose.onNodeWithTag("chat-composer-model-picker").getUnclippedBoundsInRoot()
            val modelVis = compose.onNodeWithTag("chat-composer-model-picker-visual", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val thinkVis = compose.onNodeWithTag("chat-composer-thinking-visual", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val ctxVis = compose.onNodeWithTag("chat-composer-context-visual", useUnmergedTree = true).getUnclippedBoundsInRoot()

            assertTrue("Model touch height >= 44dp, was ${modelTouch.height}", modelTouch.height >= ComposerFocusToolTouchHeight - 0.5.dp)
            assertTrue("Model visual height == 34dp, was ${modelVis.height}", abs(modelVis.height.value - ComposerFocusToolVisualHeight.value) <= 0.5f)
            assertTrue("Thinking visual height == 34dp, was ${thinkVis.height}", abs(thinkVis.height.value - ComposerFocusToolVisualHeight.value) <= 0.5f)
            assertTrue("Context visual height == 34dp, was ${ctxVis.height}", abs(ctxVis.height.value - ComposerFocusToolVisualHeight.value) <= 0.5f)

            val modelCenter = (modelVis.top.value + modelVis.bottom.value) / 2f
            val thinkCenter = (thinkVis.top.value + thinkVis.bottom.value) / 2f
            val ctxCenter = (ctxVis.top.value + ctxVis.bottom.value) / 2f
            assertTrue("Model vs Thinking centerline aligned ($modelCenter vs $thinkCenter)", abs(modelCenter - thinkCenter) <= 0.5f)
            assertTrue("Model vs Context centerline aligned ($modelCenter vs $ctxCenter)", abs(modelCenter - ctxCenter) <= 0.5f)
            assertTrue("Model pill capped <= 220dp, was ${modelVis.width}", modelVis.width <= 220.5.dp)
        }

        // 1) Unselected placeholder state
        assertDefaultAlignment()

        // 2) Long selected model name stays single-line, ellipsized within 220dp, and height-aligned
        compose.runOnIdle { currentModel = longModel }
        compose.waitForIdle()
        assertDefaultAlignment()

        // 3) Large font scale expands height smoothly without clipping while keeping centerline aligned
        compose.runOnIdle { fontScale = 1.6f }
        compose.waitForIdle()
        val largeModelVis = compose.onNodeWithTag("chat-composer-model-picker-visual", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val largeThinkVis = compose.onNodeWithTag("chat-composer-thinking-visual", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Large text expands pill height >= 34dp, was ${largeModelVis.height}", largeModelVis.height >= ComposerFocusToolVisualHeight)
        val largeModelCenter = (largeModelVis.top.value + largeModelVis.bottom.value) / 2f
        val largeThinkCenter = (largeThinkVis.top.value + largeThinkVis.bottom.value) / 2f
        assertTrue("Large text centerline stays aligned ($largeModelCenter vs $largeThinkCenter)", abs(largeModelCenter - largeThinkCenter) <= 0.5f)
    }

    @Test
    fun segmentedReplyDelivery_commitsNaturalBoundariesAndDisablesAutoExpandThinking() {
        val live = app.amber.core.settings.DisplaySetting(
            replyDeliveryMode = app.amber.core.settings.ReplyDeliveryMode.LIVE_STREAMING,
            showThinkingContent = true,
        )
        assertTrue(live.effectiveShowThinkingContent())

        val segmented = live.copy(
            replyDeliveryMode = app.amber.core.settings.ReplyDeliveryMode.SEGMENTED_BLOCKS,
        )
        assertFalse(segmented.effectiveShowThinkingContent())

        // Incomplete single paragraph is withheld while streaming
        assertEquals(
            "",
            app.amber.feature.ui.components.message.committedSegmentedReplyPrefix(
                text = "你好，我正在帮你整理这段回复",
                isStreaming = true,
            ),
        )
        // Completed paragraph followed by blank line commits cleanly without the trailing draft
        assertEquals(
            "第一段完整结论。",
            app.amber.feature.ui.components.message.committedSegmentedReplyPrefix(
                text = "第一段完整结论。\n\n第二段还在生成",
                isStreaming = true,
            ),
        )
        // Code fence containing blank lines is never split mid-fence
        assertEquals(
            "",
            app.amber.feature.ui.components.message.committedSegmentedReplyPrefix(
                text = "```kotlin\nval a = 1\n\nval b = 2\n",
                isStreaming = true,
            ),
        )
        // Closing the code fence commits the complete fenced block
        assertEquals(
            "```kotlin\nval a = 1\n\nval b = 2\n```",
            app.amber.feature.ui.components.message.committedSegmentedReplyPrefix(
                text = "```kotlin\nval a = 1\n\nval b = 2\n```\n",
                isStreaming = true,
            ),
        )
        // Streaming completion flushes full text immediately
        assertEquals(
            "完整单段回复",
            app.amber.feature.ui.components.message.committedSegmentedReplyPrefix(
                text = "完整单段回复",
                isStreaming = false,
            ),
        )
    }

    @Test
    fun modeSheetOverlay_latchesContentAndKeepsBottomDockStationaryDuringExit() {
        assertEquals(
            PersonalAgentModeTab.TASKS,
            resolveModeSheetRenderedTab(PersonalAgentModeTab.CHAT, PersonalAgentModeTab.TASKS),
        )
        assertEquals(
            PersonalAgentModeTab.CENTER,
            resolveModeSheetRenderedTab(PersonalAgentModeTab.CHAT, PersonalAgentModeTab.CENTER),
        )
        assertEquals(
            PersonalAgentModeTab.CENTER,
            resolveModeSheetRenderedTab(PersonalAgentModeTab.CENTER, PersonalAgentModeTab.TASKS),
        )
        assertTrue(ModeSheetExitFadeDurationMs >= 180)
        assertTrue(ModeSheetExitSlideDurationMs >= ModeSheetExitFadeDurationMs)

        var activeTab by mutableStateOf(PersonalAgentModeTab.TASKS)
        compose.setContent {
            PersonalAgentModeSheetOverlay(
                activeTab = activeTab,
                onSelectTab = { activeTab = it },
                onOpenTaskSettings = {},
                bottomBar = {
                    PersonalAgentBottomBar(
                        selectedTab = activeTab,
                        activeTaskCount = 1,
                        selectedRole = null,
                        cloud = MacGatewayRepository.State(loaded = true),
                        mac = MacGatewayRepository.State(loaded = true),
                        onSelectChat = { activeTab = PersonalAgentModeTab.CHAT },
                        onOpenTasks = { activeTab = nextModeTabOnTap(activeTab, PersonalAgentModeTab.TASKS) },
                        onOpenCenter = { activeTab = nextModeTabOnTap(activeTab, PersonalAgentModeTab.CENTER) },
                        onSelectHost = {},
                        onOpenGatewayPairing = {},
                    )
                },
                panelBodyContent = { tab ->
                    androidx.compose.material3.Text(
                        text = "panel-${tab.name}",
                        modifier = Modifier.testTag("mode-sheet-test-body-${tab.name}"),
                    )
                },
            )
        }

        compose.waitForIdle()
        compose.onNodeWithText("panel-TASKS").assertIsDisplayed()
        val openSheetBounds = compose.onNodeWithTag("personal-agent-mode-sheet").getUnclippedBoundsInRoot()
        val openDockBounds = compose.onNodeWithTag("personal-agent-mode-sheet-bottom-dock").getUnclippedBoundsInRoot()

        // Pause clock and trigger dismiss via real close button click -> step frame-by-frame
        // into mid-exit (~112ms) so Transition starts and visibly slides the sheet downward
        // while keeping TASKS content latched and the bottom dock stationary.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("personal-agent-mode-sheet-close").performClick()
        repeat(9) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()

        compose.onNodeWithText("panel-TASKS").assertIsDisplayed()
        compose.onNodeWithTag("personal-agent-mode-sheet").assertIsDisplayed()
        val midExitDockBounds = compose.onNodeWithTag("personal-agent-mode-sheet-bottom-dock").getUnclippedBoundsInRoot()
        assertEquals(PersonalAgentModeTab.CHAT, activeTab)
        assertTrue(
            "expected bottom dock to stay stationary at y=0 (open=${openDockBounds.top}, mid=${midExitDockBounds.top})",
            abs(midExitDockBounds.top.value - openDockBounds.top.value) <= 1f,
        )

        // Let exit complete cleanly.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("personal-agent-mode-sheet").assertDoesNotExist()
        compose.onNodeWithTag("personal-agent-mode-sheet-bottom-dock").assertDoesNotExist()
        compose.onNodeWithText("panel-TASKS").assertDoesNotExist()
    }
}
