package app.amber.feature.ui.pages.chat

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.DashboardSquare01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.News01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.TransactionHistory
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.settings.Settings
import app.amber.core.model.Conversation
import app.amber.feature.ui.components.ui.Greeting
import app.amber.feature.ui.components.ui.Tooltip
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.components.ui.WorkspaceDivider
import app.amber.feature.ui.components.ui.WorkspaceTone
import app.amber.feature.ui.components.ui.workspaceBorder
import app.amber.feature.ui.components.ui.workspaceColors
import app.amber.feature.ui.context.Navigator
import app.amber.feature.ui.hooks.EditStateContent
import app.amber.feature.ui.hooks.useEditState
import app.amber.feature.ui.modifier.onClick
import app.amber.core.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    drawerState: DrawerState,
    drawerWidth: Dp = 336.dp,
    onOpenWorkspace: () -> Unit = {},
    onOpenFavoritesLive: () -> Unit = {},
) {
    val context = LocalContext.current

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )
    val scope = rememberCoroutineScope()

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
    val workspace = workspaceColors()

    // drawerShape: 之前 0dp 直角, 覆盖过来很生硬. 右侧 24dp 圆角让滑出时跟主屏边缘有过渡感.
    ModalDrawerSheet(
        modifier = Modifier.width(drawerWidth),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = workspace.paper,
        drawerContentColor = workspace.ink,
        drawerTonalElevation = 0.dp,
    ) {
        // V3 convo-history.jsx 全量重构：
        //   Amber wordmark → SearchBar → Primary nav (新聊天/今日看板/小应用)
        //   → QuickRow (Workspace 文件/伴随智能/聊天热力图统计 icon-only)
        //   → divider → 最近 label → ConversationList → Footer (avatar + name + settings gear)
        val chatTheme = app.amber.feature.ui.pages.chat.LocalChatTheme.current
        Column(
            modifier = Modifier
                .background(workspace.paper)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // (1) Amber wordmark —— 与 (2) 搜索框一起固定在顶部，不随列表滚动
                Text(
                    text = "Amber",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    color = chatTheme.ink,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )

                // (2) Search bar capsule
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(chatTheme.searchBarBg)
                        .border(
                            BorderStroke(1.dp, chatTheme.hair),
                            RoundedCornerShape(999.dp),
                        )
                        .clickable { navController.navigate(Screen.MessageSearch) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = "Search",
                        modifier = Modifier.size(16.dp),
                        tint = chatTheme.inkSoft,
                    )
                    Text(
                        text = "搜索聊天",
                        fontSize = 14.5.sp,
                        color = chatTheme.inkFaint,
                        letterSpacing = 0.2.sp,
                    )
                }

                // (3)-(6)（导航行/快捷钮/分隔线/最近标签）在 ConversationList 的 header 项里：
                // 上滑时随会话列表一起被推出视野，给 session 列表让出空间；footer 仍固定。
                ConversationList(
                    current = current,
                    conversations = conversations,
                    conversationJobs = conversationJobs.keys,
                    listState = conversationListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    header = {
                        V3DrawerHeader(
                            navController = navController,
                            drawerState = drawerState,
                            chatTheme = chatTheme,
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenFavoritesLive = onOpenFavoritesLive,
                        )
                    },
                    onClick = {
                        scope.launch {
                            if (it.id != current.id) {
                                withTimeoutOrNull(220L) {
                                    drawerState.close()
                                }
                                navigateToChatPage(navController, it.id)
                            } else {
                                drawerState.close()
                            }
                        }
                    },
                    onRegenerateTitle = {
                        vm.generateTitle(it, true)
                    },
                    onDelete = {
                        vm.deleteConversation(it)
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController)
                        }
                    },
                    onPin = {
                        vm.updatePinnedStatus(it)
                    }
                )
            }

            // (8) Footer hair line + avatar + name + settings gear（V3 设计稿原版）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(chatTheme.hair),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    size = 32.dp,
                    containerColor = chatTheme.userBubble,
                    editContainerColor = workspace.paper,
                    editContentColor = chatTheme.accent,
                    showEditBadge = false,  // V3 footer 头像无铅笔徽标，仍可点击换头像
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
                Text(
                    text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = chatTheme.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            nicknameEditState.open(settings.displaySetting.userNickname)
                        },
                )
                Icon(
                    imageVector = HugeIcons.Settings03,
                    contentDescription = stringResource(R.string.settings),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { navController.navigate(Screen.Setting) },
                    tint = chatTheme.inkSoft,
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
private fun DrawerActions(navController: Navigator, todayBoardEnabled: Boolean = false) {
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
        if (todayBoardEnabled) {
            DrawerNavRow(
                icon = HugeIcons.News01,
                label = "今日看板",
                onClick = { navController.navigate(Screen.TodayBoard) },
            )
        }
        DrawerNavRow(
            icon = HugeIcons.DashboardSquare01,
            label = "小应用",
            onClick = { navController.navigate(Screen.MiniAppList) },
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

/**
 * 抽屉滚动头 (3)-(6)：主导航行 → 快捷钮 → 分隔线 → 最近标签。
 * 作为 ConversationList 的 header 项渲染（LazyColumn 第 0 项），随会话列表一起滚动；
 * (1) wordmark 与 (2) 搜索框固定在 ChatDrawerContent 顶部，不在此列。
 */
@Composable
private fun V3DrawerHeader(
    navController: Navigator,
    drawerState: DrawerState,
    chatTheme: ChatTheme,
    onOpenWorkspace: () -> Unit,
    onOpenFavoritesLive: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // (3) Primary nav rows: 新聊天 / 今日看板 / 小应用
        V3NavRow(
            icon = HugeIcons.MessageAdd01,
            label = "新聊天",
            accent = true,
            chatTheme = chatTheme,
            onClick = {
                scope.launch { drawerState.close() }
                navigateToChatPage(navController)
            },
        )
        // V3 设计稿 4 套主题中 "今日看板" 永远显示（不绑 todayBoardEnabled flag），
        // 点开后用户没启用时由 TodayBoard 页面自己引导启用
        V3NavRow(
            icon = HugeIcons.News01,
            label = "今日看板",
            accent = false,
            chatTheme = chatTheme,
            onClick = { navController.navigate(Screen.TodayBoard) },
        )
        V3NavRow(
            icon = HugeIcons.DashboardSquare01,
            label = "小应用",
            accent = false,
            chatTheme = chatTheme,
            onClick = { navController.navigate(Screen.MiniAppList) },
        )

        // (4) QuickRow: 3 icon-only buttons (Workspace 文件 / 伴随智能 / 聊天热力图统计)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            V3QuickBtn(
                icon = HugeIcons.Folder01,
                contentDescription = "Workspace 文件",
                chatTheme = chatTheme,
                onClick = onOpenWorkspace,
            )
            V3QuickBtn(
                icon = HugeIcons.Sparkles,
                contentDescription = "伴随智能",
                chatTheme = chatTheme,
                onClick = onOpenFavoritesLive,
            )
            V3QuickBtn(
                icon = HugeIcons.ChartColumn,
                contentDescription = "聊天热力图统计",
                chatTheme = chatTheme,
                onClick = { navController.navigate(Screen.Stats) },
            )
        }

        // (5) Divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .height(1.dp)
                .background(chatTheme.hair),
        )

        // (6) 最近 section label: 跟"新聊天"(V3NavRow accent=true) 同款 — accent 色 + Medium
        Row(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Time02,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = chatTheme.accent,
            )
            Text(
                text = "最近",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                color = chatTheme.accent,
            )
        }
    }
}

