package app.amber.feature.ui.pages.sessionhome

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.modelcouncil.CouncilRoomManager
import app.amber.feature.modelcouncil.CouncilRoomOpResult
import app.amber.feature.modelcouncil.toCouncilParticipant
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.theme.JetBrainsMonoFamily
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.home.ContinueCandidate
import app.amber.feature.home.ContinueRoute
import app.amber.feature.home.ContinueStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import app.amber.feature.ui.pages.chat.ChatDrawerVM
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.MessageSquarePlus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.Pen
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Session 首页 —— Fixed home of the app (Terminal × Modern graphite design).
 *
 * Layout (top → bottom):
 *   Header: mono wordmark "Amber" + blinking cursor + date, settings gear, profile avatar
 *   Scrollable list: flat search field + feature rail (5 entries) + session rows
 *   FAB (bottom-end): new conversation
 *
 * The session list itself pages from [ChatDrawerVM] (activity-scoped, shared with the
 * chat drawer); delete / pin / title / settings actions go through [SessionHomeVM].
 */
@Composable
fun SessionHomePage() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val tokens = LocalAmberTokens.current
    val vm: SessionHomeVM = koinViewModel()
    val listVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = listVm.conversations.collectAsLazyPagingItems()
    val continueCandidates = vm.continueCandidates.collectAsStateWithLifecycle().value
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Council Room: 首页没有「当前会话」，每次点议会现开一个新会话承载（council_state
    // 以 UPDATE 写在会话行上，行不存在房间会丢，故必须先落库；开房失败则回收占位会话）。
    val councilRoomManager: CouncilRoomManager = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val openCouncilRoom: () -> Unit = {
        scope.launch {
            val targetConversationId = Uuid.random()
            val councilSettings = settingsStore.settingsFlow.value
            val councilConversation = Conversation.ofId(
                id = targetConversationId,
                assistantId = AMBER_AGENT_ID,
                newConversation = true,
            ).updateCurrentMessages(councilSettings.presetMessages)
            conversationRepo.insertConversation(councilConversation)
            val guests = councilSettings.agentRuntime.modelCouncil.defaultSeats.map { seat ->
                seat.toCouncilParticipant().copy(
                    modelName = councilSettings.findModelById(seat.modelId)?.displayName.orEmpty(),
                )
            }
            val result = councilRoomManager.openRoom(
                conversationId = targetConversationId,
                hostAssistantId = AMBER_AGENT_ID,
                hostName = "Amber",
                objective = "多模型协作讨论",
                initialGuests = guests,
                maxRounds = councilSettings.agentRuntime.modelCouncil.defaultRounds.coerceIn(2, 6),
                hostModelIdOverride = councilSettings.agentRuntime.modelCouncil.hostModelId,
            )
            if (result is CouncilRoomOpResult.Err) {
                vm.deleteConversation(councilConversation)
                android.util.Log.w("SessionHomeCouncil", "openRoom failed: ${result.code}")
                return@launch
            }
            navController.navigate(Screen.CouncilRoom(conversationId = targetConversationId.toString()))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            HomeHeader(
                settings = settings,
                onOpenSettings = { navController.navigate(Screen.Setting) },
                onOpenProfile = { navController.navigate(Screen.Profile) },
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 100.dp),
            ) {
                item(key = "home_search") {
                    HomeSearchField(onClick = { navController.navigate(Screen.MessageSearch) })
                }
                item(key = "home_features") {
                    HomeFeatureRail(
                        onDeepRead = { navController.navigate(Screen.TodayBoard) },
                        onMiniApps = { navController.navigate(Screen.MiniAppList) },
                        onNovel = { navController.navigate(Screen.NovelProjects) },
                        onWebMount = { navController.navigate(Screen.SettingExperimentalWebMount) },
                        onCouncil = openCouncilRoom,
                    )
                }

                // P8-08 首页「继续」聚合：点击路由到任务焦点，X 暂时隐藏（dismissUntil）
                if (continueCandidates.isNotEmpty()) {
                    item(key = "home_continue_header") {
                        ContinueSectionHeader(count = continueCandidates.size)
                    }
                    items(
                        count = continueCandidates.size,
                        key = { index ->
                            val candidate = continueCandidates[index]
                            "continue_${candidate.sourceKind.name}_${candidate.sourceId}"
                        },
                    ) { index ->
                        val candidate = continueCandidates[index]
                        ContinueCandidateRow(
                            candidate = candidate,
                            onOpen = {
                                navController.navigate(
                                    candidate.route.toScreen()
                                ) { launchSingleTop = true }
                            },
                            onDismiss = { vm.dismissContinueCandidate(candidate) },
                        )
                    }
                }

                // 首次加载完成前不渲染空态，避免加载瞬间闪现「暂无会话」
                if (conversations.itemCount == 0 &&
                    conversations.loadState.refresh is LoadState.NotLoading
                ) {
                    item(key = "home_empty") {
                        HomeEmptyState(modifier = Modifier.padding(vertical = 56.dp))
                    }
                }

                items(
                    count = conversations.itemCount,
                    key = conversations.itemKey { item ->
                        when (item) {
                            is app.amber.feature.ui.pages.chat.ConversationListItem.DateHeader -> "date_${item.date}"
                            is app.amber.feature.ui.pages.chat.ConversationListItem.PinnedHeader -> "pinned_header"
                            is app.amber.feature.ui.pages.chat.ConversationListItem.Item -> item.conversation.id.toString()
                        }
                    },
                ) { index ->
                    val item = conversations[index]
                    if (item is app.amber.feature.ui.pages.chat.ConversationListItem.Item) {
                        HomeSessionRow(
                            conversation = item.conversation,
                            // 首页是 hub：用 push（保留 SessionHome 在栈底），返回能回到首页；
                            // 不能用 navigateToChatPage（其内部 clearAndNavigate 会清掉首页）
                            onOpen = {
                                navController.navigate(
                                    Screen.Chat(id = item.conversation.id.toString())
                                ) { launchSingleTop = true }
                            },
                            onDelete = { vm.deleteConversation(item.conversation) },
                            onTogglePin = { vm.updatePinnedStatus(item.conversation) },
                        )
                    }
                    // 分页流里的日期/置顶分隔头在首页设计里不渲染
                }
            }
        }

        // 列表底部渐隐，给 FAB 让出视觉空间（对齐设计稿的 mask 渐隐）。
        // 让位 navigationBars：锚到「列表视口底」而非屏幕底，三键导航下不失效。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth()
                .height(52.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, tokens.bg),
                    )
                )
        )

        // Floating new-session button
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 20.dp, bottom = 24.dp)
                .size(56.dp)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    ambientColor = tokens.accent.copy(alpha = 0.30f),
                    spotColor = tokens.accent.copy(alpha = 0.30f),
                )
                .clip(CircleShape)
                .background(tokens.accent)
                .clickable {
                    // 新会话：push 保留首页在栈底，与列表点开一致
                    navController.navigate(Screen.Chat(id = Uuid.random().toString())) {
                        launchSingleTop = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.MessageSquarePlus,
                contentDescription = stringResource(R.string.chat_page_new_message),
                modifier = Modifier.size(24.dp),
                tint = tokens.accentInk,
            )
        }
    }
}

