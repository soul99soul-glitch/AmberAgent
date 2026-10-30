package app.amber.feature.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The single app-level modal layer.
 *
 * A route can own a modal (open state, data, callbacks) and publish its content here instead of
 * composing it inside the route's own bounds. The shell renders the layer above every route, so
 * the modal's scrim also covers chrome the route does not own: the chat header, the composer and
 * the two-pane session list (whose settings entry navigates). Without this, the first tap after
 * opening a modal can reach that chrome instead of dismissing the modal.
 *
 * The shell does not know anything about the modal itself; it only hosts whatever the owning
 * route publishes, so the open state stays with the route that owns it.
 */
@Stable
internal class AppModalOverlayHostState {
    /**
     * The pair is intentional: Compose memoizes composable lambdas, and a remembered wrapper
     * lambda kept publishing the first `open=false` content on the real two-pane NavDisplay while
     * the route state had already flipped. Re-publishing the current lambda plus an explicit
     * [revision] makes the app-level layer recompose whenever the route's visible state changes.
     */
    internal var published: PublishedAppModalOverlay? by mutableStateOf(null)
        private set
    private var owner: Any? = null

    internal fun show(owner: Any, revision: Any?, content: @Composable () -> Unit) {
        this.owner = owner
        published = PublishedAppModalOverlay(revision, content)
    }

    internal fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        published = null
    }
}

internal data class PublishedAppModalOverlay(
    val revision: Any?,
    val content: @Composable () -> Unit,
)

internal val LocalAppModalOverlayHost = staticCompositionLocalOf<AppModalOverlayHostState?> { null }

@Composable
internal fun rememberAppModalOverlayHostState(): AppModalOverlayHostState =
    remember { AppModalOverlayHostState() }

/**
 * Renders the published modal above the routes. With no modal published the host emits no layout,
 * so the app stays fully interactive (no invisible full-screen layer stealing touches).
 */
@Composable
internal fun AppModalOverlayHost(state: AppModalOverlayHostState) {
    val published = state.published ?: return
    Box(Modifier.fillMaxSize()) {
        published.content()
    }
}

/**
 * Publishes [content] into the app-level modal layer for as long as this composable is composed.
 * The route keeps ownership of the modal state; the shell only renders it. When there is no host
 * (isolated previews/tests that render a route on its own) the content composes in place so the
 * route still works standalone.
 *
 * [revision] must change whenever the modal's visible state changes (for the model menu: its open
 * flag and the panel insets). It makes the app-level layer recompose even if Compose memoized the
 * content lambda; the lambda itself must read dynamic values through state, not capture them.
 */
@Composable
internal fun HostInAppModalOverlay(
    revision: Any?,
    content: @Composable () -> Unit,
) {
    val host = LocalAppModalOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val owner = remember { Any() }
    SideEffect { host.show(owner, revision, content) }
    DisposableEffect(host, owner) {
        onDispose { host.clear(owner) }
    }
}
