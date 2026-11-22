package app.amber.feature.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.List
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Share
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.amber.ai.provider.ProviderSetting
import app.amber.agent.R
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.ShareSheet
import app.amber.feature.ui.components.ui.rememberShareSheetState
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderMonogram
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderSegmented
import app.amber.feature.ui.pages.setting.components.ProviderStatusChip
import app.amber.feature.ui.pages.setting.components.providerDangerColor
import app.amber.feature.ui.pages.setting.components.toProviderMonogram
import app.amber.feature.ui.pages.setting.provider.ProviderDeleteDialog
import app.amber.feature.ui.pages.setting.provider.isUsingDefaultBaseUrl
import app.amber.feature.ui.pages.setting.provider.resetBaseUrlToDefault
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingProviderDetailPage(id: Uuid, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val shareSheetState = rememberShareSheetState()
    var fetchedCandidates by remember(provider.id) { mutableStateOf<ProviderModelCandidates?>(null) }
    var modelListRefreshKey by remember(provider.id) { mutableIntStateOf(0) }
    fun invalidateModelCandidates() {
        fetchedCandidates = null
        modelListRefreshKey += 1
    }
    // Config-tab draft, hoisted here so switching pager tabs (which disposes the
    // config page) keeps unsaved key/baseUrl/name edits. Keyed on provider.id, not
    // the provider instance, so models-tab writes don't reset the draft.
    var internalProvider by remember(provider.id) { mutableStateOf(provider) }
    // The config tab never edits models, but the OAuth consoles commit through it.
    // Pin the draft's models to the store so an OAuth commit can neither revert
    // newer models-tab edits nor have its own model writes merged away.
    LaunchedEffect(provider) {
        internalProvider = internalProvider.copyProvider(models = provider.models)
    }
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current

    val onEdit: (ProviderSetting) -> Unit = { newProvider ->
        vm.updateSettings { currentSettings ->
            currentSettings.copy(
                providers = currentSettings.providers.map {
                    if (newProvider.id == it.id) newProvider else it
                }
            )
        }
    }
    val onDelete = {
        vm.updateSettings { currentSettings ->
            currentSettings.copy(
                providers = currentSettings.providers.filterNot { it.id == provider.id }
            )
        }
        navController.popBackStack()
    }

    // Auto-commit the config draft when leaving the Configuration tab — the
    // Models tab and the chat path both read the persisted provider, so an
    // API key that was typed but never saved would fetch/chat with an empty
    // credential. Dirty check keeps the write a no-op when nothing changed;
    // deleting the provider makes the map a no-op for the stale draft id.
    val latestDraftProvider by rememberUpdatedState(internalProvider)
    val latestSavedProvider by rememberUpdatedState(provider)
    val persistDirtyDraft = {
        val draft = latestDraftProvider
        if (draft != latestSavedProvider) {
            onEdit(draft)
        }
    }
    LaunchedEffect(pager.targetPage) {
        if (pager.targetPage != 0) persistDirtyDraft()
    }
    DisposableEffect(Unit) {
        onDispose { persistDirtyDraft() }
    }
    // With no Save button, backgrounding must persist too (process death would drop the draft).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { persistDirtyDraft() }

    ShareSheet(shareSheetState)

    // Identity header collapses into the top bar's small title once the config list scrolls.
    // The models tab does not report scroll, so switching tabs restores the expanded header.
    var headerCollapsed by remember(provider.id) { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { headerCollapsed = false }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.amberCanvas(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackButton()
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        // Plain alpha fade: AnimatedVisibility here would resolve to the outer
                        // ColumnScope overload across the layout-scope DSL marker.
                        val titleAlpha by animateFloatAsState(if (headerCollapsed) 1f else 0f, label = "detailTitle")
                        Text(
                            text = provider.name,
                            modifier = Modifier.graphicsLayer { alpha = titleAlpha },
                            style = type.body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
                            color = t.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ProviderIconButton(
                        imageVector = Lucide.Share,
                        contentDescription = stringResource(R.string.export_title),
                        onClick = { shareSheetState.show(provider) },
                    )
                    Box {
                        ProviderIconButton(
                            imageVector = Lucide.EllipsisVertical,
                            contentDescription = stringResource(R.string.more_options),
                            onClick = { showMenu = true },
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.setting_model_page_reset_to_default)) },
                                enabled = !internalProvider.isUsingDefaultBaseUrl(),
                                onClick = {
                                    showMenu = false
                                    internalProvider = internalProvider.resetBaseUrlToDefault()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.provider_delete_provider),
                                        color = providerDangerColor(),
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = !headerCollapsed,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        ProviderMonogram(
                            text = provider.name.toProviderMonogram(),
                            size = 52.dp,
                            enabled = provider.enabled,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = provider.name,
                                style = type.body.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
                                color = t.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val (statusLabel, statusTone) = provider.statusChip(rememberOAuthSignedIn(provider))
                            ProviderStatusChip(text = stringResource(statusLabel), tone = statusTone)
                        }
                    }
                }
                ProviderSegmented(
                    options = listOf(
                        ProviderSegOption(
                            value = 0,
                            label = stringResource(R.string.setting_provider_page_configuration),
                            icon = Lucide.Settings,
                        ),
                        ProviderSegOption(
                            value = 1,
                            label = stringResource(R.string.setting_provider_page_models),
                            icon = Lucide.List,
                            badge = provider.models.size.toString(),
                        ),
                    ),
                    selected = pager.currentPage,
                    onSelected = { page -> scope.launch { pager.animateScrollToPage(page) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
        },
        containerColor = Color.Transparent,
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .padding(it)
                .consumeWindowInsets(it)
        ) { page ->
            when (page) {
                0 -> {
                    SettingProviderConfigPage(
                        internalProvider = internalProvider,
                        onInternalChange = { internalProvider = it },
                        onEdit = {
                            onEdit(it)
                            toaster.show(
                                context.getString(R.string.setting_provider_page_save_success),
                                type = ToastType.Success
                            )
                        },
                        onModelsFetched = { fetchedCandidates = it },
                        onModelCandidatesInvalidated = ::invalidateModelCandidates,
                        onDelete = {
                            onDelete()
                        },
                        onScrolledChange = { scrolled ->
                            if (pager.currentPage == 0) headerCollapsed = scrolled
                        },
                    )
                }

                1 -> {
                    SettingProviderModelPage(
                        provider = provider,
                        onEdit = onEdit,
                        fetchedCandidates = fetchedCandidates,
                        modelListRefreshKey = modelListRefreshKey,
                        onRefetch = ::invalidateModelCandidates,
                        currentModelId = settings.chatModelId,
                        // Undo re-inserts into the stored provider atomically, so it cannot
                        // roll back config edits committed after the delete.
                        onRestoreModel = { model, index ->
                            vm.updateSettings { currentSettings ->
                                currentSettings.copy(
                                    providers = currentSettings.providers.map { stored ->
                                        if (stored.id != provider.id || stored.models.any { it.id == model.id }) {
                                            stored
                                        } else {
                                            stored.copyProvider(
                                                models = stored.models.toMutableList().apply {
                                                    add(index.coerceIn(0, size), model)
                                                },
                                            )
                                        }
                                    }
                                )
                            }
                        },
                        onSetCurrent = { model ->
                            vm.updateSettings { currentSettings ->
                                currentSettings.copy(chatModelId = model.id)
                            }
                        },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        ProviderDeleteDialog(
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                onDelete()
            },
        )
    }
}
