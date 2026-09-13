package app.amber.feature.ui.pages.setting

import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import app.amber.feature.ui.components.ui.Switch
import app.amber.feature.ui.components.ui.SwitchSize
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.amber.ai.core.InputSchema
import app.amber.agent.R
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.runtime.CapabilityBackedCasLedger
import app.amber.core.ai.mcp.McpImportApplyResult
import app.amber.core.ai.mcp.McpImportPreparation
import app.amber.core.ai.mcp.McpImportPreview
import app.amber.core.ai.mcp.McpImportTransaction
import app.amber.core.ai.mcp.McpImportTransport
import app.amber.core.ai.mcp.McpManager
import app.amber.core.ai.mcp.McpServerConfig
import app.amber.core.ai.mcp.McpStatus
import app.amber.core.ai.mcp.McpTool
import app.amber.core.ai.mcp.RealMcpConnectPreflight
import app.amber.feature.runtime.CapabilityPermissionStore
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.Tag
import app.amber.feature.ui.components.ui.TagType
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.pages.setting.components.ProviderCard
import app.amber.feature.ui.pages.setting.components.ProviderCommandButton
import app.amber.feature.ui.pages.setting.components.ProviderGhostButton
import app.amber.feature.ui.pages.setting.components.ProviderIconButton
import app.amber.feature.ui.pages.setting.components.ProviderLabeledField
import app.amber.feature.ui.pages.setting.components.ProviderPillSeg
import app.amber.feature.ui.pages.setting.components.ProviderSecretField
import app.amber.feature.ui.pages.setting.components.ProviderSegOption
import app.amber.feature.ui.pages.setting.components.ProviderSheetGrabber
import app.amber.feature.ui.pages.setting.components.ProviderSmallIconButton
import app.amber.feature.ui.pages.setting.components.ProviderTextField
import app.amber.feature.ui.hooks.EditState
import app.amber.feature.ui.hooks.EditStateContent
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.theme.CustomColors
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.extendColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val workspace = workspaceColors()
    val capabilityPermissionStore = koinInject<CapabilityPermissionStore>()
    val transaction = remember {
        McpImportTransaction(
            preflight = RealMcpConnectPreflight(),
            approvalLedger = CapabilityBackedCasLedger(capabilityPermissionStore),
            existingServerNames = {
                vm.settings.value.mcpServers.map { it.commonOptions.name }.toSet()
            },
            publish = { configs ->
                val current = vm.settings.value
                vm.updateSettings(current.copy(mcpServers = current.mcpServers + configs))
            },
        )
    }
    val creationState = useEditState<McpServerConfig> {
        vm.updateSettings(
            settings.copy(
                mcpServers = mcpConfigs + it
            )
        )
    }
    val editState = useEditState<McpServerConfig> { newConfig ->
        vm.updateSettings(
            settings.copy(
                mcpServers = mcpConfigs.map {
                    if (it.id == newConfig.id) {
                        newConfig
                    } else {
                        it
                    }
                }
            ))
    }
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.setting_mcp_page_title),
                navigationIcon = { BackButton() },
                actions = {
                    ProviderGhostButton(
                        text = stringResource(R.string.setting_mcp_page_import_confirm),
                        imageVector = Lucide.Download,
                        modifier = Modifier.padding(end = 6.dp),
                        onClick = { showImportDialog = true },
                    )
                    ProviderGhostButton(
                        text = stringResource(R.string.add),
                        imageVector = Lucide.Plus,
                        onClick = { creationState.open(McpServerConfig.StreamableHTTPServer()) },
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = SettingPageHorizontalInset, vertical = 8.dp)
            ) {
                item("mcp_servers_section") {
                    SettingSectionTitle(stringResource(R.string.setting_mcp_page_title))
                }
                items(mcpConfigs, key = { it.id }) { mcpConfig ->
                    McpServerItem(
                        item = mcpConfig,
                        status = status[mcpConfig.id] ?: McpStatus.Idle,
                        onEdit = {
                            editState.open(mcpConfig)
                        },
                        onDelete = {
                            vm.updateSettings(
                                settings.copy(
                                    mcpServers = mcpConfigs.filter { it.id != mcpConfig.id }
                                )
                            )
                        },
                        onStartAuthorization = {
                            mcpManager.startAuthorization(mcpConfig, context)
                        },
                        onClearAuthorization = {
                            scope.launch {
                                mcpManager.clearAuthorization(mcpConfig)
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = workspace.ink,
                    )
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                        color = workspace.muted,
                    )
                }
            }
        }
    }
    McpServerConfigModal(creationState)
    McpServerConfigModal(editState)
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            transaction = transaction,
        )
    }
}