/** V3 convo-history.jsx NavRow —— accent=true 时 newchat 用 accent 字色, 否则 ink */
@Composable
private fun V3NavRow(
    icon: ImageVector,
    label: String,
    accent: Boolean,
    chatTheme: app.amber.feature.ui.pages.chat.ChatTheme,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(19.dp),
            tint = if (accent) chatTheme.accent else chatTheme.inkSoft,
        )
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
            letterSpacing = 0.2.sp,
            color = if (accent) chatTheme.accent else chatTheme.ink,
        )
    }
}

/** V3 convo-history.jsx QuickBtn —— 36×36 icon-only 圆角方块 */
@Composable
private fun V3QuickBtn(
    icon: ImageVector,
    contentDescription: String,
    chatTheme: app.amber.feature.ui.pages.chat.ChatTheme,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(chatTheme.searchBarBg)
            .border(BorderStroke(1.dp, chatTheme.hair), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = chatTheme.inkSoft,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesLiveSheet(
    navController: Navigator,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live 伴随", style = MaterialTheme.typography.titleMedium)
            Text("实时监听屏幕内容，让 Amber 边看边帮你。", style = MaterialTheme.typography.bodyMedium, color = workspaceColors().muted)
            TextButton(onClick = { onDismiss(); navController.navigate(Screen.LiveCompanion) }) {
                Text("打开 Live 伴随")
            }
        }
    }
}
