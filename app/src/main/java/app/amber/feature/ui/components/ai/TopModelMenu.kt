package app.amber.feature.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 渲染为「header 下方那块 position:relative 容器」的子元素：[modifier] 由调用方注入
 * `padding(top = innerPadding.top)` 使其从 header 正下方开始铺满 (fillMaxSize)。
 * - [open] true 时卷帘展开 (grid-rows 0fr→1fr 等价 expandVertically from Top, .34s)；
 *   背景遮罩 rgba(20,18,16,.28) 同步淡入，点遮罩 [onClose]。
 * - 选中态一律 --accent（绝不用 signal 绿）；机器事实（服务商名/模型id/ctx/±）全 mono。
 */
private val RollerEasing = CubicBezierEasing(0.2f, 0.85f, 0.25f, 1f)

@Composable
fun TopModelMenu(
    open: Boolean,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    currentProviderId: Uuid?,
    currentModelId: Uuid?,
    onSelect: (Model) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chatTheme = LocalChatTheme.current

    // 哪些组展开。初始 = [currentProviderId]；open 变 true 时重置为 [currentProviderId]。
    var openIds by remember { mutableStateOf(setOfNotNull(currentProviderId)) }
    LaunchedEffect(open, currentProviderId) {
        if (open) openIds = setOfNotNull(currentProviderId)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景遮罩：铺满 (inset:0)，淡入淡出，点击关闭。仅 open 时拦截触摸（关闭态让内容可点）。
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(280)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(chatTheme.sheetBackdrop)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }

        // 下拉本体：卷帘从顶部展开。
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = tween(340, easing = RollerEasing), expandFrom = Alignment.Top),
            exit = shrinkVertically(animationSpec = tween(340, easing = RollerEasing), shrinkTowards = Alignment.Top),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp), clip = false)
                    .background(chatTheme.surface)
                    .drawBehind {
                        // 底部 1px line-2
                        val y = size.height - 1.dp.toPx()
                        drawRect(
                            color = chatTheme.hair,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                            size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()),
                        )
                    }
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp),
            ) {
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
                .clickable(onClick = onToggle)
                .padding(horizontal = 2.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = provider.name,
                style = type.meta.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
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
            .clickable(onClick = onClick)
            .padding(top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = model.displayName,
            style = type.meta.copy(
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                letterSpacing = 0.sp,
            ),
            color = if (selected) chatTheme.accent else tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.weight(1f))
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
