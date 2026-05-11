package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.IconButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Comment01
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Globe02
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.News01
import me.rerere.hugeicons.stroke.Note01
import me.rerere.hugeicons.stroke.PlayCircle02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.agent.webmount.core.WebMountAuthMethod
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStationState
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStatus
import me.rerere.rikkahub.data.agent.webmount.oauth.OAuthAppCredentials
import me.rerere.rikkahub.data.agent.webmount.oauth.OAuthProvider
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthClient
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingExperimentalWebMountPage(
    webMountManager: WebMountManager = koinInject(),
    oauthClient: WebMountOAuthClient = koinInject(),
    oauthStore: WebMountOAuthTokenStore = koinInject(),
    profileRegistry: ProfileRegistry = koinInject(),
    cookieProvider: me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider = koinInject(),
) {
    val states by webMountManager.states.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val navController = LocalNavController.current
    // Track which station ids are currently probing so the same probe button
    // can be disabled per-station while leaving siblings interactive.
    var busyStations by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Bump on every TokenStore update — triggers OAuth section recomposition.
    var oauthRevision by remember { mutableStateOf(0) }
    LaunchedEffect(oauthStore) {
        oauthStore.updates.collectLatest { oauthRevision++ }
    }
    val globalEnabled by webMountManager.globalEnabledFlow.collectAsStateWithLifecycle()
    val evalEnabled by webMountManager.evalEnabledFlow.collectAsStateWithLifecycle()
    var loginDialogStationId by remember { mutableStateOf<String?>(null) }
    val appIdRequiredMessage = stringResource(R.string.setting_webmount_oauth_app_id_required)
    val connectedTemplate = stringResource(R.string.setting_webmount_oauth_connected_toast)
    val disconnectedTemplate = stringResource(R.string.setting_webmount_oauth_disconnected_toast)
    val failedTemplate = stringResource(R.string.setting_webmount_oauth_failed_toast)

    ExperimentalSettingsScaffold(
        title = stringResource(R.string.setting_webmount_title),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ExperimentHeroCard(
                    icon = { Icon(HugeIcons.Globe02, contentDescription = null) },
                    title = stringResource(R.string.setting_webmount_title),
                    description = stringResource(R.string.setting_webmount_desc),
                    trailing = {
                        Switch(
                            checked = globalEnabled,
                            onCheckedChange = { webMountManager.setGlobalEnabled(it) },
                        )
                    },
                )
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_global_section_title),
                ) {
                    ExperimentStatusRow(
                        label = stringResource(R.string.setting_webmount_global_status_label),
                        value = if (globalEnabled) {
                            stringResource(R.string.setting_webmount_global_status_on)
                        } else {
                            stringResource(R.string.setting_webmount_global_status_off)
                        },
                    )
                    ExperimentNote(
                        text = if (globalEnabled) {
                            stringResource(R.string.setting_webmount_global_on_note)
                        } else {
                            stringResource(R.string.setting_webmount_global_off_note)
                        },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.setting_webmount_eval_label),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                stringResource(R.string.setting_webmount_eval_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = evalEnabled,
                            enabled = globalEnabled,
                            onCheckedChange = { webMountManager.setEvalEnabled(it) },
                        )
                    }
                }
            }
            item {
                ExperimentNote(text = stringResource(R.string.setting_webmount_about_note))
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_section_oauth),
                ) {
                    val providers = oauthClient.providers().toList()
                    if (providers.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_webmount_no_oauth_providers))
                    } else {
                        providers.forEachIndexed { index, provider ->
                            // Read fresh each recomposition so saves/disconnects update the UI.
                            @Suppress("UNUSED_EXPRESSION") oauthRevision
                            val credentials = oauthStore.getCredentials(provider.id)
                            val token = oauthStore.getToken(provider.id)
                            OAuthProviderRow(
                                provider = provider,
                                hasCredentials = credentials != null,
                                hasToken = token != null,
                                tokenExpiresAtMs = token?.expiresAtMs ?: 0L,
                                initialAppId = credentials?.appId.orEmpty(),
                                initialAppSecret = credentials?.appSecret.orEmpty(),
                                initialScope = credentials?.scope.orEmpty(),
                                onSaveCredentials = { appId, appSecret, scope ->
                                    if (appId.isBlank()) {
                                        toaster.show(appIdRequiredMessage)
                                    } else {
                                        oauthStore.putCredentials(
                                            provider.id,
                                            OAuthAppCredentials(
                                                provider = provider.id,
                                                appId = appId.trim(),
                                                appSecret = appSecret.trim().ifBlank { null },
                                                scope = scope.trim().ifBlank { null },
                                            ),
                                        )
                                    }
                                },
                                onConnect = {
                                    scope.launch {
                                        runCatching {
                                            val result = oauthClient.connect(provider.id)
                                            when (result) {
                                                is WebMountOAuthClient.ConnectResult.Success ->
                                                    toaster.show(
                                                        connectedTemplate.format(provider.displayName)
                                                    )
                                                is WebMountOAuthClient.ConnectResult.NotConfigured ->
                                                    toaster.show(result.reason)
                                                is WebMountOAuthClient.ConnectResult.Failed ->
                                                    toaster.show(
                                                        failedTemplate.format(result.reason)
                                                    )
                                            }
                                        }.onFailure { toaster.show(it.message ?: it.toString()) }
                                    }
                                },
                                onDisconnect = {
                                    oauthClient.disconnect(provider.id)
                                    toaster.show(
                                        disconnectedTemplate.format(provider.displayName)
                                    )
                                },
                            )
                            if (index != providers.lastIndex) ExperimentDivider()
                        }
                    }
                }
            }
            item {
                WebMountProfileSection(
                    registry = profileRegistry,
                    onToast = { toaster.show(it) },
                )
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_section_stations),
                ) {
                    val stationStates = states.values.toList()
                    if (stationStates.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_webmount_no_stations))
                    } else {
                        stationStates.forEachIndexed { index, state ->
                            WebMountStationRow(
                                state = state,
                                busy = state.id in busyStations,
                                onConfigure = {
                                    configureFor(state.id)?.let { navController.navigate(it) }
                                },
                                onProbe = {
                                    busyStations = busyStations + state.id
                                    scope.launch {
                                        runCatching { webMountManager.probe(state.id) }
                                            .onFailure { toaster.show(it.message ?: it.toString()) }
                                        busyStations = busyStations - state.id
                                    }
                                },
                                onWriteProbe = {
                                    busyStations = busyStations + state.id
                                    scope.launch {
                                        runCatching { webMountManager.runWriteProbe(state.id) }
                                            .onFailure { toaster.show(it.message ?: it.toString()) }
                                        busyStations = busyStations - state.id
                                    }
                                },
                                onSignIn = if (isCookieAuthStation(state)) {
                                    { loginDialogStationId = state.id }
                                } else null,
                                onSignOut = signOutLauncherFor(
                                    state = state,
                                    registry = profileRegistry,
                                    webMountManager = webMountManager,
                                    cookieProvider = cookieProvider,
                                    toaster = toaster,
                                ),
                            )
                            if (index != stationStates.lastIndex) {
                                ExperimentDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    // Per-user-feedback fix: iCloud-style login card (was a broken
    // full-screen Activity). The 90%-height Surface inside a 16dp-padded
    // Box mirrors ICloudLoginDialog exactly so the visual matches the
    // reference screenshot.
    loginDialogStationId?.let { stationId ->
        val adapter = webMountManager.adapterOf(stationId)
        val loginUrl = adapter?.primaryLoginUrl()
        if (adapter == null || loginUrl.isNullOrBlank()) {
            // Station has no login URL — nothing we can do. Dismiss silently.
            loginDialogStationId = null
            return@let
        }
        val stationName = states[stationId]?.displayName ?: adapter.displayName
        val signedInTemplate = stringResource(R.string.setting_webmount_login_dialog_signed_in)
        val notReadyTemplate = stringResource(R.string.setting_webmount_login_dialog_not_ready)
        WebMountLoginDialog(
            url = loginUrl,
            title = stationName,
            onDismiss = {
                loginDialogStationId = null
                // Probe the configured login cookie across the profile's
                // origins. If found, toast success; otherwise the user may
                // not have completed login.
                val profile = profileRegistry.byId(stationId)?.profile
                val cookieName = profile?.hints?.loginCookie
                val urls = buildList {
                    profile?.origins?.let { addAll(it) }
                    adapter.endpoints.forEach { addAll(it.cookieUrls); add(it.origin); add(it.apiBase) }
                }.filter { it.isNotBlank() }.distinct()
                val captured = cookieName != null && urls.isNotEmpty() &&
                    cookieProvider.getCookies(emptyList(), urls).value(cookieName) != null
                toaster.show(
                    if (captured) signedInTemplate.format(stationName)
                    else notReadyTemplate.format(stationName)
                )
            },
        )
    }
}

/**
 * Per-user-feedback — iCloud-style login card. Mirrors
 * `ICloudLoginDialog` in [SettingExperimentalPage]: a 16dp-inset Box
 * containing a 90%-height extraLarge-shape Surface with a header row
 * (title + X close) and the WebView filling the rest.
 *
 * The headless WebView pool already enables third-party cookies (M2.3
 * holistic review W-2 fix). This Dialog's user-facing WebView does the
 * same explicitly in onCreated so SSO redirects (e.g. passport.* →
 * www.*) persist their Set-Cookie hops into the process-global jar
 * that the agent's headless sessions read from.
 */
@Composable
private fun WebMountLoginDialog(
    url: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val state = rememberWebViewState(
        url = url,
        settings = {
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
        },
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, top = 12.dp, end = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_webmount_login_dialog_title, title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.update_card_close),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    WebView(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onCreated = { webView ->
                            val cookieManager = android.webkit.CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(webView, true)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Detail page for a station, if it has one. Returns null for stations whose
 * configuration is fully inline in the station card (login + probe buttons).
 *
 * Phase 1 M1.6 onwards: sites with extra config (e.g. Feishu app credentials,
 * GitHub default org) get their own per-station detail page added here.
 */
@Suppress("UNUSED_PARAMETER")
private fun configureFor(stationId: String): me.rerere.rikkahub.Screen? = null

@Composable
private fun WebMountStationRow(
    state: WebMountStationState,
    busy: Boolean,
    onConfigure: () -> Unit,
    onProbe: () -> Unit,
    onWriteProbe: () -> Unit,
    onSignIn: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
) {
    val workspace = workspaceColors()
    val canWriteProbe =
        state.capability == WebMountCapability.READ_ONLY ||
            state.capability == WebMountCapability.READ_WRITE
    val hasConfigureTarget = configureFor(state.id) != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.muted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(iconForStation(state.id), contentDescription = null)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stationDisplayNameRes(state.id)
                        ?.let { stringResource(it) }
                        ?: state.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(stationIdKey(state.id)),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            WebMountStatusPill(state.status)
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WebMountCapabilityPill(state.capability)
            state.authMethods.forEach { method ->
                WebMountAuthPill(method)
            }
        }

        state.message?.takeIf { it.isNotBlank() }?.let { message ->
            ExperimentNote(text = message)
        }

        ExperimentActionRow {
            if (hasConfigureTarget) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_configure),
                    primary = true,
                    enabled = !busy,
                    onClick = onConfigure,
                )
            }
            // Phase 2 M2.3.5: surface a "Sign in" action on cookie stations
            // where login is missing. Opens the InlineLoginActivity for that
            // station so the user doesn't have to chase the deep link from
            // an agent tool response.
            if (onSignIn != null) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_sign_in),
                    primary = !hasConfigureTarget,
                    enabled = !busy,
                    onClick = onSignIn,
                )
            }
            if (onSignOut != null) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_sign_out),
                    enabled = !busy,
                    onClick = onSignOut,
                )
            }
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_probe),
                enabled = !busy,
                onClick = onProbe,
            )
            if (canWriteProbe) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_write_probe),
                    enabled = !busy,
                    onClick = onWriteProbe,
                )
            }
        }

        ExperimentStatusRow(
            label = stringResource(R.string.setting_webmount_last_updated),
            value = formatTimestamp(state.updatedAtMillis),
        )
    }
}

