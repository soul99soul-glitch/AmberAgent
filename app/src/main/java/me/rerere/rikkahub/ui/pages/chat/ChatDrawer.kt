package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.WorkspaceDivider
import me.rerere.rikkahub.ui.components.ui.WorkspaceTone
import me.rerere.rikkahub.ui.components.ui.workspaceBorder
import me.rerere.rikkahub.ui.components.ui.workspaceColors
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
) {
    val context = LocalContext.current

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // Menu popup 状态
    var showMenuPopup by remember { mutableStateOf(false) }
    val workspace = workspaceColors()

    ModalDrawerSheet(
        modifier = Modifier.width(326.dp),
        drawerShape = RoundedCornerShape(0.dp),
        drawerContainerColor = workspace.paper,
        drawerContentColor = workspace.ink,
        drawerTonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(workspace.paper)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    size = 48.dp,
                    containerColor = workspace.blueContainer,
                    editContainerColor = workspace.paper,
                    editContentColor = workspace.blue,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    userAvatar = newAvatar
                                )
                            )
                        )
                    },
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            color = workspace.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                        )

                        Icon(
                            imageVector = HugeIcons.PencilEdit01,
                            contentDescription = "Edit",
                            modifier = Modifier
                                .onClick {
                                    nicknameEditState.open(settings.displaySetting.userNickname)
                                }
                                .size(20.dp),
                            tint = workspace.muted,
                        )
                    }
                    Greeting(
                        style = MaterialTheme.typography.bodyMedium,
                        color = workspace.muted,
                    )
                }
            }

            DrawerActions(navController = navController)
            WorkspaceDivider(modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp))

            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRegenerateTitle = {
                    vm.generateTitle(it, true)
                },
                onDelete = {
                    vm.deleteConversation(it)
                    // Refresh the conversation list to immediately remove the deleted item
                    // This fixes the issue where deleted conversations sometimes remain visible
                    // until manually clicked (issue #747)
                    conversations.refresh()
                    if (it.id == current.id) {
                        navigateToChatPage(navController)
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                }
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 6.dp)
            ) {
                Box {
                    DrawerAction(
                        icon = {
                            Icon(HugeIcons.Sparkles, "Menu")
                        },
                        label = {
                            Text(stringResource(R.string.menu))
                        },
                        onClick = {
                            showMenuPopup = true
                        },
                    )
                    DropdownMenu(
                        expanded = showMenuPopup,
                        onDismissRequest = { showMenuPopup = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_ai_translator)) },
                            leadingIcon = { Icon(HugeIcons.LanguageCircle, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.Translator)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_menu_image_generation)) },
                            leadingIcon = { Icon(HugeIcons.Image02, null) },
                            onClick = {
                                showMenuPopup = false
                                navController.navigate(Screen.ImageGen)
                            }
                        )
                    }
                }

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.InLove, stringResource(R.string.favorite_page_title))
                    },
                    label = {
                        Text(stringResource(R.string.favorite_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Favorite)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.ChartColumn, "统计数据")
                    },
                    label = {
                        Text("统计数据")
                    },
                    onClick = {
                        navController.navigate(Screen.Stats)
                    },
                )

                Spacer(Modifier.weight(1f))

                DrawerAction(
                        icon = {
                            Icon(HugeIcons.Settings03, null)
                        },
                        label = { Text(stringResource(R.string.settings)) },
                        onClick = {
                            navController.navigate(Screen.Setting)
                        },
                        tone = WorkspaceTone.Accent,
                    )
            }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

}

@Composable
private fun DrawerActions(navController: Navigator) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DrawerNavRow(
            icon = HugeIcons.Search01,
            label = stringResource(R.string.chat_page_search_chats),
            onClick = { navController.navigate(Screen.MessageSearch) },
            tone = WorkspaceTone.Accent,
        )
        DrawerNavRow(
            icon = HugeIcons.TransactionHistory,
            label = stringResource(R.string.chat_page_history),
            onClick = { navController.navigate(Screen.History) },
        )
    }
}

@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val workspace = workspaceColors()
    val tint = if (tone == WorkspaceTone.Accent) workspace.blue else workspace.muted
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = if (tone == WorkspaceTone.Accent) workspace.blueContainer else Color.Transparent,
        contentColor = if (tone == WorkspaceTone.Accent) workspace.blue else workspace.ink,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = tint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (tone == WorkspaceTone.Accent) workspace.blue else workspace.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
    tone: WorkspaceTone = WorkspaceTone.Neutral,
) {
    val workspace = workspaceColors()
    Tooltip(
        tooltip = { label() }
    ) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(44.dp),
            color = if (tone == WorkspaceTone.Accent) workspace.blueContainer else workspace.paper,
            shape = RoundedCornerShape(9.dp),
            contentColor = if (tone == WorkspaceTone.Accent) workspace.blue else workspace.ink,
            border = workspaceBorder(),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(22.dp)) { icon() }
            }
        }
    }
}
