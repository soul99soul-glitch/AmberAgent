package app.amber.feature.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.ai.core.ReasoningLevel
import app.amber.agent.R
import app.amber.ai.provider.ModelAbility
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.core.utils.formatNumber
import app.amber.feature.ui.pages.chat.LocalChatTheme
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import kotlin.uuid.Uuid

/**
 * Graphite TopModelMenu —— 1:1 还原 redesign/aa-model.jsx：从 ChatHeader 正下方"卷帘"
 * 展开的服务商/模型手风琴（OpenCode Mobile 风），不是底部弹层。
 *
 * 渲染为覆盖整个应用窗口的 overlay：调用方必须把它挂在 **app shell 的模态层**（比
 * Scaffold 更高，见 `HostInAppModalOverlay`），这样遮罩能盖住 header、输入区和展开屏左侧的
 * 会话列表；只挂在 ChatPage 自己身上时，header 返回和左栏设置都会穿透。
 * [panelTopInset] 让面板仍从 header 正下方展开，[panelStartInset] 让双栏布局里的面板只占
 * 聊天栏（遮罩仍铺满整窗）。
 * - [open] true 时卷帘展开 (grid-rows 0fr→1fr 等价 expandVertically from Top, .34s)；
 *   背景遮罩 rgba(20,18,16,.28) 同步淡入，点遮罩 [onClose]；Back 键同样关闭。
 * - 面板自身吃掉空白处的命中测试（不透到遮罩），所以点 header/内边距等菜单内部空白不会误关。
 * - 选中态一律 --accent（绝不用 signal 绿）；机器事实（服务商名/模型id/ctx/±）全 mono。
 */
private val RollerEasing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1f)

/**
 * 面板卷帘收起时长。遮罩的退出动画必须至少同样长：AnimatedVisibility 在动画结束前一直保留
 * 命中测试节点，若遮罩先消失（旧实现 280ms vs 面板 340ms），最后一段动画里点击 header
 * 返回/展开屏左栏设置会穿透过去。
 */
private const val MenuExitDurationMillis = 340

/** 面板绝对高度上限（平板等大屏也不要把菜单拉得过高）。 */
private val MenuMaxHeight = 560.dp

/**
 * 面板下方必须保留的可点遮罩高度。真机上展开长列表时面板会铺满 header 以下的整屏，
 * 结果“菜单外部”只剩被面板盖住的地方，只能点模型才关得掉——所以按可用高度收面板，
 * 任何屏高都留一条能点关闭的外部空白。
 */
private val MenuBottomScrimHeight = 48.dp