@Composable
private fun McpServerItem(
    item: McpServerConfig,
    status: McpStatus,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
    onStartAuthorization: () -> Unit,
    onClearAuthorization: () -> Unit,
) {
    val dismissBoxState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val workspace = workspaceColors()
    SwipeToDismissBox(
        state = dismissBoxState,
        backgroundContent = {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ProviderSmallIconButton(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(R.string.cancel),
                    onClick = { scope.launch { dismissBoxState.reset() } },
                )
                ProviderSmallIconButton(
                    imageVector = Lucide.Trash2,
                    contentDescription = stringResource(R.string.delete),
                    onClick = onDelete,
                    tint = workspace.muted,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        modifier = modifier
    ) {
        ProviderCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = workspace.row,
                        contentColor = workspace.muted,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Lucide.Server,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                text = item.commonOptions.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = workspace.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val dotColor =
                                if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .drawWithContent {
                                        drawCircle(
                                            color = dotColor
                                        )
                                    }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            McpInlinePill(text = item.transportLabel())
                            McpInlinePill(
                                text = status.statusLabel(),
                                tone = when (status) {
                                    McpStatus.Connected -> WorkspaceTone.Success
                                    is McpStatus.Error -> WorkspaceTone.Danger
                                    McpStatus.Connecting,
                                    is McpStatus.Reconnecting,
                                    McpStatus.Authorizing,
                                    McpStatus.NeedsAuthorization,
                                    -> WorkspaceTone.Warning
                                    McpStatus.Idle -> WorkspaceTone.Neutral
                                },
                            )
                        }
                    }

                    ProviderIconButton(
                        imageVector = Lucide.Settings,
                        contentDescription = stringResource(R.string.setting_mcp_page_edit_server),
                        onClick = { onEdit(item) },
                        tint = workspace.muted,
                    )
                }
                McpOAuthSection(
                    item = item,
                    status = status,
                    onStartAuthorization = onStartAuthorization,
                    onClearAuthorization = onClearAuthorization,
                )
            }
        }
    }
}

@Composable
private fun McpOAuthSection(
    item: McpServerConfig,
    status: McpStatus,
    onStartAuthorization: () -> Unit,
    onClearAuthorization: () -> Unit,
) {
    val workspace = workspaceColors()
    val oauth = item.commonOptions.oauth
    val showSection = oauth != null || status == McpStatus.Authorizing || status == McpStatus.NeedsAuthorization
    if (!showSection) return
    val expiresAtText = remember(oauth?.expiresAt) {
        val expiresAt = oauth?.expiresAt ?: 0L
        if (expiresAt > 0) {
            Instant.ofEpochMilli(expiresAt)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        } else {
            null
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = workspace.hairline,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            status == McpStatus.Authorizing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.setting_mcp_page_oauth_authorizing),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                )
            }

            oauth != null && oauth.isAuthorized -> {
                Text(
                    text = stringResource(R.string.setting_mcp_page_oauth_authorized),
                    style = MaterialTheme.typography.labelSmall,
                    color = workspace.muted,
                )
                expiresAtText?.let { expires ->
                    Text(
                        text = stringResource(R.string.setting_mcp_page_oauth_expires_at, expires),
                        style = MaterialTheme.typography.labelSmall,
                        color = workspace.muted,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onClearAuthorization,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.setting_mcp_page_oauth_cancel))
                }
            }

            else -> {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onStartAuthorization,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.setting_mcp_page_oauth_login))
                }
            }
        }
    }
}

