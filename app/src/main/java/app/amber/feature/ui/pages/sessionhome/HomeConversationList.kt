package app.amber.feature.ui.pages.sessionhome

import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListScope
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import app.amber.feature.ui.theme.AmberTokens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.core.model.Conversation
import app.amber.feature.ui.adaptive.LocalTwoPaneLayout
import app.amber.feature.ui.components.ds.AmberContinuousShape
import app.amber.feature.ui.components.ds.AmberDepthStyle
import app.amber.feature.ui.components.ds.amberRim
import app.amber.feature.ui.components.ds.amberShadow
import app.amber.feature.ui.theme.JetBrainsMonoFamily
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalThemePack
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

internal fun LazyListScope.homeConversationItems(
    conversations: LazyPagingItems<Conversation>,
    hasConversationError: Boolean,
    rowVersions: Map<kotlin.uuid.Uuid, Long> = emptyMap(),
    homeSearchQuery: String,
    lastConversationId: String?,
    tokens: AmberTokens,
    onRetry: () -> Unit,
    onOpen: (Conversation) -> Unit,
    onDelete: (Conversation) -> Unit,
    onTogglePin: (Conversation) -> Unit,
) {
    val refreshError = conversations.loadState.refresh as? LoadState.Error
    val appendError = conversations.loadState.append as? LoadState.Error
    val isRefreshLoading = conversations.loadState.refresh is LoadState.Loading
    item(key = "home_conversations_header") {
        HomeConversationHeader()
    }

    if (hasConversationError && conversations.itemCount == 0) {
        item(key = "home_conversations_error") {
            HomeConversationErrorState(onRetry = onRetry)
        }
    } else {
        if (hasConversationError) {
            item(key = "home_conversations_error_inline") {
                HomeConversationInlineError(onRetry = onRetry)
            }
        }
        if (refreshError != null && conversations.itemCount > 0) {
            item(key = "home_conversations_refresh_error_inline") {
                HomeConversationInlineError(onRetry = { conversations.retry() })
            }
        }
        if (refreshError != null && conversations.itemCount == 0) {
            item(key = "home_conversations_refresh_error") {
                HomeConversationErrorState(onRetry = { conversations.retry() })
            }
        }
        if (!isRefreshLoading && refreshError == null && conversations.itemCount == 0 &&
            homeSearchQuery.isNotBlank()
        ) {
            item(key = "home_search_empty") {
                HomeSearchEmptyState()
            }
        } else if (!isRefreshLoading && refreshError == null && conversations.itemCount == 0) {
            item(key = "home_empty") {
                HomeEmptyState(modifier = Modifier.padding(vertical = 56.dp))
            }
        }

        items(
            count = conversations.itemCount,
            key = conversations.itemKey { it.id.toString() },
        ) { index ->
            val conversation = conversations[index] ?: return@items
            val isLastVisited = conversation.id.toString() == lastConversationId
            key(conversation.id, rowVersions[conversation.id] ?: 0L) {
                HomeSessionRow(
                    conversation = conversation,
                    tileColor = homeConversationTileColor(
                        tokens = tokens,
                        isLastVisited = isLastVisited,
                    ),
                    isLastVisited = isLastVisited,
                    isFirst = index == 0,
                    isLast = index == conversations.itemCount - 1,
                    onOpen = { onOpen(conversation) },
                    onDelete = { onDelete(conversation) },
                    onTogglePin = { onTogglePin(conversation) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = tween(
                            durationMillis = HomeSessionSwipeMotion.FadeMs,
                            easing = HomeSessionSwipeMotion.EaseOut,
                        ),
                        placementSpec = tween(
                            durationMillis = HomeSessionSwipeMotion.CollapseMs,
                            easing = HomeSessionSwipeMotion.EaseOut,
                        ),
                    ),
                )
            }
        }

        if (appendError != null) {
            item(key = "home_conversations_append_error") {
                HomeConversationInlineError(onRetry = { conversations.retry() })
            }
        }
    }
}

@Composable
private fun HomeSearchEmptyState() {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.parity_home_search_no_match),
            fontSize = 13.5.sp,
            color = tokens.ink3,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HomeConversationErrorState(onRetry: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.parity_home_conversations_error),
            fontSize = 13.5.sp,
            color = tokens.ink3,
            textAlign = TextAlign.Center,
        )
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text(stringResource(R.string.parity_home_retry))
        }
    }
}

@Composable
private fun HomeConversationInlineError(onRetry: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.parity_home_conversations_error),
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            fontSize = 12.5.sp,
            color = tokens.ink3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text(stringResource(R.string.parity_home_retry))
        }
    }
}

@Composable
internal fun HomeContinueInlineError(onRetry: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.parity_home_continue_error),
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            fontSize = 12.5.sp,
            color = tokens.ink3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.parity_home_retry))
        }
    }
}

@Composable
private fun HomeConversationHeader() {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "//",
            fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            color = tokens.accent,
        )
        Text(
            text = stringResource(R.string.amber_redesign_conversations),
            fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            color = tokens.ink2,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(tokens.line.copy(alpha = 0.46f)),
        )
    }
}

/* ------------------------------------------------------------ empty state --- */

@Composable
private fun HomeEmptyState(modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    val themePack = LocalThemePack.current
    if (themePack?.emptyArt == "character") {
        val cardRadius = themePack.design?.components?.cardRadius?.toFloat()?.dp ?: 22.dp
        val shape = AmberContinuousShape(cardRadius)
        Box(
            modifier = modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .heightIn(min = 76.dp)
                .amberShadow(shape, AmberDepthStyle.Card)
                .clip(shape)
                .background(tokens.surface, shape)
                .amberRim(shape, AmberDepthStyle.Card),
            contentAlignment = Alignment.Center,
        ) {
            HomeEmptyArtBackground(
                canvasStyle = themePack.canvasStyle,
                isDark = tokens.isDark,
                modifier = Modifier.matchParentSize(),
            )
            HomeEmptyStateContent(modifier = Modifier.fillMaxWidth())
        }
    } else {
        HomeEmptyStateContent(modifier = modifier.fillMaxWidth())
    }
}

@Composable
private fun HomeEmptyStateContent(modifier: Modifier) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "// 0 results",
            fontFamily = homeChromeFontFamily(JetBrainsMonoFamily),
            fontSize = 12.sp,
            color = tokens.ink4,
            textAlign = TextAlign.Center,
        )
        Text(
            // Two-pane moves the new-session button from the bottom corner into the header.
            text = stringResource(
                if (LocalTwoPaneLayout.current != null) R.string.session_home_empty_hint_two_pane
                else R.string.session_home_empty_hint,
            ),
            fontSize = 13.5.sp,
            color = tokens.ink3,
            textAlign = TextAlign.Center,
        )
    }
}
