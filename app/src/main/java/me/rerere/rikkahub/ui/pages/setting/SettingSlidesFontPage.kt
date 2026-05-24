package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.data.font.FontPackCategory
import me.rerere.rikkahub.data.font.FontPackState
import me.rerere.rikkahub.data.font.SlidesFontRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.WorkspaceTopBar
import me.rerere.rikkahub.ui.components.ui.workspaceColors
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

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = "Slides 字体资源",
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text("可下载中文字体") },
                ) {
                    fonts.forEach { state ->
                        key(state.pack.id) {
                        val progress = downloads[state.pack.id]
                        var showMenu by remember { mutableStateOf(false) }
                        item(
                            headlineContent = {
                                Text(
                                    state.pack.displayName,
                                    fontWeight = if (state.installed) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FlowRow(
                                        horizontalArrangement = spacedBy(6.dp),
                                        verticalArrangement = spacedBy(4.dp),
                                    ) {
                                        Text(
                                            formatBytes(state.pack.fileSizeBytes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        StyleBadge(state.pack.category.label())
                                    }
                                    if (progress != null) {
                                        LinearProgressIndicator(
                                            progress = { progress.fraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            "${(progress.fraction * 100).toInt()}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else if (!state.installed) {
                                        Button(
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
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp),
                                        ) {
                                            Text("下载", style = MaterialTheme.typography.labelMedium)
                                        }
                                    } else {
                                        Text(
                                            "已安装",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Box(contentAlignment = Alignment.CenterEnd) {
                                    IconButton(
                                        onClick = { showMenu = true },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            HugeIcons.MoreVertical,
                                            contentDescription = "更多",
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("来源") },
                                            onClick = {
                                                showMenu = false
                                                context.openUrl(state.pack.sourcePageUrl)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("许可证") },
                                            onClick = {
                                                showMenu = false
                                                context.openUrl(state.pack.licenseUrl)
                                            },
                                        )
                                        if (state.installed) {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        "删除",
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                },
                                                onClick = {
                                                    showMenu = false
                                                    scope.launch {
                                                        fontRepository.delete(state.pack.id)
                                                        toaster.show("已删除字体", type = ToastType.Success)
                                                    }
                                                },
                                            )
                                        }
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
}

@Composable
private fun StyleBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
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
