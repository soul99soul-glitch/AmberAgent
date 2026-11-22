package app.amber.feature.ui.pages.setting

import android.net.Uri
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Plug
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Share
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import app.amber.ai.provider.GoogleAuthMode
import app.amber.ai.provider.OpenAIAuthMode
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.hasUsableAuth
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.decodeProviderSetting
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderEmptyState
import app.amber.feature.ui.pages.setting.components.ProviderGroupSegment
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderListRow
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderRowDivider
import app.amber.feature.ui.pages.setting.components.ProviderRowSubtitle
import app.amber.feature.ui.pages.setting.components.ProviderSearchField
import app.amber.feature.ui.pages.setting.components.ProviderSectionLabel
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSheetHeader
import app.amber.feature.ui.pages.setting.components.ProviderSplitBar
import app.amber.feature.ui.pages.setting.components.ProviderStatusChip
import app.amber.feature.ui.pages.setting.components.ProviderTemplatePickerSheet
import app.amber.feature.ui.pages.setting.components.ProviderTone
import app.amber.feature.ui.pages.setting.components.buildCodexOAuthInitial
import app.amber.feature.ui.pages.setting.components.providerHostLabel
import app.amber.feature.ui.pages.setting.components.providerSlugLabel
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.core.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.amber.ai.provider.providers.google.AntigravityAuthStore
import app.amber.ai.provider.providers.google.GoogleGeminiAuthStore
import app.amber.ai.provider.providers.grok.GrokAuthStatus
import app.amber.ai.provider.providers.grok.GrokAuthStore
import app.amber.ai.provider.providers.openai.OpenAIChatGptSiwcAuthStore
import app.amber.ai.provider.providers.openai.OpenAICodexAuthStore
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 14dp row inset + 40dp monogram + 12dp gap. */
private val ProviderRowDividerInset = 66.dp

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddPicker by remember { mutableStateOf(false) }
    var startCodexOAuth by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    val lazyListState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val filteredProviders = remember(settings.providers, searchQuery) {
        val query = searchQuery.trim()
        if (searchQuery.isBlank()) {
            settings.providers
        } else {
            settings.providers.filter { provider ->
                provider.name.contains(query, ignoreCase = true) ||
                    provider.providerSlugLabel().contains(query, ignoreCase = true) ||
                    provider.providerHostLabel().contains(query, ignoreCase = true)
            }
        }
    }
    val enabledProviders = remember(filteredProviders) { filteredProviders.filter { it.enabled } }
    val disabledProviders = remember(filteredProviders) { filteredProviders.filterNot { it.enabled } }
    val titleCollapsed by remember { derivedStateOf { lazyListState.firstVisibleItemIndex > 0 } }
    val fabExpanded by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
        }
    }
    val exitSearch = {
        searchQuery = ""
        searching = false
    }
    val openProvider: (ProviderSetting) -> Unit = { provider ->
        navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
    }

    BackHandler(enabled = searching, onBack = exitSearch)
    LaunchedEffect(searching) {
        if (searching) {
            lazyListState.scrollToItem(0)
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }

    Scaffold(
        modifier = Modifier.amberCanvas(),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(52.dp)
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton()
                Text(
                    text = if (titleCollapsed && !searching) stringResource(R.string.setting_page_providers) else "",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    style = type.body.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
                    color = t.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProviderIconButton(
                    imageVector = if (searching) Lucide.X else Lucide.Search,
                    contentDescription = stringResource(
                        if (searching) R.string.provider_filter_clear else R.string.provider_search_hint,
                    ),
                    onClick = {
                        if (searching) {
                            exitSearch()
                        } else {
                            searching = true
                        }
                    },
                )
                ImportProviderButton(
                    showImportDialog = showImportDialog,
                    onShowImportDialogChange = { showImportDialog = it },
                    existingProviders = settings.providers,
                    onImport = vm::importProviders,
                ) {
                    vm.updateSettings { current ->
                        current.copy(providers = listOf(it.freshImportIds()) + current.providers)
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.provider_add_provider_fab), color = t.accentInk, style = LocalAmberType.current.body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)) },
                icon = { Icon(Lucide.Plus, contentDescription = null) },
                onClick = { showAddPicker = true },
                expanded = fabExpanded,
                containerColor = t.accent,
                contentColor = t.accentInk,
                shape = RoundedCornerShape(18.dp),
            )
        },
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            state = lazyListState,
        ) {
            item("header") {
                if (searching) {
                    ProviderSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = stringResource(R.string.provider_search_hint),
                        modifier = Modifier
                            .padding(top = 4.dp, bottom = 4.dp)
                            .focusRequester(focusRequester),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.setting_page_providers),
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                        style = type.body.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
                        color = t.ink,
                    )
                }
            }
            when {
                searching && searchQuery.isNotBlank() -> {
                    if (filteredProviders.isEmpty()) {
                        item("no_match") {
                            ProviderCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 18.dp),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.provider_search_no_match),
                                        style = type.body,
                                        color = t.ink3,
                                    )
                                    ProviderCommandButton(
                                        text = stringResource(R.string.provider_search_clear),
                                        onClick = { searchQuery = "" },
                                        plain = true,
                                    )
                                }
                            }
                        }
                    } else {
                        providerGroup(
                            key = "results",
                            label = { stringResource(R.string.provider_section_results) },
                            providers = filteredProviders,
                            onClick = openProvider,
                        )
                    }
                }

                settings.providers.isEmpty() -> {
                    item("empty") {
                        ProviderEmptyCard(
                            onCodexOAuth = { startCodexOAuth = true },
                            onImport = { showImportDialog = true },
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }
                }

                else -> {
                    providerGroup(
                        key = "enabled",
                        label = { stringResource(R.string.setting_provider_page_enabled) },
                        providers = enabledProviders,
                        onClick = openProvider,
                    )
                    providerGroup(
                        key = "disabled",
                        label = { stringResource(R.string.setting_provider_page_disabled) },
                        providers = disabledProviders,
                        onClick = openProvider,
                    )
                }
            }
        }
    }

    AddProviderFlow(
        showPicker = showAddPicker,
        onShowPickerChange = { showAddPicker = it },
        startCodexOAuth = startCodexOAuth,
        onStartCodexOAuthConsumed = { startCodexOAuth = false },
        existingProviderIds = remember(settings.providers) {
            settings.providers.map { it.id }.toSet()
        },
    ) {
        vm.updateSettings { current ->
            current.copy(providers = listOf(it) + current.providers)
        }
    }
}

