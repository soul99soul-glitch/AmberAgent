package app.amber.feature.ui.pages.setting

import android.net.Uri
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.ChartNoAxesCombined
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.decodeProviderSetting
import app.amber.feature.ui.components.ds.BlinkingCursor
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderLiveDot
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSplitBar
import app.amber.feature.ui.pages.setting.components.ProviderTemplatePickerSheet
import app.amber.feature.ui.pages.setting.components.ProviderHairline
import app.amber.feature.ui.pages.setting.components.providerAuthLabel
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    var searchQuery by remember { mutableStateOf("") }
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
    Scaffold(
        modifier = Modifier.amberCanvas(),
        topBar = {
            ProviderRegistryTopBar(
                title = stringResource(R.string.setting_page_providers),
                actions = {
                    ImportProviderButton(
                        existingProviders = settings.providers,
                        onImport = vm::importProviders,
                    ) {
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
        containerColor = Color.Transparent,
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
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
            )
            ProviderRegistryFilter(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.setting_provider_page_filter_placeholder),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                state = lazyListState,
            ) {
                if (onlineProviders.isNotEmpty()) {
                    item("online_label") {
                        ProviderRegistrySectionLabel(
                            stringResource(R.string.setting_provider_page_online),
                            count = onlineProviders.size,
                        )
                    }
                    itemsIndexed(
                        items = onlineProviders,
                        key = { _, provider -> "online_provider_${provider.id}" },
                    ) { index, provider ->
                        ProviderGroupRow(
                            index = index,
                            lastIndex = onlineProviders.lastIndex,
                            modifier = Modifier.padding(
                                bottom = if (index == onlineProviders.lastIndex) 4.dp else 0.dp,
                            ),
                        ) {
                            ProviderItem(
                                modifier = Modifier.fillMaxWidth(),
                                provider = provider,
                                onEdit = {
                                    navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                                },
                            )
                        }
                    }
                }
                if (disabledProviders.isNotEmpty()) {
                    item("disabled_label") {
                        ProviderRegistrySectionLabel(
                            stringResource(R.string.setting_provider_page_disabled),
                            count = disabledProviders.size,
                        )
                    }
                    itemsIndexed(
                        items = disabledProviders,
                        key = { _, provider -> "disabled_provider_${provider.id}" },
                    ) { index, provider ->
                        ProviderGroupRow(
                            index = index,
                            lastIndex = disabledProviders.lastIndex,
                            modifier = Modifier
                                .padding(bottom = if (index == disabledProviders.lastIndex) 18.dp else 0.dp),
                        ) {
                            ProviderItem(
                                modifier = Modifier.fillMaxWidth(),
                                provider = provider,
                                onEdit = {
                                    navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
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
private fun ProviderRegistrySectionLabel(
    text: String,
    count: Int? = null,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text("//", style = type.eyebrow, color = t.accent)
        Text(
            text.uppercase(),
            style = type.eyebrow,
            color = t.ink2,
            maxLines = 1,
        )
        Spacer(Modifier.width(3.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(t.line)
        )
        count?.let {
            Text(
                text = it.toString(),
                style = type.meta.copy(fontSize = 11.sp),
                color = t.ink3,
            )
        }
    }
}

@Composable
private fun ProviderRegistryFilter(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(t.surface2)
            .border(1.dp, t.line, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            tint = t.ink3,
            modifier = Modifier.size(17.dp),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = type.meta.copy(fontSize = 12.sp, color = t.ink),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                text = placeholder,
                                style = type.meta.copy(fontSize = 12.sp),
                                color = t.ink3,
                            )
                            BlinkingCursor(width = 6.dp, height = 17.dp)
                        }
                    }
                    innerTextField()
                },
            )
        }
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .pressable(onClick = { onValueChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(R.string.provider_filter_clear),
                    tint = t.ink3,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderGroupRow(
    index: Int,
    lastIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val first = index == 0
    val last = index == lastIndex
    val shape = RoundedCornerShape(
        topStart = if (first) 14.dp else 0.dp,
        topEnd = if (first) 14.dp else 0.dp,
        bottomEnd = if (last) 14.dp else 0.dp,
        bottomStart = if (last) 14.dp else 0.dp,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val radius = 14.dp.toPx()
                val half = stroke / 2f
                val top = if (first) half else -radius
                val bottom = if (last) size.height - half else size.height + radius
                drawRoundRect(
                    color = t.line,
                    topLeft = Offset(half, top),
                    size = Size(size.width - stroke, bottom - top),
                    cornerRadius = CornerRadius(radius, radius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
            },
    ) {
        if (!first) ProviderHairline()
        content()
    }
}

@Composable
private fun ProviderRegistryTopBar(
    title: String,
    actions: @Composable RowScope.() -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 4.dp, end = 10.dp),
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
    Row(
        modifier = modifier.height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.ChartNoAxesCombined,
            contentDescription = null,
            tint = t.ink3,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(9.dp))
        ProviderStat(providerCount, stringResource(R.string.setting_page_providers))
        ProviderStatSep()
        ProviderStat(modelCount, stringResource(R.string.setting_provider_page_models))
        ProviderStatSep()
        ProviderStat(
            onlineCount,
            stringResource(R.string.setting_provider_page_online),
            accent = true,
        )
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
    existingProviders: List<ProviderSetting>,
    onImport: suspend (List<ProviderSetting>) -> Unit,
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var fileProviders by remember { mutableStateOf<List<ProviderSetting>?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    val pickFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            fileBusy = true
            try {
                fileProviders = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(::readProviderImport)
                        ?: error("Cannot open import file")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Parser and resolver exception messages may contain credentials from the file.
                toaster.show(context.getString(R.string.provider_file_import_error), type = ToastType.Error)
            } finally {
                fileBusy = false
            }
        }
    }

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
        imageVector = Lucide.Share,
        contentDescription = stringResource(R.string.setting_provider_page_import_dialog_title),
        rotate180 = true,
        onClick = { if (!fileBusy) showImportDialog = true },
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
            onPickFile = {
                showImportDialog = false
                pickFileLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
            },
        )
    }
    fileProviders?.let { providers ->
        ProviderFileImportPreview(
            providers = providers,
            existingProviders = existingProviders,
            saving = fileBusy,
            onDismiss = { if (!fileBusy) fileProviders = null },
            onConfirm = { selected ->
                scope.launch {
                    fileBusy = true
                    try {
                        onImport(selected)
                        fileProviders = null
                        toaster.show(context.getString(R.string.setting_provider_page_import_success), type = ToastType.Success)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        toaster.show(context.getString(R.string.provider_file_import_save_error), type = ToastType.Error)
                    } finally {
                        fileBusy = false
                    }
                }
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
private fun ProviderImportDialog(
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = t.raised,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_import_dialog_title),
                style = type.screenTitle,
                color = t.ink,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.setting_provider_page_import_dialog_message),
                style = type.secondary,
                color = t.ink3,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_scan_qr_code),
                imageVector = Lucide.Camera,
                onClick = onScanQr,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ProviderCommandButton(
                text = stringResource(R.string.setting_provider_page_select_from_gallery),
                imageVector = Lucide.Image,
                onClick = onPickImage,
                modifier = Modifier.fillMaxWidth(),
            )
            ProviderCommandButton(
                text = stringResource(R.string.provider_file_import_select),
                onClick = onPickFile,
                modifier = Modifier.fillMaxWidth(),
            )
            ProviderGhostButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                accent = false,
                modifier = Modifier.align(Alignment.End),
            )
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
        containerColor = t.raised,
        dragHandle = { ProviderSheetGrabber() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text("//", style = type.eyebrow, color = t.accent)
                Text(
                    stringResource(R.string.setting_provider_page_new),
                    style = type.eyebrow,
                    color = t.ink3,
                )
            }
            Text(
                text = title,
                style = type.screenTitle,
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
                ProviderSplitBar(
                    cancelText = stringResource(R.string.cancel),
                    onCancel = onDismiss,
                    confirmText = confirmText,
                    onConfirm = { onConfirm(currentProvider) },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun AddButton(
    existingProviderIds: Set<Uuid>,
    onAdd: (ProviderSetting) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var autoStartOAuth by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }

    ProviderGhostButton(
        text = stringResource(R.string.setting_provider_page_add),
        imageVector = Lucide.Plus,
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
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderMonogram(
            text = provider.name.toProviderMonogram(),
            size = 40.dp,
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
                    ProviderLiveDot(size = 7.dp)
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
            ProviderRegistryAuthBadge(provider.providerAuthLabel())
            Text(
                text = stringResource(
                    R.string.setting_provider_page_model_count,
                    provider.models.size,
                ),
                style = type.meta.copy(fontSize = 11.sp),
                color = if (provider.models.isNotEmpty()) t.ink2 else t.ink4,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProviderRegistryAuthBadge(text: String) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(t.surface2)
            .border(1.dp, t.line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.meta.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
            color = if (text == "—") t.ink4 else t.ink2,
            maxLines = 1,
        )
    }
}