@Composable
private fun McpInlinePill(
    text: String,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val workspace = workspaceColors()
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        WorkspaceTone.Neutral -> workspace.row to workspace.muted
        WorkspaceTone.Accent -> scheme.primaryContainer to scheme.primary
        WorkspaceTone.Success -> workspace.greenContainer to workspace.green
        WorkspaceTone.Warning -> workspace.amberContainer to workspace.amber
        WorkspaceTone.Danger -> workspace.redContainer to workspace.red
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, content.copy(alpha = 0.18f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun McpServerConfig.transportLabel(): String = when (this) {
    is McpServerConfig.SseTransportServer -> "SSE"
    is McpServerConfig.StreamableHTTPServer -> "Streamable HTTP"
}

@Composable
private fun McpStatus.statusLabel(): String = when (this) {
    McpStatus.Idle -> stringResource(R.string.setting_mcp_status_idle)
    McpStatus.Connecting -> stringResource(R.string.setting_mcp_status_connecting)
    McpStatus.Connected -> stringResource(R.string.setting_mcp_status_connected)
    is McpStatus.Reconnecting -> stringResource(R.string.setting_mcp_status_reconnecting)
    is McpStatus.Error -> stringResource(R.string.setting_mcp_status_error)
    McpStatus.NeedsAuthorization -> stringResource(R.string.setting_mcp_status_needs_authorization)
    McpStatus.Authorizing -> stringResource(R.string.setting_mcp_status_authorizing)
}

@Composable
private fun McpServerConfigModal(state: EditState<McpServerConfig>) {
    state.EditStateContent { config, updateValue ->
        val pagerState = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LocalAmberTokens.current.raised,
            dragHandle = { ProviderSheetGrabber() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_basic_settings))
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_tools))
                        }
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> {
                            McpCommonOptionsConfigure(
                                config = config,
                                update = updateValue
                            )
                        }

                        1 -> {
                            McpToolsConfigure(
                                config = config,
                                update = updateValue,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    ProviderCommandButton(
                        text = stringResource(R.string.setting_mcp_page_save),
                        accent = true,
                        enabled = config.commonOptions.name.isNotBlank(),
                        onClick = { state.confirm() },
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun McpCommonOptionsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit
) {
    val enableLabel = stringResource(R.string.setting_mcp_page_enable)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setting_mcp_page_enable), style = LocalAmberType.current.body)
                Text(
                    stringResource(R.string.setting_mcp_page_enable_desc),
                    style = LocalAmberType.current.secondary,
                    color = LocalAmberTokens.current.ink3,
                )
            }
            Switch(
                checked = config.commonOptions.enable,
                modifier = Modifier.semantics { contentDescription = enableLabel },
                onCheckedChange = { enabled ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(
                                commonOptions = config.commonOptions.copy(enable = enabled)
                            )
                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                commonOptions = config.commonOptions.copy(enable = enabled)
                            )
                        }
                    )
                },
            )
        }

        ProviderLabeledField(stringResource(R.string.setting_mcp_page_name)) {
            ProviderTextField(
                value = config.commonOptions.name,
                onValueChange = { name ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )
                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                commonOptions = config.commonOptions.copy(name = name)
                            )
                        }
                    )
                },
                placeholder = stringResource(R.string.setting_mcp_page_name_placeholder),
            )
        }

        val currentTypeIndex = when (config) {
            is McpServerConfig.StreamableHTTPServer -> 0
            is McpServerConfig.SseTransportServer -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setting_mcp_page_transport_type), style = LocalAmberType.current.body)
            Text(
                stringResource(R.string.setting_mcp_page_transport_type_desc),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
            ProviderPillSeg(
                options = listOf(
                    ProviderSegOption(0, "Streamable HTTP"),
                    ProviderSegOption(1, "SSE"),
                ),
                selected = currentTypeIndex,
                onSelected = { index ->
                    val newConfig = when (index) {
                        0 -> McpServerConfig.StreamableHTTPServer(
                            id = config.id,
                            commonOptions = config.commonOptions,
                            url = when (config) {
                                is McpServerConfig.SseTransportServer -> config.url
                                is McpServerConfig.StreamableHTTPServer -> config.url
                            }
                        )
                        else -> McpServerConfig.SseTransportServer(
                            id = config.id,
                            commonOptions = config.commonOptions,
                            url = when (config) {
                                is McpServerConfig.SseTransportServer -> config.url
                                is McpServerConfig.StreamableHTTPServer -> config.url
                            }
                        )
                    }
                    update(newConfig)
                },
                mono = true,
            )
        }

        ProviderLabeledField(stringResource(R.string.setting_mcp_page_url_label)) {
            ProviderTextField(
                value = when (config) {
                    is McpServerConfig.SseTransportServer -> config.url
                    is McpServerConfig.StreamableHTTPServer -> config.url
                },
                onValueChange = { url ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(url = url)
                            is McpServerConfig.StreamableHTTPServer -> config.copy(url = url)
                        }
                    )
                },
                placeholder = when (config) {
                    is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_placeholder)
                    is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_placeholder)
                },
                mono = true,
            )
        }
        Text(
            when (config) {
                is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_desc)
                is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_desc)
            },
            style = LocalAmberType.current.secondary,
            color = LocalAmberTokens.current.ink3,
        )

        // 请求头配置
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.setting_mcp_page_custom_headers), style = LocalAmberType.current.body)
            Text(
                stringResource(R.string.setting_mcp_page_custom_headers_desc),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                config.commonOptions.headers.forEachIndexed { index, header ->
                    var headerName by remember(header.first) { mutableStateOf(header.first) }
                    var headerValue by remember(header.second) { mutableStateOf(header.second) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            ProviderLabeledField(stringResource(R.string.setting_mcp_page_header_name)) {
                                ProviderTextField(
                                value = headerName,
                                onValueChange = {
                                    headerName = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] =
                                        it.trim() to updatedHeaders[index].second
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                placeholder = stringResource(R.string.setting_mcp_page_header_name_placeholder),
                                mono = true,
                                )
                            }
                            ProviderLabeledField(stringResource(R.string.setting_mcp_page_header_value)) {
                                if (headerName.equals("authorization", ignoreCase = true)) {
                                    ProviderSecretField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].first to it
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                        }
                                    )
                                },
                                        placeholder = stringResource(R.string.setting_mcp_page_header_value_placeholder),
                                    )
                                } else {
                                    ProviderTextField(
                                        value = headerValue,
                                        onValueChange = {
                                            headerValue = it
                                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                                            updatedHeaders[index] = updatedHeaders[index].first to it
                                            update(
                                                when (config) {
                                                    is McpServerConfig.SseTransportServer -> config.copy(
                                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                                    )
                                                    is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                                    )
                                                }
                                            )
                                        },
                                        placeholder = stringResource(R.string.setting_mcp_page_header_value_placeholder),
                                        mono = true,
                                    )
                                }
                            }
                        }
                        ProviderSmallIconButton(
                            imageVector = Lucide.Trash2,
                            contentDescription = stringResource(R.string.setting_mcp_page_delete_header),
                            onClick = {
                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            update(
                                when (config) {
                                    is McpServerConfig.SseTransportServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )

                                    is McpServerConfig.StreamableHTTPServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )
                                }
                            )
                            },
                        )
                    }
                }

                ProviderGhostButton(
                    text = stringResource(R.string.setting_mcp_page_add_header),
                    imageVector = Lucide.Plus,
                    onClick = {
                        val updatedHeaders = config.commonOptions.headers.toMutableList()
                        updatedHeaders.add("" to "")
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )

                                is McpServerConfig.StreamableHTTPServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )
                            }
                        )
                    },
                    modifier = Modifier.align(Alignment.Start),
                )
            }
        }
    }
}

