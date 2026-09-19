package app.amber.feature.ui.pages.webmount

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Hand
import com.composables.icons.lucide.CircleAlert
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.webmount.primitives.SessionHandle
import app.amber.feature.webmount.primitives.NetworkLog
import app.amber.feature.webmount.primitives.WebMountLease
import app.amber.feature.webmount.primitives.WebMountLeaseFailure
import app.amber.feature.webmount.primitives.WebMountLeaseResult
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionMetadata
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.webmount.primitives.WebViewPool
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.X
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicReference

/**
 * Human-facing view of one pooled WebMount session.
 *
 * The page never creates a WebView. It first mounts the exact live handle from
 * the pool in read-only mode, then claims a HUMAN lease only after the user
 * asks to take over. Leaving the route or sending the activity to the
 * background releases that lease and detaches the view so the agent can
 * continue safely.
 */
@Composable
fun WebMountSessionPage(
    sessionId: String,
    reopen: Boolean = false,
    owner: WebMountSessionOwner = koinInject(),
    /** ZCode opens an explicitly interactive page; other callers stay read-only. */
    initialHumanControl: Boolean = false,
) {
    val navController = LocalNavController.current
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sessions by owner.sessions.collectAsStateWithLifecycle()
    val metadata = sessions.firstOrNull { it.sessionId == sessionId }
    val latestMetadata = rememberUpdatedState(metadata)
    val leaseState = remember(sessionId) { mutableStateOf<WebMountLease?>(null) }
    val leaseRef = remember(sessionId) { AtomicReference<WebMountLease?>(null) }
    val acquireMutex = remember(sessionId) { Mutex() }
    val failureState = remember(sessionId) { mutableStateOf<WebMountLeaseFailure?>(null) }
    var acquiring by remember(sessionId) { mutableStateOf(false) }
    var closing by remember(sessionId) { mutableStateOf(false) }
    var selectedPopupId by remember(sessionId) { mutableStateOf<String?>(null) }
    var popupMenuOpen by remember(sessionId) { mutableStateOf(false) }
    var reopenConsumed by remember(sessionId) { mutableStateOf(false) }
    var initialHumanControlConsumed by remember(sessionId) { mutableStateOf(false) }
    var confirmCloseAgent by remember(sessionId) { mutableStateOf(false) }
    var isForeground by remember(lifecycleOwner, sessionId) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    fun releaseHuman(reason: String) {
        val lease = leaseRef.getAndSet(null) ?: return
        owner.release(lease.leaseId, reason)
        leaseState.value = null
        selectedPopupId = null
        popupMenuOpen = false
    }

    fun hasCurrentHumanLease(): Boolean {
        val lease = leaseRef.get() ?: return false
        val currentMetadata = latestMetadata.value ?: return false
        return currentMetadata.sessionId == lease.sessionId &&
            currentMetadata.owner == WebMountOwner.HUMAN &&
            !currentMetadata.needsReopen
    }

    suspend fun acquireHuman(forceReopen: Boolean): Boolean = acquireMutex.withLock {
            if (acquiring) return@withLock false
            if (leaseRef.get() != null) {
                if (hasCurrentHumanLease()) return@withLock true
                releaseHuman("human lease no longer active")
            }
            val currentMetadata = latestMetadata.value
            if (currentMetadata == null) {
                failureState.value = WebMountLeaseFailure.SESSION_UNAVAILABLE
                return@withLock false
            }
            acquiring = true
            try {
                val result = if (forceReopen) {
                    owner.reopen(
                        sessionId = sessionId,
                        conversationId = currentMetadata.conversationId,
                    )
                } else {
                    owner.acquire(
                        sessionId = sessionId,
                        actor = WebMountOwner.HUMAN,
                        conversationId = currentMetadata.conversationId,
                    )
                }
                when (result) {
                    is WebMountLeaseResult.Granted -> {
                        if (!isForeground) {
                            owner.release(
                                result.lease.leaseId,
                                "activity backgrounded before human claim completed",
                            )
                            false
                        } else {
                            leaseRef.set(result.lease)
                            leaseState.value = result.lease
                            failureState.value = null
                            selectedPopupId = null
                            true
                        }
                    }
                    is WebMountLeaseResult.Rejected -> {
                        failureState.value = result.failure
                        false
                    }
                }
            } finally {
                acquiring = false
            }
    }

    fun closeSession() {
        if (closing) return
        closing = true
        releaseHuman("human closed session")
        scope.launchClose(owner, sessionId) {
            navController.popBackStack()
        }
    }

    // Ordinary entry is a read-only watch. Reopen is an explicit action from
    // the task card; ZCode opts into one initial HUMAN claim because opening
    // its page is itself an explicit interactive action. A rejected claim is
    // kept visible in the header and is never presented as a successful claim.
    LaunchedEffect(
        sessionId,
        reopen,
        initialHumanControl,
        isForeground,
        metadata?.sessionId,
    ) {
        if (!isForeground || metadata == null) return@LaunchedEffect
        if (reopen && !reopenConsumed) {
            reopenConsumed = acquireHuman(forceReopen = true)
        } else if (initialHumanControl && !initialHumanControlConsumed) {
            initialHumanControlConsumed = acquireHuman(forceReopen = false)
        }
    }

    DisposableEffect(lifecycleOwner, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    isForeground = false
                    releaseHuman("activity backgrounded")
                }
                Lifecycle.Event.ON_START -> isForeground = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseHuman("page left")
        }
    }

    LaunchedEffect(metadata, leaseState.value?.leaseId) {
        if (leaseRef.get() != null && !hasCurrentHumanLease()) {
            releaseHuman("human lease no longer active")
        }
    }

    val pool: WebViewPool = koinInject()
    val previewHandle = remember(
        sessionId,
        isForeground,
        metadata,
        leaseState.value?.leaseId,
    ) {
        if (isForeground && leaseState.value == null && metadata?.needsReopen != true) {
            pool.peek(sessionId)
        } else {
            null
        }
    }
    val handle = leaseState.value?.handle ?: previewHandle
    val interactive = leaseState.value != null && hasCurrentHumanLease()
    val loadState = if (handle == null) {
        SessionHandle.LoadState.idle()
    } else {
        handle.loadState.collectAsStateWithLifecycle().value
    }
    val popups = if (handle == null) {
        emptyList()
    } else {
        handle.popups.collectAsStateWithLifecycle().value
    }
    val dialogs = if (handle == null) {
        emptyList()
    } else {
        handle.jsDialogs.collectAsStateWithLifecycle().value
    }
    val selectedPopup = popups.firstOrNull { it.popupId == selectedPopupId }
    val visibleWebView = selectedPopup?.webView ?: handle?.webView

    LaunchedEffect(popups, selectedPopupId) {
        if (selectedPopupId != null && selectedPopup == null) {
            selectedPopupId = null
        }
    }

    BackHandler(
        enabled = selectedPopupId != null ||
            (interactive && visibleWebView?.canGoBack() == true),
    ) {
        if (selectedPopupId != null) {
            selectedPopupId = null
        } else if (interactive) {
            visibleWebView?.goBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                expandedHeight = 52.dp,
                title = {
                    Text(
                        text = metadata?.title?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.parity_webmount_page_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = type.screenTitle,
                        color = t.ink,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (handle != null) {
                        IconButton(
                            onClick = { visibleWebView?.reload() },
                            enabled = interactive && !acquiring,
                        ) {
                            Icon(
                                imageVector = Lucide.RefreshCw,
                                contentDescription = stringResource(R.string.parity_webmount_refresh),
                            )
                        }
                        if (popups.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { popupMenuOpen = true }) {
                                    Icon(
                                        imageVector = Lucide.EllipsisVertical,
                                        contentDescription = stringResource(R.string.parity_webmount_popup_menu),
                                    )
                                }
                                PopupSelectorMenu(
                                    expanded = popupMenuOpen,
                                    popups = popups,
                                    selectedPopupId = selectedPopupId,
                                    onSelect = { id ->
                                        selectedPopupId = id
                                        popupMenuOpen = false
                                    },
                                    onDismiss = { popupMenuOpen = false },
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = {
                            if (metadata?.owner == WebMountOwner.AGENT) {
                                confirmCloseAgent = true
                            } else {
                                closeSession()
                            }
                        },
                        enabled = !closing,
                    ) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = stringResource(R.string.parity_webmount_close),
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = t.bg,
                    scrolledContainerColor = t.bg,
                    titleContentColor = t.ink,
                    navigationIconContentColor = t.ink2,
                    actionIconContentColor = t.ink2,
                ),
            )
        },
        modifier = Modifier.amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            SessionHeader(
                metadata = metadata,
                loadState = loadState,
                selectedPopup = selectedPopup,
                lease = leaseState.value.takeIf { interactive },
                acquiring = acquiring,
                closing = closing,
                failure = failureState.value,
                onTakeover = {
                    scope.launch {
                        acquireHuman(forceReopen = false)
                    }
                },
                onRelease = { releaseHuman("human returned session") },
                onReopen = {
                    scope.launch {
                        if (acquireHuman(forceReopen = true)) {
                            reopenConsumed = true
                        }
                    }
                },
            )

            if (handle != null && visibleWebView != null) {
                // Keep the progress slot reserved so the WebView does not jump
                // when loading starts or finishes.
                val loading = loadState.status == SessionHandle.LoadStatus.LOADING
                LinearProgressIndicator(
                    progress = { if (loading) (loadState.progress / 100f).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (loading) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                    trackColor = if (loading) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                )
                PooledWebView(
                    webView = visibleWebView,
                    readOnly = !interactive,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    key = visibleWebView,
                )
            } else if (metadata == null) {
                MissingSessionState(modifier = Modifier.weight(1f))
            } else {
                // Keep this area quiet while a HUMAN claim is pending or
                // rejected. The header owns the actionable explanation.
                Box(modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }

    if (confirmCloseAgent) {
        AlertDialog(
            onDismissRequest = { confirmCloseAgent = false },
            title = { Text(stringResource(R.string.parity_webmount_close_agent_title)) },
            text = { Text(stringResource(R.string.parity_webmount_close_agent_message)) },
            dismissButton = {
                TextButton(onClick = { confirmCloseAgent = false }) {
                    Text(stringResource(R.string.parity_webmount_dialog_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCloseAgent = false
                        closeSession()
                    },
                ) {
                    Text(stringResource(R.string.parity_webmount_close_agent_confirm))
                }
            },
        )
    }

    if (interactive) {
        dialogs.firstOrNull()?.let { dialog ->
            JsDialog(
                dialog = dialog,
                onResolve = { confirmed, value ->
                    handle?.resolveJsDialog(dialog.dialogId, confirmed, value)
                },
            )
        }
    }
}

@Composable
internal fun SessionHeader(
    metadata: WebMountSessionMetadata?,
    loadState: SessionHandle.LoadState,
    selectedPopup: SessionHandle.PopupInfo?,
    lease: WebMountLease?,
    acquiring: Boolean,
    closing: Boolean,
    failure: WebMountLeaseFailure?,
    onTakeover: () -> Unit,
    onRelease: () -> Unit,
    onReopen: () -> Unit,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    val popupFallback = stringResource(R.string.parity_webmount_popup)
    val ownerText = when {
        metadata == null -> stringResource(R.string.parity_webmount_session_missing)
        metadata.needsReopen -> stringResource(R.string.parity_webmount_session_needs_reopen)
        metadata.owner == WebMountOwner.AGENT -> stringResource(R.string.parity_webmount_session_agent_owned)
        metadata.owner == WebMountOwner.HUMAN -> stringResource(R.string.parity_webmount_session_human_owned)
        else -> when (loadState.status) {
            SessionHandle.LoadStatus.LOADING -> stringResource(R.string.parity_webmount_session_loading)
            SessionHandle.LoadStatus.READY -> stringResource(R.string.parity_webmount_session_ready)
            SessionHandle.LoadStatus.FAILED -> stringResource(R.string.parity_webmount_session_failed)
            SessionHandle.LoadStatus.IDLE -> stringResource(R.string.parity_webmount_session_available)
        }
    }
    val detail = when (failure) {
        WebMountLeaseFailure.OWNER_CONFLICT -> stringResource(R.string.parity_webmount_owner_conflict)
        WebMountLeaseFailure.NEEDS_REOPEN -> stringResource(R.string.parity_webmount_needs_reopen_detail)
        WebMountLeaseFailure.SESSION_UNAVAILABLE,
        WebMountLeaseFailure.MISSING_CONVERSATION,
        WebMountLeaseFailure.MISSING_RUN,
        WebMountLeaseFailure.CONVERSATION_MISMATCH,
        WebMountLeaseFailure.RUN_MISMATCH -> stringResource(R.string.parity_webmount_takeover_failed)
        null -> null
    }

    val address = selectedPopup?.url
        ?.takeIf { it.isNotBlank() }
        ?.let(NetworkLog::redactedUrl)
        ?: selectedPopup?.title?.takeIf { it.isNotBlank() }
        ?: metadata?.redactedUrl?.takeIf { it.isNotBlank() }
        ?: popupFallback
    val addressScroll = rememberScrollState()
    LaunchedEffect(address) { addressScroll.scrollTo(0) }
    val needsReopen = metadata?.needsReopen == true || failure == WebMountLeaseFailure.NEEDS_REOPEN
    val pageFailed = loadState.status == SessionHandle.LoadStatus.FAILED
    val statusIcon = when {
        needsReopen || pageFailed || metadata == null -> Lucide.CircleAlert
        lease != null -> Lucide.Hand
        metadata.owner == WebMountOwner.AGENT -> Lucide.Bot
        else -> Lucide.Globe
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = ownerText,
                tint = if (pageFailed) MaterialTheme.colorScheme.error else t.ink2,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = address,
                    modifier = Modifier.horizontalScroll(addressScroll),
                    maxLines = 1,
                    softWrap = false,
                    style = type.meta,
                    color = t.ink2,
                )
            }
            if (metadata != null) {
                TextButton(
                    onClick = when {
                        lease != null -> onRelease
                        needsReopen -> onReopen
                        else -> onTakeover
                    },
                    enabled = !acquiring && !closing,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(
                            when {
                                lease != null -> R.string.parity_webmount_release
                                needsReopen -> R.string.parity_webmount_session_reopen
                                else -> R.string.parity_webmount_takeover
                            },
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
        val notice = detail ?: when {
            needsReopen -> stringResource(R.string.parity_webmount_session_needs_reopen)
            pageFailed -> stringResource(R.string.parity_webmount_session_failed)
            metadata?.owner == WebMountOwner.AGENT ->
                stringResource(R.string.parity_webmount_session_agent_owned)
            else -> null
        }
        notice?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = type.secondary,
                color = if (detail != null || pageFailed) {
                    MaterialTheme.colorScheme.error
                } else {
                    t.ink3
                },
            )
        }
        Hairline()
    }
}

@Composable
private fun PopupSelectorMenu(
    expanded: Boolean,
    popups: List<SessionHandle.PopupInfo>,
    selectedPopupId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val popupFallback = stringResource(R.string.parity_webmount_popup)
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.parity_webmount_primary_page)) },
            onClick = { onSelect(null) },
        )
        popups.forEach { popup ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = popup.title?.takeIf { it.isNotBlank() }
                                ?: popup.url.orEmpty()
                                    .ifBlank { popupFallback },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (popup.popupId == selectedPopupId) {
                            Text(
                                text = stringResource(R.string.parity_webmount_session_ready),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                onClick = { onSelect(popup.popupId) },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PooledWebView(
    webView: WebView,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    key: Any,
) {
    androidx.compose.runtime.key(key) {
        DisposableEffect(webView, readOnly) {
            val wasFocusable = webView.isFocusable
            val wasFocusableInTouchMode = webView.isFocusableInTouchMode
            val wasClickable = webView.isClickable
            val wasLongClickable = webView.isLongClickable
            if (readOnly) {
                // The pooled view remains visible, but must not accept touch,
                // keyboard focus, long-press, or accessibility click actions
                // until the owner has granted a HUMAN lease.
                webView.isFocusable = false
                webView.isFocusableInTouchMode = false
                webView.isClickable = false
                webView.isLongClickable = false
                webView.clearFocus()
            }
            webView.setOnTouchListener(if (readOnly) {
                View.OnTouchListener { _, _ -> true }
            } else {
                null
            })
            onDispose {
                webView.setOnTouchListener(null)
                webView.isFocusable = wasFocusable
                webView.isFocusableInTouchMode = wasFocusableInTouchMode
                webView.isClickable = wasClickable
                webView.isLongClickable = wasLongClickable
            }
        }
        DisposableEffect(webView) {
            onDispose {
                (webView.parent as? ViewGroup)?.removeView(webView)
            }
        }
        AndroidView(
            factory = { _ ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                webView
            },
            modifier = modifier,
            update = { view ->
                if (view.parent == null) {
                    view.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
        )
    }
}

@Composable
private fun MissingSessionState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        AmberCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.parity_webmount_session_missing),
                modifier = Modifier.padding(24.dp),
                style = LocalAmberType.current.secondary,
                color = LocalAmberTokens.current.ink3,
            )
        }
    }
}

