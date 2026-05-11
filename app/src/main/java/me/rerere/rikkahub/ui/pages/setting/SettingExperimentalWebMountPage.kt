package me.rerere.rikkahub.ui.pages.setting

import android.webkit.CookieManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
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
import me.rerere.rikkahub.data.agent.webmount.core.WebMountCapability
import me.rerere.rikkahub.data.agent.webmount.core.WebMountManager
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStationState
import me.rerere.rikkahub.data.agent.webmount.core.WebMountStatus
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.oauth.OAuthAppCredentials
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthClient
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import me.rerere.rikkahub.data.agent.webmount.usersites.AuthKind
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSite
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSiteRegistry
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * Phase 2 Plan v2 — unified WebMount Stations setting page.
 *
 * One concept on screen: **网站**. The old triple of "OAuth providers /
 * Site Profiles / Available stations" has been collapsed into a single
 * editable list sourced from [UserSiteRegistry]. Profile JSON import is
 * gone (no user ever needs it); OAuth credentials are edited via a
 * dialog launched from the relevant site row.
 *
 * Hero toggle controls whether the agent has any WebMount capability at
 * all; per-site rows handle login / sign-out / delete.
 */
@Composable
fun SettingExperimentalWebMountPage(
    webMountManager: WebMountManager = koinInject(),
    oauthClient: WebMountOAuthClient = koinInject(),
    oauthStore: WebMountOAuthTokenStore = koinInject(),
    userSiteRegistry: UserSiteRegistry = koinInject(),
    cookieProvider: WebMountCookieProvider = koinInject(),
) {
    val states by webMountManager.states.collectAsStateWithLifecycle()
    val sites by userSiteRegistry.sites.collectAsStateWithLifecycle()
    val globalEnabled by webMountManager.globalEnabledFlow.collectAsStateWithLifecycle()
    val evalEnabled by webMountManager.evalEnabledFlow.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    // Per-site UI state.
    var busyStations by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loginDialogSite by remember { mutableStateOf<UserSite?>(null) }
    var oauthEditDialog by remember { mutableStateOf<UserSite?>(null) }
    var addSiteDialogOpen by remember { mutableStateOf(false) }
    // Phase 2 Plan v2 review B-1 fix: cookie-state revision counter. The
    // login dialog dismiss + sign-out actions bump this; UserSiteCard reads
    // it inside its `loggedIn` computation so rows re-probe CookieManager
    // on every state change instead of caching the answer for the page's
    // lifetime.
    var cookieRevision by remember { mutableStateOf(0) }

    // Forces OAuth section to recompose when token store updates.
    var oauthRevision by remember { mutableStateOf(0) }
    LaunchedEffect(oauthStore) {
        oauthStore.updates.collectLatest { oauthRevision++ }
    }

    val signedInTemplate = stringResource(R.string.setting_webmount_login_dialog_signed_in)
    val notReadyTemplate = stringResource(R.string.setting_webmount_login_dialog_not_ready)
    val signedOutTemplate = stringResource(R.string.setting_webmount_signed_out_toast)
    val noCookiesMessage = stringResource(R.string.setting_webmount_signed_out_nothing)
    val deletedTemplate = stringResource(R.string.setting_webmount_site_deleted_toast)
    val addedTemplate = stringResource(R.string.setting_webmount_site_added_toast)
    val duplicateMessage = stringResource(R.string.setting_webmount_add_site_duplicate)
    val noNativeTestHint = stringResource(R.string.setting_webmount_site_no_native_test_hint)
    val restoredTemplate = stringResource(R.string.setting_webmount_restore_seed_toast)
    val nothingToRestore = stringResource(R.string.setting_webmount_restore_seed_none)
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
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_section_sites),
                ) {
                    if (sites.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_webmount_sites_empty))
                    } else {
                        sites.forEachIndexed { index, site ->
                            // Recompose OAuth-dependent state when token store updates.
                            @Suppress("UNUSED_EXPRESSION") oauthRevision
                            UserSiteCard(
                                site = site,
                                stationState = states[site.id],
                                cookieProvider = cookieProvider,
                                oauthStore = oauthStore,
                                webMountManager = webMountManager,
                                cookieRevision = cookieRevision,
                                busy = site.id in busyStations,
                                onSignIn = if (site.authKind == AuthKind.COOKIE) {
                                    { loginDialogSite = site }
                                } else null,
                                onSignOut = if (site.authKind == AuthKind.COOKIE) {
                                    {
                                        val urls = collectKnownUrlsFor(site, webMountManager)
                                        val cleared = cookieProvider.clearCookiesFor(urls)
                                        cookieRevision++
                                        toaster.show(
                                            if (cleared > 0) signedOutTemplate.format(site.displayName, cleared)
                                            else noCookiesMessage.format(site.displayName)
                                        )
                                    }
                                } else null,
                                onConnect = if (site.authKind == AuthKind.OAUTH) {
                                    {
                                        scope.launch {
                                            runCatching {
                                                val result = oauthClient.connect(site.id)
                                                when (result) {
                                                    is WebMountOAuthClient.ConnectResult.Success ->
                                                        toaster.show(connectedTemplate.format(site.displayName))
                                                    is WebMountOAuthClient.ConnectResult.NotConfigured ->
                                                        toaster.show(result.reason)
                                                    is WebMountOAuthClient.ConnectResult.Failed ->
                                                        toaster.show(failedTemplate.format(result.reason))
                                                }
                                            }.onFailure { toaster.show(it.message ?: it.toString()) }
                                        }
                                    }
                                } else null,
                                onDisconnect = if (site.authKind == AuthKind.OAUTH) {
                                    {
                                        oauthClient.disconnect(site.id)
                                        toaster.show(disconnectedTemplate.format(site.displayName))
                                    }
                                } else null,
                                onEditOAuth = if (site.authKind == AuthKind.OAUTH) {
                                    { oauthEditDialog = site }
                                } else null,
                                onTest = {
                                    if (site.nativeAdapterId != null) {
                                        busyStations = busyStations + site.id
                                        scope.launch {
                                            runCatching { webMountManager.probe(site.id) }
                                                .onFailure { toaster.show(it.message ?: it.toString()) }
                                            busyStations = busyStations - site.id
                                        }
                                    } else {
                                        toaster.show(noNativeTestHint)
                                    }
                                },
                                onDelete = {
                                    if (userSiteRegistry.remove(site.id)) {
                                        // Plan v2 review W-2 fix: wipe ALL data
                                        // associated with this site so "delete"
                                        // is honest. OAuth credentials + tokens
                                        // were silently surviving the delete and
                                        // would auto-attach if the user later
                                        // re-added the same site id.
                                        if (site.authKind == AuthKind.OAUTH) {
                                            oauthStore.clearToken(site.id)
                                            oauthStore.clearCredentials(site.id)
                                        }
                                        if (site.authKind == AuthKind.COOKIE) {
                                            val urls = collectKnownUrlsFor(site, webMountManager)
                                            cookieProvider.clearCookiesFor(urls)
                                        }
                                        cookieRevision++
                                        toaster.show(deletedTemplate.format(site.displayName))
                                    }
                                },
                            )
                            if (index != sites.lastIndex) ExperimentDivider()
                        }
                    }
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_webmount_add_site),
                            enabled = true,
                            primary = true,
                            onClick = { addSiteDialogOpen = true },
                        )
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_webmount_restore_seed),
                            enabled = true,
                            onClick = {
                                val added = userSiteRegistry.restoreMissingSeeds()
                                toaster.show(
                                    if (added > 0) restoredTemplate.format(added)
                                    else nothingToRestore
                                )
                            },
                        )
                    }
                }
            }
            item {
                ExperimentNote(text = stringResource(R.string.setting_webmount_about_note))
            }
        }
    }

    // Dialogs --------------------------------------------------------------

    loginDialogSite?.let { site ->
        WebMountLoginDialog(
            url = site.homepageUrl,
            title = site.displayName,
            onDismiss = {
                loginDialogSite = null
                cookieRevision++  // B-1 fix: force row re-probe of CookieManager
                val captured = site.loginCookieName?.let { cookieName ->
                    val urls = collectKnownUrlsFor(site, webMountManager)
                    cookieProvider.getCookies(emptyList(), urls).value(cookieName) != null
                } == true
                toaster.show(
                    if (captured) signedInTemplate.format(site.displayName)
                    else notReadyTemplate.format(site.displayName)
                )
            },
        )
    }

    oauthEditDialog?.let { site ->
        val provider = oauthClient.provider(site.id)
        if (provider == null) {
            oauthEditDialog = null
        } else {
            @Suppress("UNUSED_EXPRESSION") oauthRevision
            val existing = oauthStore.getCredentials(site.id)
            OAuthEditDialog(
                siteName = site.displayName,
                initialAppId = existing?.appId.orEmpty(),
                initialAppSecret = existing?.appSecret.orEmpty(),
                initialScope = existing?.scope.orEmpty(),
                providerHint = provider.setupHint(),
                onDismiss = { oauthEditDialog = null },
                onSave = { appId, appSecret, scopeText ->
                    if (appId.isBlank()) {
                        toaster.show(appIdRequiredMessage)
                    } else {
                        oauthStore.putCredentials(
                            site.id,
                            OAuthAppCredentials(
                                provider = site.id,
                                appId = appId.trim(),
                                appSecret = appSecret.trim().ifBlank { null },
                                scope = scopeText.trim().ifBlank { null },
                            ),
                        )
                        oauthEditDialog = null
                    }
                },
            )
        }
    }

    if (addSiteDialogOpen) {
        AddCustomSiteDialog(
            onDismiss = { addSiteDialogOpen = false },
            onAdd = { name, url, cookieName ->
                val ok = userSiteRegistry.add(
                    UserSite(
                        id = "user_" + slugify(name),
                        displayName = name,
                        homepageUrl = url,
                        authKind = if (cookieName.isNullOrBlank()) AuthKind.ANONYMOUS else AuthKind.COOKIE,
                        loginCookieName = cookieName?.takeIf { it.isNotBlank() },
                        nativeAdapterId = null,
                        iconKey = null,
                    )
                )
                if (ok) {
                    addSiteDialogOpen = false
                    toaster.show(addedTemplate.format(name))
                } else {
                    toaster.show(duplicateMessage)
                }
            },
        )
    }
}