@Composable
private fun WebMountStatusPill(status: WebMountStatus) {
    val workspace = workspaceColors()
    val (containerAlpha, color, labelRes) = when (status) {
        WebMountStatus.READ_WRITE -> Triple(0.12f, workspace.blue, R.string.setting_webmount_status_read_write)
        WebMountStatus.READ_ONLY -> Triple(0.12f, workspace.blue, R.string.setting_webmount_status_read_only)
        WebMountStatus.PROBING -> Triple(0.10f, workspace.muted, R.string.setting_webmount_status_probing)
        WebMountStatus.LOGIN_REQUIRED -> Triple(0.12f, workspace.muted, R.string.setting_webmount_status_login_required)
        WebMountStatus.DEGRADED -> Triple(0.14f, workspace.muted, R.string.setting_webmount_status_degraded)
        WebMountStatus.ERROR -> Triple(0.14f, MaterialTheme.colorScheme.error, R.string.setting_webmount_status_error)
        WebMountStatus.NOT_CONFIGURED -> Triple(0.08f, workspace.faint, R.string.setting_webmount_status_not_configured)
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = containerAlpha),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WebMountCapabilityPill(capability: WebMountCapability) {
    val workspace = workspaceColors()
    val labelRes = when (capability) {
        WebMountCapability.READ_WRITE -> R.string.setting_webmount_capability_read_write
        WebMountCapability.READ_ONLY -> R.string.setting_webmount_capability_read_only
        WebMountCapability.NONE -> R.string.setting_webmount_capability_none
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = workspace.row,
        contentColor = workspace.muted,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WebMountAuthPill(method: WebMountAuthMethod) {
    val workspace = workspaceColors()
    val labelRes = when (method) {
        WebMountAuthMethod.COOKIE -> R.string.setting_webmount_auth_cookie
        WebMountAuthMethod.OAUTH -> R.string.setting_webmount_auth_oauth
        WebMountAuthMethod.ANONYMOUS -> R.string.setting_webmount_auth_anonymous
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = workspace.row,
        contentColor = workspace.faint,
        border = BorderStroke(1.dp, workspace.hairline),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun OAuthProviderRow(
    provider: OAuthProvider,
    hasCredentials: Boolean,
    hasToken: Boolean,
    tokenExpiresAtMs: Long,
    initialAppId: String,
    initialAppSecret: String,
    initialScope: String,
    onSaveCredentials: (String, String, String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val workspace = workspaceColors()
    var appId by remember(provider.id, initialAppId) { mutableStateOf(initialAppId) }
    var appSecret by remember(provider.id, initialAppSecret) { mutableStateOf(initialAppSecret) }
    var scopeField by remember(provider.id, initialScope) { mutableStateOf(initialScope) }
    val nowMs = remember { System.currentTimeMillis() }
    val tokenExpired = hasToken && tokenExpiresAtMs > 0 && tokenExpiresAtMs <= nowMs
    val statusRes = when {
        hasToken && !tokenExpired -> R.string.setting_webmount_oauth_status_connected
        hasToken && tokenExpired -> R.string.setting_webmount_oauth_status_expired
        hasCredentials -> R.string.setting_webmount_oauth_status_ready
        else -> R.string.setting_webmount_oauth_status_need_setup
    }
    val statusColor = when {
        hasToken && !tokenExpired -> workspace.blue
        hasToken && tokenExpired -> MaterialTheme.colorScheme.error
        else -> workspace.muted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(8.dp),
                color = workspace.row,
                contentColor = workspace.muted,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.Globe02, contentDescription = null)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "provider id: ${provider.id} · redirect: ${provider.defaultRedirectUri}",
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = statusColor.copy(alpha = 0.12f),
                contentColor = statusColor,
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.25f)),
            ) {
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text(stringResource(R.string.setting_webmount_oauth_app_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = appSecret,
            onValueChange = { appSecret = it },
            label = { Text(stringResource(R.string.setting_webmount_oauth_app_secret)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = scopeField,
            onValueChange = { scopeField = it },
            label = { Text(stringResource(R.string.setting_webmount_oauth_scope)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ExperimentActionRow {
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_oauth_save),
                primary = true,
                enabled = true,
                onClick = { onSaveCredentials(appId, appSecret, scopeField) },
            )
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_oauth_connect),
                enabled = hasCredentials,
                onClick = onConnect,
            )
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_oauth_disconnect),
                enabled = hasToken,
                onClick = onDisconnect,
            )
        }
        ExperimentNote(text = provider.setupHint())
    }
}

/**
 * Phase 2 M2.3.5 — return an `onSignIn` handler if this station is cookie-
 * auth, has a primary login URL, and is currently not signed in. Returns
 * null otherwise so the "Sign in" button doesn't render.
 */
/**
 * Per-user-feedback fix: clicking the per-station "Sign in" button now
 * triggers an iCloud-style Compose Dialog (see [WebMountLoginDialog])
 * rather than launching the full-screen [InlineLoginActivity]. The
 * Activity used to render blank and lacked the X-close affordance.
 *
 * Returns null for non-cookie stations (OAuth has its own Connect button
 * in the OAuth providers section).
 */
private fun isCookieAuthStation(state: WebMountStationState): Boolean =
    WebMountAuthMethod.COOKIE in state.authMethods

/**
 * Phase 2 follow-up — "Sign out" action for cookie stations. Clears every
 * cookie associated with the station's known URLs (profile origins +
 * adapter endpoint URLs) and refreshes the station state so the UI
 * immediately reflects the logged-out state. Returns null for OAuth /
 * anonymous stations (those don't have cookie-based sessions to clear —
 * OAuth has its own Disconnect button under OAuth providers).
 */
@Composable
private fun signOutLauncherFor(
    state: WebMountStationState,
    registry: ProfileRegistry,
    webMountManager: WebMountManager,
    cookieProvider: me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider,
    toaster: com.dokar.sonner.ToasterState,
): (() -> Unit)? {
    if (WebMountAuthMethod.COOKIE !in state.authMethods) return null
    val signedOutTemplate = stringResource(R.string.setting_webmount_signed_out_toast)
    val noCookiesMessage = stringResource(R.string.setting_webmount_signed_out_nothing)
    return {
        // Gather every URL we know about for this station: profile.origins
        // + the adapter's declared cookieUrls / loginUrl / origin / apiBase.
        // Together these cover every host the station might have set cookies on.
        val profile = registry.byId(state.id)?.profile
        val adapter = webMountManager.adapterOf(state.id)
        val urls = buildSet {
            profile?.origins?.let { addAll(it) }
            adapter?.endpoints?.forEach { endpoint ->
                add(endpoint.origin)
                add(endpoint.apiBase)
                add(endpoint.loginUrl)
                addAll(endpoint.cookieUrls)
            }
        }.filter { it.isNotBlank() }.distinct()
        val cleared = cookieProvider.clearCookiesFor(urls)
        toaster.show(
            if (cleared > 0) signedOutTemplate.format(state.displayName, cleared)
            else noCookiesMessage.format(state.displayName)
        )
    }
}

/**
 * Phase 2 M2.3.4 — per-station Compose icons. Built-in station ids map to
 * topical HugeIcons; unknown stations fall back to the generic Globe02.
 */
private fun iconForStation(stationId: String) = when (stationId) {
    "hackernews" -> HugeIcons.News01
    "reddit" -> HugeIcons.Comment01
    "github" -> HugeIcons.Github
    "bilibili" -> HugeIcons.PlayCircle02
    "juejin" -> HugeIcons.Idea
    "zhihu" -> HugeIcons.BubbleChatQuestion
    "feishu_docs" -> HugeIcons.Note01
    else -> HugeIcons.Globe02
}

/**
 * Phase 2 M2.3.3 — localized display-name resource per station id.
 * The data layer's [me.rerere.rikkahub.data.agent.webmount.core.WebMountAdapter.displayName]
 * is kept as a static label for non-UI consumers (logs, agent tool output);
 * the settings UI calls this to render in the user's current locale.
 */
@androidx.annotation.StringRes
private fun stationDisplayNameRes(stationId: String): Int? = when (stationId) {
    "hackernews" -> R.string.station_hackernews_name
    "reddit" -> R.string.station_reddit_name
    "github" -> R.string.station_github_name
    "bilibili" -> R.string.station_bilibili_name
    "juejin" -> R.string.station_juejin_name
    "zhihu" -> R.string.station_zhihu_name
    "feishu_docs" -> R.string.station_feishu_docs_name
    else -> null
}

@Suppress("UNUSED_PARAMETER")
private fun stationIdKey(stationId: String): Int = R.string.setting_webmount_station_generic_desc

private fun formatTimestamp(ms: Long): String =
    if (ms <= 0L) {
        "—"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }
