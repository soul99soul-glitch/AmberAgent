package me.rerere.rikkahub.ui.pages.setting

import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MoreVertical
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.PulseDialogButton
import me.rerere.rikkahub.ui.components.ui.PulseDialogVariant
import me.rerere.rikkahub.ui.components.ui.PulsePrimaryButton
import me.rerere.rikkahub.ui.components.ui.PulseSecondaryButton
import me.rerere.rikkahub.ui.components.ui.PulseGhostButton
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.ImageUtils
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

    // Stat counts power the hero card. Configured = providers with at
    // least one model wired up; Models = sum across all providers. Both
    // recompute cheaply from settings.providers.
    val configuredCount = remember(settings.providers) {
        settings.providers.count { it.enabled && it.models.isNotEmpty() }
    }
    val modelCount = remember(settings.providers) {
        settings.providers.sumOf { it.models.size }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.setting_provider_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    ImportProviderButton {
                        vm.updateSettings(
                            settings.copy(
                                providers = listOf(it.copyProvider(Uuid.random())) + settings.providers
                            )
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                state = lazyListState,
            ) {
                // Hero stat card row — 2-up tiles. Configured count goes
                // chartreuse-filled (the active brand accent) so it reads
                // as the primary metric; Models is plain tan as the
                // contextual aggregate.
                item {
                    StatHeroRow(
                        configuredCount = configuredCount,
                        modelCount = modelCount,
                    )
                }

                // Search bar — pill-shaped to match Pulse vocabulary.
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        placeholder = { Text(stringResource(R.string.setting_provider_page_search_providers)) },
                        leadingIcon = {
                            Icon(HugeIcons.Search01, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                    )
                }

                // ALL-CAPS section eyebrow above the list.
                item {
                    Text(
                        text = stringResource(R.string.setting_provider_page_section_label).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.08.em,
                        ),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                    )
                }

                lazyItems(filteredProviders, key = { it.id }) { provider ->
                    ProviderItem(
                        modifier = Modifier.fillMaxWidth(),
                        provider = provider,
                        onEdit = {
                            navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                        },
                        onCopy = {
                            val copy = provider.copyProvider(
                                id = Uuid.random(),
                                name = "${provider.name} Copy",
                                builtIn = false,
                            )
                            val providerIndex = settings.providers.indexOfFirst { it.id == provider.id }
                            val newProviders = settings.providers.toMutableList().apply {
                                add(if (providerIndex == -1) 0 else providerIndex + 1, copy)
                            }
                            vm.updateSettings(settings.copy(providers = newProviders))
                        },
                        onDelete = {
                            pendingDeleteProvider = provider
                        },
                    )
                }

                // Sport-orange "+ Add Provider" CTA at the bottom of the
                // list — matches the mockup pattern of putting the
                // primary list-mutating action below the rows it acts on.
                item {
                    AddProviderCTA(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        onAdd = {
                            vm.updateSettings(
                                settings.copy(
                                    providers = listOf(it) + settings.providers
                                )
                            )
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
                PulseDialogButton(
                    onClick = { pendingDeleteProvider = null },
                    text = stringResource(R.string.cancel),
                    variant = PulseDialogVariant.Ghost,
                )
            },
            confirmButton = {
                PulseDialogButton(
                    onClick = {
                        pendingDeleteProvider = null
                        vm.updateSettings(settings.copy(providers = settings.providers - provider))
                    },
                    text = stringResource(R.string.delete),
                    variant = PulseDialogVariant.Secondary,
                )
            },
        )
    }
}

/**
 * Hero stat row at the top of the providers list. Two square-ish tiles
 * showing CONFIGURED count (chartreuse-filled, the brand "active"
 * accent) and MODELS count (plain tan, the contextual aggregate).
 * Reads at a glance — matches the Pulse mockup's two-up KPI pattern.
 */
@Composable
private fun StatHeroRow(
    configuredCount: Int,
    modelCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.setting_provider_page_stat_configured),
            value = configuredCount.toString().padStart(2, '0'),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            border = null,
        )
        StatTile(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.setting_provider_page_stat_models),
            value = modelCount.toString().padStart(2, '0'),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    border: BorderStroke?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = container,
        contentColor = content,
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.em,
                ),
                color = content.copy(alpha = 0.78f),
            )
            Text(
                text = value,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                color = content,
            )
        }
    }
}

