package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
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
import androidx.compose.ui.platform.LocalContext
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
import me.rerere.rikkahub.data.agent.webmount.core.WebMountUserAgents
import me.rerere.rikkahub.data.agent.webmount.core.WebMountWebViewCompat
import me.rerere.rikkahub.data.agent.webmount.cookie.WebMountCookieProvider
import me.rerere.rikkahub.data.agent.webmount.oauth.OAuthAppCredentials
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthClient
import me.rerere.rikkahub.data.agent.webmount.oauth.WebMountOAuthTokenStore
import me.rerere.rikkahub.data.agent.webmount.usersites.AuthKind
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSite
import me.rerere.rikkahub.data.agent.webmount.usersites.UserSiteRegistry
import me.rerere.rikkahub.data.agent.webmount.usersites.loginCookieCandidatesFor
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
    profileRegistry: me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry = koinInject(),
    settingsStore: me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator = koinInject(),
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
    // Pre-login cookie snapshot — captured the moment user taps Sign-in.
    // The dismiss handler diffs against post-snapshot to infer the session
    // cookie name and backfills site.loginCookieName when it was empty.
    var preLoginCookies by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
    val unknownStatusTemplate = stringResource(R.string.setting_webmount_login_dialog_unknown_status)
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
                    // One-tap install for the /webmount slash command. Adds a
                    // globally-available QuickMessage that prefixes whatever
                    // task the user types with a system-style instruction
                    // teaching the agent to use WebMount tools.
                    ExperimentActionRow {
                        ExperimentActionButton(
                            text = stringResource(R.string.setting_webmount_install_slash_command),
                            enabled = globalEnabled,
                            primary = true,
                            onClick = {
                                scope.launch {
                                    val installed = installWebMountSlashCommand(settingsStore)
                                    toaster.show(
                                        if (installed) "已添加 /webmount 斜杠命令。聊天里输 / 选 webmount。"
                                        else "你已经装过 /webmount 了。"
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item {
                ExperimentSectionCard(
                    title = stringResource(R.string.setting_webmount_section_sites),
                ) {
                    // Per-user feedback: keep [+ 添加网站] / [恢复示例] anchored
                    // to the top of the section so they don't get pushed off
                    // screen as the list grows.
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
                    if (sites.isEmpty()) {
                        ExperimentNote(text = stringResource(R.string.setting_webmount_sites_empty))
                    } else {
                        sites.forEachIndexed { index, site ->
                            // Recompose OAuth-dependent state when token store updates.
                            @Suppress("UNUSED_EXPRESSION") oauthRevision
                            val oauthProviderId = site.oauthProviderId ?: site.id
                            UserSiteCard(
                                site = site,
                                stationState = states[site.id],
                                cookieProvider = cookieProvider,
                                oauthStore = oauthStore,
                                webMountManager = webMountManager,
                                profileRegistry = profileRegistry,
                                cookieRevision = cookieRevision,
                                busy = site.id in busyStations,
                                onSignIn = if (site.authKind != AuthKind.ANONYMOUS) {
                                    {
                                        // Capture pre-login cookies so we can
                                        // diff after the dialog dismisses and
                                        // infer the session cookie name.
                                        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
                                        preLoginCookies = cookieProvider.snapshotCookieEntries(urls)
                                        loginDialogSite = site
                                    }
                                } else null,
                                onSignOut = if (site.authKind != AuthKind.ANONYMOUS) {
                                    {
                                        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
                                        val cleared = cookieProvider.clearCookiesFor(urls)
                                        if (site.authKind == AuthKind.OAUTH) {
                                            oauthClient.disconnect(oauthProviderId)
                                            oauthRevision++
                                        }
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
                                                val result = oauthClient.connect(oauthProviderId)
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
                                        oauthClient.disconnect(oauthProviderId)
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
                                        // B-5 fix: drop the authKind guard so a
                                        // site whose kind flipped during its
                                        // lifetime (e.g. once OAuth, now COOKIE)
                                        // doesn't leak OAuth state. clearToken /
                                        // clearCredentials are no-ops when no
                                        // entry exists.
                                        oauthStore.clearToken(oauthProviderId)
                                        oauthStore.clearCredentials(oauthProviderId)
                                        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
                                        cookieProvider.clearCookiesFor(urls)
                                        profileRegistry.remove(site.id)
                                        cookieRevision++
                                        toaster.show(deletedTemplate.format(site.displayName))
                                    }
                                },
                            )
                            if (index != sites.lastIndex) ExperimentDivider()
                        }
                    }
                }
            }
        }
    }

    // Dialogs --------------------------------------------------------------

    loginDialogSite?.let { site ->
        WebMountLoginDialog(
            url = site.homepageUrl,
            title = site.displayName,
            stationId = site.id,
            onClearSession = {
                val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
                val cleared = cookieProvider.clearCookiesFor(urls)
                if (site.authKind == AuthKind.OAUTH) {
                    oauthClient.disconnect(site.oauthProviderId ?: site.id)
                    oauthRevision++
                }
                cookieRevision++
                preLoginCookies = emptyMap()
                toaster.show(
                    if (cleared > 0) signedOutTemplate.format(site.displayName, cleared)
                    else noCookiesMessage.format(site.displayName)
                )
            },
            onDismiss = {
                CookieManager.getInstance().flush()
                loginDialogSite = null
                cookieRevision++

                val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
                val postCookies = cookieProvider.snapshotCookieEntries(urls)
                val newCookies = postCookies.filterKeys { it !in preLoginCookies }
                preLoginCookies = emptyMap()
                val preferredCookieNames = loginCookieCandidatesFor(site)

                // If the user didn't configure a cookie name AND we detected a
                // newly-set session-like cookie during the login session, save
                // it back so the row's "logged_in" badge starts working without
                // any further intervention. Toast tells the user what happened.
                var autoInferredName: String? = null
                val savedCookieMissing = site.loginCookieName?.let { postCookies[it] == null } ?: true
                if (site.loginCookieName.isNullOrBlank() || savedCookieMissing) {
                    val guessSourceCookies = if (preferredCookieNames.isNotEmpty()) postCookies else newCookies
                    val guess = cookieProvider.guessSessionCookieName(
                        newCookies = guessSourceCookies,
                        preferredNames = preferredCookieNames,
                    )
                    if (guess != null) {
                        userSiteRegistry.update(site.id) { it.copy(loginCookieName = guess) }
                        autoInferredName = guess
                    }
                }

                val effectiveCookieNames = buildList {
                    autoInferredName?.let { add(it) }
                    addAll(preferredCookieNames)
                }.distinct()
                val captured = if (effectiveCookieNames.isNotEmpty()) {
                    effectiveCookieNames.any { postCookies[it] != null }
                } else null
                val message = when {
                    autoInferredName != null && captured == true ->
                        "已登录 $autoInferredName cookie 已识别并保存为 ${site.displayName} 的登录标记"
                    captured == true -> signedInTemplate.format(site.displayName)
                    captured == false -> notReadyTemplate.format(site.displayName)
                    else -> unknownStatusTemplate.format(site.displayName)
                }
                toaster.show(message)
            },
        )
    }

    oauthEditDialog?.let { site ->
        // Bug fix: OAuth providers are keyed by their own id ("feishu"), not
        // the UserSite id ("feishu_docs"). Use the explicit oauthProviderId
        // mapping with site.id as a fallback for sites where they match.
        val providerId = site.oauthProviderId ?: site.id
        val provider = oauthClient.provider(providerId)
        if (provider == null) {
            oauthEditDialog = null
        } else {
            @Suppress("UNUSED_EXPRESSION") oauthRevision
            val existing = oauthStore.getCredentials(providerId)
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
                            providerId,
                            OAuthAppCredentials(
                                provider = providerId,
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
            onAdd = { name, url, needsLogin, cookieName ->
                val ok = userSiteRegistry.add(
                    UserSite(
                        id = "user_" + slugify(name),
                        displayName = name,
                        homepageUrl = url,
                        authKind = if (needsLogin) AuthKind.COOKIE else AuthKind.ANONYMOUS,
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
    profileRegistry: me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry,
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
        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
        if (urls.isEmpty()) null
        else {
            val bundle = cookieProvider.getCookies(emptyList(), urls)
            (listOf(name) + loginCookieCandidatesFor(site)).distinct()
                .any { bundle.value(it) != null }
        }
    } ?: loginCookieCandidatesFor(site).takeIf { it.isNotEmpty() }?.let { names ->
        val urls = collectKnownUrlsFor(site, webMountManager, profileRegistry)
        if (urls.isEmpty()) null
        else {
            val bundle = cookieProvider.getCookies(emptyList(), urls)
            names.any { bundle.value(it) != null }
        }
    }
    val oauthProviderIdLocal = site.oauthProviderId ?: site.id
    val hasToken = if (site.authKind == AuthKind.OAUTH) {
        oauthStore.getToken(oauthProviderIdLocal) != null
    } else false
    val hasCredentials = if (site.authKind == AuthKind.OAUTH) {
        oauthStore.getCredentials(oauthProviderIdLocal) != null
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
            val clearState = onSignOut
            val showClearState = clearState != null && (
                loggedIn == true || hasToken || site.authKind == AuthKind.OAUTH
                )
            if (showClearState) {
                ExperimentActionButton(
                    text = stringResource(
                        if (loggedIn == true || hasToken) R.string.setting_webmount_sign_out
                        else R.string.setting_webmount_clear_session
                    ),
                    enabled = !busy,
                    onClick = clearState,
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
    stationId: String?,
    onClearSession: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val loginUserAgent = remember(context, stationId, url) {
        WebMountUserAgents.loginUserAgent(context, stationId, url)
    }
    val state = rememberWebViewState(
        url = url,
        settings = {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            WebMountWebViewCompat.applyBrowserLikeSettings(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            builtInZoomControls = true
            displayZoomControls = false
            loginUserAgent?.let { userAgentString = it }
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
                        if (onClearSession != null) {
                            TextButton(
                                onClick = {
                                    onClearSession()
                                    state.webView?.apply {
                                        stopLoading()
                                        clearCache(true)
                                        clearHistory()
                                        clearFormData()
                                        evaluateJavascript(
                                            "try{localStorage.clear();sessionStorage.clear();}catch(e){}",
                                            null,
                                        )
                                    }
                                    state.reload()
                                },
                            ) {
                                Text(stringResource(R.string.setting_webmount_clear_session))
                            }
                        }
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
                            WebMountWebViewCompat.injectFeishuCompatibility(webView, url)
                        },
                        onProgressChanged = { webView, progress ->
                            if (progress >= 25) {
                                WebMountWebViewCompat.injectFeishuCompatibility(
                                    webView,
                                    webView?.url ?: state.currentUrl ?: url,
                                )
                            }
                        },
                        onPageFinished = { webView, finishedUrl ->
                            CookieManager.getInstance().flush()
                            WebMountWebViewCompat.injectFeishuCompatibility(
                                webView,
                                finishedUrl ?: state.currentUrl ?: url,
                            )
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
    onAdd: (name: String, url: String, needsLogin: Boolean, cookieName: String?) -> Unit,
) {
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var needsLogin by remember { mutableStateOf(true) }
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
                // Per-user feedback: most user-added sites need login (微博, X,
                // 小红书 etc.). The old "leave blank for public sites"
                // helper text confused users who didn't know their site's
                // cookie name but did know it needed login — they ended up
                // with no Sign-in button. Make it an explicit toggle.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.setting_webmount_custom_needs_login_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.setting_webmount_custom_needs_login_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = needsLogin,
                        onCheckedChange = { needsLogin = it },
                    )
                }
                OutlinedTextField(
                    value = cookieInput,
                    onValueChange = { cookieInput = it },
                    enabled = needsLogin,
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
                    onAdd(
                        nameInput.trim(),
                        urlInput.trim(),
                        needsLogin,
                        cookieInput.trim().ifBlank { null }.takeIf { needsLogin },
                    )
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
 * Compatibility wrapper — kept so the page's existing call sites stay
 * unchanged. Delegates to the shared
 * [me.rerere.rikkahub.data.agent.webmount.usersites.collectSiteUrls]
 * which also includes synthesized-profile origins (Plan-v2 review B-2).
 */
private fun collectKnownUrlsFor(
    site: UserSite,
    manager: WebMountManager,
    profileRegistry: me.rerere.rikkahub.data.agent.webmount.profile.ProfileRegistry,
): List<String> = me.rerere.rikkahub.data.agent.webmount.usersites.collectSiteUrls(site, manager, profileRegistry)

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

/**
 * Install the /webmount slash command into the global QuickMessages list AND
 * subscribe every assistant to it (assistant.quickMessageIds), since the chat
 * input's slash panel filters by per-assistant subscription — a quick message
 * that exists globally but isn't in the current assistant's quickMessageIds
 * will silently not appear.
 *
 * Idempotent: re-tapping returns false. Also back-fills the subscription if a
 * prior buggy install left an orphan QuickMessage (in global list but in no
 * assistant's id set) — in that case we keep the existing QuickMessage and
 * just patch the assistant subscriptions, and return true so the toast tells
 * the user something actually happened.
 *
 * The template is a system-style instruction that frames whatever follows
 * (the user's actual task) so the agent uses WebMount tools by default,
 * handles the "site not yet in list / not signed in" cases gracefully, and
 * doesn't fall back to generic web search just because it doesn't recognize
 * the site.
 */
private suspend fun installWebMountSlashCommand(
    settingsStore: me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator,
): Boolean {
    val current = settingsStore.settingsFlow.value
    val existing = current.quickMessages.firstOrNull { it.title.equals("webmount", ignoreCase = true) }
    val template = """
        请用 WebMount 工具帮我完成下面这件事。准则:
        1. 先调 wm_stations 看我现在哪些网站可用、登录态如何。
        2. 涉及的网站不在列表里就 wm_site_add 加进来 (默认 needs_login=true)。
        3. 如果某个站 login_status="unknown" 不要假设我没登录 — 直接 wm_open + wm_extract 试,
           只有页面真出登录墙再让我去设置页登录。
        4. 公开网站直接 wm_open + wm_extract,不需要登录。
        5. 用完一次后,如果这是个用户加的自定义站且能记下有用 selectors,顺手调 wm_profile_synthesize
           保存 hints,下次更快。

        我的任务:
    """.trimIndent()
    val quickMessage = existing ?: me.rerere.rikkahub.data.model.QuickMessage(
        title = "webmount",
        content = template + "\n",  // trailing newline so cursor lands on a fresh line
    )
    val allSubscribed = current.assistants.all { quickMessage.id in it.quickMessageIds }
    if (existing != null && allSubscribed) return false
    settingsStore.update { s ->
        val nextMessages = if (existing == null) s.quickMessages + quickMessage else s.quickMessages
        val nextAssistants = s.assistants.map { a ->
            if (quickMessage.id in a.quickMessageIds) a
            else a.copy(quickMessageIds = a.quickMessageIds + quickMessage.id)
        }
        s.copy(quickMessages = nextMessages, assistants = nextAssistants)
    }
    return true
}

private fun slugify(name: String): String =
    name.trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "site" }
        .take(40)
