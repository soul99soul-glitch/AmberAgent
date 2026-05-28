package app.amber.feature.ui.pages.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.Screen
import app.amber.feature.live.LiveModeCard
import app.amber.feature.live.LiveModeUiState
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.context.LocalNavController
import app.amber.core.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveCompanionPage(vm: LiveCompanionVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val liveSetting = settings.agentRuntime.liveMode
    val scrollState = rememberScrollState()

    DisposableEffect(Unit) {
        onDispose { vm.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AI 伴随",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.compactStatusLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.pauseOrResume() }, enabled = state.active) {
                        Icon(
                            imageVector = if (state.paused) HugeIcons.Play else HugeIcons.Pause,
                            contentDescription = null,
                        )
                    }
                    IconButton(onClick = { navController.navigate(Screen.SettingAgentExecution) }) {
                        Icon(HugeIcons.Settings03, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            LiveBottomBar(
                state = state,
                voiceEnabled = liveSetting.voiceInputEnabled,
                onInstruction = vm::submitFocusInstruction,
                onSendToChat = {
                    val text = vm.exportCurrentCard()
                    if (text.isNullOrBlank()) {
                        Toast.makeText(context, "还没有可发送的 Live 卡片", Toast.LENGTH_SHORT).show()
                    } else {
                        navigateToChatPage(navController, initText = text)
                    }
                },
                onVoiceError = { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerLow,
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LiveStatusPanel(
                    state = state,
                    autoRefresh = liveSetting.autoRefresh,
                    onToggleAutoRefresh = vm::setAutoRefresh,
                    onStart = vm::start,
                    onPause = vm::pauseOrResume,
                )

                state.error?.takeIf { it.isNotBlank() }?.let { error ->
                    ErrorNote(
                        title = state.statusText,
                        error = error,
                        retrying = state.nextAnalysisAfterMillis > System.currentTimeMillis(),
                    )
                }

                if (state.requestedAction.isNotBlank()) {
                    ActionProgressCard(state)
                }

                when {
                    state.needsAccessibility -> GuidanceCard(
                        title = "需要开启无障碍",
                        body = "Live 伴随要读取另一侧应用的 UI 树。开启 AmberAgent 无障碍服务后，再回到这里开始伴随。",
                        action = "去开启",
                        onAction = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )

                    state.noModelConfigured -> GuidanceCard(
                        title = "需要配置聊天模型",
                        body = "Live 会复用当前聊天模型做短分析。先选一个可用模型，再回来启动伴随。",
                        action = "去设置",
                        onAction = { navController.navigate(Screen.SettingModels) },
                    )

                    state.card != null -> LiveCard(
                        card = state.card!!,
                        actionLabel = state.resultActionLabel(),
                        stale = state.requestedAction.isNotBlank(),
                        pendingAction = state.requestedAction,
                        enabled = state.active && !state.paused && !state.analyzing,
                        onInstruction = vm::submitFocusInstruction,
                    )

                    else -> WaitingCard(state = state, onStart = vm::start)
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun LiveStatusPanel(
    state: LiveModeUiState,
    autoRefresh: Boolean,
    onToggleAutoRefresh: (Boolean) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LivePulse(active = state.active && !state.paused, analyzing = state.analyzing, hasError = state.error != null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = state.prominentStatusLabel(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.analyzing) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    }
                }
                Text(
                    text = state.statusDetail(autoRefresh),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { onToggleAutoRefresh(!autoRefresh) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        text = if (autoRefresh) "自动分析：开" else "自动分析：关",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (!state.active) {
                Button(
                    onClick = onStart,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("开启")
                }
            } else {
                FilledTonalButton(
                    onClick = onPause,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(if (state.paused) "继续" else "暂停")
                }
            }
        }
    }
}

@Composable
private fun LivePulse(active: Boolean, analyzing: Boolean, hasError: Boolean) {
    val color = when {
        hasError -> MaterialTheme.colorScheme.error
        analyzing -> MaterialTheme.colorScheme.tertiary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = HugeIcons.Sparkles,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ActionProgressCard(state: LiveModeUiState) {
    val action = state.requestedAction
    val retrying = state.nextAnalysisAfterMillis > System.currentTimeMillis()
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.analyzing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(HugeIcons.Sparkles, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = when {
                        retrying -> "${action}排队中"
                        state.analyzing -> "正在$action"
                        state.paused -> "${action}已暂停"
                        else -> "已收到：$action"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        retrying -> "模型服务忙，恢复后结果会显示在下面的“${action.resultTitle()}”里。"
                        state.analyzing -> "完成后会直接覆盖下面的结果卡片。"
                        state.paused -> "点击继续后再读取屏幕并生成结果。"
                        else -> "等待屏幕稳定后开始，结果会显示在下面的“${action.resultTitle()}”里。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}

@Composable
private fun LiveCard(
    card: LiveModeCard,
    actionLabel: String,
    stale: Boolean,
    pendingAction: String = "",
    enabled: Boolean,
    onInstruction: (String) -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = if (stale) "上一张结果" else actionLabel.resultTitle(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (stale && pendingAction.isNotBlank()) {
                Text(
                    text = "新的“$pendingAction”还在处理，下面先保留上一张卡片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (actionLabel) {
                "找重点" -> {
                    LiveSection(title = "结论", content = card.watching.ifBlank { "不确定" }, prominent = true)
                    LiveSection(title = "重点", items = card.keyPoints, emptyText = "暂时没有提取到明确重点。")
                }

                "总结" -> {
                    LiveSection(
                        title = "总结",
                        content = card.watching.ifBlank { "这块屏幕信息还不够明确。" },
                        prominent = true,
                    )
                    LiveSection(title = "关键信息", items = card.keyPoints)
                }

                "找下一步" -> {
                    LiveSection(title = "下一步建议", items = card.suggestions, emptyText = "暂时没有足够信息判断下一步。")
                    LiveSection(title = "判断依据", items = card.keyPoints)
                    LiveSection(title = "正在看什么", content = card.watching.ifBlank { "这块屏幕信息还不够明确。" })
                }

                "查风险" -> {
                    LiveSection(title = "结论", content = card.watching.ifBlank { "暂未发现明确风险" }, prominent = true)
                    LiveSection(
                        title = "风险点",
                        items = card.keyPoints,
                        emptyText = "暂时没有发现明确风险。",
                    )
                }

                "写回复" -> {
                    LiveSection(title = "回复草稿", content = card.suggestions.firstOrNull() ?: card.watching, prominent = true)
                    LiveSection(title = "语气", items = card.keyPoints)
                }

                else -> {
                    LiveSection(
                        title = "正在看什么",
                        content = card.watching.ifBlank { "这块屏幕信息还不够明确。" },
                        prominent = true,
                    )
                    LiveSection(title = "重点内容", items = card.keyPoints)
                    LiveSection(title = "可以怎么做", items = card.suggestions)
                }
            }
            DynamicActionChips(
                currentAction = if (stale && pendingAction.isNotBlank()) pendingAction else actionLabel,
                enabled = enabled,
                onInstruction = onInstruction,
            )
        }
    }
}

@Composable
private fun DynamicActionChips(
    currentAction: String,
    enabled: Boolean,
    onInstruction: (String) -> Unit,
) {
    val actions = remember(currentAction) {
        listOf("找重点", "帮我写回复", "有什么风险", "下一步", "总结一下")
            .filterNot { it.liveActionLabel() == currentAction }
            .take(3)
    }
    if (actions.isEmpty()) return
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEach { action ->
            AssistChip(
                onClick = { onInstruction(action) },
                enabled = enabled,
                label = {
                    Text(
                        text = action,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun LiveSection(
    title: String,
    content: String? = null,
    items: List<String> = emptyList(),
    emptyText: String? = null,
    prominent: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        if (!content.isNullOrBlank()) {
            Text(
                text = content,
                style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        items.take(4).forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("·", color = MaterialTheme.colorScheme.primary)
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (content.isNullOrBlank() && items.isEmpty() && !emptyText.isNullOrBlank()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WaitingCard(
    state: LiveModeUiState,
    onStart: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (state.active) "等待第一张伴随卡片" else "伴随尚未开启",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    !state.active -> "开启后会读取另一侧应用的 UI 树，页面稳定后再调用模型分析。"
                    state.currentAppLabel.isNotBlank() -> "已读取：${state.currentAppLabel}${state.currentTitle.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
                    else -> state.statusText
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.active) {
                Button(
                    onClick = onStart,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("开启伴随")
                }
            }
        }
    }
}

@Composable
private fun GuidanceCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.22f),
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onAction) {
                Text(action)
            }
        }
    }
}

@Composable
private fun ErrorNote(title: String, error: String, retrying: Boolean) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
            )
            if (retrying) {
                Text(
                    text = "这不是 UI 树读取频率导致的错误，而是模型服务暂时不可用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}

private fun LiveModeUiState.compactStatusLabel(): String =
    when {
        !active -> "未开启"
        paused -> "已暂停"
        error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> "模型繁忙"
        error != null -> "分析失败"
        requestedAction.isNotBlank() && analyzing -> "正在$requestedAction"
        requestedAction.isNotBlank() -> "等待$requestedAction"
        analyzing -> "正在分析"
        card != null -> "正在伴随"
        currentAppLabel.isNotBlank() -> "正在读取"
        else -> "已开启"
    }

private fun LiveModeUiState.prominentStatusLabel(): String =
    when {
        !active -> "伴随未开启"
        paused -> "伴随已暂停"
        error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> "模型服务繁忙"
        error != null -> "分析失败"
        requestedAction.isNotBlank() && analyzing -> "正在$requestedAction"
        requestedAction.isNotBlank() -> "已收到：$requestedAction"
        analyzing -> "正在分析屏幕"
        card != null -> "正在伴随"
        currentAppLabel.isNotBlank() -> "正在读取屏幕"
        else -> "伴随已开启"
    }

private fun LiveModeUiState.statusDetail(autoRefresh: Boolean): String {
    val target = listOf(currentAppLabel, currentTitle)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val mode = if (autoRefresh) "自动分析" else "手动分析"
    return when {
        !active -> "点击开启后开始读取另一侧窗口"
        paused -> "已停止读取和模型调用"
        error != null && nextAnalysisAfterMillis > System.currentTimeMillis() -> "仍会读取屏幕，模型调用退避中"
        requestedAction.isNotBlank() -> "结果会显示在下面的“${requestedAction.resultTitle()}”卡片里"
        analyzing -> target.ifBlank { "正在整理当前 UI 树" }
        target.isNotBlank() -> "$target · $mode"
        else -> "$statusText · $mode"
    }
}

private fun LiveModeUiState.resultActionLabel(): String =
    completedAction.ifBlank {
        currentFocus.liveActionLabel().takeUnless { it == "屏幕分析" } ?: "屏幕分析"
    }

private fun String.liveActionLabel(): String {
    val text = trim()
    return when {
        text.isBlank() -> "屏幕分析"
        "重点" in text -> "找重点"
        "总结" in text || "摘要" in text -> "总结"
        "下一步" in text || "怎么做" in text -> "找下一步"
        "风险" in text || "问题" in text -> "查风险"
        "回复" in text || "回话" in text -> "写回复"
        else -> text.take(12)
    }
}

private fun String.resultTitle(): String = when (this) {
    "屏幕分析" -> "伴随结果"
    "找重点" -> "找重点结果"
    "总结" -> "总结结果"
    "找下一步" -> "下一步建议"
    "查风险" -> "风险点"
    "写回复" -> "回复建议"
    else -> "${this}结果"
}

@Composable
private fun LiveBottomBar(
    state: LiveModeUiState,
    voiceEnabled: Boolean,
    onInstruction: (String) -> Unit,
    onSendToChat: () -> Unit,
    onVoiceError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoldToSpeakButton(
                modifier = Modifier.weight(1f),
                enabled = state.active && !state.paused && voiceEnabled,
                onText = onInstruction,
                onVoiceError = onVoiceError,
            )
            FilledTonalButton(
                onClick = onSendToChat,
                enabled = state.card != null,
                modifier = Modifier.height(52.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(HugeIcons.Message01, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("聊天")
            }
        }
    }
}

@Composable
private fun HoldToSpeakButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onText: (String) -> Unit,
    onVoiceError: (String) -> Unit,
) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (!granted) onVoiceError("需要麦克风权限才能语音补充关注点")
        },
    )

    fun stopListening() {
        if (listening) {
            recognizer?.stopListening()
            listening = false
        }
    }

    fun startListening() {
        if (!enabled) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onVoiceError("当前系统语音识别不可用")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        speechRecognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    listening = false
                }

                override fun onError(error: Int) {
                    listening = false
                    onVoiceError("没听清，再按住说一次")
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()
                    if (text.isBlank()) {
                        onVoiceError("没听清，再按住说一次")
                    } else {
                        onText(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
        )
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        listening = true
        speechRecognizer.startListening(intent)
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
        }
    }

    Surface(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        startListening()
                        tryAwaitRelease()
                        stopListening()
                    }
                )
            },
        shape = RoundedCornerShape(18.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            listening -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            listening -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(HugeIcons.VolumeHigh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = if (listening) "松开结束" else "按住说话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