@Composable
fun TopModelMenu(
    open: Boolean,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    currentProviderId: Uuid?,
    currentModelId: Uuid?,
    onSelect: (Model) -> Unit,
    onClose: () -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit = {},
    modifier: Modifier = Modifier,
    /** Panel (not scrim) starts below the chat header. */
    panelTopInset: Dp = 0.dp,
    /** Two-pane detail pane start: the scrim spans the window, the panel stays in the chat pane. */
    panelStartInset: Dp = 0.dp,
    /** Optional block above the provider list (chat: context usage). */
    header: (@Composable () -> Unit)? = null,
) {
    val chatTheme = LocalChatTheme.current
    val selectedModel = remember(providers, modelType, currentModelId) {
        providers.asSequence()
            .flatMap { provider -> provider.models.asSequence() }
            .firstOrNull { model -> model.type == modelType && model.id == currentModelId }
    }
    val reasoningLevels = remember(selectedModel) {
        selectedModel?.let(::reasoningLevelsForModel).orEmpty()
    }
    val showReasoningPicker = reasoningLevel != null &&
        selectedModel?.abilities?.contains(ModelAbility.REASONING) == true &&
        reasoningLevels.isNotEmpty()

    // 打开时接管系统返回：先收菜单，而不是直接退出会话页。
    BackHandler(enabled = open, onBack = onClose)

    // 打开菜单时收起输入法：键盘不是菜单的操作对象，却会把 header 以下的可用高度压到放不下
    // provider/model（真机折叠屏 + 键盘 + 上下固定 header/footer 时列表为 0 高）。
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    // 哪些组展开。初始 = [currentProviderId]；open 变 true 时重置为 [currentProviderId]。
    var openIds by remember { mutableStateOf(setOfNotNull(currentProviderId)) }
    LaunchedEffect(open, currentProviderId) {
        if (open) openIds = setOfNotNull(currentProviderId)
    }

    // 只让 overlay 占「应用可用区域」：底部扣掉导航栏（3 键导航会整条吃掉预留的 48dp 遮罩，
    // 点上去直接回桌面）与 IME。union 取两者较大值，键盘弹出时 IME inset 已包含导航栏，
    // 不会双扣。只扣底部，左右保持窗口坐标，面板才能和聊天栏的 window 左边界对齐。
    val overlayInsets = WindowInsets.navigationBars
        .union(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom)
    BoxWithConstraints(modifier = modifier.fillMaxSize().windowInsetsPadding(overlayInsets)) {
        // 可用高度 = 整窗高度减去 header 高度（[panelTopInset]），再扣掉必须留出的 48dp
        // 外部遮罩。面板在 header 下卷帘展开，遮罩则铺满 header 以上和面板以下的整窗。
        val panelMaxHeight = minOf(
            MenuMaxHeight,
            (maxHeight - panelTopInset - MenuBottomScrimHeight).coerceAtLeast(0.dp),
        )
        // 背景遮罩：铺满 (inset:0)，淡入淡出，点击关闭。仅 open 时拦截触摸（关闭态让内容可点）。
        // 它必须从窗口顶端开始，而不是从 header 下方开始：header 返回键和展开屏左栏设置
        // 同样要落在遮罩里，第一次点击只能关闭菜单。
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(280)),
            // 与面板收起同长，保证末段（panel 仍可见时）点击仍被遮罩拦截。
            exit = fadeOut(tween(MenuExitDurationMillis)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(chatTheme.sheetBackdrop)
                    .testTag("top-model-menu-scrim")
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }

        // 下拉本体：卷帘从顶部展开。只让面板避开 header 与左侧列表，遮罩不受影响。
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = tween(340, easing = RollerEasing), expandFrom = Alignment.Top),
            exit = shrinkVertically(animationSpec = tween(MenuExitDurationMillis, easing = RollerEasing), shrinkTowards = Alignment.Top),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = panelStartInset, top = panelTopInset),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("top-model-menu-panel")
                    // 面板吃掉空白处（header、内边距、行间空隙）的命中测试：这些位置的子节点没有
                    // pointer input，否则会透到后面的遮罩把菜单误关。只观察、不消费，点击/滚动照旧。
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            waitForUpOrCancellation()
                        }
                    }
                    // 面板背景比顶栏 bg 更灰 (surface2)，去掉阴影，仅靠底部 hairline 收边（参照 OpenCode）
                    .background(LocalAmberTokens.current.surface2)
                    .drawBehind {
                        // 底部 1px 收边线（取代阴影）
                        val y = size.height - 1.dp.toPx()
                        drawRect(
                            color = chatTheme.hair,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                            size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
                        )
                    }
                    .heightIn(max = panelMaxHeight)
            ) {
                // header + provider/model 列表放在同一个滚动容器里：紧凑高度（键盘/折叠屏）下
                // 固定的 header 会把列表挤成 0 高、连一个模型行都看不到；让它随列表滚走，
                // 保证任何高度都能滚到并选中模型。footer 仍固定在面板底部。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (header != null) {
                        header()
                        HorizontalDivider(
                            color = chatTheme.hair,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = 12.dp,
                        ),
                    ) {
                        if (providers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.model_list_no_providers),
                                style = LocalAmberType.current.meta.copy(
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.sp,
                                ),
                                color = LocalAmberTokens.current.ink3,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                            )
                        } else {
                            providers.forEach { provider ->
                                ProviderGroup(
                                    provider = provider,
                                    modelType = modelType,
                                    open = openIds.contains(provider.id),
                                    active = provider.id == currentProviderId,
                                    selectedModelId = currentModelId,
                                    onToggle = {
                                        openIds = if (openIds.contains(provider.id)) openIds - provider.id
                                        else openIds + provider.id
                                    },
                                    onSelect = onSelect,
                                )
                            }
                        }
                    }
                }

                if (showReasoningPicker) {
                    ModelMenuReasoningFooter(
                        currentLevel = reasoningLevel,
                        levels = reasoningLevels,
                        onChange = onUpdateReasoningLevel,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelMenuReasoningFooter(
    currentLevel: ReasoningLevel,
    levels: List<Pair<ReasoningLevel, String>>,
    onChange: (ReasoningLevel) -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val localizedLevels = levels.map { (level, _) ->
        level to stringResource(
            when (level) {
                ReasoningLevel.OFF -> R.string.reasoning_off
                ReasoningLevel.AUTO -> R.string.reasoning_auto
                ReasoningLevel.LOW -> R.string.reasoning_light
                ReasoningLevel.MEDIUM -> R.string.reasoning_medium
                ReasoningLevel.HIGH -> R.string.reasoning_heavy
                ReasoningLevel.XHIGH -> R.string.reasoning_xhigh
                ReasoningLevel.MAX -> R.string.reasoning_max
            }
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.surface2)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(color = chatTheme.hair)
        Text(
            text = stringResource(R.string.chat_input_reasoning_level),
            style = LocalAmberType.current.meta.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.sp,
            ),
            color = tokens.ink3,
        )
        ThinkingLevelSegment(
            levels = localizedLevels,
            current = currentLevel,
            onChange = onChange,
        )
    }
}

@Composable
private fun ProviderGroup(
    provider: ProviderSetting,
    modelType: ModelType,
    open: Boolean,
    active: Boolean,
    selectedModelId: Uuid?,
    onToggle: () -> Unit,
    onSelect: (Model) -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val models = remember(provider, modelType) {
        provider.models.filter { it.type == modelType }
    }
    if (models.isEmpty()) return

    // 无任何行间分割线（按用户反馈：每行无 divider，参照魔魂扣安卓版）。
    Column(modifier = Modifier.fillMaxWidth()) {
        // 服务商行：整行可点切换该组展开。左 mono 名（active→accent/600，否则 ink-2/500），
        // 右 mono "−"(展开)/"+"(收起)。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = provider.name,
                // 选中态只用 accent 颜色表达，不加粗（统一 Medium 字重）
                style = type.meta.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                ),
                color = if (active) chatTheme.accent else tokens.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (open) "−" else "+",
                style = type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
                color = tokens.ink4,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(16.dp),
            )
        }
        // 模型列表：同样卷帘展开；内层 paddingLeft 16，展开时底 padding 6。
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = tween(280, easing = RollerEasing), expandFrom = Alignment.Top),
            exit = shrinkVertically(animationSpec = tween(280, easing = RollerEasing), shrinkTowards = Alignment.Top),
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) {
                models.forEach { model ->
                    ModelLine(
                        model = model,
                        selected = model.id == selectedModelId,
                        onClick = { onSelect(model) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelLine(
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val chatTheme = LocalChatTheme.current
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    // mono 整行：左模型id（选中→accent/600，否则 ink-3/400），右 ctx（ink-4/11.5）。
    // 铁律：不渲染 note 角标 / 不渲染 ✓，选中只用 accent 表达，ctx 列保持对齐。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = model.displayName,
            // 选中态只用 accent 颜色表达，不加粗（统一 Normal 字重）
            style = type.meta.copy(
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.sp,
            ),
            color = if (selected) chatTheme.accent else tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 模型名吃满弹性空间 → ctx 始终顶到最右、跨行对齐（修复 ctx 漂移）
            modifier = Modifier.weight(1f),
        )
        model.contextWindowTokens?.let { ctx ->
            Text(
                text = ctx.formatNumber(),
                style = type.meta.copy(fontSize = 11.sp),
                color = tokens.ink4,
                maxLines = 1,
            )
        }
    }
}
