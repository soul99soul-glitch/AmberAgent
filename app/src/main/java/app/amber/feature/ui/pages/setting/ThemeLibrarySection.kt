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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.agent.R
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
            importError = listOf(context.getString(R.string.setting_theme_library_read_failed))
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
            putExtra(
                Intent.EXTRA_SUBJECT,
                context.getString(R.string.setting_theme_library_export_subject, pkg.name),
            )
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    send,
                    context.getString(R.string.setting_theme_library_export_chooser),
                )
            )
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardGroup(
            modifier = Modifier.padding(horizontal = 2.dp),
            title = { SectionLabel(stringResource(R.string.setting_theme_library_title)) },
        ) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_theme_library_export_current_title)) },
                supportingContent = { Text(stringResource(R.string.setting_theme_library_export_current_desc)) },
                trailingContent = {
                    TextButton(onClick = exportCurrent) {
                        Text(stringResource(R.string.export_title), color = workspace.ink)
                    }
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.setting_theme_library_import_title)) },
                supportingContent = { Text(stringResource(R.string.setting_theme_library_import_desc)) },
                trailingContent = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                        Text(stringResource(R.string.setting_theme_library_import_action), color = workspace.ink)
                    }
                },
            )

            // 内置主题
            listOf(
                "WARM" to R.string.setting_theme_library_builtin_warm,
                "SAGE" to R.string.setting_theme_library_builtin_sage,
            ).forEach { (family, labelRes) ->
                val active = displaySetting.amberBaseFamily == family &&
                    displaySetting.appliedThemePackageId == null
                item(
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(labelRes))
                            if (active) BuiltinActiveTag()
                        }
                    },
                    supportingContent = { Text(stringResource(R.string.setting_theme_library_builtin_desc)) },
                    trailingContent = {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    applyMessage = when (manager.applyBuiltin(family)) {
                                        ThemePackageApplyResult.Applied,
                                        ThemePackageApplyResult.AlreadyApplied,
                                        -> null
                                        ThemePackageApplyResult.Reverted -> context.getString(R.string.setting_theme_library_apply_reverted_error)
                                        else -> context.getString(R.string.setting_theme_library_apply_error)
                                    }
                                }
                            },
                        ) {
                            Text(
                                stringResource(
                                    if (active) {
                                        R.string.setting_theme_library_active
                                    } else {
                                        R.string.setting_theme_library_apply
                                    },
                                ),
                                color = if (active) workspace.faint else workspace.ink,
                            )
                        }
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
                    supportingContent = {
                        Text(stringResource(R.string.setting_theme_library_imported_detail, entity.id))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        applyMessage = when (manager.apply(entity.id)) {
                                            ThemePackageApplyResult.Applied,
                                            ThemePackageApplyResult.AlreadyApplied,
                                            -> null
                                            ThemePackageApplyResult.Reverted -> context.getString(R.string.setting_theme_library_apply_reverted_error)
                                            else -> context.getString(R.string.setting_theme_library_apply_error)
                                        }
                                    }
                                },
                            ) {
                                Text(
                                    stringResource(
                                        if (applied) {
                                            R.string.setting_theme_library_active
                                        } else {
                                            R.string.setting_theme_library_apply
                                        },
                                    ),
                                    color = if (applied) workspace.faint else workspace.ink,
                                )
                            }
                            TextButton(
                                onClick = {
                                    scope.launch { manager.remove(entity.id) }
                                },
                            ) {
                                Text(stringResource(R.string.setting_theme_library_remove), color = workspace.faint)
                            }
                        }
                    },
                )
            }
            if (importedPackages.isEmpty()) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_theme_library_empty_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_theme_library_empty_desc)) },
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
            title = { Text(stringResource(R.string.setting_theme_library_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pkg.name, style = MaterialTheme.typography.titleMedium)
                    ThemePackageTokenPreview(preview.candidate)
                    Text(
                        stringResource(
                            R.string.setting_theme_library_token_counts,
                            pkg.colors.size,
                            pkg.fonts.size,
                            pkg.layout.size,
                            if (pkg.id.startsWith(ThemePackage.BUILTIN_ID_PREFIX)) {
                                stringResource(R.string.setting_theme_library_builtin_id_suffix)
                            } else {
                                ""
                            },
                        ),
                    )
                    if (preview.unknownTokens.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.setting_theme_library_unknown_tokens,
                                preview.unknownTokens.joinToString(", "),
                            ),
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
                                ThemePackageApplyResult.Reverted -> context.getString(R.string.setting_theme_library_apply_reverted_error)
                                else -> context.getString(R.string.setting_theme_library_apply_error)
                            }
                            if (result == ThemePackageApplyResult.Applied ||
                                result == ThemePackageApplyResult.AlreadyApplied
                            ) {
                                importPreview = null
                            }
                        }
                    },
                ) { Text(stringResource(R.string.setting_theme_library_import_apply)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        manager.discardTryOn(pkg.id, preview.candidateDigest)
                        importPreview = null
                    },
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 导入校验失败提示
    importError?.let { issues ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.setting_theme_library_import_failed_title)) },
            text = { Text(issues.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text(stringResource(R.string.update_card_close))
                }
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
            stringResource(
                R.string.setting_theme_library_font_summary,
                displaySetting.chatFontFamily.name.lowercase(),
                displaySetting.fontSizeRatio,
            ),
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
            text = stringResource(R.string.setting_theme_library_active),
            style = MaterialTheme.typography.labelSmall,
            color = workspace.ink,
        )
    }
}
