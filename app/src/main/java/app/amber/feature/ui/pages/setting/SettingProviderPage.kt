package app.amber.feature.ui.pages.setting

import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import me.rerere.hugeicons.stroke.ArrowRight01
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import me.rerere.ai.provider.ProviderSetting
import app.amber.agent.R
import me.rerere.rikkahub.Screen
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.AutoAIIcon
import app.amber.feature.ui.components.ui.WorkspaceSearchField
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.decodeProviderSetting
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.pages.setting.components.ProviderConfigure
import app.amber.feature.ui.pages.setting.components.ProviderTemplatePickerSheet
import app.amber.feature.ui.theme.CustomColors
import app.amber.core.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import androidx.compose.foundation.lazy.items as lazyItems

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var searchQuery by remember { mutableStateOf("") }
    var pendingDeleteProvider by remember { mutableStateOf<ProviderSetting?>(null) }
    val lazyListState = rememberLazyListState()
    val filteredProviders = remember(settings.providers, searchQuery) {
        if (searchQuery.isBlank()) {
            settings.providers
        } else {
            settings.providers.filter { provider ->
                provider.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_provider_page_title),
                navigationIcon = { BackButton() },
                actions = {
                    ImportProviderButton {
                        vm.updateSettings(
                            settings.copy(
                                providers = listOf(it.copyProvider(Uuid.random())) + settings.providers
                            )
                        )
                    }
                    AddButton(
                        existingProviderIds = remember(settings.providers) {
                            settings.providers.map { it.id }.toSet()
                        },
                    ) {
                        vm.updateSettings(
                            settings.copy(
                                providers = listOf(it) + settings.providers
                            )
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspaceColors().canvas,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            WorkspaceSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = stringResource(R.string.setting_provider_page_search_providers),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
                state = lazyListState,
            ) {
                // Notion grouped-row pattern: every item shares one outer
                // rounded hairline, internal 1dp gaps act as dividers.
                val total = filteredProviders.size
                lazyItems(filteredProviders, key = { it.id }) { provider ->
                    val index = filteredProviders.indexOfFirst { it.id == provider.id }
                    ProviderItem(
                        modifier = Modifier.fillMaxWidth(),
                        provider = provider,
                        isFirst = index == 0,
                        isLast = index == total - 1,
                        onEdit = {
                            navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                        },
                        onDelete = {
                            pendingDeleteProvider = provider
                        },
                    )
                }
            }
        }
    }

    pendingDeleteProvider?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProvider = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.setting_provider_page_delete_dialog_text)) },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProvider = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteProvider = null
                        vm.updateSettings(settings.copy(providers = settings.providers - provider))
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
        )
    }
}

