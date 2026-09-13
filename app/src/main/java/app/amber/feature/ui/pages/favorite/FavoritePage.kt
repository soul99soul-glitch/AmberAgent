package app.amber.feature.ui.pages.favorite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.R
import app.amber.core.utils.navigateToChatPage
import app.amber.core.utils.plus
import app.amber.core.utils.toLocalDateTime
import app.amber.feature.ui.components.ds.AmberCard
import app.amber.feature.ui.components.ds.Hairline
import app.amber.feature.ui.components.ds.SectionLabel
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.pressable
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trash2
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

@Composable
fun FavoritePage(vm: FavoriteVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = vm.nodeFavorites.collectAsStateWithLifecycle().value
    val favoriteRemovedText = stringResource(R.string.favorite_page_removed)
    val undoText = stringResource(R.string.history_page_undo)

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.favorite_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        modifier = Modifier
            .amberCanvas()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FavoriteHeader(count = favorites.size) }
            item {
                SectionLabel(
                    text = stringResource(R.string.favorite_page_title),
                    modifier = Modifier.padding(top = 20.dp, start = 2.dp, bottom = 2.dp),
                )
            }
            if (favorites.isEmpty()) {
                item { FavoriteEmptyState() }
            } else {
                itemsIndexed(
                    items = favorites,
                    key = { _, item -> item.refKey },
                ) { index, item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (index > 0) Hairline()
                        SwipeableFavoriteCard(
                            item = item,
                            groupedFirst = index == 0,
                            groupedLast = index == favorites.lastIndex,
                            onClick = {
                                navigateToChatPage(
                                    navController,
                                    item.conversationId,
                                    nodeId = item.nodeId,
                                )
                            },
                            onDelete = {
                                scope.launch {
                                    val entity = vm.getEntityByRefKey(item.refKey) ?: return@launch
                                    vm.removeFavorite(item.refKey)
                                    val result = snackbarHostState.showSnackbar(
                                        message = favoriteRemovedText,
                                        actionLabel = undoText,
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        vm.restoreFavorite(entity)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteHeader(count: Int) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = tokens.raised,
        borderColor = tokens.line2,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FavoriteTile()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.favorite_page_title),
                    style = type.sessionTitle,
                    color = tokens.ink,
                )
                Text(
                    text = stringResource(R.string.favorite_page_count, count),
                    style = type.meta,
                    color = tokens.ink2,
                )
            }
        }
    }
}

@Composable
private fun FavoriteEmptyState() {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FavoriteTile()
            Text(
                text = stringResource(R.string.favorite_page_no_favorites),
                style = type.sessionTitle,
                color = tokens.ink,
            )
            Text(
                text = stringResource(R.string.favorite_page_empty_hint),
                style = type.secondary,
                color = tokens.ink2,
            )
        }
    }
}

@Composable
private fun FavoriteTile() {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(tokens.accent.copy(alpha = 0.13f), RoundedCornerShape(12.dp))
            .border(1.dp, tokens.accent.copy(alpha = 0.32f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.Heart,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tokens.accent,
        )
    }
}

@Composable
private fun SwipeableFavoriteCard(
    item: NodeFavoriteListItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    groupedFirst: Boolean,
    groupedLast: Boolean,
) {
    val dismissState = rememberSwipeToDismissBoxState(initialValue = SwipeToDismissBoxValue.Settled)
    val tokens = LocalAmberTokens.current

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Lucide.Trash2,
                    contentDescription = stringResource(R.string.favorite_remove),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        FavoriteCard(
            item = item,
            onClick = onClick,
            groupedFirst = groupedFirst,
            groupedLast = groupedLast,
        )
    }
}

@Composable
private fun FavoriteCard(
    item: NodeFavoriteListItem,
    onClick: () -> Unit,
    groupedFirst: Boolean,
    groupedLast: Boolean,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val dateText = Instant.ofEpochMilli(item.createdAt).toLocalDateTime()

    val rowShape = RoundedCornerShape(
        topStart = if (groupedFirst) 14.dp else 0.dp,
        topEnd = if (groupedFirst) 14.dp else 0.dp,
        bottomStart = if (groupedLast) 14.dp else 0.dp,
        bottomEnd = if (groupedLast) 14.dp else 0.dp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .background(tokens.surface, rowShape)
            .border(1.dp, tokens.line, rowShape)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FavoriteTile()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = item.conversationTitle.ifBlank {
                        stringResource(R.string.favorite_page_untitled_conversation)
                    },
                    modifier = Modifier.weight(1f),
                    style = type.body.copy(fontWeight = FontWeight.Bold),
                    color = tokens.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FavoriteDatePill(dateText)
            }
            Text(
                text = item.preview,
                style = type.secondary,
                color = tokens.ink2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FavoriteDatePill(text: String) {
    val tokens = LocalAmberTokens.current
    Text(
        text = text,
        modifier = Modifier
            .height(22.dp)
            .background(tokens.surface2, CircleShape)
            .border(1.dp, tokens.line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = LocalAmberType.current.meta.copy(fontSize = 10.5.sp, lineHeight = 13.sp),
        color = tokens.ink2,
        maxLines = 1,
    )
}
