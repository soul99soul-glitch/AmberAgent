package me.rerere.rikkahub.ui.pages.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel

/**
 * Conversations home — the primary entry surface for the Pulse app.
 *
 * Replaces what used to be a drawer-only list view with a full-screen
 * page that lands when the user opens Pulse: a sport-orange hero count
 * ("04") under an ALL-CAPS "ACTIVE THREADS · READY" eyebrow, followed by
 * tan modular cards for each conversation. Tapping a row navigates into
 * that specific chat. The bottom-nav's Chats tab routes here.
 *
 * The "active threads" framing intentionally treats every conversation
 * as live signal rather than dead history — Pulse's brand voice is
 * sports-tech / live-data, not archive-of-records. The headline count
 * pulls from the actual list size so the eye lands on a non-zero number
 * the moment the app opens.
 *
 * Top bar gives only secondary actions (search + delete-all); there's
 * no nav-up because this IS the up. Search jumps to the message search
 * screen; delete-all opens a destructive-action dialog.
 */
@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val activeCount = conversations.size

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
        val snackMessageUndo = stringResource(R.string.history_page_undo)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ConversationsHeader(
                    activeCount = activeCount,
                    onSearchClick = { navController.navigate(Screen.MessageSearch) },
                    onDeleteAllClick = { showDeleteAllDialog = true },
                )
            }
            items(conversations, key = { it.id }) { conversation ->
                SwipeableConversationItem(
                    conversation = conversation,
                    onClick = {
                        navigateToChatPage(navController, conversation.id)
                    },
                    onDelete = {
                        scope.launch {
                            val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                            vm.deleteConversation(conversation)
                            val result = snackbarHostState.showSnackbar(
                                message = snackMessageDeleted,
                                actionLabel = snackMessageUndo,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                vm.restoreConversation(fullConversation)
                            }
                        }
                    },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun ConversationsHeader(
    activeCount: Int,
    onSearchClick: () -> Unit,
    onDeleteAllClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Top row: Pulse brand title + secondary action icons. The icons
        // are circular outlined chips (mockup-faithful) so they pop on
        // the cream surface without competing with the hero count below.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.02).em,
                ),
                modifier = Modifier.weight(1f),
            )
            HeaderIconButton(
                icon = HugeIcons.GlobalSearch,
                contentDescription = stringResource(R.string.history_page_search_messages),
                onClick = onSearchClick,
            )
            HeaderIconButton(
                icon = HugeIcons.Delete01,
                contentDescription = stringResource(R.string.history_page_delete_all),
                onClick = onDeleteAllClick,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // Hero count — the signature Pulse eyebrow + sport-orange numeral
        // pattern. activeCount.toString().padStart(2, '0') so single-digit
        // counts render as "04" / "07" (matching the mockup) instead of
        // dropping the leading zero.
        Text(
            text = "ACTIVE THREADS · READY",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.10.em,
            ),
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            text = activeCount.toString().padStart(2, '0'),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.04).em,
                lineHeight = 64.sp,
            ),
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> onDelete()
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
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier,
    ) {
        ConversationCard(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick,
        )
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.isPinned) {
                        // Chartreuse dot signals "pinned" — same Pulse signal-dot
                        // vocabulary used elsewhere (running-tool indicator,
                        // unread message dot, active provider, etc.)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                Text(
                    text = conversation.createAt.toLocalDateTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    imageVector = if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                    contentDescription = if (conversation.isPinned) {
                        stringResource(R.string.history_page_unpin)
                    } else {
                        stringResource(R.string.history_page_pin)
                    },
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