private fun LazyListScope.providerGroup(
    key: String,
    label: @Composable () -> String,
    providers: List<ProviderSetting>,
    onClick: (ProviderSetting) -> Unit,
) {
    if (providers.isEmpty()) return
    item("${key}_label") {
        ProviderSectionLabel(label(), count = providers.size)
    }
    itemsIndexed(
        items = providers,
        key = { _, provider -> "${key}_provider_${provider.id}" },
    ) { index, provider ->
        ProviderGroupSegment(
            first = index == 0,
            last = index == providers.lastIndex,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (index > 0) ProviderRowDivider(inset = ProviderRowDividerInset)
            ProviderItem(
                provider = provider,
                onEdit = { onClick(provider) },
            )
        }
    }
}

@Composable
private fun ProviderEmptyCard(
    onCodexOAuth: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalAmberTokens.current
    val chevron: @Composable () -> Unit = {
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            tint = t.ink4,
            modifier = Modifier.size(16.dp),
        )
    }
    ProviderCard(modifier = modifier.fillMaxWidth()) {
        ProviderEmptyState(
            icon = Lucide.Plug,
            title = stringResource(R.string.provider_empty_title),
            body = stringResource(R.string.provider_empty_body),
        )
        ProviderRowDivider()
        ProviderListRow(
            title = stringResource(R.string.setting_provider_page_template_oauth_codex_title),
            subtitle = { ProviderRowSubtitle(stringResource(R.string.setting_provider_page_template_oauth_codex_subtitle)) },
            trailing = { chevron() },
            onClick = onCodexOAuth,
        )
        ProviderRowDivider()
        ProviderListRow(
            title = stringResource(R.string.provider_empty_import),
            subtitle = { ProviderRowSubtitle(stringResource(R.string.provider_empty_import_sub), mono = true) },
            trailing = { chevron() },
            onClick = onImport,
        )
    }
}

/** List-row status: one chip vocabulary, cheap (no token-store reads). */
internal fun ProviderSetting.statusChip(oauthSignedIn: Boolean? = null): Pair<Int, ProviderTone> {
    if (!enabled) return R.string.setting_provider_page_disabled to ProviderTone.Neutral
    if (isOAuthMode()) {
        return when (oauthSignedIn) {
            false -> R.string.provider_status_signed_out to ProviderTone.Warn
            else -> R.string.provider_status_signed_in to ProviderTone.Ok
        }
    }
    return when {
        hasUsableAuth() -> R.string.provider_status_configured to ProviderTone.Ok
        else -> R.string.provider_status_missing_key to ProviderTone.Neutral
    }
}

internal fun ProviderSetting.isOAuthMode(): Boolean = when (this) {
    is ProviderSetting.OpenAI ->
        authMode == OpenAIAuthMode.CODEX_OAUTH ||
            authMode == OpenAIAuthMode.CHATGPT_SIWC ||
            authMode == OpenAIAuthMode.GROK_OAUTH
    is ProviderSetting.Google ->
        authMode == GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH || authMode == GoogleAuthMode.ANTIGRAVITY_OAUTH
    is ProviderSetting.Claude -> false
}

