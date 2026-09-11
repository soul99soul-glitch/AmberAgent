package app.amber.feature.ui.pages.sessionhome

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import app.amber.feature.ui.theme.AmberTokens
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
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
import app.amber.feature.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException

/**
 * Session 首页 —— Fixed home of the app (Terminal × Modern graphite design).
 *
 * Layout (top → bottom):
 *   Header: mono wordmark "Amber" + blinking cursor + date, settings gear, profile avatar
 *   Scrollable list: rounded search field + hub entries (5 actions) + session rows
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
    val toaster = LocalToaster.current
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
            var placeholderInserted = false
            try {
                conversationRepo.insertConversation(councilConversation)
                placeholderInserted = true
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
                    conversationRepo.deleteConversation(councilConversation)
                    placeholderInserted = false
                    toaster.show(
                        result.message.ifBlank { result.code },
                        type = ToastType.Error,
                    )
                    android.util.Log.w("SessionHomeCouncil", "openRoom failed: ${result.code}")
                    return@launch
                }
                navController.navigate(Screen.CouncilRoom(conversationId = targetConversationId.toString()))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (placeholderInserted) {
                    try {
                        conversationRepo.deleteConversation(councilConversation)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (cleanupError: Exception) {
                        android.util.Log.e(
                            "SessionHomeCouncil",
                            "failed to clean up placeholder conversation",
                            cleanupError,
                        )
                    }
                }
                val message = error.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.error_title_operation)
                toaster.show(message, type = ToastType.Error)
                android.util.Log.e("SessionHomeCouncil", "failed to open council room", error)
            }
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 104.dp),
            ) {
                item(key = "home_search") {
                    HomeSearchField(onClick = { navController.navigate(Screen.MessageSearch) })
                }
                // 候选保持独立 Lazy item，避免无上限的聚合列表被一次性组合；各段共享
                // surface/边框，视觉上仍是一张 hub 卡片。
                if (continueCandidates.isNotEmpty()) {
                    item(key = "home_continue_header") {
                        ContinueSectionHeader(
                            modifier = homeHubSegmentModifier(
                                tokens = tokens,
                                top = true,
                                bottom = false,
                            ),
                            count = continueCandidates.size,
                        )
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
                            modifier = homeHubSegmentModifier(
                                tokens = tokens,
                                top = false,
                                bottom = false,
                            ),
                            candidate = candidate,
                            onOpen = {
                                navController.navigate(candidate.route.toScreen()) { launchSingleTop = true }
                            },
                            onDismiss = { vm.dismissContinueCandidate(candidate) },
                        )
                    }
                }
                item(key = "home_features") {
                    HomeFeatureRail(
                        modifier = homeHubSegmentModifier(
                            tokens = tokens,
                            top = continueCandidates.isEmpty(),
                            bottom = true,
                        ),
                        onDeepRead = { navController.navigate(Screen.TodayBoard) },
                        onMiniApps = { navController.navigate(Screen.MiniAppList) },
                        onNovel = { navController.navigate(Screen.NovelProjects) },
                        onWebMount = { navController.navigate(Screen.SettingExperimentalWebMount) },
                        onCouncil = openCouncilRoom,
                    )
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

        // Floating new-session button —— iOS 同款胶囊（铅笔 + 新对话）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 20.dp, bottom = 24.dp)
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
                }
                // FAB 按设计最小 56dp；字体放大时允许继续增高。
                .heightIn(min = 56.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Lucide.Pen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = tokens.accentInk,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = stringResource(R.string.history_page_new_conversation),
                    style = LocalAmberType.current.body.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.accentInk,
                )
            }
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
    val defaultUserName = stringResource(R.string.user_default_name)
    val type = LocalAmberType.current
    val cursorHeight = with(LocalDensity.current) { type.screenTitle.fontSize.toDp() }
    val cursorWidth = cursorHeight * 0.54f

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
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Amber",
                    style = type.screenTitle.copy(
                        fontFamily = JetBrainsMonoFamily,
                        letterSpacing = (-0.5).sp,
                    ),
                    color = tokens.ink,
                )
                Spacer(Modifier.width(3.dp))
                // 光标尺寸跟随 wordmark 的字体缩放，保持品牌块比例。
                Box(
                    modifier = Modifier
                        .width(cursorWidth)
                        .height(cursorHeight)
                        .background(tokens.accent.copy(alpha = cursorAlpha)),
                )
            }
            Text(
                text = todayLabel(),
                style = type.meta.copy(fontFamily = JetBrainsMonoFamily),
                color = tokens.ink3,
                maxLines = 2,
                overflow = TextOverflow.Clip,
            )
        }

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

        Spacer(Modifier.width(4.dp))

        // 头像点击进资料页（onUpdate=null 时 UIAvatar 不弹换头像框，仅响应 onClick）
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenProfile),
            contentAlignment = Alignment.Center,
        ) {
            UIAvatar(
                name = settings.displaySetting.userNickname.ifBlank { defaultUserName },
                value = settings.displaySetting.userAvatar,
                size = 34.dp,
                containerColor = tokens.accent,
                showEditBadge = false,
            )
        }
    }
}

@Composable
private fun todayLabel(): String {
    val now = LocalDate.now()
    val weekday = arrayOf(
        stringResource(R.string.session_home_weekday_sunday),
        stringResource(R.string.session_home_weekday_monday),
        stringResource(R.string.session_home_weekday_tuesday),
        stringResource(R.string.session_home_weekday_wednesday),
        stringResource(R.string.session_home_weekday_thursday),
        stringResource(R.string.session_home_weekday_friday),
        stringResource(R.string.session_home_weekday_saturday),
    )[now.dayOfWeek.value % 7]
    return stringResource(
        R.string.session_home_today_label,
        now.monthValue,
        now.dayOfMonth,
        weekday,
    )
}

/* ------------------------------------------------------------------ search --- */

