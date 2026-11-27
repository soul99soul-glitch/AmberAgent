package app.amber.feature.ui.pages.setting

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.amber.agent.R
import app.amber.core.settings.DisplaySetting
import app.amber.core.utils.navigateToChatPage
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.ThemePackage
import app.amber.feature.ui.theme.ThemePackageApplyResult
import app.amber.feature.ui.theme.ThemePackageImportResult
import app.amber.feature.ui.theme.ThemePackageManager
import app.amber.feature.ui.theme.ThemePackTransfer
import app.amber.feature.ui.theme.ThemeTryOnHost
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalDarkThemePalette
import app.amber.feature.ui.theme.SIT_TERRACOTTA_ACCENT_HEX
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.amber.core.settings.themeRgb
import app.amber.feature.ui.theme.AmberBase
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.ThemePackageApplier
import app.amber.feature.ui.theme.ThemePackageValidation
import app.amber.feature.ui.theme.ThemePackageValidator
import app.amber.feature.ui.theme.baseTokens
import app.amber.feature.ui.theme.buildAmberTokens
import app.amber.feature.ui.theme.themePackPaperTokens
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide

/**
 * P8-09 — 主题设置区接入：主题库区块（导出当前主题 / 导入主题包 + preview /
 * 内置主题 apply / 导入包 apply/remove）。复用设置页的卡片样式。
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
    val navigator = LocalNavController.current
    val importedPackages by manager.observeLibrary().collectAsState(initial = emptyList())
    val activeTryOn by manager.tryOn.collectAsState(initial = null)
    val previewBringIntoViewRequester = remember { BringIntoViewRequester() }
    var themeRequest by rememberSaveable { mutableStateOf("") }
    var applyMessage by remember { mutableStateOf<String?>(null) }

    // applyMessage 只在失败时赋值；弹一次 toast 后立即清空，避免重复弹出
    LaunchedEffect(applyMessage) {
        applyMessage?.let {
            toaster.show(it, type = ToastType.Info)
            applyMessage = null
        }
    }

    LaunchedEffect(activeTryOn?.candidateDigest) {
        if (activeTryOn != null) previewBringIntoViewRequester.bringIntoView()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            if (json == null) {
                applyMessage = context.getString(R.string.setting_theme_library_read_failed)
                return@launch
            }
            val result = try {
                manager.prepareImport(json)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                applyMessage = error.localizedMessage ?: context.getString(R.string.setting_theme_library_try_on_failed)
                return@launch
            }
            when (result) {
                is ThemePackageImportResult.Preview -> Unit
                is ThemePackageImportResult.Rejected -> applyMessage = result.issues.joinToString("\n")
            }
        }
    }

    val exportCurrent: () -> Unit = export@{
        val exported = try {
            ThemePackTransfer.export(displaySetting)
        } catch (_: IllegalArgumentException) {
            toaster.show(context.getString(R.string.setting_theme_library_export_failed), type = ToastType.Error)
            return@export
        }
        val currentLibraryEntry = importedPackages.firstOrNull {
            it.id == displaySetting.appliedThemePackageId
        }
        val document = exported.copy(
            id = currentLibraryEntry?.id ?: displaySetting.themePack?.id ?: exported.id,
            displayName = currentLibraryEntry?.name
                ?: displaySetting.themePack?.displayName
                ?: context.getString(R.string.setting_theme_library_custom_theme_name),
        )
        val safeId = document.id
            .map { char -> if (char.isLetterOrDigit() || char in "._-") char else '-' }
            .joinToString("")
            .trim('.', '-', '_')
            .take(80)
            .ifBlank { "theme" }
        val fileName = "amber-theme-$safeId.json"
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    val exportDir = File(context.cacheDir, "theme-export")
                    check(exportDir.isDirectory || exportDir.mkdirs()) { "Unable to create export directory" }
                    File(exportDir, fileName).apply {
                        writeText(ThemePackTransfer.encode(document), Charsets.UTF_8)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toaster.show(context.getString(R.string.setting_theme_library_export_failed), type = ToastType.Error)
                return@launch
            }

            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        context.getString(R.string.setting_theme_library_export_subject, document.displayName),
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    Intent.createChooser(
                        send,
                        context.getString(R.string.setting_theme_library_export_chooser),
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                toaster.show(context.getString(R.string.setting_theme_library_export_failed), type = ToastType.Error)
            }
        }
    }

    fun openThemeChat(promptRes: Int) {
        val request = themeRequest.trim()
        if (request.isEmpty()) {
            applyMessage = context.getString(R.string.setting_theme_library_empty_request)
            return
        }
        navigateToChatPage(
            navigator = navigator,
            initText = context.getString(promptRes, request),
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Theme cards (iOS parity): built-ins first, then imported packs; tap a card to apply.
        SettingSectionTitle(
            text = stringResource(R.string.setting_theme_library_title),
            modifier = Modifier.padding(top = 10.dp),
        )
        val warmActive = displaySetting.amberBaseFamily == "WARM" &&
            displaySetting.appliedThemePackageId == null &&
            displaySetting.themePack == null &&
            displaySetting.accentColor.equals(SIT_TERRACOTTA_ACCENT_HEX, ignoreCase = true)
        val darkPalette = LocalDarkThemePalette.current
        val builtinActiveId = displaySetting.appliedThemePackageId?.takeIf { id ->
            id in ThemePackTransfer.builtinPackIds && displaySetting.themePack == ThemePackTransfer.builtin(id)
        }
        val cards = buildList {
            add(
                ThemeCardModel(
                    key = "builtin:WARM",
                    name = stringResource(R.string.setting_theme_library_builtin_warm),
                    preview = themeCardPreviewTokens(DisplaySetting(accentColor = SIT_TERRACOTTA_ACCENT_HEX), darkPalette),
                    selected = warmActive,
                    onApply = { manager.applyBuiltin("WARM") },
                ),
            )
            ThemePackTransfer.builtinPackIds.forEach { id ->
                val document = requireNotNull(ThemePackTransfer.builtin(id))
                add(
                    ThemeCardModel(
                        key = id,
                        name = when (id) {
                            "pi-steel" -> stringResource(R.string.setting_theme_library_builtin_pi)
                            "notion-blue" -> stringResource(R.string.setting_theme_library_builtin_notion)
                            else -> document.displayName
                        },
                        preview = themeCardPreviewTokens(
                            ThemePackageApplier.applyTokens(ThemePackTransfer.asPackage(document), DisplaySetting()),
                            darkPalette,
                        ),
                        selected = builtinActiveId == id,
                        onApply = { manager.applyBuiltinPack(id) },
                    ),
                )
            }
            importedPackages.forEach { entity ->
                val pkg = (ThemePackageValidator.validateJson(entity.json) as? ThemePackageValidation.Valid)?.themePackage
                add(
                    ThemeCardModel(
                        key = entity.id,
                        name = entity.name,
                        preview = themeCardPreviewTokens(
                            pkg?.let { ThemePackageApplier.applyTokens(it, DisplaySetting()) } ?: DisplaySetting(),
                            darkPalette,
                        ),
                        selected = displaySetting.appliedThemePackageId == entity.id && builtinActiveId != entity.id,
                        onApply = { manager.apply(entity.id) },
                        onRemove = { scope.launch { manager.remove(entity.id) } },
                    ),
                )
            }
        }
        // Cards keep their phone proportions: wider windows (unfolded, tablet) add columns
        // instead of stretching each miniature page.
        BoxWithConstraints {
        val columns = themeCardColumns(maxWidth)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ThemeCardSpacing)) {
                row.forEach { card ->
                    ThemeCard(
                        card = card,
                        onClick = {
                            scope.launch {
                                applyMessage = when (card.onApply()) {
                                    ThemePackageApplyResult.Applied,
                                    ThemePackageApplyResult.AlreadyApplied,
                                    -> null
                                    ThemePackageApplyResult.Reverted -> context.getString(R.string.setting_theme_library_apply_reverted_error)
                                    else -> context.getString(R.string.setting_theme_library_apply_error)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                Text(stringResource(R.string.setting_theme_library_import_title), color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = exportCurrent) {
                Text(stringResource(R.string.setting_theme_library_export_current_title), color = workspace.muted)
            }
        }
        activeTryOn?.let { preview ->
            SettingCardGroup(
                title = stringResource(R.string.setting_theme_library_import_preview),
                modifier = Modifier.bringIntoViewRequester(previewBringIntoViewRequester),
            ) {
                rawItem {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(preview.pkg.name, style = MaterialTheme.typography.titleMedium)
                        ThemePackageTokenPreview(preview.candidate)
                        Text(
                            stringResource(
                                R.string.setting_theme_library_token_counts,
                                preview.pkg.colors.size,
                                preview.pkg.fonts.size,
                                preview.pkg.layout.size,
                                if (preview.pkg.id.startsWith(ThemePackage.BUILTIN_ID_PREFIX)) {
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
                        ThemeTryOnHost(
                            packageId = preview.pkg.id,
                            candidateDigest = preview.candidateDigest,
                            manager = manager,
                        )
                    }
                }
            }
        }

        SettingCardGroup(
            title = stringResource(R.string.setting_theme_library_ai_title),
        ) {
            rawItem {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.setting_theme_library_ai_desc))
                    OutlinedTextField(
                        value = themeRequest,
                        onValueChange = { themeRequest = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.setting_theme_library_ai_request_hint)) },
                        minLines = 3,
                        maxLines = 5,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openThemeChat(R.string.setting_theme_library_generate_prompt) },
                            enabled = themeRequest.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.setting_theme_library_generate_and_try_on))
                        }
                        OutlinedButton(
                            onClick = { openThemeChat(R.string.setting_theme_library_modify_prompt) },
                            enabled = themeRequest.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.setting_theme_library_modify_and_try_on))
                        }
                    }
                }
            }
        }

    }

}

@Composable
internal fun ThemeFamilyChoice(
    selected: String,
    onSelected: (String) -> Unit,
) {
    val colors = workspaceColors()
    val options = listOf(
        "WARM" to stringResource(R.string.setting_display_page_base_family_warm),
        "SAGE" to stringResource(R.string.setting_display_page_base_family_sage),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { (family, label) ->
            val active = family == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelected(family) }
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else colors.row,
                    )
                    .border(
                        width = 1.dp,
                        color = if (active) MaterialTheme.colorScheme.primary else colors.hairline,
                        shape = CircleShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        colors.muted
                    },
                ) {
                    Text(label, style = app.amber.feature.ui.theme.LocalAmberType.current.meta)
                }
            }
        }
    }
}

/** The swatch follows the same active try-on and light/dark palette as the surrounding app. */
@Composable
private fun ThemePackageTokenPreview(displaySetting: DisplaySetting) {
    val tokens = LocalAmberTokens.current
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
                "${displaySetting.themePack?.displayName ?: displaySetting.amberBaseFamily} · ${displaySetting.accentColor}",
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

private val ThemeCardSpacing = 12.dp

/** Two columns on phones; one more per ~180dp of width, up to four on unfolded and tablet screens. */
internal fun themeCardColumns(width: Dp): Int =
    ((width + ThemeCardSpacing) / (180.dp + ThemeCardSpacing)).toInt().coerceIn(2, 4)

private class ThemeCardModel(
    val key: String,
    val name: String,
    val preview: AmberTokens,
    val selected: Boolean,
    val onApply: suspend () -> ThemePackageApplyResult,
    val onRemove: (() -> Unit)? = null,
)

private fun themeCardPreviewTokens(display: DisplaySetting, darkPalette: Boolean): AmberTokens {
    val pack = display.themePack
    val family = when {
        display.amberBaseFamily == "SAGE" && darkPalette -> AmberBase.SAGE_DARK
        display.amberBaseFamily == "SAGE" -> AmberBase.SAGE
        darkPalette -> AmberBase.DARK
        else -> AmberBase.LIGHT
    }
    val base = pack?.let { themePackPaperTokens(it.paper, darkPalette) } ?: baseTokens(family)
    val accentRgb = themeRgb(pack?.accentHex ?: display.accentColor)
        ?: runCatching { android.graphics.Color.parseColor(display.accentColor) and 0xFFFFFF }.getOrNull()
        ?: (SIT_TERRACOTTA_ACCENT_HEX.drop(1).toInt(16))
    return buildAmberTokens(base, Color(0xFF000000L or accentRgb.toLong()), pack?.design)
}

/** A miniature page of the theme above its name; the outline marks the theme in use. */
@Composable
private fun ThemeCard(
    card: ThemeCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = LocalAmberTokens.current
    val preview = card.preview
    val shape = RoundedCornerShape(18.dp)
    val selectedLabel = stringResource(R.string.setting_theme_library_active)
    Column(
        modifier = modifier
            .clip(shape)
            // Always 2dp so selecting a card does not shift its size.
            .border(2.dp, if (card.selected) current.accent else preview.line, shape)
            .clickable(onClick = onClick)
            .semantics {
                selected = card.selected
                if (card.selected) stateDescription = selectedLabel
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(preview.bg)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(preview.accent))
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(width = 56.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(preview.ink2),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                PreviewBar(preview.ink3, 0.72f)
                PreviewBar(preview.ink3, 0.52f)
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(width = 44.dp, height = 15.dp)
                        .clip(CircleShape)
                        .background(preview.accent),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(preview.surface)
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                card.name,
                modifier = Modifier.weight(1f),
                color = preview.ink,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when {
                card.selected -> Box(
                    Modifier.size(24.dp).clip(CircleShape).background(current.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Check,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = current.accentInk,
                    )
                }
                card.onRemove != null -> TextButton(onClick = card.onRemove) {
                    Text(stringResource(R.string.setting_theme_library_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PreviewBar(color: Color, fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(6.dp)
            .clip(CircleShape)
            .background(color),
    )
}
