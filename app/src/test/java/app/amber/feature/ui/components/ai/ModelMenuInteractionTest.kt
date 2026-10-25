package app.amber.feature.ui.components.ai

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.model.Conversation
import app.amber.core.settings.Settings
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.overlay.AppModalOverlayHost
import app.amber.feature.ui.overlay.LocalAppModalOverlayHost
import app.amber.feature.ui.overlay.rememberAppModalOverlayHostState
import app.amber.feature.ui.pages.chat.ChatModelMenuOverlay
import app.amber.feature.ui.pages.chat.ChatPageTopBar
import app.amber.feature.ui.pages.sessionhome.HomeHeader
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.uuid.Uuid

/**
 * Compose UI **interaction** tests for the Graphite model menu (`ModelList.kt`, design §6.2
 * "Top model menu" + the model-picker reasoning segment) — criterion 5.
 *
 * Seam: the model menu's reasoning selector [ThinkingLevelSegment] is a stateless `internal`
 * composable that takes its segment data + an `onChange` callback (no `koinViewModel()` / no
 * `koinInject`), so we render it directly with fake data and drive real taps. It reads only
 * `LocalChatTheme`, which has a default value, so no Koin/theme scaffolding is required.
 * ModelList receives favorites and updates from its owner, so the real search field,
 * provider expansion, lazy row, and selection callback can be exercised without starting Koin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Stub Application: the real `AmberAgentApp.onCreate()` calls `startKoin {…}`, and Robolectric
// re-creates the Application per test — the 2nd test would then throw
// KoinApplicationAlreadyStartedException. These tests render a stateless composable that needs no
// Koin, so a bare Application keeps each test isolated.
@Config(sdk = [34], application = Application::class)
class ModelMenuInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val claudeLevels = reasoningLevelsForModel(Model(modelId = "claude-sonnet-4.5"))

    @Test
    fun reasoningSegment_rendersEveryLevelLabel() {
        compose.setContent {
            Column {
                ThinkingLevelSegment(
                    levels = claudeLevels,
                    current = ReasoningLevel.AUTO,
                    onChange = {},
                )
            }
        }
        // claude segment set = auto / low / med / high / xhigh / max
        compose.onNodeWithText("auto").assertIsDisplayed()
        compose.onNodeWithText("low").assertIsDisplayed()
        compose.onNodeWithText("high").assertIsDisplayed()
        compose.onNodeWithText("max").assertIsDisplayed()
    }

    @Test
    fun topModelMenu_reasoningFooterFiresCallbackWithTheSupportedLevel() {
        var picked: ReasoningLevel? = null
        val model = Model(
            modelId = "deepseek-v4.1-flash",
            displayName = "DeepSeek Flash",
            abilities = listOf(ModelAbility.REASONING),
        )
        val provider = ProviderSetting.OpenAI(models = listOf(model))
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = listOf(provider),
                modelType = ModelType.CHAT,
                currentProviderId = provider.id,
                currentModelId = model.id,
                onSelect = {},
                onClose = {},
                reasoningLevel = ReasoningLevel.MAX,
                onUpdateReasoningLevel = { picked = it },
            )
        }

        assertNull("no selection before any tap", picked)
        compose.onNodeWithText("Thinking level").assertIsDisplayed()
        compose.onNodeWithText("High").performClick()
        assertEquals(ReasoningLevel.HIGH, picked)
    }

    @Test
    fun reasoningSegment_selectionStateMachine_movesActiveAcrossTaps() {
        // Closed→Open is the menu opening (covered structurally below); here we drive the
        // Selected transition: the hoisted `current` follows whatever the user taps, and the
        // segment re-renders with the new active label — a real Idle→Selected→Reselect cycle.
        compose.setContent {
            var current by remember { mutableStateOf(ReasoningLevel.AUTO) }
            ThinkingLevelSegment(
                levels = claudeLevels,
                current = current,
                onChange = { current = it },
            )
        }

        compose.onNodeWithText("low").performClick()
        // re-select a different one; both labels stay present, no crash, callback re-drives state
        compose.onNodeWithText("max").performClick()
        compose.onNodeWithText("max").assertIsDisplayed()
        compose.onNodeWithText("low").assertIsDisplayed()
    }

    @Test
    fun reasoningSegment_perModelLevelSets_areCorrect() {
        // The menu shows different reasoning sets per model family (design §ThinkingLevel).
        // gpt → low/med/high/xhigh (no "auto"); deepseek → off/high/max.
        compose.setContent {
            ThinkingLevelSegment(
                levels = reasoningLevelsForModel(Model(modelId = "gpt-5")),
                current = ReasoningLevel.MEDIUM,
                onChange = {},
            )
        }
        compose.onNodeWithText("low").assertIsDisplayed()
        compose.onNodeWithText("xhigh").assertIsDisplayed()
        // gpt set has no "auto" segment
        compose.onNodeWithText("auto").assertDoesNotExist()
    }

    @Test
    fun topModelMenu_openWithoutProvidersShowsEmptyState() {
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = emptyList(),
                modelType = ModelType.CHAT,
                currentProviderId = null,
                currentModelId = null,
                onSelect = {},
                onClose = {},
            )
        }

        compose.onNodeWithText("No available AI providers, please add in settings")
            .assertIsDisplayed()
    }

    // ── Dismiss geometry, driven by real touch coordinates ─────────────────────────────
    // 真机反馈：菜单打开后只有「点模型」能关掉，点输入区/底部空白没反应。根因是 overlay 放在
    // Scaffold 的 content slot 里，被 bottomBar 高度截断；同时面板自身空白处会穿透到遮罩。
    // 下面这些用例用坐标注入触摸（而不是 semantics performClick），覆盖这两条。

    private fun dismissProvider(vararg modelIds: String) = ProviderSetting.OpenAI(
        name = "Dismiss provider",
        models = modelIds.map { id ->
            Model(modelId = id, displayName = id, type = ModelType.CHAT)
        },
    )

    @Test
    fun topModelMenu_touchOnLowerScrimClosesAndDoesNotReachThePageBelow() {
        val provider = dismissProvider("dismiss-model")
        var closed = 0
        var pageTaps = 0
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize().clickable { pageTaps += 1 }) {
                TopModelMenu(
                    open = open,
                    providers = listOf(provider),
                    modelType = ModelType.CHAT,
                    currentProviderId = provider.id,
                    currentModelId = provider.models.first().id,
                    onSelect = {},
                    onClose = {
                        closed += 1
                        open = false
                    },
                )
            }
        }

        // 面板只有 ~110dp 高，屏幕底部 8dp 处是遮罩（真机上这段对应输入区所在的下半屏）。
        compose.onRoot().performTouchInput { click(Offset(centerX, height - 8.dp.toPx())) }
        compose.waitForIdle()

        assertEquals("scrim below the panel must dismiss the menu", 1, closed)
        assertEquals("the dismissing tap must not reach the page behind", 0, pageTaps)
        // 关闭后必须仍在页面上：底层页面重新可点（不是被残留 overlay 或导航吞掉）。
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("page must stay interactive after dismissing", 1, pageTaps)
    }

    @Test
    fun topModelMenu_touchOnPanelBlankAreaKeepsItOpen() {
        val provider = dismissProvider("blank-model")
        var closed = 0
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = listOf(provider),
                modelType = ModelType.CHAT,
                currentProviderId = provider.id,
                currentModelId = provider.models.first().id,
                onSelect = {},
                onClose = { closed += 1 },
                // 复刻真实 header（上下文用量）与内边距：没有 pointerInput/clickable。
                header = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("model-menu-blank-header"),
                    )
                },
            )
        }

        compose.onNodeWithTag("model-menu-blank-header").performTouchInput { click(center) }
        compose.waitForIdle()

        assertEquals("blank area inside the panel must not dismiss", 0, closed)
    }

    @Test
    fun topModelMenu_longListKeepsBottomScrimTappableAndFooterVisible() {
        // 真机遗漏：真实服务商长列表 + 推理 footer 会把面板撑满 header 以下整屏，
        // 面板下沿之外没有可点的外部遮罩。面板须按可用高度收口、始终留 48dp。
        val provider = longListProvider()
        var closed = 0
        var pageTaps = 0
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            Box(Modifier.fillMaxSize().clickable { pageTaps += 1 }) {
                TopModelMenu(
                    open = open,
                    providers = listOf(provider),
                    modelType = ModelType.CHAT,
                    currentProviderId = provider.id,
                    currentModelId = provider.models.first().id,
                    onSelect = {},
                    onClose = {
                        closed += 1
                        open = false
                    },
                    reasoningLevel = ReasoningLevel.HIGH,
                    onUpdateReasoningLevel = {},
                )
            }
        }

        compose.onNodeWithText("Thinking level").assertIsDisplayed()
        compose.onRoot().performTouchInput { click(Offset(centerX, height - 8.dp.toPx())) }
        compose.waitForIdle()

        assertEquals("the reserved scrim below a long panel must dismiss", 1, closed)
        assertEquals("that tap must not reach the page behind", 0, pageTaps)
        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals("page must stay interactive after dismissing", 1, pageTaps)
    }

    @Test
    fun topModelMenu_longListScrollKeepsFooterAndDoesNotDismiss() {
        val provider = longListProvider()
        var closed = 0
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = listOf(provider),
                modelType = ModelType.CHAT,
                currentProviderId = provider.id,
                currentModelId = provider.models.first().id,
                onSelect = {},
                onClose = { closed += 1 },
                reasoningLevel = ReasoningLevel.HIGH,
                onUpdateReasoningLevel = {},
            )
        }

        compose.onNodeWithText("claude-0").assertIsDisplayed()
        verticalList().performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals("scrolling a long list must not dismiss", 0, closed)
        // footer 不参与滚动，始终留在面板底部可见。
        compose.onNodeWithText("Thinking level").assertIsDisplayed()
        compose.onNodeWithText("claude-0").assertIsNotDisplayed()
    }

    @Test
    fun topModelMenu_shortAvailableHeightStillLeavesBottomScrimAndFooter() {
        // 键盘打开/短屏时可用高度远小于 560dp：面板必须收在可用高度内并留 48dp 遮罩，
        // footer 仍可见（这里用 top inset 把可用高度压到 ~170dp 模拟）。
        val provider = longListProvider()
        var closed = 0
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = listOf(provider),
                modelType = ModelType.CHAT,
                currentProviderId = provider.id,
                currentModelId = provider.models.first().id,
                onSelect = {},
                onClose = { closed += 1 },
                reasoningLevel = ReasoningLevel.HIGH,
                onUpdateReasoningLevel = {},
                panelTopInset = 300.dp,
            )
        }

        compose.onNodeWithText("Thinking level").assertIsDisplayed()
        compose.onRoot().performTouchInput { click(Offset(centerX, height - 8.dp.toPx())) }
        compose.waitForIdle()

        assertEquals("short panels must keep the bottom scrim tappable", 1, closed)
    }

    @Test
    fun topModelMenu_shortHeightWithUsageHeaderStillReachesAndSelectsAModel() {
        // 真机折叠屏 + 键盘：header 以下只剩 ~180dp，固定 usage header + 固定思考等级 footer
        // 会把 provider/model 列表挤成 0 高（真机 XML 里连一行模型都没有）。
        // 这里在同样的短高度下带真实 header + long models + reasoning footer，必须能滚到并选中模型。
        val provider = longListProvider()
        var selected: Model? = null
        var closed = 0
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val topInset = (maxHeight - 180.dp).coerceAtLeast(0.dp)
                TopModelMenu(
                    open = true,
                    providers = listOf(provider),
                    modelType = ModelType.CHAT,
                    currentProviderId = provider.id,
                    currentModelId = provider.models.first().id,
                    onSelect = { selected = it },
                    onClose = { closed += 1 },
                    reasoningLevel = ReasoningLevel.HIGH,
                    onUpdateReasoningLevel = {},
                    header = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("model-menu-usage-header"),
                        )
                    },
                    panelTopInset = topInset,
                )
            }
        }

        // footer 仍固定可见；header 在短高度下不再是不可滚动的固定块。
        compose.onNodeWithText("Thinking level").assertIsDisplayed()
        compose.onNodeWithTag("model-menu-usage-header").assertIsDisplayed()

        // 真实交互：把 header 滑走后模型行可见，点击后回调到选中。
        repeat(3) {
            verticalList().performTouchInput {
                swipeUp(
                    startY = bottom - 2.dp.toPx(),
                    endY = top + 2.dp.toPx(),
                    durationMillis = 800,
                )
            }
            compose.waitForIdle()
        }
        compose.waitForIdle()
        compose.onNodeWithText("claude-0").assertIsDisplayed().performClick()

        assertEquals(provider.models.first().id, selected?.id)
        assertEquals("selecting a model must not dismiss through the panel", 0, closed)
    }

    private fun longListProvider() = ProviderSetting.OpenAI(
        name = "Long dismiss provider",
        models = listOf(
            Model(
                modelId = "claude-sonnet-4.5",
                displayName = "claude-0",
                type = ModelType.CHAT,
                abilities = listOf(ModelAbility.REASONING),
            ),
        ) + List(39) { index ->
            Model(
                modelId = "long-$index",
                displayName = "long-$index",
                type = ModelType.CHAT,
            )
        },
    )

    @Test
    fun topModelMenu_closedDoesNotInterceptPageTouches() {
        var pageTaps = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().clickable { pageTaps += 1 }) {
                TopModelMenu(
                    open = false,
                    providers = emptyList(),
                    modelType = ModelType.CHAT,
                    currentProviderId = null,
                    currentModelId = null,
                    onSelect = {},
                    onClose = {},
                )
            }
        }

        compose.onRoot().performTouchInput { click(center) }
        compose.waitForIdle()

        assertEquals(1, pageTaps)
    }

    @Test
    fun topModelMenu_providerAccordionAndModelSelectionStillWork() {
        val provider = dismissProvider("selectable-model")
        var selected: Model? = null
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = listOf(provider),
                modelType = ModelType.CHAT,
                currentProviderId = provider.id,
                currentModelId = null,
                onSelect = { selected = it },
                onClose = {},
            )
        }

        compose.onNodeWithText("selectable-model").assertIsDisplayed()
        compose.onNodeWithText("Dismiss provider").performClick()
        compose.onNodeWithText("selectable-model").assertDoesNotExist()
        compose.onNodeWithText("Dismiss provider").performClick()
        compose.onNodeWithText("selectable-model").assertIsDisplayed().performClick()

        assertEquals(provider.models.first().id, selected?.id)
    }

    @Test
    fun topModelMenu_scrollingInsideThePanelDoesNotDismiss() {
        val provider = ProviderSetting.OpenAI(
            name = "Long dismiss provider",
            models = List(30) { index ->
                Model(
                    modelId = "long-$index",
                    displayName = "long-$index",
                    type = ModelType.CHAT,
                )
            },
        )
        var closed = 0
        compose.setContent {
            TopModelMenu(
                open = true,
                providers = listOf(provider),
                modelType = ModelType.CHAT,
                currentProviderId = provider.id,
                currentModelId = null,
                onSelect = {},
                onClose = { closed += 1 },
            )
        }

        compose.onNodeWithText("long-0").assertIsDisplayed()
        verticalList().performTouchInput { swipeUp() }
        compose.waitForIdle()

        assertEquals("scrolling the panel must not dismiss", 0, closed)
        compose.onNodeWithText("long-0").assertIsNotDisplayed()
    }

    @Test
    fun topModelMenu_backPressDismissesWhileOpen() {
        var closed = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            TopModelMenu(
                open = true,
                providers = emptyList(),
                modelType = ModelType.CHAT,
                currentProviderId = null,
                currentModelId = null,
                onSelect = {},
                onClose = { closed += 1 },
            )
        }

        compose.runOnIdle { dispatcher?.onBackPressed() }

        assertEquals(1, closed)
    }

    @Test
    fun modelList_keepsSearchAndModelRowVisibleAndSelectable() {
        val model = Model(
            modelId = "model-visible",
            displayName = "model-visible",
            type = ModelType.CHAT,
        )
        val provider = ProviderSetting.OpenAI(
            name = "Test provider",
            models = listOf(model),
        )
        var picked: Model? = null

        compose.setContent {
            CompositionLocalProvider(
                LocalNavController provides Navigator(mutableListOf(Screen.SessionHome)),
            ) {
                Column(Modifier.height(600.dp)) {
                    ModelList(
                        currentModel = null,
                        providers = listOf(provider),
                        modelType = ModelType.CHAT,
                        onSelect = { picked = it },
                        onDismiss = {},
                        favoriteModelIds = emptyList(),
                        onFavoriteModelsChange = {},
                    )
                }
            }
        }

        val context = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithText(
            context.getString(R.string.model_list_search_placeholder),
        ).assertIsDisplayed()
        compose.onNodeWithText("model-visible").assertIsDisplayed().performClick()
        assertEquals(model.id, picked?.id)
    }

    @Test
    fun modelList_initialScrollTargetsCurrentModelAfterCollapsedProvider() {
        val firstProvider = ProviderSetting.OpenAI(
            name = "First provider",
            models = List(20) { index ->
                Model(
                    modelId = "first-$index",
                    displayName = "first-$index",
                    type = ModelType.CHAT,
                )
            },
        )
        val target = Model(
            modelId = "target-model",
            displayName = "target-model",
            type = ModelType.CHAT,
        )
        val secondProvider = ProviderSetting.OpenAI(
            name = "Second provider",
            models = listOf(target) + List(29) { index ->
                Model(
                    modelId = "second-${index + 1}",
                    displayName = "second-${index + 1}",
                    type = ModelType.CHAT,
                )
            },
        )

        compose.setContent {
            CompositionLocalProvider(
                LocalNavController provides Navigator(mutableListOf(Screen.SessionHome)),
            ) {
                Column(Modifier.height(600.dp)) {
                    ModelList(
                        currentModel = target.id,
                        providers = listOf(firstProvider, secondProvider),
                        modelType = ModelType.CHAT,
                        onSelect = {},
                        onDismiss = {},
                        favoriteModelIds = emptyList(),
                        onFavoriteModelsChange = {},
                    )
                }
            }
        }

        // The first provider is collapsed by default; the selected model's provider is open.
        // Its first row must be visible even though the earlier provider owns many hidden rows.
        compose.onNodeWithText("target-model").assertIsDisplayed()
    }

    @Test
    fun modelList_providerBadgeTargetsHeaderWhenSearchFiltersOutFirstProvider() {
        val providers = listOf(
            catalogProvider("First provider", "excluded"),
            catalogProvider("Second provider", "match"),
        )
        renderModelList(providers)

        compose.onNode(hasSetTextAction()).performTextInput("match")
        // Calibrate the physical header anchor with a direct list action. Scroll
        // semantics expose a distance estimate, not a stable item-index encoding.
        verticalList().performScrollToIndex(1)
        val expectedTop = headerTop("second-provider")
        verticalList().performScrollToIndex(0)
        compose.onNodeWithText("Second provider").performClick()
        compose.waitForIdle()

        // The filtered first group emits no header, but the second provider still has
        // its leading group gap. Jumping to that gap leaves the header below the top.
        assertEquals(expectedTop, headerTop("second-provider"), 0.5f)
    }

    @Test
    fun modelList_providerBadgeUsesExpansionFromUpdatedCurrentModel() {
        val providers = listOf(
            catalogProvider("First provider", "first"),
            catalogProvider("Second provider", "second"),
        )
        var selectedModel by mutableStateOf(providers.first().models.first().id)
        renderModelList(providers, currentModel = { selectedModel })

        compose.runOnIdle { selectedModel = providers[1].models.first().id }
        verticalList().performScrollToIndex(2)
        val expectedTop = headerTop("second-provider")
        verticalList().performScrollToIndex(0)
        compose.onNodeWithText("Second provider").performClick()
        compose.waitForIdle()

        // The previous provider now contains only its collapsed header. The next
        // header follows that header and its gap, irrespective of its old row count.
        assertEquals(expectedTop, headerTop("second-provider"), 0.5f)
    }

    @Test
    fun modelList_badgeFollowsProviderHeaderAfterEarlierGroupIsCollapsed() {
        val providers = List(6) { index ->
            catalogProvider("Provider $index long badge", "model-$index")
        }
        renderModelList(providers)

        compose.onNodeWithText("provider-0-long-badge").performClick()
        compose.onNodeWithText("model-0-0").assertDoesNotExist()
        val expectedBadgeLeft = expectedThirdBadgeLeft()
        // The collapsed first group contributes one item, the second contributes
        // gap/header/20 models, then the third group's gap and header occupy 23/24.
        verticalList().performScrollToIndex(24)

        assertThirdBadgeFollows(expectedBadgeLeft)
    }

    @Test
    fun modelList_badgeFollowsProviderHeaderAfterSearchChangesGroupPositions() {
        val providers = List(6) { index ->
            catalogProvider(
                "Provider $index long badge",
                if (index == 0) "excluded" else "match-$index",
            )
        }
        renderModelList(providers)

        compose.onNode(hasSetTextAction()).performTextInput("match")
        val expectedBadgeLeft = expectedThirdBadgeLeft()
        // Only providers 1..5 remain: gap/header/20 models, then gap/header.
        verticalList().performScrollToIndex(23)

        assertThirdBadgeFollows(expectedBadgeLeft)
    }

    private fun catalogProvider(name: String, modelPrefix: String) = ProviderSetting.OpenAI(
        name = name,
        models = List(20) { index ->
            Model(
                modelId = "$modelPrefix-$index",
                displayName = "$modelPrefix-$index",
                type = ModelType.CHAT,
            )
        },
    )

    private fun renderModelList(
        providers: List<ProviderSetting>,
        currentModel: @Composable () -> kotlin.uuid.Uuid? = { null },
    ) {
        compose.setContent {
            CompositionLocalProvider(
                LocalNavController provides Navigator(mutableListOf(Screen.SessionHome)),
            ) {
                Column(Modifier.height(600.dp)) {
                    ModelList(
                        currentModel = currentModel(),
                        providers = providers,
                        modelType = ModelType.CHAT,
                        onSelect = {},
                        onDismiss = {},
                        favoriteModelIds = emptyList(),
                        onFavoriteModelsChange = {},
                    )
                }
            }
        }
    }

    private fun verticalList() = compose.onNode(SemanticsMatcher("vertical model list") {
        it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
    })

    private fun headerTop(label: String): Float {
        compose.onNodeWithText(label).assertIsDisplayed()
        return compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
    }

    private fun horizontalList() = compose.onNode(SemanticsMatcher("provider badge row") {
        it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
    })

    private fun expectedThirdBadgeLeft(): Float {
        horizontalList().performScrollToIndex(2)
        val expected = compose.onNodeWithText("Provider 2 long badge")
            .fetchSemanticsNode().boundsInRoot.left
        horizontalList().performScrollToIndex(0)
        return expected
    }

    private fun assertThirdBadgeFollows(expectedLeft: Float) {
        // Assert the visible badge anchor itself, independent of scroll-range units.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Provider 2 long badge").fetchSemanticsNodes()
                .any { kotlin.math.abs(it.boundsInRoot.left - expectedLeft) <= 0.5f }
        }
        compose.onNodeWithText("Provider 2 long badge").assertIsDisplayed()
        assertEquals(
            expectedLeft,
            compose.onNodeWithText("Provider 2 long badge").fetchSemanticsNode().boundsInRoot.left,
            0.5f,
        )
    }

    // ── App-shell modal layer: header + two-pane home chrome are behind the same scrim ───────
    // 真机遗漏（PR38 head）：菜单只挂在 ChatPage 内部并且从 header 下方开始铺遮罩，于是
    // 1) 手机点 header 返回直接离开会话；2) 展开屏点左栏 SessionHomePage 顶栏设置直接跳页。
    // 下面用生产组件接线（ChatPageTopBar / HomeHeader / ChatModelMenuOverlay 发布到
    // AppModalOverlayHost）和真实坐标点按来锁定这两条路径。

    private fun regressionProvider(modelId: String = "app-shell-model") = ProviderSetting.OpenAI(
        name = "App shell provider",
        models = listOf(
            Model(
                modelId = modelId,
                displayName = modelId,
                type = ModelType.CHAT,
            ),
        ),
    )

    private fun settleMenuAnimation() {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
    }

    private fun assertMenuDismissedWithoutNavigation(
        closed: Int,
        navigations: Int,
        navigationLabel: String,
    ) {
        assertEquals("first tap must dismiss the menu once", 1, closed)
        assertEquals("$navigationLabel must not fire while the menu is open", 0, navigations)
        compose.onNodeWithTag("top-model-menu-panel").assertDoesNotExist()
    }

    /** Production phone shell: ChatPageTopBar + the model menu published to the app modal host. */
    @Composable
    private fun PhoneAppShellFixture(
        provider: ProviderSetting,
        onBack: () -> Unit,
        onMenuClosed: () -> Unit,
        onRouteBack: () -> Unit = {},
        initiallyOpen: Boolean = true,
    ) {
        val host = rememberAppModalOverlayHostState()
        var menuOpen by remember { mutableStateOf(initiallyOpen) }
        val density = LocalDensity.current
        var headerHeight by remember { mutableStateOf(0.dp) }
        val conversationFlow = remember {
            MutableStateFlow(
                Conversation(
                    assistantId = Uuid.random(),
                    title = "Regression conversation",
                    messageNodes = emptyList(),
                ),
            )
        }
        CompositionLocalProvider(LocalAppModalOverlayHost provides host) {
            Box(Modifier.fillMaxSize()) {
                // Stands in for the route-level back handler NavDisplay installs first.
                BackHandler { onRouteBack() }
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.onGloballyPositioned {
                            headerHeight = with(density) { it.size.height.toDp() }
                        },
                    ) {
                        ChatPageTopBar(
                            conversationFlow = conversationFlow,
                            bigScreen = false,
                            onBack = onBack,
                            currentChatModel = null,
                            generating = false,
                            processingStatus = null,
                            recapEligible = false,
                            recapOpen = false,
                            shelfOpen = false,
                            activityOpen = false,
                            otherActivityCount = 0,
                            modelMenuOpen = menuOpen,
                            onToggleModelMenu = { menuOpen = !menuOpen },
                            onIslandOpenRecap = {},
                            onIslandJumpToLive = {},
                            onIslandStop = {},
                            onToggleShelf = {},
                            onToggleActivity = {},
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth())
                }
                ChatModelMenuOverlay(
                    open = menuOpen,
                    providers = listOf(provider),
                    currentProviderId = provider.id,
                    currentModelId = provider.models.first().id,
                    reasoningLevel = null,
                    onSelect = {},
                    onClose = {
                        onMenuClosed()
                        menuOpen = false
                    },
                    onUpdateReasoningLevel = {},
                    panelTopInset = headerHeight,
                    panelStartInset = 0.dp,
                )
                AppModalOverlayHost(host)
            }
        }
    }

    /** Production wide shell: TwoPaneScene's list pane (HomeHeader settings) + chat detail pane. */
    @Composable
    private fun TwoPaneAppShellFixture(
        provider: ProviderSetting,
        onBack: () -> Unit,
        onOpenSettings: () -> Unit,
        onMenuClosed: () -> Unit,
    ) {
        val host = rememberAppModalOverlayHostState()
        var menuOpen by remember { mutableStateOf(true) }
        val density = LocalDensity.current
        var headerHeight by remember { mutableStateOf(0.dp) }
        val conversationFlow = remember {
            MutableStateFlow(
                Conversation(
                    assistantId = Uuid.random(),
                    title = "Regression conversation",
                    messageNodes = emptyList(),
                ),
            )
        }
        CompositionLocalProvider(LocalAppModalOverlayHost provides host) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(TWO_PANE_LIST_WIDTH).fillMaxHeight()) {
                        HomeHeader(
                            settings = remember { Settings() },
                            searchExpanded = false,
                            onOpenSearch = {},
                            onOpenSettings = onOpenSettings,
                            onOpenProfile = {},
                            onNewSession = {},
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.onGloballyPositioned {
                                    headerHeight = with(density) { it.size.height.toDp() }
                                },
                            ) {
                                ChatPageTopBar(
                                    conversationFlow = conversationFlow,
                                    bigScreen = true,
                                    onBack = onBack,
                                    currentChatModel = null,
                                    generating = false,
                                    processingStatus = null,
                                    recapEligible = false,
                                    recapOpen = false,
                                    shelfOpen = false,
                                    activityOpen = false,
                                    otherActivityCount = 0,
                                    modelMenuOpen = menuOpen,
                                    onToggleModelMenu = { menuOpen = !menuOpen },
                                    onIslandOpenRecap = {},
                                    onIslandJumpToLive = {},
                                    onIslandStop = {},
                                    onToggleShelf = {},
                                    onToggleActivity = {},
                                )
                            }
                            Box(Modifier.weight(1f).fillMaxWidth())
                        }
                        ChatModelMenuOverlay(
                            open = menuOpen,
                            providers = listOf(provider),
                            currentProviderId = provider.id,
                            currentModelId = provider.models.first().id,
                            reasoningLevel = null,
                            onSelect = {},
                            onClose = {
                                onMenuClosed()
                                menuOpen = false
                            },
                            onUpdateReasoningLevel = {},
                            panelTopInset = headerHeight,
                            panelStartInset = TWO_PANE_LIST_WIDTH,
                        )
                    }
                }
                AppModalOverlayHost(host)
            }
        }
    }

    @Test
    fun appShellModalLayer_closedToOpenPublishesThroughTheHost() {
        val provider = regressionProvider("closed-open-model")
        var navBack = 0
        var closed = 0
        compose.setContent {
            PhoneAppShellFixture(
                provider = provider,
                onBack = { navBack += 1 },
                onMenuClosed = { closed += 1 },
                initiallyOpen = false,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("top-model-menu-panel").assertDoesNotExist()

        // Real production trigger: the header model row toggles the route-owned open state, and
        // the app-level host must render the panel/scrim for this closed -> open transition.
        compose.onNodeWithTag("chat-header-model-menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("top-model-menu-scrim").assertIsDisplayed()
        compose.onNodeWithTag("top-model-menu-panel").assertIsDisplayed()

        val headerBack = compose.onNodeWithTag("chat-header-back")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(headerBack) }
        settleMenuAnimation()
        assertEquals(1, closed)
        assertEquals("opening from the header must not navigate", 0, navBack)

        val headerBackAfterDismiss = compose.onNodeWithTag("chat-header-back")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(headerBackAfterDismiss) }
        compose.waitForIdle()
        assertEquals(1, navBack)
    }

    @Test
    fun appShellModalLayer_phoneHeaderBackClosesMenuThenNavigatesOnSecondTap() {
        val provider = regressionProvider()
        var navBack = 0
        var closed = 0
        compose.setContent {
            PhoneAppShellFixture(
                provider = provider,
                onBack = { navBack += 1 },
                onMenuClosed = { closed += 1 },
            )
        }
        compose.waitForIdle()

        // The scrim must start at the window origin (covering the chat header), while the panel
        // stays anchored below the 62dp chat header row.
        val scrim = compose.onNodeWithTag("top-model-menu-scrim")
            .fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("top-model-menu-panel")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("scrim must cover the whole window from the top", 0f, scrim.top, 0.5f)
        assertEquals("scrim must cover the whole window from the left", 0f, scrim.left, 0.5f)
        assertTrue(
            "panel must stay below the header (was ${panel.top})",
            panel.top >= with(compose.density) { 61.dp.toPx() },
        )

        val headerBack = compose.onNodeWithTag("chat-header-back")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(headerBack) }
        settleMenuAnimation()

        assertMenuDismissedWithoutNavigation(
            closed = closed,
            navigations = navBack,
            navigationLabel = "the header back callback",
        )

        val headerBackAfterDismiss = compose.onNodeWithTag("chat-header-back")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(headerBackAfterDismiss) }
        compose.waitForIdle()
        assertEquals("after dismissal the same header back target navigates", 1, navBack)
    }

    @Test
    @Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp")
    fun appShellModalLayer_twoPaneHomeSettingsClosesMenuThenNavigatesOnSecondTap() {
        val provider = regressionProvider("wide-model")
        var navSettings = 0
        var closed = 0
        // HomeHeader's cursor blinks forever; drive frames manually so idling stays deterministic.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            TwoPaneAppShellFixture(
                provider = provider,
                onBack = {},
                onOpenSettings = { navSettings += 1 },
                onMenuClosed = { closed += 1 },
            )
        }
        compose.mainClock.advanceTimeBy(1_000)

        // Scrim covers the two-pane list too; the panel stays inside the chat detail pane.
        val scrim = compose.onNodeWithTag("top-model-menu-scrim")
            .fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("top-model-menu-panel")
            .fetchSemanticsNode().boundsInRoot
        assertEquals("two-pane scrim must cover the window from the left", 0f, scrim.left, 0.5f)
        assertTrue(
            "panel must start at the chat pane (was ${panel.left})",
            panel.left >= with(compose.density) { TWO_PANE_LIST_WIDTH.toPx() } - 1f,
        )

        val settings = compose.onNodeWithTag("home-header-settings")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(settings) }
        compose.mainClock.advanceTimeBy(800)

        assertMenuDismissedWithoutNavigation(
            closed = closed,
            navigations = navSettings,
            navigationLabel = "the two-pane home settings callback",
        )

        compose.onRoot().performTouchInput { click(settings) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("after dismissal the same settings target navigates", 1, navSettings)
    }

    @Test
    fun appShellModalLayer_duringCloseAnimationTapsStayInTheDismissingModal() {
        val provider = regressionProvider("animating-model")
        var navBack = 0
        var closed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PhoneAppShellFixture(
                provider = provider,
                onBack = { navBack += 1 },
                onMenuClosed = { closed += 1 },
            )
        }
        compose.mainClock.advanceTimeBy(1_000)

        val headerBack = compose.onNodeWithTag("chat-header-back")
            .fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { click(headerBack) }
        // Let the host publish `open=false` first, then drive the exit animation explicitly.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(80)
        compose.onRoot().performTouchInput { click(headerBack) }
        compose.mainClock.advanceTimeBy(80)
        compose.onRoot().performTouchInput { click(headerBack) }
        // Late phase (~316ms): the panel shrink (340ms) is still running. The old scrim
        // (fadeOut 280ms) was already gone here, so this tap reached the header.
        compose.mainClock.advanceTimeBy(140)
        compose.onNodeWithTag("top-model-menu-panel").assertExists()
        compose.onRoot().performTouchInput { click(headerBack) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(
            "taps in the last frames of the panel exit must not reach the header",
            0,
            navBack,
        )

        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().performTouchInput { click(headerBack) }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("once the animation is gone the header works again", 1, navBack)
        // A tap landed on the still-fading scrim may re-request dismissal; what matters is that
        // the menu was dismissed and the header never received the taps.
        assertTrue("the taps dismissed the menu", closed >= 1)
    }

    @Test
    fun appShellModalLayer_systemBackClosesMenuBeforeTheRouteHandler() {
        val provider = regressionProvider("back-model")
        var routeBack = 0
        var closed = 0
        var dispatcher: OnBackPressedDispatcher? = null
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            PhoneAppShellFixture(
                provider = provider,
                onBack = {},
                onMenuClosed = { closed += 1 },
                onRouteBack = { routeBack += 1 },
            )
        }
        compose.waitForIdle()

        compose.runOnIdle { dispatcher?.onBackPressed() }
        settleMenuAnimation()
        assertEquals("system back must dismiss the menu first", 1, closed)
        assertEquals("system back must not pop the route while the menu is open", 0, routeBack)

        compose.runOnIdle { dispatcher?.onBackPressed() }
        compose.waitForIdle()
        assertEquals("after dismissal the route back handler works", 1, routeBack)
    }

    private companion object {
        // twoPaneListWidth(1280dp) = 384dp — the wide regression uses a foldable-sized window.
        val TWO_PANE_LIST_WIDTH = 384.dp
    }

}