/** 将多个 Lazy item 拼成一张 hub 卡片，同时保留候选列表的独立虚拟化。 */
private fun homeHubSegmentModifier(
    tokens: AmberTokens,
    top: Boolean,
    bottom: Boolean,
): Modifier {
    val radius = 14.dp
    val shape = RoundedCornerShape(
        topStart = if (top) radius else 0.dp,
        topEnd = if (top) radius else 0.dp,
        bottomStart = if (bottom) radius else 0.dp,
        bottomEnd = if (bottom) radius else 0.dp,
    )
    return Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .clip(shape)
        .background(tokens.surface)
        .border(1.dp, tokens.line, shape)
}

/** 圆角搜索栏（外观）——点按进入全站消息搜索页。 */
@Composable
private fun HomeSearchField(onClick: () -> Unit) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(tokens.surface2)
            .border(1.dp, tokens.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = tokens.ink3,
        )
        Text(
            text = stringResource(R.string.chat_page_search_chats),
            style = type.secondary,
            color = tokens.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ------------------------------------------------------------ feature rail --- */

private data class FeatureEntry(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** Five entries share normal-width space; larger text can scroll without squeezing their labels. */
@Composable
private fun HomeFeatureRail(
    modifier: Modifier = Modifier,
    onDeepRead: () -> Unit,
    onMiniApps: () -> Unit,
    onNovel: () -> Unit,
    onWebMount: () -> Unit,
    onCouncil: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val features = listOf(
        FeatureEntry(Lucide.BookOpenText, stringResource(R.string.session_home_feature_deep_read), onDeepRead),
        FeatureEntry(Lucide.Grid2x2, stringResource(R.string.session_home_feature_mini_apps), onMiniApps),
        FeatureEntry(Lucide.Pen, stringResource(R.string.session_home_feature_novel), onNovel),
        FeatureEntry(Lucide.Earth, stringResource(R.string.session_home_feature_sites), onWebMount),
        FeatureEntry(Lucide.MessageCircle, stringResource(R.string.session_home_feature_council), onCouncil),
    )

    val labelStyle = LocalAmberType.current.secondary.copy(fontWeight = FontWeight.Medium)
    val textMeasurer = rememberTextMeasurer()
    val labelWidth = with(LocalDensity.current) {
        features.maxOf { feature ->
            textMeasurer.measure(
                text = feature.label,
                style = labelStyle,
                softWrap = false,
                maxLines = 1,
            ).size.width
        }.toDp()
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        // Measure the actual labels: Android's nonlinear font scaling makes a large sp
        // surrogate an unreliable minimum width for these smaller labels.
        val itemWidth = maxOf(maxWidth / features.size, labelWidth + 12.dp)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.Top,
        ) {
            features.forEach { feature ->
                Column(
                    modifier = Modifier
                        .width(itemWidth)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = feature.onClick)
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = feature.icon,
                        contentDescription = feature.label,
                        modifier = Modifier.size(24.dp),
                        tint = tokens.ink2,
                    )
                    Text(
                        text = feature.label,
                        style = labelStyle,
                        color = tokens.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
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
private fun ContinueSectionHeader(
    modifier: Modifier = Modifier,
    count: Int,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
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
            text = stringResource(R.string.session_home_continue),
            style = type.meta.copy(fontFamily = JetBrainsMonoFamily, letterSpacing = 0.4.sp),
            color = tokens.ink2,
        )
        Text(
            text = "$count",
            style = type.meta.copy(fontFamily = JetBrainsMonoFamily),
            color = tokens.ink4,
        )
    }
}

/** 单条继续候选：标题 + 摘要 + 状态徽标，右侧 X 暂时隐藏。 */
@Composable
private fun ContinueCandidateRow(
    modifier: Modifier = Modifier.fillMaxWidth(),
    candidate: ContinueCandidate,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.surface)
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
                        style = type.body.copy(fontWeight = FontWeight.SemiBold),
                        letterSpacing = (-0.2).sp,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ContinueStatusChip(status = candidate.status)
                }
                if (candidate.summary.isNotBlank()) {
                    Text(
                        text = candidate.summary,
                        style = type.secondary,
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
                    contentDescription = stringResource(R.string.session_home_hide_continue_candidate),
                    modifier = Modifier.size(15.dp),
                    tint = tokens.ink4,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(1.dp)
                .background(tokens.line),
        )
    }
}

@Composable
private fun ContinueStatusChip(status: ContinueStatus) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    val (label, color) = when (status) {
        ContinueStatus.WAITING_USER -> stringResource(R.string.session_home_status_waiting) to tokens.accent
        ContinueStatus.FAILED_RESUMABLE -> stringResource(R.string.session_home_status_resumable) to tokens.signal
        ContinueStatus.PAUSED -> stringResource(R.string.session_home_status_paused) to tokens.ink3
        ContinueStatus.DRAFT -> stringResource(R.string.session_home_status_draft) to tokens.ink3
    }
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = type.tinyTag.copy(fontFamily = JetBrainsMonoFamily, letterSpacing = 0.3.sp),
            color = color,
        )
    }
}