/** Whether the OAuth token store holds a usable session; null for non-OAuth providers or while loading. */
@Composable
internal fun rememberOAuthSignedIn(provider: ProviderSetting): Boolean? {
    if (!provider.isOAuthMode()) return null
    val codex = koinInject<OpenAICodexAuthStore>()
    val siwc = koinInject<OpenAIChatGptSiwcAuthStore>()
    val grok = koinInject<GrokAuthStore>()
    val gemini = koinInject<GoogleGeminiAuthStore>()
    val antigravity = koinInject<AntigravityAuthStore>()
    fun read(): Boolean = when (provider) {
        is ProviderSetting.OpenAI -> when (provider.authMode) {
            OpenAIAuthMode.CODEX_OAUTH -> codex.get(provider.id) != null
            OpenAIAuthMode.CHATGPT_SIWC -> siwc.get(provider.id)?.isUsable() == true
            OpenAIAuthMode.GROK_OAUTH -> GrokAuthStatus.from(grok.get(provider.id), System.currentTimeMillis()).usable
            else -> true
        }
        is ProviderSetting.Google -> when (provider.authMode) {
            GoogleAuthMode.GEMINI_CODE_ASSIST_OAUTH -> gemini.get(provider.id) != null
            GoogleAuthMode.ANTIGRAVITY_OAUTH -> antigravity.get(provider.id) != null
            else -> true
        }
        is ProviderSetting.Claude -> true
    }
    // Secure-store reads decrypt on disk; keep them off the main thread (null = not loaded yet).
    var signedIn by remember(provider.id, provider.authMode()) { mutableStateOf<Boolean?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(provider.id, provider.authMode(), lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            signedIn = withContext(Dispatchers.IO) { read() }
        }
    }
    return signedIn
}

private fun ProviderSetting.authMode(): Any? = when (this) {
    is ProviderSetting.OpenAI -> authMode
    is ProviderSetting.Google -> authMode
    is ProviderSetting.Claude -> null
}

@Composable
private fun ImportProviderButton(
    showImportDialog: Boolean,
    onShowImportDialogChange: (Boolean) -> Unit,
    existingProviders: List<ProviderSetting>,
    onImport: suspend (List<ProviderSetting>) -> Unit,
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
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
        onClick = { if (!fileBusy) onShowImportDialogChange(true) },
    )

    if (showImportDialog) {
        ProviderImportDialog(
            onDismiss = { onShowImportDialogChange(false) },
            onScanQr = {
                onShowImportDialogChange(false)
                scanQrCodeLauncher.launch(null)
            },
            onPickImage = {
                onShowImportDialogChange(false)
                pickImageLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onPickFile = {
                onShowImportDialogChange(false)
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
                .padding(bottom = 16.dp),
        ) {
            ProviderSheetHeader(stringResource(R.string.setting_provider_page_import_dialog_title))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_message),
                    style = type.secondary,
                    color = t.ink3,
                    modifier = Modifier.padding(bottom = 6.dp),
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
                ProviderCommandButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    plain = true,
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
                .fillMaxHeight(0.92f),
        ) {
            ProviderSheetHeader(title)
            ProviderConsole(
                provider = draftProvider,
                onEdit = { draftProvider = it },
                onCommit = { draftProvider = it },
                autoStartOAuth = autoStartOAuth,
                onAutoStartConsumed = onAutoStartConsumed,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
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

/** Template picker -> editor sheet -> [onAdd]; opened by the FAB or the empty-state shortcuts. */
@Composable
private fun AddProviderFlow(
    showPicker: Boolean,
    onShowPickerChange: (Boolean) -> Unit,
    startCodexOAuth: Boolean,
    onStartCodexOAuthConsumed: () -> Unit,
    existingProviderIds: Set<Uuid>,
    onAdd: (ProviderSetting) -> Unit,
) {
    var autoStartOAuth by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }

    LaunchedEffect(startCodexOAuth) {
        if (startCodexOAuth) {
            onStartCodexOAuthConsumed()
            autoStartOAuth = true
            editingProvider = buildCodexOAuthInitial()
        }
    }

    if (showPicker) {
        ProviderTemplatePickerSheet(
            existingProviderIds = existingProviderIds,
            onDismiss = { onShowPickerChange(false) },
            onPick = { initial, requestAutoStart ->
                onShowPickerChange(false)
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
    val (statusRes, tone) = provider.statusChip(rememberOAuthSignedIn(provider))
    val modelCount = stringResource(R.string.setting_provider_page_model_count, provider.models.size)
    val host = provider.providerHostLabel()
    ProviderListRow(
        title = provider.name,
        modifier = modifier,
        minHeight = 64.dp,
        dimmed = !provider.enabled,
        leading = {
            ProviderMonogram(
                text = provider.name.toProviderMonogram(),
                size = 40.dp,
                enabled = provider.enabled,
            )
        },
        subtitle = {
            Text(
                text = buildAnnotatedString {
                    append(modelCount)
                    append(" · ")
                    withStyle(SpanStyle(fontFamily = type.meta.fontFamily, fontSize = 11.5.sp)) {
                        append(host)
                    }
                },
                style = type.secondary.copy(fontSize = 12.5.sp),
                color = t.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = { ProviderStatusChip(text = stringResource(statusRes), tone = tone) },
        onClick = onEdit,
    )
}