/* ------------------------------------------------------------------ header --- */

/** Amber wordmark（mono + 闪烁光标）+ 日期 + 设置齿轮 + 头像。 */
@Composable
private fun HomeHeader(
    settings: Settings,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val tokens = LocalAmberTokens.current

    // 终端光标：1.05s steps 闪烁（前半段不透明，后半段隐藏）
    val transition = rememberInfiniteTransition(label = "home-cursor")
    val cursorAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1050
                1f at 0
                1f at 520
                0f at 525
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "home-cursor-alpha",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Amber",
                fontFamily = JetBrainsMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 23.sp,
                letterSpacing = (-0.5).sp,
                color = tokens.ink,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(3.dp))
            // 设计稿光标 0.54em × 1em（wordmark 23sp → 约 12×23）
            Box(
                modifier = Modifier
                    .width(12.dp)
                    .height(23.dp)
                    .background(tokens.accent.copy(alpha = cursorAlpha)),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = todayLabel(),
                fontFamily = JetBrainsMonoFamily,
                fontSize = 11.5.sp,
                letterSpacing = 0.2.sp,
                color = tokens.ink3,
                // 与 wordmark 文本基线对齐（HTML align-items: baseline）
                modifier = Modifier.alignByBaseline(),
            )
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.Settings,
                contentDescription = stringResource(R.string.settings),
                modifier = Modifier.size(20.dp),
                tint = tokens.ink2,
            )
        }

        Spacer(Modifier.width(10.dp))

        // 头像点击进资料页（onUpdate=null 时 UIAvatar 不弹换头像框，仅响应 onClick）
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenProfile),
            contentAlignment = Alignment.Center,
        ) {
            UIAvatar(
                name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                value = settings.displaySetting.userAvatar,
                size = 34.dp,
                containerColor = tokens.accent,
                showEditBadge = false,
            )
        }
    }
}

