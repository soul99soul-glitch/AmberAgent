package app.amber.feature.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.scene.SinglePaneSceneStrategy
import app.amber.agent.R
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.theme.LocalAmberTokens

/**
 * Tablet / unfolded-landscape layout: the session home stays on the left as a narrow list
 * while the open chat fills the right. Phones and portrait windows keep the single-pane stack;
 * the back stack is the same in both, so rotating or folding only changes the scene.
 */
internal enum class TwoPaneRole { List, Detail }

private const val TWO_PANE_ROLE_METADATA_KEY = "app.amber.adaptive.twoPaneRole"

internal fun twoPaneMetadata(role: TwoPaneRole): Map<String, Any> =
    mapOf(TWO_PANE_ROLE_METADATA_KEY to role)

/** Non-null while a route is laid out inside the two-pane scene. */
@Immutable
internal data class TwoPaneLayout(val listWidth: Dp, val detailWidth: Dp)

internal val LocalTwoPaneLayout = staticCompositionLocalOf<TwoPaneLayout?> { null }

/**
 * Tall enough for both panes, and either wide outright or wide in landscape. The landscape
 * floor keeps the chat pane at least as wide as the narrowest list pane.
 */
internal fun isTwoPaneWindow(widthDp: Float, heightDp: Float): Boolean =
    heightDp >= 480f && (widthDp >= 840f || (widthDp >= 720f && widthDp > heightDp))

/**
 * Roughly a third of the window, never a half: iPad-sidebar proportions. The 360dp floor is the
 * phone width the home header and feature rail are designed for.
 */
internal fun twoPaneListWidth(availableWidth: Dp): Dp =
    (availableWidth * 0.3f).coerceIn(360.dp, 400.dp)

internal data class TwoPaneSlots(val listIndex: Int, val detailIndex: Int?)

/** The last list entry pairs with the top entry only when everything above the list is detail. */
internal fun twoPaneSlots(roles: List<TwoPaneRole?>): TwoPaneSlots? {
    val listIndex = roles.lastIndexOf(TwoPaneRole.List)
    if (listIndex < 0) return null
    val above = roles.subList(listIndex + 1, roles.size)
    if (!above.all { it == TwoPaneRole.Detail }) return null
    return TwoPaneSlots(listIndex, detailIndex = if (above.isEmpty()) null else roles.lastIndex)
}

private class TwoPaneSceneStrategy : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(
        entries: List<NavEntry<NavKey>>,
    ): Scene<NavKey>? {
        val slots = twoPaneSlots(entries.map { it.metadata[TWO_PANE_ROLE_METADATA_KEY] as? TwoPaneRole })
            ?: return null
        val list = entries[slots.listIndex]
        val detail = slots.detailIndex?.let(entries::get)
        return TwoPaneScene(
            list = list,
            detail = detail,
            previousEntries = if (detail != null) entries.dropLast(1) else entries.subList(0, slots.listIndex),
        )
    }
}

/** Scene strategies for [androidx.navigation3.ui.NavDisplay]: two-pane when the window allows it, else single. */
@Composable
internal fun rememberAdaptiveSceneStrategies(): List<SceneStrategy<NavKey>> {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    // Derived so dragging a free-form window only recomposes the routes when the layout flips.
    val twoPane by remember(windowInfo, density) {
        derivedStateOf {
            val size = windowInfo.containerSize
            with(density) { isTwoPaneWindow(size.width.toDp().value, size.height.toDp().value) }
        }
    }
    return remember(twoPane) {
        if (twoPane) listOf(TwoPaneSceneStrategy(), SinglePaneSceneStrategy())
        else listOf(SinglePaneSceneStrategy())
    }
}

private class TwoPaneScene(
    private val list: NavEntry<NavKey>,
    private val detail: NavEntry<NavKey>?,
    override val previousEntries: List<NavEntry<NavKey>>,
) : Scene<NavKey> {
    // One key for every detail so switching or closing a chat swaps the right pane in place
    // instead of sliding the whole scene (list included).
    override val key: Any = TwoPaneScene::class to list.contentKey
    override val entries: List<NavEntry<NavKey>> = listOfNotNull(list, detail)

    override val content: @Composable () -> Unit = {
        val tokens = LocalAmberTokens.current
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(tokens.bg)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            // A whole device pixel: a 1dp rule lands on fractional pixels and reads soft and thick.
            val hairline = with(LocalDensity.current) { 1f.toDp() }
            val listWidth = twoPaneListWidth(maxWidth)
            val layout = TwoPaneLayout(listWidth = listWidth, detailWidth = maxWidth - listWidth - hairline)
            CompositionLocalProvider(LocalTwoPaneLayout provides layout) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(listWidth).fillMaxHeight()) {
                        list.Content()
                    }
                    Box(Modifier.width(hairline).fillMaxHeight().background(tokens.line2))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (detail != null) {
                            key(detail.contentKey) { detail.Content() }
                        } else {
                            TwoPaneEmptyDetail()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TwoPaneEmptyDetail() {
    val tokens = LocalAmberTokens.current
    Box(Modifier.fillMaxSize().amberCanvas(), contentAlignment = Alignment.Center) {
        Icon(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
                .size(width = 120.dp, height = 31.dp),
            painter = painterResource(R.drawable.amber_wordmark),
            contentDescription = null,
            tint = tokens.ink3.copy(alpha = 0.6f),
        )
    }
}
