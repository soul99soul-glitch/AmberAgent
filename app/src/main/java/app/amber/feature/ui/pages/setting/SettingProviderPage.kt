package app.amber.feature.ui.pages.setting

import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.ChartBarLine
import me.rerere.hugeicons.stroke.Share01
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.decodeProviderSetting
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderHairline
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderLiveDot
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderSearchField
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.ProviderTemplatePickerSheet
import app.amber.feature.ui.pages.setting.components.providerAuthLabel
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    var searchQuery by remember { mutableStateOf("") }
    var pendingDeleteProvider by remember { mutableStateOf<ProviderSetting?>(null) }
    val lazyListState = rememberLazyListState()
    val filteredProviders = remember(settings.providers, searchQuery) {
        val query = searchQuery.trim()
        if (searchQuery.isBlank()) {
            settings.providers
        } else {
            settings.providers.filter { provider ->
                provider.name.contains(query, ignoreCase = true) ||
                    provider.providerSlugLabel().contains(query, ignoreCase = true)
            }
        }
    }
    val onlineProviders = remember(filteredProviders) { filteredProviders.filter { it.enabled } }
    val disabledProviders = remember(filteredProviders) { filteredProviders.filterNot { it.enabled } }
    val totalModelCount = remember(settings.providers) { settings.providers.sumOf { it.models.size } }
    val onlineCount = remember(settings.providers) { settings.providers.count { it.enabled } }
    val t = LocalAmberTokens.current

    Scaffold(
        topBar = {
            ProviderRegistryTopBar(
                title = "提供商",
                onBack = { navController.popBackStack() },
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
            )
        },
        containerColor = t.bg,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ProviderAggregateStrip(
                providerCount = settings.providers.size,
                modelCount = totalModelCount,
                onlineCount = onlineCount,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 10.dp, bottom = 2.dp),
            )
            ProviderSearchField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = "搜索提供商 / slug",
                imageVector = HugeIcons.Search01,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp),
                state = lazyListState,
            ) {
                if (onlineProviders.isNotEmpty()) {
                    item("online_label") {
                        ProviderSectionLabel("在线", count = onlineProviders.size)
                    }
                    item("online_group") {
                        ProviderCard(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            onlineProviders.forEachIndexed { index, provider ->
                                if (index > 0) ProviderHairline(Modifier.padding(horizontal = 14.dp))
                                ProviderItem(
                                    modifier = Modifier.fillMaxWidth(),
                                    provider = provider,
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
                if (disabledProviders.isNotEmpty()) {
                    item("disabled_label") {
                        ProviderSectionLabel("未启用", count = disabledProviders.size)
                    }
                    item("disabled_group") {
                        ProviderCard(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                            disabledProviders.forEachIndexed { index, provider ->
                                if (index > 0) ProviderHairline(Modifier.padding(horizontal = 14.dp))
                                ProviderItem(
                                    modifier = Modifier.fillMaxWidth(),
                                    provider = provider,
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
            }
        }
    }

    pendingDeleteProvider?.let { provider ->
        ProviderConfirmDialog(
            title = stringResource(R.string.confirm_delete),
            message = stringResource(R.string.setting_provider_page_delete_dialog_text),
            confirmText = stringResource(R.string.delete),
            onDismiss = { pendingDeleteProvider = null },
            onConfirm = {
                pendingDeleteProvider = null
                vm.updateSettings(settings.copy(providers = settings.providers - provider))
            },
        )
    }
}

@Composable
private fun ProviderRegistryTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.bg)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton()
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = type.screenTitle,
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

@Composable
private fun ProviderAggregateStrip(
    providerCount: Int,
    modelCount: Int,
    onlineCount: Int,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.ChartBarLine,
            contentDescription = null,
            tint = t.accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(9.dp))
        ProviderStat(providerCount, "提供商")
        ProviderStatSep()
        ProviderStat(modelCount, "模型")
        ProviderStatSep()
        ProviderStat(onlineCount, "在线", accent = true)
    }
}

@Composable
private fun ProviderStat(value: Int, label: String, accent: Boolean = false) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            value.toString(),
            style = type.meta.copy(fontWeight = FontWeight.Bold),
            color = if (accent) t.accent else t.ink,
        )
        Text(
            " $label",
            style = type.secondary.copy(fontSize = 11.5.sp),
            color = t.ink3,
        )
    }
}

@Composable
private fun ProviderStatSep() {
    val t = LocalAmberTokens.current
    Box(
        Modifier
            .padding(horizontal = 13.dp)
            .size(width = 1.dp, height = 11.dp)
            .background(t.line2)
    )
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

    ProviderIconButton(
        imageVector = HugeIcons.Share01,
        contentDescription = null,
        rotate180 = true,
        onClick = { showImportDialog = true },
    )

    if (showImportDialog) {
        ProviderImportDialog(
            onDismiss = { showImportDialog = false },
            onScanQr = {
                showImportDialog = false
                scanQrCodeLauncher.launch(null)
            },
            onPickImage = {
                showImportDialog = false
                pickImageLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
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
private fun ProviderConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Dialog(onDismissRequest = onDismiss) {
        ProviderCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = title,
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = t.ink,
                )
                Text(
                    text = message,
                    style = type.secondary,
                    color = t.ink3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    ProviderCommandButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                    )
                    ProviderCommandButton(
                        text = confirmText,
                        onClick = onConfirm,
                        accent = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderImportDialog(
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onPickImage: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Dialog(onDismissRequest = onDismiss) {
        ProviderCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = t.ink,
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_message),
                    style = type.secondary,
                    color = t.ink3,
                )
                ProviderCommandButton(
                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                    imageVector = HugeIcons.Camera01,
                    onClick = onScanQr,
                    accent = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProviderCommandButton(
                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                    imageVector = HugeIcons.Image02,
                    onClick = onPickImage,
                    modifier = Modifier.fillMaxWidth(),
                )
                ProviderCommandButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProviderEditorSheet(
    title: String,
    initialProvider: ProviderSetting,
    confirmText: String,
    autoStartOAuth: Boolean,
    onAutoStartConsumed: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (ProviderSetting) -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftProvider by remember(initialProvider.id) { mutableStateOf(initialProvider) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.bg,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 18.dp),
        ) {
            Text(
                text = title,
                style = type.screenTitle.copy(fontSize = 22.sp),
                color = t.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProviderConsole(
                provider = draftProvider,
                onEdit = { draftProvider = it },
                onCommit = { draftProvider = it },
                autoStartOAuth = autoStartOAuth,
                onAutoStartConsumed = onAutoStartConsumed,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { currentProvider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderCommandButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    ProviderCommandButton(
                        text = confirmText,
                        onClick = { onConfirm(currentProvider) },
                        accent = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderSheetGrabber() {
    val t = LocalAmberTokens.current
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .width(42.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(t.line2)
    )
}

@Composable
private fun AddButton(
    existingProviderIds: Set<Uuid>,
    onAdd: (ProviderSetting) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var autoStartOAuth by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }

    ProviderIconButton(
        imageVector = HugeIcons.Add01,
        contentDescription = "Add",
        iconSize = 22.dp,
        onClick = { showPicker = true },
    )

    if (showPicker) {
        ProviderTemplatePickerSheet(
            existingProviderIds = existingProviderIds,
            onDismiss = { showPicker = false },
            onPick = { initial, requestAutoStart ->
                showPicker = false
                autoStartOAuth = requestAutoStart
                editingProvider = initial
            },
        )
    }

    editingProvider?.let { initial ->
        ProviderEditorSheet(
            title = stringResource(R.string.setting_provider_page_add_provider),
            initialProvider = initial,
            confirmText = stringResource(R.string.setting_provider_page_add),
            autoStartOAuth = autoStartOAuth,
            onAutoStartConsumed = { autoStartOAuth = false },
            onDismiss = {
                autoStartOAuth = false
                editingProvider = null
            },
            onConfirm = {
                onAdd(it)
                autoStartOAuth = false
                editingProvider = null
            },
        )
    }
}

@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,  // 保留参数兼容，但行内已不再显示删除按钮，删除移到 detail 页
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderMonogram(
            text = provider.name.toProviderMonogram(),
            enabled = provider.enabled,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = provider.name,
                    style = type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (provider.enabled) t.ink else t.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (provider.enabled) {
                    ProviderLiveDot()
                }
            }
            Text(
                text = provider.providerSlugLabel(),
                style = type.meta.copy(fontSize = 11.sp),
                color = t.ink4,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProviderAuthBadge(provider.providerAuthLabel())
            Text(
                text = "${provider.models.size} 模型",
                style = type.meta.copy(fontSize = 11.5.sp),
                color = if (provider.models.isNotEmpty()) t.ink2 else t.ink4,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProviderAuthBadge(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
            .background(t.surface2)
            .border(1.dp, t.line, androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
            color = if (text == "—") t.ink4 else t.ink3,
            maxLines = 1,
        )
    }
}