private fun todayLabel(): String {
    val now = LocalDate.now()
    val weekday = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[now.dayOfWeek.value % 7]
    return "今天 · ${now.monthValue}月${now.dayOfMonth}日 $weekday"
}

/* ------------------------------------------------------------------ search --- */

/** 扁平搜索栏（外观）——点按进入全站消息搜索页。 */
@Composable
private fun HomeSearchField(onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(top = 9.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = tokens.ink3,
            )
            Text(
                text = "搜索会话",
                fontSize = 14.5.sp,
                color = tokens.ink4,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(1.dp)
            .background(tokens.line),
    )
}

/* ------------------------------------------------------------ feature rail --- */

private data class FeatureEntry(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** 功能入口行：5 个入口均分，图标在上、文案在下，中间 1dp 竖分隔线。 */
@Composable
private fun HomeFeatureRail(
    onDeepRead: () -> Unit,
    onMiniApps: () -> Unit,
    onNovel: () -> Unit,
    onWebMount: () -> Unit,
    onCouncil: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val features = listOf(
        FeatureEntry(Lucide.BookOpenText, "深度阅读", onDeepRead),
        FeatureEntry(Lucide.Grid2x2, "小应用", onMiniApps),
        FeatureEntry(Lucide.Pen, "小说创作", onNovel),
        FeatureEntry(Lucide.Earth, "站点", onWebMount),
        FeatureEntry(Lucide.MessageCircle, "模型议会", onCouncil),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        features.forEachIndexed { index, feature ->
            if (index > 0) {
                // 列间 1dp 边界线；宽度由五个 weight(1f) 等分列决定
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(26.dp)
                        .background(tokens.line),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .clickable(onClick = feature.onClick)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = feature.label,
                    modifier = Modifier.size(24.dp),
                    tint = tokens.accent,
                )
                Text(
                    text = feature.label,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.2.sp,
                    color = tokens.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // 底线紧贴功能行底部（行内 12dp bottom padding 已给出间距，对齐设计稿 borderBottom）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(1.dp)
            .background(tokens.line),
    )
}

/* ------------------------------------------------------------- session row --- */

/** P8-08：继续路由 → 应用内页面（点击准确路由到任务焦点）。 */
private fun ContinueRoute.toScreen(): Screen = when (this) {
    is ContinueRoute.CouncilRoom -> Screen.CouncilRoom(conversationId = conversationId)
    is ContinueRoute.DeepRead -> Screen.DeepRead(topicId = topicId, title = title)
    is ContinueRoute.Chat -> Screen.Chat(id = conversationId)
}

/** 首页「继续」聚合区块标题：mono 标签 + 数量。 */
@Composable
private fun ContinueSectionHeader(count: Int) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Lucide.Clock,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tokens.accent,
        )
        Text(
            text = "继续",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
            color = tokens.ink2,
        )
        Text(
            text = "$count",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.5.sp,
            color = tokens.ink4,
        )
    }
}

/** 单条继续候选：标题 + 摘要 + 状态徽标，右侧 X 暂时隐藏。 */
@Composable
private fun ContinueCandidateRow(
    candidate: ContinueCandidate,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.bg)
                .clickable(onClick = onOpen)
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = candidate.title,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ContinueStatusChip(status = candidate.status)
                }
                if (candidate.summary.isNotBlank()) {
                    Text(
                        text = candidate.summary,
                        fontSize = 12.5.sp,
                        color = tokens.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = "暂时隐藏",
                    modifier = Modifier.size(15.dp),
                    tint = tokens.ink4,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(1.dp)
                .background(tokens.line),
        )
    }
}

