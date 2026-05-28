package me.rerere.rikkahub.ui.pages.favorite

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Favourite
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.WorkspaceLeadingIcon
import me.rerere.rikkahub.ui.components.ui.WorkspaceStatusPill
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceBorder
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import app.amber.core.utils.navigateToChatPage
import app.amber.core.utils.plus
import app.amber.core.utils.toLocalDateTime
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
    val workspace = workspaceColors()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                title = {
                    Text(stringResource(R.string.favorite_page_title))
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = workspace.canvas,
    ) { innerPadding ->
        if (favorites.isEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
            ) {
                item { FavoriteEmptyState() }
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                FavoriteHeader(count = favorites.size)
            }
            items(favorites, key = { it.id }) { item ->
                SwipeableFavoriteCard(
                    item = item,
                    onClick = { navigateToChatPage(navController, item.conversationId, nodeId = item.nodeId) },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(),
                )
            }
        }
    }
}

@Composable
private fun FavoriteHeader(count: Int) {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        border = workspaceBorder(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Favourite,
                tone = WorkspaceTone.Accent,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.favorite_page_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = workspace.ink,
                )
                Text(
                    text = stringResource(R.string.favorite_page_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
            }
        }
    }
}

@Composable
private fun FavoriteEmptyState() {
    val workspace = workspaceColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        border = workspaceBorder(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Favourite,
                tone = WorkspaceTone.Neutral,
            )
            Text(
                text = stringResource(R.string.favorite_page_no_favorites),
                style = MaterialTheme.typography.titleSmall,
                color = workspace.ink,
            )
            Text(
                text = stringResource(R.string.favorite_page_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = workspace.muted,
            )
        }
    }
}

@Composable
private fun SwipeableFavoriteCard(
    item: NodeFavoriteListItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
    )
    val workspace = workspaceColors()

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        workspace.redContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.assistant_page_remove),
                    tint = workspace.red,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        FavoriteCard(
            item = item,
            onClick = onClick,
        )
    }
}

@Composable
private fun FavoriteCard(
    item: NodeFavoriteListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspace = workspaceColors()
    val dateText = Instant.ofEpochMilli(item.createdAt).toLocalDateTime()

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = workspace.paper,
        border = workspaceBorder(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            WorkspaceLeadingIcon(
                icon = HugeIcons.Favourite,
                tone = WorkspaceTone.Accent,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.conversationTitle.ifBlank { stringResource(R.string.favorite_page_untitled_conversation) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    color = workspace.ink,
                )
                Text(
                    text = item.preview,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = workspace.muted,
                )
                WorkspaceStatusPill(
                    text = dateText,
                    tone = WorkspaceTone.Neutral,
                )
            }
        }
    }
}