/** 会话没有可靠的内容分类字段，统一使用中性消息 glyph；置顶只作弱 accent 提示。 */
@Composable
private fun SessionGlyph(isPinned: Boolean) {
    val tokens = LocalAmberTokens.current
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(shape)
            .background(tokens.surface2)
            .border(
                width = 1.dp,
                color = if (isPinned) tokens.accent.copy(alpha = 0.28f) else tokens.line,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.MessageCircle,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (isPinned) tokens.accent.copy(alpha = 0.72f) else tokens.ink2,
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
    val type = LocalAmberType.current
    val newMessageLabel = stringResource(R.string.chat_page_new_message)
    val deleteLabel = stringResource(R.string.delete)
    val pinLabel = stringResource(R.string.history_page_pin)
    val unpinLabel = stringResource(R.string.history_page_unpin)
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
                    .padding(horizontal = 16.dp),
                contentAlignment = if (isDelete) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = if (isDelete) Lucide.Trash2 else Lucide.Pin,
                        contentDescription = if (isDelete) {
                            deleteLabel
                        } else {
                            if (conversation.isPinned) unpinLabel else pinLabel
                        },
                        modifier = Modifier.size(17.dp),
                        tint = if (isDelete) tokens.accentInk else tokens.accent,
                    )
                    Text(
                        text = when {
                            isDelete -> deleteLabel
                            conversation.isPinned -> unpinLabel
                            else -> pinLabel
                        },
                        style = type.tinyTag.copy(
                            fontFamily = JetBrainsMonoFamily,
                            letterSpacing = 0.4.sp,
                        ),
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
                    .background(tokens.surface)
                    .clickable(onClick = onOpen)
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionGlyph(isPinned = conversation.isPinned)
                Spacer(Modifier.width(12.dp))
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
                                newMessageLabel
                            },
                            style = type.body.copy(fontWeight = FontWeight.SemiBold),
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
                                tint = tokens.accent.copy(alpha = 0.72f),
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
                            style = type.secondary,
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
                        style = type.meta.copy(fontFamily = JetBrainsMonoFamily),
                        color = tokens.ink3,
                    )
                    SessionCountBadge(count = conversation.messageCount)
                }
            }
            // 行分隔线内缩 16dp，与首页内容基线对齐。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
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
    val type = LocalAmberType.current
    val countColor = tokens.accent
    Box(
        modifier = Modifier
            .heightIn(min = 18.dp)
            .widthIn(min = 18.dp)
            .border(1.dp, countColor.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else "$count",
            style = type.tinyTag.copy(fontFamily = JetBrainsMonoFamily),
            color = countColor,
        )
    }
}

/** 相对时间：今天 → HH:mm，昨天 → 昨天，更早 → M月d日（跨年带年份）。 */
@Composable
private fun sessionTimeLabel(instant: Instant): String {
    val zone = ZoneId.systemDefault()
    val dateTime = instant.atZone(zone)
    val today = LocalDate.now(zone)
    val date = dateTime.toLocalDate()
    return when {
        date == today -> String.format("%02d:%02d", dateTime.hour, dateTime.minute)
        date == today.minusDays(1) -> stringResource(R.string.chat_page_yesterday)
        date.year == today.year -> stringResource(
            R.string.session_home_date_month_day,
            date.monthValue,
            date.dayOfMonth,
        )
        else -> stringResource(
            R.string.session_home_date_year_month_day,
            date.year,
            date.monthValue,
            date.dayOfMonth,
        )
    }
}

/* ------------------------------------------------------------ empty state --- */

@Composable
private fun HomeEmptyState(modifier: Modifier = Modifier) {
    val tokens = LocalAmberTokens.current
    val type = LocalAmberType.current
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.session_home_empty_hint),
            style = type.body,
            color = tokens.ink3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