@Composable
private fun ContinueStatusChip(status: ContinueStatus) {
    val tokens = LocalAmberTokens.current
    val (label, color) = when (status) {
        ContinueStatus.WAITING_USER -> "待处理" to tokens.accent
        ContinueStatus.FAILED_RESUMABLE -> "可继续" to tokens.signal
        ContinueStatus.PAUSED -> "已暂停" to tokens.ink3
        ContinueStatus.DRAFT -> "草稿" to tokens.ink3
    }
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.5.sp,
            letterSpacing = 0.3.sp,
            color = color,
        )
    }
}

/**
 * 会话行：标题 + 最后消息预览 +（消息数 / 时间）。
 * 左滑（EndToStart）删除，右滑（StartToEnd）置顶/取消置顶——M3 SwipeToDismissBox 双向，
 * 与 HistoryPage 的单向删除同款机制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSessionRow(
    conversation: Conversation,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold,
        // 置顶是「动作后行仍保留」的方向：在 confirmValueChange 里执行动作并返回
        // false，状态永远不进入 StartToEnd，松手必弹回 Settled。旧版是进入
        // StartToEnd 后 LaunchedEffect 里 onTogglePin()+reset()，但置顶引起的列表
        // 重排会和 reset 赛跑，行会卡在滑出位置不回弹。删除方向返回 true 交给
        // 下面的 LaunchedEffect（项会被移除，无需回弹）。
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onTogglePin()
                false
            } else {
                true
            }
        },
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // 左滑露出右侧「删除」（accent 底）；右滑露出左侧「置顶」（surface2 底）
            val isDelete = dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isDelete) tokens.accent else tokens.surface2)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (isDelete) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isDelete) Lucide.Trash2 else Lucide.Pin,
                        contentDescription = if (isDelete) "删除" else "置顶",
                        modifier = Modifier.size(17.dp),
                        tint = if (isDelete) tokens.accentInk else tokens.accent,
                    )
                    Text(
                        text = when {
                            isDelete -> "删除"
                            conversation.isPinned -> "取消置顶"
                            else -> "置顶"
                        },
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        letterSpacing = 0.4.sp,
                        color = if (isDelete) tokens.accentInk else tokens.accent,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tokens.bg)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = conversation.title.ifBlank {
                                stringResource(id = R.string.chat_page_new_message)
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.2).sp,
                            color = tokens.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (conversation.isPinned) {
                            Icon(
                                imageVector = Lucide.Pin,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = tokens.accent,
                            )
                        }
                    }

                    // 设计稿：预览去掉 markdown 强调符号（** / 反引号）
                    val preview = conversation.lastMessagePreview
                        .replace("**", "")
                        .replace("`", "")
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview,
                            fontSize = 13.sp,
                            color = tokens.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                // 右侧：时间（对齐标题行）+ 消息数圆圈徽章，压缩行高
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = sessionTimeLabel(conversation.updateAt),
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 11.sp,
                        color = tokens.ink4,
                    )
                    SessionCountBadge(count = conversation.messageCount)
                }
            }
            // 行分隔线内缩 20dp，与搜索栏/功能行底线及内容 padding 对齐（设计稿 srow-fg 内缩 20px）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(1.dp)
                    .background(tokens.line),
            )
        }
    }
}

/** 消息数圆圈徽章：只显示数字（>99 显 99+），细圆圈描边。 */
@Composable
private fun SessionCountBadge(count: Int) {
    val tokens = LocalAmberTokens.current
    Box(
        modifier = Modifier
            .heightIn(min = 18.dp)
            .widthIn(min = 18.dp)
            .border(1.dp, tokens.accent.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else "$count",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = tokens.accent,
        )
    }
}

/** 相对时间：今天 → HH:mm，昨天 → 昨天，更早 → M月d日（跨年带年份）。 */
private fun sessionTimeLabel(instant: Instant): String {
    val zone = ZoneId.systemDefault()
    val dateTime = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> String.format("%02d:%02d", dateTime.hour, dateTime.minute)
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
}

/* ------------------------------------------------------------ empty state --- */

@Composable
private fun HomeEmptyState(modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "// 0 results",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 12.sp,
            color = tokens.ink4,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "暂无会话，点击右下角按钮开始",
            fontSize = 13.5.sp,
            color = tokens.ink3,
            textAlign = TextAlign.Center,
        )
    }
}
