package app.amber.feature.ui.pages.setting

import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.core.settings.DisplaySetting
import app.amber.core.utils.JsonInstant
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ui.CardGroup
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.ThemePackage
import app.amber.feature.ui.theme.ThemePackageApplyResult
import app.amber.feature.ui.theme.ThemePackageExporter
import app.amber.feature.ui.theme.ThemePackageImportResult
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.buildAmberTokens
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * P8-09 — 主题设置区接入：主题库区块（导出当前主题 / 导入主题包 + preview /
 * 内置主题 apply / 导入包 apply/remove）。复用现有主题设置页
 * （SettingDisplayPage）的卡片样式。
 */
@Composable
fun ThemeLibrarySection(
    displaySetting: DisplaySetting,
    modifier: Modifier = Modifier,
    manager: ThemePackageManager = koinInject(),
) {
    val workspace = workspaceColors()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val importedPackages by manager.observeLibrary().collectAsState(initial = emptyList())
    var importPreview by remember { mutableStateOf<ThemePackageImportResult.Preview?>(null) }
    var importError by remember { mutableStateOf<List<String>?>(null) }
    var applyMessage by remember { mutableStateOf<String?>(null) }

    // applyMessage 只在失败时赋值；弹一次 toast 后立即清空，避免重复弹出
    LaunchedEffect(applyMessage) {
        applyMessage?.let {
            toaster.show(it, type = ToastType.Info)
            applyMessage = null
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (json == null) {
            importError = listOf("无法读取所选文件")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            when (val result = manager.prepareImport(json)) {
                is ThemePackageImportResult.Preview -> importPreview = result
                is ThemePackageImportResult.Rejected -> importError = result.issues
            }
        }
    }

    val exportCurrent: () -> Unit = {
        val pkg = ThemePackageExporter.export(displaySetting)
        val text = JsonInstant.encodeToString(ThemePackage.serializer(), pkg)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Amber 主题包：${pkg.name}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "导出主题包")) }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardGroup(
            modifier = Modifier.padding(horizontal = 2.dp),
            title = { SectionLabel("主题库") },
        ) {
            item(
                headlineContent = { Text("导出当前主题") },
                supportingContent = { Text("把当前色系、强调色、字体与布局导出为主题包 JSON，可分享或备份") },
                trailingContent = {
                    TextButton(onClick = exportCurrent) { Text("导出", color = workspace.ink) }
                },
            )
            item(
                headlineContent = { Text("导入主题包") },
                supportingContent = { Text("从文件导入主题包（JSON）；内置主题不可被导入包覆盖") },
                trailingContent = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                        Text("导入", color = workspace.ink)
                    }
                },
            )

            // 内置主题
            listOf("WARM" to "暖石墨 Warm", "SAGE" to "鼠尾草 Sage").forEach { (family, label) ->
                val active = displaySetting.amberBaseFamily == family &&
                    displaySetting.appliedThemePackageId == null
                item(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label)
                            if (active) BuiltinActiveTag()
                        }
                    },
                    supportingContent = { Text("内置主题 · 明暗跟随系统模式") },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    applyMessage = when (manager.applyBuiltin(family)) {
                                        ThemePackageApplyResult.Applied,
                                        ThemePackageApplyResult.AlreadyApplied,
                                        -> null
                                        ThemePackageApplyResult.Reverted -> "应用失败，已回退到上一个主题"
                                        else -> "应用失败"
                                    }
                                }
                            },
                        ) { Text(if (active) "使用中" else "应用", color = if (active) workspace.faint else workspace.ink) }
                    },
                )
            }

            // 导入的主题包
            importedPackages.forEach { entity ->
                val applied = displaySetting.appliedThemePackageId == entity.id
                item(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (applied) BuiltinActiveTag()
                        }
                    },
                    supportingContent = { Text("导入主题 · ${entity.id}") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        applyMessage = when (manager.apply(entity.id)) {
                                            ThemePackageApplyResult.Applied,
                                            ThemePackageApplyResult.AlreadyApplied,
                                            -> null
                                            ThemePackageApplyResult.Reverted -> "应用失败，已回退到上一个主题"
                                            else -> "应用失败"
                                        }
                                    }
                                },
                            ) { Text(if (applied) "使用中" else "应用", color = if (applied) workspace.faint else workspace.ink) }
                            TextButton(
                                onClick = {
                                    scope.launch { manager.remove(entity.id) }
                                },
                            ) { Text("移除", color = workspace.faint) }
                        }
                    },
                )
            }
            if (importedPackages.isEmpty()) {
                item(
                    headlineContent = { Text("暂无导入的主题包") },
                    supportingContent = { Text("导入后在这里应用或移除") },
                )
            }
        }
    }

    // 导入 preview：包名 / token 概览 / 最小 token 视觉预览 / 未知 token 提示。
    // 这里展示的是内存 candidate；取消只丢弃 try-on，不会写入库或 Settings。
    importPreview?.let { preview ->
        val pkg = preview.pkg
        AlertDialog(
            onDismissRequest = {
                manager.discardTryOn(pkg.id, preview.candidateDigest)
                importPreview = null
            },
            title = { Text("导入主题包") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pkg.name, style = MaterialTheme.typography.titleMedium)
                    ThemePackageTokenPreview(preview.candidate)
                    Text(
                        "颜色 ${pkg.colors.size} · 字体 ${pkg.fonts.size} · 布局 ${pkg.layout.size}" +
                            if (pkg.id.startsWith(ThemePackage.BUILTIN_ID_PREFIX)) " · 内置保留 id" else "",
                    )
                    if (preview.unknownTokens.isNotEmpty()) {
                        Text(
                            "以下 token 不在允许列表内，已保留但不会应用：${preview.unknownTokens.joinToString("、")}",
                            color = Color(0xFFB45F06),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = manager.applyPrepared(pkg.id, preview.candidateDigest)
                            applyMessage = when (result) {
                                ThemePackageApplyResult.Applied,
                                ThemePackageApplyResult.AlreadyApplied,
                                -> null
                                ThemePackageApplyResult.Reverted -> "应用失败，已回退到上一个主题"
                                else -> "应用失败"
                            }
                            if (result == ThemePackageApplyResult.Applied ||
                                result == ThemePackageApplyResult.AlreadyApplied
                            ) {
                                importPreview = null
                            }
                        }
                    },
                ) { Text("导入并应用") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        manager.discardTryOn(pkg.id, preview.candidateDigest)
                        importPreview = null
                    },
                ) { Text("取消") }
            },
        )
    }

    // 导入校验失败提示
    importError?.let { issues ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("无法导入主题包") },
            text = { Text(issues.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("知道了") }
            },
        )
    }
}

/** 只用 Android 已支持的 base/accent tokens，避免把 iOS 专属槽位冒充成 Android 能力。 */
@Composable
private fun ThemePackageTokenPreview(displaySetting: DisplaySetting) {
    val base = when (displaySetting.amberBaseFamily) {
        "SAGE" -> AmberBase.SAGE
        else -> AmberBase.LIGHT
    }
    val accent = runCatching { Color(AndroidColor.parseColor(displaySetting.accentColor)) }
        .getOrDefault(Color(0xFFB8623A))
    val tokens = buildAmberTokens(base, accent)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .background(tokens.bg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(26.dp)
                    .fillMaxWidth(0.28f)
                    .background(tokens.accent),
            )
            Text(
                "${displaySetting.amberBaseFamily} · ${displaySetting.accentColor}",
                color = tokens.ink,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            "字体 ${displaySetting.chatFontFamily.name.lowercase()} · 字号 ${displaySetting.fontSizeRatio}",
            color = tokens.ink2,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun BuiltinActiveTag() {
    val workspace = workspaceColors()
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "使用中",
            style = MaterialTheme.typography.labelSmall,
            color = workspace.ink,
        )
    }
}