@Composable
private fun McpToolsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mcpManager.getClient(config) == null) {
            item {
                Text(stringResource(R.string.setting_mcp_page_tools_unavailable_message))
            }
        }
        items(config.commonOptions.tools) { tool ->
            McpToolCard(
                tool = tool,
                onEnableChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(enable = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                },
                onNeedsApprovalChange = { newVal ->
                    update(
                        config.clone(
                            commonOptions = config.commonOptions.copy(
                                tools = config.commonOptions.tools.map {
                                    if (tool.name == it.name) {
                                        it.copy(needsApproval = newVal)
                                    } else {
                                        it
                                    }
                                }
                            )
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun McpToolCard(
    tool: McpTool,
    onEnableChange: (Boolean) -> Unit,
    onNeedsApprovalChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val needsApprovalLabel = stringResource(R.string.setting_mcp_page_needs_approval)
    val enableLabel = stringResource(R.string.setting_mcp_page_enable)
    ProviderCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.name,
                    style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                    color = LocalAmberTokens.current.ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.code_block_collapse else R.string.code_block_expand
                        ),
                        tint = LocalAmberTokens.current.ink3,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                McpToolToggle(
                    label = needsApprovalLabel,
                    checked = tool.needsApproval,
                    onCheckedChange = onNeedsApprovalChange,
                    modifier = Modifier.weight(1f),
                )
                McpToolToggle(
                    label = enableLabel,
                    checked = tool.enable,
                    onCheckedChange = onEnableChange,
                    modifier = Modifier.weight(1f),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                val toolDescription = tool.description
                if (!toolDescription.isNullOrBlank()) {
                    Text(
                        text = toolDescription,
                        style = LocalAmberType.current.secondary,
                        color = LocalAmberTokens.current.ink3,
                    )
                }
                tool.inputSchema?.let { it as? InputSchema.Obj }?.let { schema ->
                    if (schema.properties.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            schema.properties.forEach { (key, _) ->
                                Tag(
                                    type = if (schema.required?.contains(key) == true) TagType.INFO else TagType.DEFAULT
                                ) {
                                    Text(text = key, style = LocalAmberType.current.meta)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToolToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = LocalAmberType.current.meta,
            color = tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            modifier = Modifier.semantics { contentDescription = label },
            onCheckedChange = onCheckedChange,
            size = SwitchSize.Small,
        )
    }
}

@Composable
private fun McpImportModal(
    onDismiss: () -> Unit,
    transaction: McpImportTransaction,
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<McpImportPreview?>(null) }
    var sessionId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.setting_mcp_page_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setting_mcp_page_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                    preview = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("{ \"mcpServers\": { ... } }") },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } }
            )
            preview?.let { McpImportPreviewCard(it) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                OutlinedButton(onClick = {
                    when (val preparation = transaction.prepare(jsonText.trim())) {
                        is McpImportPreparation.Ready -> {
                            preview = preparation.preview
                            errorMessage = null
                        }
                        is McpImportPreparation.Rejected -> {
                            preview = null
                            errorMessage = preparation.errors.joinToString("\n")
                        }
                    }
                }) {
                    Text(stringResource(R.string.setting_mcp_page_import_preview))
                }
                Button(
                    enabled = preview != null && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val currentPreview = preview
                                if (currentPreview != null) {
                                    transaction.approve(currentPreview, sessionId)
                                    when (val result = transaction.apply(jsonText.trim(), sessionId)) {
                                        is McpImportApplyResult.Applied -> onDismiss()
                                        is McpImportApplyResult.Stale -> {
                                            preview = null
                                            errorMessage = result.reason
                                            sessionId = UUID.randomUUID().toString()
                                        }
                                        is McpImportApplyResult.Rejected -> {
                                            errorMessage = result.errors.joinToString("\n")
                                        }
                                    }
                                }
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            if (busy) {
                                R.string.setting_mcp_page_importing
                            } else {
                                R.string.setting_mcp_page_import_approve
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun McpImportPreviewCard(preview: McpImportPreview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        R.string.setting_mcp_page_import_preview_summary,
                        preview.serverCount,
                        preview.headerNameCount,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        if (preview.risk == "high") {
                            R.string.setting_mcp_page_import_risk_high
                        } else {
                            R.string.setting_mcp_page_import_risk_normal
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (preview.risk == "high") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            preview.servers.forEachIndexed { index, server ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        server.serverName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "transport: ${if (server.transport == McpImportTransport.SSE) "sse" else "streamable_http"} · origin: ${server.origin}" +
                            if (server.risk == "high") {
                                stringResource(R.string.setting_mcp_page_import_risk_high_suffix)
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (server.headerNames.isNotEmpty()) {
                        Text(
                            "headers: ${server.headerNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    server.note?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (index < preview.servers.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}