@Composable
private fun JsDialog(
    dialog: SessionHandle.JsDialogInfo,
    onResolve: (confirmed: Boolean, value: String?) -> Unit,
) {
    var promptValue by remember(dialog.dialogId) {
        mutableStateOf(dialog.defaultValue.orEmpty())
    }
    val title = when (dialog.type) {
        SessionHandle.JsDialogType.ALERT -> stringResource(R.string.parity_webmount_dialog_alert_title)
        SessionHandle.JsDialogType.CONFIRM -> stringResource(R.string.parity_webmount_dialog_confirm_title)
        SessionHandle.JsDialogType.PROMPT -> stringResource(R.string.parity_webmount_dialog_prompt_title)
    }
    AlertDialog(
        onDismissRequest = { onResolve(false, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(dialog.message)
                if (dialog.type == SessionHandle.JsDialogType.PROMPT) {
                    OutlinedTextField(
                        value = promptValue,
                        onValueChange = { promptValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.parity_webmount_dialog_input_hint)) },
                        singleLine = false,
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }
        },
        dismissButton = if (dialog.type == SessionHandle.JsDialogType.ALERT) {
            null
        } else {
            { TextButton(onClick = { onResolve(false, null) }) { Text(stringResource(R.string.parity_webmount_dialog_cancel)) } }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onResolve(
                        true,
                        promptValue.takeIf { dialog.type == SessionHandle.JsDialogType.PROMPT },
                    )
                },
            ) {
                Text(stringResource(R.string.parity_webmount_dialog_ok))
            }
        },
    )
}

private fun CoroutineScope.launchClose(
    owner: WebMountSessionOwner,
    sessionId: String,
    onComplete: () -> Unit,
) = launch {
    owner.close(sessionId, "human closed session")
    onComplete()
}
