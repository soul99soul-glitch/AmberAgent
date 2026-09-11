package app.amber.feature.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.miniapp.MiniAppSpeechEngine
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.workspaceColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * W16-C: 独立 TTS 试听页（iOS TTSSettingsView 对齐）。只承诺系统 TTS 试听：
 * 当前引擎、语速（0.5×–2×，映射到 MiniApp 的 0..1 语速契约）、试听/停止；
 * 无引擎/初始化失败诚实显示，离页停止。云端 TTS 与聊天朗读不在本页承诺范围。
 *
 * 试听操作用单一 Job 串行：启动和朗读中都可点击停止，离开页面停止。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingTtsPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = workspaceColors()

    // 页面展示 Android 语义的倍率；MiniAppSpeechEngine 接收 iOS 对齐的
    // 0..1 语速（0.5 = 正常），所以发送时除以 2。
    val speeds = remember { listOf(0.5f to "0.5×", 1f to "1×", 1.5f to "1.5×", 2f to "2×") }
    var speedIndex by remember { mutableStateOf(1) }
    var speaking by remember { mutableStateOf(false) }
    var engineError by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var speakJob by remember { mutableStateOf<Job?>(null) }
    // 每个页面实例一个独立 speech owner；离页 close，不与 MiniApp runner 共享。
    val speechEngine = remember {
        MiniAppSpeechEngine(
            context = context,
            onSpeechFinished = { speaking = false },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            speakJob?.cancel()
            speechEngine.close()
            speaking = false
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("语音合成") },
                navigationIcon = { BackButton() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 20.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Android 当前使用系统语音合成（TextToSpeech）。本页仅提供试听，不参与聊天朗读或录音转写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = colors.paper) {
                    ListItem(
                        headlineContent = { Text("系统 TTS") },
                        supportingContent = {
                            Text(
                                when {
                                    engineError != null -> engineError!!
                                    checked -> "使用系统 TTS 引擎，本机可直接试听"
                                    else -> "尚未检测，点击试听时确认引擎可用"
                                }
                            )
                        },
                        trailingContent = {
                            if (busy && !speaking) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(
                                    when {
                                        engineError != null -> "不可用"
                                        checked -> "可用"
                                        else -> "未检测"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        engineError != null -> MaterialTheme.colorScheme.error
                                        checked -> colors.ink
                                        else -> colors.muted
                                    },
                                )
                            }
                        },
                    )
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = colors.paper) {
                    Column {
                        ListItem(
                            headlineContent = { Text("语速") },
                            supportingContent = { Text("调整试听语速") },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        speeds[speedIndex].second,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colors.ink,
                                    )
                                    TextButton(onClick = { speedIndex = (speedIndex + 1) % speeds.size }) {
                                        Text("切换")
                                    }
                                }
                            },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    if (speaking || busy) {
                                        // 停止覆盖"启动中"与"朗读中"两种状态。
                                        speakJob?.cancel()
                                        speakJob = null
                                        speakJob = scope.launch {
                                            busy = true
                                            try {
                                                runCatching { speechEngine.dispatch("speech.stop", paramsOf()) }
                                                    .onFailure { if (it is CancellationException) throw it }
                                            } finally {
                                                speaking = false
                                                busy = false
                                            }
                                        }
                                    } else {
                                        engineError = null
                                        // 引擎可能在 speak 返回前回报 onDone，先设置启动状态。
                                        speaking = true
                                        busy = true
                                        speakJob = scope.launch {
                                            try {
                                                // speak 返回即引擎接受；引擎不可用/初始化失败会抛错。
                                                speechEngine.dispatch(
                                                    "speech.speak",
                                                    paramsOf(
                                                        "text" to "你好，这是 AmberAgent 的语音试听。系统 TTS 可用。",
                                                        "language" to Locale.getDefault().toLanguageTag(),
                                                        "rate" to (speeds[speedIndex].first / 2f),
                                                    ),
                                                )
                                                checked = true
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (error: Throwable) {
                                                speaking = false
                                                engineError = error.message ?: "系统 TTS 引擎不可用"
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                },
                                // 启动中 speaking 已置 true，允许取消尚未完成的初始化；
                                // 停止请求本身完成前避免重复提交 stop。
                                enabled = !busy || speaking,
                            ) {
                                Text(
                                    when {
                                        speaking -> "停止试听"
                                        busy -> "处理中…"
                                        else -> "系统 TTS 试听"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun paramsOf(vararg pairs: Pair<String, Any>): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        pairs.forEach { (key, value) ->
            val primitive = when (value) {
                is Float -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
            put(key, primitive)
        }
    }