/**
 * Sport-orange CTA card at the bottom of the provider list. Renders as
 * a pill-bordered tan card with the "+ Add Provider" PulseSecondaryButton
 * inside, so the action reads as deliberate (a row in the list) rather
 * than a free-floating button. Tapping opens the provider-add dialog
 * with the same flow as the previous TopBar action.
 */
@Composable
private fun AddProviderCTA(
    onAdd: (ProviderSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dialogState = useEditState<ProviderSetting> { onAdd(it) }

    PulseSecondaryButton(
        onClick = { dialogState.open(ProviderSetting.OpenAI()) },
        text = stringResource(R.string.setting_provider_page_add_provider),
        leadingIcon = HugeIcons.Add01,
        modifier = modifier,
    )

    if (dialogState.isEditing) {
        AlertDialog(
            onDismissRequest = { dialogState.dismiss() },
            title = { Text(stringResource(R.string.setting_provider_page_add_provider)) },
            text = {
                dialogState.currentState?.let {
                    ProviderConfigure(it) { newState -> dialogState.currentState = newState }
                }
            },
            confirmButton = {
                PulseDialogButton(
                    onClick = { dialogState.confirm() },
                    text = stringResource(R.string.setting_provider_page_add),
                    variant = PulseDialogVariant.Primary,
                )
            },
            dismissButton = {
                PulseDialogButton(
                    onClick = { dialogState.dismiss() },
                    text = stringResource(R.string.cancel),
                    variant = PulseDialogVariant.Ghost,
                )
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
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Scan QR (primary action — chartreuse Pulse pill)
                        PulsePrimaryButton(
                            onClick = {
                                showImportDialog = false
                                scanQrCodeLauncher.launch(null)
                            },
                            text = stringResource(R.string.setting_provider_page_scan_qr_code),
                            leadingIcon = HugeIcons.Camera01,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Pick from gallery (secondary action — ghost outline)
                        PulseGhostButton(
                            onClick = {
                                showImportDialog = false
                                pickImageLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            text = stringResource(R.string.setting_provider_page_select_from_gallery),
                            leadingIcon = HugeIcons.Image02,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                PulseDialogButton(
                    onClick = { showImportDialog = false },
                    text = stringResource(R.string.cancel),
                    variant = PulseDialogVariant.Ghost,
                )
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

/**
 * Provider row card. Tan/cream surface with:
 *   - Provider icon (AutoAIIcon) on the left
 *   - Name + short description stacked
 *   - Right side: chartreuse pulse-dot for enabled (sport-orange dot for
 *     disabled), model count in mono labelSmall, then the more-actions
 *     menu trigger
 *
 * Disabled state: card uses a subdued container with reduced content
 * alpha, and the leading dot flips to sport-orange to read as a warning
 * without the M3 errorContainer's heavy fill that the previous
 * implementation used (which made disabled providers look like errors).
 */
@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val enabled = provider.enabled
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(20.dp),
        onClick = onEdit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = provider.name,
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Pulse dot — chartreuse when enabled, sport-orange
                    // when disabled. The dot reads at-a-glance and frees
                    // up horizontal space the old Tag stack consumed.
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondary
                            ),
                    )
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.setting_provider_page_model_count,
                        provider.models.size,
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.05.em,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = HugeIcons.MoreVertical,
                        contentDescription = stringResource(R.string.more_options),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(HugeIcons.Edit01, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        leadingIcon = { Icon(HugeIcons.Copy01, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onCopy()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                HugeIcons.Delete01,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
