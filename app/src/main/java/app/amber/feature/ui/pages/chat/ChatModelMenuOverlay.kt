package app.amber.feature.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.feature.ui.components.ai.TopModelMenu
import app.amber.feature.ui.overlay.HostInAppModalOverlay
import kotlin.uuid.Uuid

/**
 * Production wiring for the chat model menu: the menu owns its animation/gesture handling, but it
 * is published into the app-level modal layer so its scrim covers the whole interactive window —
 * the chat header (back), the composer and the two-pane session list (settings entry). The panel
 * itself stays anchored under the chat header and inside the chat pane.
 *
 * Kept as one composable so tests can drive the same host/registration path the app uses instead
 * of recreating an overlay fixture.
 *
 * The published lambda reads its inputs through [rememberUpdatedState] instead of capturing the
 * parameters directly. Compose memoizes composable lambdas, and on the two-pane NavDisplay the
 * memoized lambda kept capturing the first `open=false` value, so the host rendered nothing after
 * the header toggle. A stable state holder + an explicit revision key keeps the published content
 * current on every real composition.
 */
@Composable
internal fun ChatModelMenuOverlay(
    open: Boolean,
    providers: List<ProviderSetting>,
    currentProviderId: Uuid?,
    currentModelId: Uuid?,
    reasoningLevel: ReasoningLevel?,
    onSelect: (Model) -> Unit,
    onClose: () -> Unit,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    panelTopInset: Dp,
    panelStartInset: Dp,
    header: (@Composable () -> Unit)? = null,
) {
    val latest = rememberUpdatedState(
        ChatModelMenuOverlayContent(
            open = open,
            providers = providers,
            currentProviderId = currentProviderId,
            currentModelId = currentModelId,
            reasoningLevel = reasoningLevel,
            onSelect = onSelect,
            onClose = onClose,
            onUpdateReasoningLevel = onUpdateReasoningLevel,
            panelTopInset = panelTopInset,
            panelStartInset = panelStartInset,
            header = header,
        ),
    )
    HostInAppModalOverlay(
        revision = ChatModelMenuRevision(
            open = open,
            providers = providers,
            currentProviderId = currentProviderId,
            currentModelId = currentModelId,
            reasoningLevel = reasoningLevel,
            panelTopInset = panelTopInset,
            panelStartInset = panelStartInset,
        ),
    ) {
        val content = latest.value
        TopModelMenu(
            open = content.open,
            providers = content.providers,
            modelType = ModelType.CHAT,
            currentProviderId = content.currentProviderId,
            currentModelId = content.currentModelId,
            onSelect = content.onSelect,
            onClose = content.onClose,
            reasoningLevel = content.reasoningLevel,
            onUpdateReasoningLevel = content.onUpdateReasoningLevel,
            panelTopInset = content.panelTopInset,
            panelStartInset = content.panelStartInset,
            header = content.header,
        )
    }
}

private class ChatModelMenuOverlayContent(
    val open: Boolean,
    val providers: List<ProviderSetting>,
    val currentProviderId: Uuid?,
    val currentModelId: Uuid?,
    val reasoningLevel: ReasoningLevel?,
    val onSelect: (Model) -> Unit,
    val onClose: () -> Unit,
    val onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    val panelTopInset: Dp,
    val panelStartInset: Dp,
    val header: (@Composable () -> Unit)?,
)

/** Host-visible inputs; changing any of them must make the app-level layer recompose. */
private data class ChatModelMenuRevision(
    val open: Boolean,
    val providers: List<ProviderSetting>,
    val currentProviderId: Uuid?,
    val currentModelId: Uuid?,
    val reasoningLevel: ReasoningLevel?,
    val panelTopInset: Dp,
    val panelStartInset: Dp,
)