@Composable
private fun ImportProviderButton(
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        handleQRResult(result, onAdd, toaster, context)
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            handleImageQRCode(it, onAdd, toaster, context)
        }
    }

    IconButton(
        onClick = {
            showImportDialog = true
        }
    ) {
        Icon(HugeIcons.FileImport, null)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 主要操作：扫描二维码
                        Button(
                            onClick = {
                                showImportDialog = false
                                scanQrCodeLauncher.launch(null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Camera01,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // 次要操作：从相册选择
                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                pickImageLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Image02,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}

private fun handleQRResult(
    result: QRResult,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        when (result) {
            is QRResult.QRError -> {
                toaster.show(
                    context.getString(
                        R.string.setting_provider_page_scan_error,
                        result
                    ), type = ToastType.Error
                )
            }

            QRResult.QRMissingPermission -> {
                toaster.show(
                    context.getString(R.string.setting_provider_page_no_permission),
                    type = ToastType.Error
                )
            }

            is QRResult.QRSuccess -> {
                val setting = decodeProviderSetting(result.content.rawValue ?: "")
                onAdd(setting)
                toaster.show(
                    context.getString(R.string.setting_provider_page_import_success),
                    type = ToastType.Success
                )
            }

            QRResult.QRUserCanceled -> {}
        }
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}

private fun handleImageQRCode(
    uri: Uri,
    onAdd: (ProviderSetting) -> Unit,
    toaster: com.dokar.sonner.ToasterState,
    context: android.content.Context
) {
    runCatching {
        // 使用ImageUtils解析二维码
        val qrContent = ImageUtils.decodeQRCodeFromUri(context, uri)

        if (qrContent.isNullOrEmpty()) {
            toaster.show(
                context.getString(R.string.setting_provider_page_no_qr_found),
                type = ToastType.Error
            )
            return
        }

        val setting = decodeProviderSetting(qrContent)
        onAdd(setting)
        toaster.show(
            context.getString(R.string.setting_provider_page_import_success),
            type = ToastType.Success
        )
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_image_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}


@Composable
private fun AddButton(
    existingProviderIds: Set<Uuid>,
    onAdd: (ProviderSetting) -> Unit,
) {
    // Two-step add flow:
    //  1. Tap "+"  → open ProviderTemplatePickerSheet (bundled brands + custom types)
    //  2. Pick row → close sheet, open the existing edit dialog pre-filled with that initial
    //     ProviderSetting, then user reviews / fills in API key / hits "Add".
    //
    // The previous implementation immediately opened a blank `ProviderSetting.OpenAI()`
    // editor with a hidden "Codex OAuth" shortcut tucked into the dismiss row. That made
    // the brand-tagged providers (DeepSeek / Kimi / 智谱 / MiMo) impossible to add back
    // once the user had deleted them, because nothing in the editor lets the user choose a
    // brand directly. The picker sheet is now the single discovery point for both.
    var showPicker by remember { mutableStateOf(false) }
    // Picker's OAuth quick-start rows ask the editor to auto-trigger the OAuth handler
    // the moment it composes, so the user doesn't have to switch tabs / pick auth mode
    // again after already telling the picker "I want OAuth". Reset on each dismiss/
    // confirm so a subsequent non-OAuth template doesn't inherit a stale `true`.
    var autoStartOAuth by remember { mutableStateOf(false) }
    val dialogState = useEditState<ProviderSetting> {
        onAdd(it)
        autoStartOAuth = false
    }

    IconButton(
        onClick = { showPicker = true }
    ) {
        Icon(HugeIcons.Add01, "Add")
    }

    if (showPicker) {
        ProviderTemplatePickerSheet(
            existingProviderIds = existingProviderIds,
            onDismiss = { showPicker = false },
            onPick = { initial, requestAutoStart ->
                showPicker = false
                autoStartOAuth = requestAutoStart
                dialogState.open(initial)
            },
        )
    }

    if (dialogState.isEditing) {
        AlertDialog(
            onDismissRequest = {
                autoStartOAuth = false
                dialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.setting_provider_page_add_provider))
            },
            text = {
                dialogState.currentState?.let {
                    ProviderConfigure(
                        provider = it,
                        autoStartOAuth = autoStartOAuth,
                        onAutoStartConsumed = { autoStartOAuth = false },
                    ) { newState ->
                        dialogState.currentState = newState
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        dialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_add))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        autoStartOAuth = false
                        dialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,  // 保留参数兼容，但行内已不再显示删除按钮，删除移到 detail 页
) {
    val workspace = workspaceColors()
    val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
    // V3: 整行 background 用主题色区分启用/禁用。深色主题里 accent 叠太多会吞掉副标题，
    // 所以深色只保留很轻的 tint；状态主要靠文字和 logo spot 表达。
    val enabledRowTint = if (chatTheme.isDark) 0.08f else 0.18f
    val rowBg = if (provider.enabled) {
        chatTheme.accent.copy(alpha = enabledRowTint).compositeOver(chatTheme.surface)
    } else {
        chatTheme.surface
    }
    val secondaryTextColor = when {
        provider.enabled && chatTheme.isDark -> chatTheme.inkSoft
        provider.enabled -> chatTheme.inkFaint
        else -> chatTheme.inkFaint
    }
    val providerIconBackground = if (provider.enabled) chatTheme.modelLogoBg else Color.Transparent
    androidx.compose.material3.Surface(
        onClick = onEdit,
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (isFirst) 18.dp else 0.dp,
            topEnd = if (isFirst) 18.dp else 0.dp,
            bottomStart = if (isLast) 18.dp else 0.dp,
            bottomEnd = if (isLast) 18.dp else 0.dp,
        ),
        color = rowBg,
        contentColor = chatTheme.ink,
        border = androidx.compose.foundation.BorderStroke(1.dp, chatTheme.hair),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // V3: logo 用 40dp 圆形 (CircleShape) bg, 比之前 36dp 圆角矩形更扩.
                // enabled = modelLogoBg spot (比 row bg 更轻), disabled = transparent.
                // AutoAIIcon 28dp 占比 ~70% 不显得拥挤.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(providerIconBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    AutoAIIcon(
                        name = provider.name,
                        // color=Transparent 让 AIIcon 内置 Surface 圆形 bg 隐形, 避免跟外层 40dp
                        // CircleShape 形成"双重圆环" (内层 24dp 圆 + 外层 40dp 圆).
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier
                            .size(28.dp)
                            .then(
                                if (!provider.enabled) Modifier.alpha(0.45f) else Modifier
                            ),
                    )
                }
                // name + 模型计数
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (provider.enabled) chatTheme.ink else chatTheme.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.setting_provider_page_model_count,
                            provider.models.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryTextColor,
                    )
                }
                // 仅 disabled 显示灰胶囊（"已禁用"），enabled 状态不显示
                if (!provider.enabled) {
                    app.amber.feature.ui.components.ui.WorkspaceStatusPill(
                        text = stringResource(R.string.setting_provider_page_disabled),
                        tone = app.amber.feature.ui.components.ui.WorkspaceTone.Neutral,
                    )
                }
                // chevron-right (进入 detail)
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = secondaryTextColor.copy(alpha = if (provider.enabled) 0.72f else 1f),
                )
            }
            // V3 hairline divider — 仅非 last 行显示，模拟"单卡 + hairline 分隔"
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(chatTheme.hair),
                )
            }
        }
    }
}
