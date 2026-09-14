package app.amber.feature.ui.pages.zcode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.agent.R
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.pages.webmount.WebMountSessionPage
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.webmount.primitives.WebMountLeaseResult
import app.amber.feature.webmount.primitives.WebMountOwner
import app.amber.feature.webmount.primitives.WebMountSessionOwner
import app.amber.feature.webmount.primitives.WebViewPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.util.UUID

/**
 * Prepares (or resumes) the pooled browser for the ZCode connection, then
 * delegates the actual page UI to the regular WebMount session surface.
 *
 * The first load is performed under a short HUMAN lease because this route is
 * an explicit user action. The lease is released before the session page is
 * shown; that page takes the lease once, while ordinary WebMount entries stay
 * read-only until the user taps Take over.
 */
@Composable
fun ZCodeSessionPage(
    url: String,
    store: ZCodeUrlStore = koinInject(),
    owner: WebMountSessionOwner = koinInject(),
    pool: WebViewPool = koinInject(),
) {
    val navController = LocalNavController.current
    val normalizedUrl = remember(url) { normalizeZCodeUrl(url) }
    var preparation by remember(url) {
        mutableStateOf<ZCodePreparation>(ZCodePreparation.Preparing)
    }

    LaunchedEffect(normalizedUrl, store, owner, pool) {
        preparation = if (normalizedUrl == null) {
            ZCodePreparation.Failed(R.string.zcode_invalid_url)
        } else {
            prepareZCodeSession(
                url = normalizedUrl,
                store = store,
                owner = owner,
                pool = pool,
            )
        }
    }

    when (val state = preparation) {
        ZCodePreparation.Preparing -> ZCodePreparationView(
            message = stringResource(R.string.zcode_preparing_session),
            showProgress = true,
        )

        is ZCodePreparation.Failed -> ZCodePreparationView(
            message = stringResource(state.messageRes),
            showProgress = false,
        )

        is ZCodePreparation.Ready -> WebMountSessionPage(
            sessionId = state.sessionId,
            initialHumanControl = true,
            owner = owner,
        )
    }

    // Keep the route-level back action available while the pooled session is
    // being prepared. Once WebMountSessionPage is visible its own handler
    // takes precedence and preserves WebView history first.
    if (preparation !is ZCodePreparation.Ready) {
        BackHandler { navController.popBackStack() }
    }
}

private sealed interface ZCodePreparation {
    data object Preparing : ZCodePreparation
    data class Ready(val sessionId: String) : ZCodePreparation
    data class Failed(@androidx.annotation.StringRes val messageRes: Int) : ZCodePreparation
}

private suspend fun prepareZCodeSession(
    url: String,
    store: ZCodeUrlStore,
    owner: WebMountSessionOwner,
    pool: WebViewPool,
): ZCodePreparation {
    val selected = try {
        store.connectionFlow.first()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        return ZCodePreparation.Failed(R.string.zcode_session_unavailable)
    }
    val liveStoredSession = selected.sessionId
        ?.takeIf { selected.url == url }
        ?.takeIf { owner.metadata(it) != null && pool.peek(it) != null }
    if (liveStoredSession != null) {
        return ZCodePreparation.Ready(liveStoredSession)
    }

    // A route can be opened directly (for example from a deep link), so make
    // the route URL the selected connection before creating a new session.
    if (selected.url != url) {
        try {
            store.save(url)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            return ZCodePreparation.Failed(R.string.zcode_session_unavailable)
        }
    }

    val sessionId = "wm_zcode_${UUID.randomUUID()}"
    val acquired = try {
        owner.acquire(
            sessionId = sessionId,
            actor = WebMountOwner.HUMAN,
        )
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        return ZCodePreparation.Failed(R.string.zcode_session_unavailable)
    }
    val lease = when (acquired) {
        is WebMountLeaseResult.Granted -> acquired.lease
        is WebMountLeaseResult.Rejected -> {
            return ZCodePreparation.Failed(R.string.zcode_session_unavailable)
        }
    }

    var bound = false
    return try {
        // SessionHandle performs this callback immediately before the WebView
        // side effect. WebMountSessionOwner supplies the owner CAS guard; the
        // HUMAN-aware implementation keeps a page leave/background race from
        // dispatching a stale navigation.
        lease.handle.loadUrlNoWait(
            url,
            dispatchWithLease = { action ->
                owner.dispatchIfHumanActive(
                    leaseId = lease.leaseId,
                    action = action,
                )
            },
        )
        val recorded = store.recordSession(url, sessionId)
        val current = store.connectionFlow.first()
        bound = recorded && current.url == url && current.sessionId == sessionId
        if (bound) {
            ZCodePreparation.Ready(sessionId)
        } else {
            ZCodePreparation.Failed(R.string.zcode_connection_changed)
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        ZCodePreparation.Failed(R.string.zcode_session_unavailable)
    } finally {
        owner.release(lease.leaseId, "zcode initial page prepared")
        if (!bound) {
            // This id was created by this preparation attempt and was never
            // published as the selected connection. Clean it up without
            // touching a newer stored/live session.
            withContext(NonCancellable) {
                owner.close(sessionId, "zcode initial page preparation failed")
            }
        }
    }
}

@Composable
private fun ZCodePreparationView(
    message: String,
    showProgress: Boolean,
) {
    val t = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = Modifier.fillMaxSize().amberCanvas().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AmberCard {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (showProgress) {
                    CircularProgressIndicator()
                }
                Text(
                    text = message,
                    style = type.secondary,
                    color = if (showProgress) t.ink3 else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