// ----------------------------------------------------------------------------
// One site row — the entire user-facing model of a website
// ----------------------------------------------------------------------------

@Composable
private fun UserSiteCard(
    site: UserSite,
    stationState: WebMountStationState?,
    cookieProvider: WebMountCookieProvider,
    oauthStore: WebMountOAuthTokenStore,
    webMountManager: WebMountManager,
    /** Bumped by the parent on cookie-state changes so this row re-probes. */
    cookieRevision: Int,
    busy: Boolean,
    onSignIn: (() -> Unit)?,
    onSignOut: (() -> Unit)?,
    onConnect: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    onEditOAuth: (() -> Unit)?,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val workspace = workspaceColors()
    // Plan v2 review B-1 + W-1 fix: probe the FULL known URL set on every
    // recomposition (no remember cache). The login dialog dismiss + sign-out
    // + delete actions all bump `cookieRevision` so this row sees a fresh
    // CookieManager snapshot. The URL set matches what onSignOut /
    // onDismiss probe — no asymmetry between the row label and the toast.
    @Suppress("UNUSED_EXPRESSION") cookieRevision
    val loggedIn = site.loginCookieName?.let { name ->
        val urls = collectKnownUrlsFor(site, webMountManager)
        if (urls.isEmpty()) null
        else cookieProvider.getCookies(emptyList(), urls).value(name) != null
    }
    val hasToken = if (site.authKind == AuthKind.OAUTH) {
        oauthStore.getToken(site.id) != null
    } else false
    val hasCredentials = if (site.authKind == AuthKind.OAUTH) {
        oauthStore.getCredentials(site.id) != null
    } else false

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
                    Icon(iconForIconKey(site.iconKey), contentDescription = null)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = site.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = workspace.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = site.homepageUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SiteStatusPill(
                authKind = site.authKind,
                stationState = stationState,
                loggedIn = loggedIn,
                hasToken = hasToken,
                hasCredentials = hasCredentials,
            )
        }

        stationState?.message?.takeIf { it.isNotBlank() }?.let { msg ->
            ExperimentNote(text = msg)
        }

        ExperimentActionRow {
            if (onSignIn != null) {
                ExperimentActionButton(
                    text = stringResource(
                        if (loggedIn == true) R.string.setting_webmount_sign_in_again
                        else R.string.setting_webmount_sign_in
                    ),
                    primary = true,
                    enabled = !busy,
                    onClick = onSignIn,
                )
            }
            if (onSignOut != null && loggedIn == true) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_sign_out),
                    enabled = !busy,
                    onClick = onSignOut,
                )
            }
            if (onConnect != null && !hasToken) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_oauth_connect),
                    primary = true,
                    enabled = !busy && hasCredentials,
                    onClick = onConnect,
                )
            }
            if (onDisconnect != null && hasToken) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_oauth_disconnect),
                    enabled = !busy,
                    onClick = onDisconnect,
                )
            }
            if (onEditOAuth != null) {
                ExperimentActionButton(
                    text = stringResource(R.string.setting_webmount_oauth_edit_credentials),
                    enabled = !busy,
                    onClick = onEditOAuth,
                )
            }
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_test),
                enabled = !busy && site.nativeAdapterId != null,
                onClick = onTest,
            )
            ExperimentActionButton(
                text = stringResource(R.string.setting_webmount_delete_site),
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun SiteStatusPill(
    authKind: AuthKind,
    stationState: WebMountStationState?,
    loggedIn: Boolean?,
    hasToken: Boolean,
    hasCredentials: Boolean,
) {
    val workspace = workspaceColors()
    // Plan v2 review W-3 fix: operational status (DEGRADED / ERROR) takes
    // precedence over auth state. A signed-in user being rate-limited wants
    // to see "Rate-limited" — sign-in is a prerequisite, not the actionable
    // info. ANONYMOUS sites still surface "Public" because they have no
    // notion of signed-in/out, but their station status (if any) is still
    // surfaced via DEGRADED / ERROR branches below.
    val (color, labelRes) = when {
        stationState?.status == WebMountStatus.ERROR ->
            MaterialTheme.colorScheme.error to R.string.setting_webmount_pill_error
        stationState?.status == WebMountStatus.DEGRADED ->
            workspace.muted to R.string.setting_webmount_pill_rate_limited
        authKind == AuthKind.ANONYMOUS ->
            workspace.blue to R.string.setting_webmount_pill_public
        authKind == AuthKind.OAUTH && hasToken ->
            workspace.blue to R.string.setting_webmount_pill_connected
        authKind == AuthKind.OAUTH && hasCredentials ->
            workspace.muted to R.string.setting_webmount_pill_ready
        authKind == AuthKind.OAUTH ->
            workspace.faint to R.string.setting_webmount_pill_needs_setup
        loggedIn == true ->
            workspace.blue to R.string.setting_webmount_pill_signed_in
        loggedIn == false ->
            workspace.muted to R.string.setting_webmount_pill_signed_out
        else ->
            workspace.faint to R.string.setting_webmount_pill_unknown
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
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

// ----------------------------------------------------------------------------
// Dialogs
// ----------------------------------------------------------------------------

/**
 * iCloud-style login card. 16dp-inset Box around a 90%-height Surface
 * with a header row (title + X close) and a WebView filling the rest.
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
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OAuthEditDialog(
    siteName: String,
    initialAppId: String,
    initialAppSecret: String,
    initialScope: String,
    providerHint: String,
    onDismiss: () -> Unit,
    onSave: (appId: String, appSecret: String, scope: String) -> Unit,
) {
    var appId by remember { mutableStateOf(initialAppId) }
    var appSecret by remember { mutableStateOf(initialAppSecret) }
    var scopeText by remember { mutableStateOf(initialScope) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_webmount_oauth_edit_title, siteName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    providerHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = appId,
                    onValueChange = { appId = it },
                    label = { Text(stringResource(R.string.setting_webmount_oauth_app_id)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
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
                    value = scopeText,
                    onValueChange = { scopeText = it },
                    label = { Text(stringResource(R.string.setting_webmount_oauth_scope)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(appId, appSecret, scopeText) }) {
                Text(stringResource(R.string.setting_webmount_oauth_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_webmount_profile_import_dialog_cancel))
            }
        },
    )
}

@Composable
private fun AddCustomSiteDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, cookieName: String?) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var cookieInput by remember { mutableStateOf("") }
    val urlValid = urlInput.startsWith("http://") || urlInput.startsWith("https://")
    val canSubmit = nameInput.isNotBlank() && urlValid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_webmount_custom_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.setting_webmount_custom_dialog_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.setting_webmount_custom_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.setting_webmount_custom_url_label)) },
                    placeholder = { Text("https://weibo.com") },
                    singleLine = true,
                    isError = urlInput.isNotBlank() && !urlValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    label = { Text(stringResource(R.string.setting_webmount_custom_cookie_label)) },
                    placeholder = { Text("SUB") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            stringResource(R.string.setting_webmount_custom_cookie_supporting),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onAdd(nameInput.trim(), urlInput.trim(), cookieInput.trim().ifBlank { null })
                },
            ) {
                Text(stringResource(R.string.setting_webmount_custom_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setting_webmount_profile_import_dialog_cancel))
            }
        },
    )
}

// ----------------------------------------------------------------------------
// Helpers
// ----------------------------------------------------------------------------

/**
 * Collect every URL the cookie provider might need to probe for this site:
 * its homepage + any adapter-declared origins / cookie URLs. Used for both
 * sign-out (clear all of them) and login probe (check any of them).
 */
private fun collectKnownUrlsFor(site: UserSite, manager: WebMountManager): List<String> {
    val adapter = site.nativeAdapterId?.let { manager.adapterOf(it) }
    return buildSet {
        add(site.homepageUrl)
        adapter?.endpoints?.forEach { endpoint ->
            add(endpoint.origin)
            add(endpoint.apiBase)
            add(endpoint.loginUrl)
            addAll(endpoint.cookieUrls)
        }
    }.filter { it.isNotBlank() }.distinct()
}

private fun iconForIconKey(iconKey: String?) = when (iconKey) {
    "hackernews" -> HugeIcons.News01
    "reddit" -> HugeIcons.Comment01
    "github" -> HugeIcons.Github
    "bilibili" -> HugeIcons.PlayCircle02
    "juejin" -> HugeIcons.Idea
    "zhihu" -> HugeIcons.BubbleChatQuestion
    "feishu_docs" -> HugeIcons.Note01
    else -> HugeIcons.Globe02
}

private fun slugify(name: String): String =
    name.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "site" }
        .take(40)
