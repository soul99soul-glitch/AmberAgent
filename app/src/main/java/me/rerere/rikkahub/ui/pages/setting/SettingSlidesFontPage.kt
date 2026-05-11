package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.font.FontPackCategory
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingSlidesFontPage(
    vm: SettingVM = koinViewModel(),
    fontRepository: SlidesFontRepository = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val fonts by fontRepository.fontsFlow.collectAsStateWithLifecycle()
    val downloads by fontRepository.downloadsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val generativeUi = settings.agentRuntime.generativeUi

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Slides 字体资源") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("默认策略") },
                ) {
                    item(
                        headlineContent = { Text("杂志风格") },
                        supportingContent = {
                            Text(
                                fonts.firstOrNull { it.pack.id == generativeUi.slidesMagazineFontPack }
                                    ?.pack
                                    ?.displayName
                                    ?: generativeUi.slidesMagazineFontPack
                            )
                        },
                    )
                    item(
                        headlineContent = { Text("瑞士网格风格") },
                        supportingContent = {
                            Text(
                                fonts.firstOrNull { it.pack.id == generativeUi.slidesSwissFontPack }
                                    ?.pack
                                    ?.displayName
                                    ?: generativeUi.slidesSwissFontPack
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("可下载中文字体") },
                ) {
                    fonts.forEach { state ->
                        val progress = downloads[state.pack.id]
                        item(
                            headlineContent = {
                                Text(
                                    state.pack.displayName,
                                    fontWeight = if (state.installed) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "${state.pack.category.label()} · ${formatBytes(state.pack.fileSizeBytes)} · ${state.pack.licenseName}"
                                    )
                                    Text(
                                        "预览：AmberAgent 生成式幻灯片 · 唐代三省六部制",
                                        fontFamily = when (state.pack.category) {
                                            FontPackCategory.MONO -> FontFamily.Monospace
                                            else -> FontFamily.Default
                                        },
                                    )
                                    if (progress != null) {
                                        LinearProgressIndicator(
                                            progress = { progress.fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(onClick = { context.openUrl(state.pack.sourcePageUrl) }) {
                                            Text("来源")
                                        }
                                        TextButton(onClick = { context.openUrl(state.pack.licenseUrl) }) {
                                            Text("许可证")
                                        }
                                        if (state.pack.category == FontPackCategory.SERIF ||
                                            state.pack.category == FontPackCategory.HANDWRITING
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    vm.updateSettings(
                                                        settings.copy(
                                                            agentRuntime = settings.agentRuntime.copy(
                                                                generativeUi = generativeUi.copy(
                                                                    slidesMagazineFontPack = state.pack.id,
                                                                )
                                                            )
                                                        )
                                                    )
                                                },
                                            ) {
                                                Text("设为杂志")
                                            }
                                        }
                                        if (state.pack.category == FontPackCategory.SANS ||
                                            state.pack.category == FontPackCategory.HANDWRITING
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    vm.updateSettings(
                                                        settings.copy(
                                                            agentRuntime = settings.agentRuntime.copy(
                                                                generativeUi = generativeUi.copy(
                                                                    slidesSwissFontPack = state.pack.id,
                                                                )
                                                            )
                                                        )
                                                    )
                                                },
                                            ) {
                                                Text("设为瑞士")
                                            }
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                when {
                                    progress != null -> Text("${(progress.fraction * 100).toInt()}%")
                                    state.installed -> OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                fontRepository.delete(state.pack.id)
                                                toaster.show("已删除字体", type = ToastType.Success)
                                            }
                                        },
                                    ) {
                                        Text("删除")
                                    }

                                    else -> Button(
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    fontRepository.download(state.pack.id)
                                                }.onSuccess {
                                                    toaster.show("字体已安装", type = ToastType.Success)
                                                }.onFailure { error ->
                                                    toaster.show(
                                                        error.message ?: "字体下载失败",
                                                        type = ToastType.Error,
                                                    )
                                                }
                                            }
                                        },
                                    ) {
                                        Text("下载")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun FontPackCategory.label(): String =
    when (this) {
        FontPackCategory.SERIF -> "宋/明体"
        FontPackCategory.SANS -> "黑体"
        FontPackCategory.HANDWRITING -> "手写阅读"
        FontPackCategory.MONO -> "等宽"
    }

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
