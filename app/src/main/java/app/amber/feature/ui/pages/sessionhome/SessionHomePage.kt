package app.amber.feature.ui.pages.sessionhome

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.core.service.ChatService
import app.amber.core.settings.Settings
import app.amber.core.settings.findModelById
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.modelcouncil.CouncilRoomManager
import app.amber.feature.modelcouncil.CouncilRoomOpResult
import app.amber.feature.modelcouncil.toCouncilParticipant
import app.amber.feature.ui.components.ui.UIAvatar
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.theme.JetBrainsMonoFamily
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.home.ContinueCandidate
import app.amber.feature.home.ContinueRoute
import app.amber.feature.home.ContinueSourceKind
import app.amber.feature.home.ContinueStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.BookOpenText
import com.composables.icons.lucide.MessageCircle
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Earth
import com.composables.icons.lucide.Grid2x2
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Pen
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.ScanSearch
import com.composables.icons.lucide.Settings
import com.dokar.sonner.ToastType
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
 * The session list observes persisted conversation summaries; the home search field
 * filters those summaries locally while the full message search remains a separate route.
 */
@Composable
fun SessionHomePage() {
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val tokens = LocalAmberTokens.current
    val vm: SessionHomeVM = koinViewModel()
    val conversations = vm.conversations.collectAsStateWithLifecycle().value
    val conversationsLoaded = vm.conversationsLoaded.collectAsStateWithLifecycle().value
    val hasConversationError = vm.hasConversationError.collectAsStateWithLifecycle().value
    var homeSearchQuery by rememberSaveable { mutableStateOf("") }
    var homeSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val homeSearchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val visibleConversations = filterHomeConversations(
        conversations = conversations,
        query = homeSearchQuery,
        untitledLabel = stringResource(R.string.parity_home_new_conversation),
    )
    val continueCandidates = vm.continueCandidates.collectAsStateWithLifecycle().value
    val hasContinueError = vm.hasContinueError.collectAsStateWithLifecycle().value
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    // A full-height radius clamps to exactly half the measured height, including pixel rounding.
    val fabShape = AmberContinuousShape(cornerRadius = 44.dp)
    val fabInteractionSource = remember { MutableInteractionSource() }

    // Council Room: 首页没有「当前会话」，每次点议会现开一个新会话承载（council_state
    // 以 UPDATE 写在会话行上，行不存在房间会丢，故必须先落库；开房失败则回收占位会话）。
    val councilRoomManager: CouncilRoomManager = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val chatService: ChatService = koinInject()
    val openCouncilRoom: () -> Unit = {
        scope.launch {
            val targetConversationId = Uuid.random()
            val councilSettings = settingsStore.settingsFlow.value
            val councilConversation = Conversation.ofId(
                id = targetConversationId,
                assistantId = AMBER_AGENT_ID,
                newConversation = true,
            ).updateCurrentMessages(councilSettings.presetMessages)
            chatService.saveConversation(targetConversationId, councilConversation)
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

    val openContinueCandidate: (ContinueCandidate) -> Unit = { candidate ->
        scope.launch {
            val canOpen = try {
                canOpenContinueRoute(candidate.route) { conversationId ->
                    val uuid = runCatching { Uuid.parse(conversationId) }.getOrNull()
                    uuid != null && conversationRepo.existsConversationById(uuid)
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                android.util.Log.e("SessionHomeRoute", "Unable to validate Continue route", error)
                toaster.show("暂时无法打开会话，请稍后重试", type = ToastType.Error)
                return@launch
            }
            if (!canOpen) {
                toaster.show("会话已不存在，无法继续", type = ToastType.Error)
                return@launch
            }
            navController.navigate(candidate.route.toScreen()) {
                launchSingleTop = true
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
                searchExpanded = homeSearchExpanded,
                onOpenSearch = { homeSearchExpanded = true },
                onOpenSettings = { navController.navigate(Screen.Setting) },
                onOpenProfile = { navController.navigate(Screen.Profile) },
            )

            AnimatedVisibility(
                visible = homeSearchExpanded,
                enter = fadeIn(animationSpec = tween(190)) +
                    expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(190),
                    ),
                exit = fadeOut(animationSpec = tween(170)) +
                    shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(170),
                    ),
            ) {
                LaunchedEffect(homeSearchExpanded) {
                    if (homeSearchExpanded) {
                        homeSearchFocusRequester.requestFocus()
                    }
                }
                HomeSearchField(
                    query = homeSearchQuery,
                    focusRequester = homeSearchFocusRequester,
                    onQueryChange = { homeSearchQuery = it },
                    onClear = { homeSearchQuery = "" },
                    onOpenFullSearch = {
                        navController.navigate(Screen.MessageSearch)
                    },
                    onCollapse = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        homeSearchQuery = ""
                        homeSearchExpanded = false
                    },
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 100.dp),
            ) {
                item(key = "home_features") {
                    HomeFeatureRail(
                        resumeCandidate = continueCandidates.firstOrNull(),
                        onOpenResume = openContinueCandidate,
                        onDeepRead = { navController.navigate(Screen.TodayBoard) },
                        onMiniApps = { navController.navigate(Screen.MiniAppList) },
                        onNovel = { navController.navigate(Screen.NovelProjects) },
                        onWebMount = { navController.navigate(Screen.SettingExperimentalWebMount) },
                        onCouncil = openCouncilRoom,
                    )
                }

                // P8-08 首页「继续」聚合：点击路由到任务焦点
                if (continueCandidates.size > 1) {
                    item(key = "home_continue_header") {
                        ContinueSectionHeader(count = continueCandidates.size - 1)
                    }
                    items(
                        count = continueCandidates.size - 1,
                        key = { index ->
                            val candidate = continueCandidates[index + 1]
                            "continue_${candidate.sourceKind.name}_${candidate.sourceId}"
                        },
                    ) { index ->
                        val candidate = continueCandidates[index + 1]
                        ContinueCandidateRow(
                            candidate = candidate,
                            onOpen = { openContinueCandidate(candidate) },
                        )
                    }
                }

                if (hasContinueError) {
                    item(key = "home_continue_error") {
                        if (continueCandidates.isEmpty()) {
                            HomeContinueErrorState(onRetry = vm::retryContinueCandidates)
                        } else {
                            HomeContinueInlineError(onRetry = vm::retryContinueCandidates)
                        }
                    }
                }

                item(key = "home_conversations_header") {
                    HomeConversationHeader()
                }

                if (hasConversationError && conversations.isEmpty()) {
                    item(key = "home_conversations_error") {
                        HomeConversationErrorState(onRetry = vm::retryConversations)
                    }
                } else {
                    if (hasConversationError) {
                        item(key = "home_conversations_error_inline") {
                            HomeConversationInlineError(onRetry = vm::retryConversations)
                        }
                    }
                    if (conversationsLoaded && visibleConversations.isEmpty() && homeSearchQuery.isNotBlank()) {
                        item(key = "home_search_empty") {
                            HomeSearchEmptyState()
                        }
                    } else if (conversationsLoaded && conversations.isEmpty()) {
                        item(key = "home_empty") {
                            HomeEmptyState(modifier = Modifier.padding(vertical = 56.dp))
                        }
                    }

                    itemsIndexed(
                        items = visibleConversations,
                        key = { _, conversation -> conversation.id.toString() },
                    ) { index, conversation ->
                        HomeSessionRow(
                            conversation = conversation,
                            tileColor = homeConversationTileColor(conversation, tokens),
                            isFirst = index == 0,
                            isLast = index == visibleConversations.lastIndex,
                            // 首页是 hub：用 push（保留 SessionHome 在栈底），返回能回到首页；
                            // 不能用 navigateToChatPage（其内部 clearAndNavigate 会清掉首页）
                            onOpen = {
                                navController.navigate(
                                    Screen.Chat(id = conversation.id.toString())
                                ) { launchSingleTop = true }
                            },
                            onDelete = { vm.deleteConversation(conversation) },
                            onTogglePin = { vm.updatePinnedStatus(conversation) },
                        )
                    }
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
                .padding(end = 18.dp, bottom = 18.dp)
                .height(48.dp)
                .clickable(
                    interactionSource = fabInteractionSource,
                    indication = null,
                    onClick = {
                        // 新会话：push 保留首页在栈底，与列表点开一致
                        navController.navigate(Screen.Chat(id = Uuid.random().toString())) {
                            launchSingleTop = true
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = fabShape,
                        ambientColor = tokens.accent.copy(alpha = 0.24f),
                        spotColor = tokens.accent.copy(alpha = 0.20f),
                    )
                    .clip(fabShape)
                    .background(tokens.accent)
                    .border(0.5.dp, tokens.accentInk.copy(alpha = 0.16f), fabShape)
                    .indication(fabInteractionSource, ripple(bounded = false))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Pen,
                        contentDescription = stringResource(R.string.chat_page_new_message),
                        modifier = Modifier.size(15.dp),
                        tint = tokens.accentInk,
                    )
                    Text(
                        text = stringResource(R.string.history_page_new_conversation),
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = tokens.accentInk,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ header --- */

/** Amber wordmark（mono + 闪烁光标）+ 日期 + 设置齿轮 + 头像。 */
@Composable
internal fun HomeHeader(
    settings: Settings,
    searchExpanded: Boolean,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val defaultUserName = stringResource(R.string.user_default_name)
    val searchInteraction = remember { MutableInteractionSource() }
    val settingsInteraction = remember { MutableInteractionSource() }
    val profileInteraction = remember { MutableInteractionSource() }

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.amber_wordmark),
                    contentDescription = "Amber",
                    modifier = Modifier.size(width = 100.dp, height = 26.dp),
                    tint = tokens.ink,
                )
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(15.dp)
                        .background(tokens.accent.copy(alpha = cursorAlpha)),
                )
            }

            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(44.dp)
                    .then(
                        if (!searchExpanded) {
                            Modifier.clickable(
                                interactionSource = searchInteraction,
                                indication = null,
                                onClick = onOpenSearch,
                            )
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !searchExpanded,
                    enter = fadeIn(animationSpec = tween(190)) +
                        expandHorizontally(
                            expandFrom = Alignment.CenterHorizontally,
                            animationSpec = tween(190),
                        ),
                    exit = fadeOut(animationSpec = tween(170)) +
                        shrinkHorizontally(
                            shrinkTowards = Alignment.CenterHorizontally,
                            animationSpec = tween(170),
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .width(58.dp)
                            .height(32.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .background(tokens.surface2)
                            .border(
                                1.dp,
                                tokens.line,
                                androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            )
                            .indication(searchInteraction, ripple(bounded = false)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.Search,
                                contentDescription = stringResource(R.string.history_page_search),
                                modifier = Modifier.size(14.dp),
                                tint = tokens.ink2,
                            )
                            Text(
                                text = stringResource(R.string.history_page_search),
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = tokens.ink2,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = settingsInteraction,
                        indication = null,
                        onClick = onOpenSettings,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(tokens.surface2)
                        .border(1.dp, tokens.line, CircleShape)
                        .indication(settingsInteraction, ripple(bounded = false)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Settings,
                        contentDescription = stringResource(R.string.settings),
                        modifier = Modifier.size(18.dp),
                        tint = tokens.ink2,
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            // 头像点击进资料页（onUpdate=null 时 UIAvatar 不弹换头像框，仅响应 onClick）
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = profileInteraction,
                        indication = null,
                        onClick = onOpenProfile,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .indication(profileInteraction, ripple(bounded = false)),
                    contentAlignment = Alignment.Center,
                ) {
                    UIAvatar(
                        name = settings.displaySetting.userNickname.ifBlank { defaultUserName },
                        value = settings.displaySetting.userAvatar,
                        size = 32.dp,
                        containerColor = tokens.accent,
                        showEditBadge = false,
                    )
                }
            }
        }

        Text(
            text = todayLabel(),
            modifier = Modifier.fillMaxWidth(),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            letterSpacing = 0.15.sp,
            lineHeight = 13.sp,
            color = tokens.ink3,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
        )
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

/** Home-only title filter; the full message search remains [Screen.MessageSearch]. */
internal fun filterHomeConversations(
    conversations: List<Conversation>,
    query: String,
    untitledLabel: String,
): List<Conversation> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return conversations
    return conversations.filter { conversation ->
        (conversation.title.ifBlank { untitledLabel })
            .contains(trimmedQuery, ignoreCase = true)
    }
}

/** Home-only title search with a direct full-message-search escape hatch. */
@Composable
private fun HomeSearchField(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onOpenFullSearch: () -> Unit,
    onCollapse: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(tokens.surface2)
                    .border(1.dp, tokens.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 14.5.sp,
                        lineHeight = 18.sp,
                        color = tokens.ink,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onOpenFullSearch() }),
                    decorationBox = { innerTextField ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(
                                imageVector = Lucide.Search,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = tokens.ink3,
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (query.isBlank()) {
                                    Text(
                                        text = stringResource(R.string.parity_home_search_sessions),
                                        fontSize = 14.5.sp,
                                        lineHeight = 18.sp,
                                        color = tokens.ink4,
                                    )
                                }
                                innerTextField()
                            }
                        }
                    },
                )
            }
            IconButton(
                onClick = if (query.isBlank()) onCollapse else onClear,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Lucide.X,
                    contentDescription = stringResource(
                        if (query.isBlank()) {
                            R.string.parity_home_search_cancel
                        } else {
                            R.string.parity_home_search_clear
                        }
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = tokens.ink3,
                )
            }
            IconButton(
                onClick = onOpenFullSearch,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    imageVector = Lucide.ScanSearch,
                    contentDescription = stringResource(R.string.parity_home_search_full),
                    modifier = Modifier.size(19.dp),
                    tint = tokens.ink2,
                )
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
private fun HomeContinueErrorState(onRetry: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.parity_home_continue_error),
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
private fun HomeContinueInlineError(onRetry: () -> Unit) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
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
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text(stringResource(R.string.parity_home_retry))
        }
    }
}

/* ------------------------------------------------------------ feature rail --- */

private data class FeatureEntry(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

private data class ContinueFeatureSpec(
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun continueFeatureSpec(sourceKind: ContinueSourceKind): ContinueFeatureSpec = when (sourceKind) {
    ContinueSourceKind.COUNCIL -> ContinueFeatureSpec(
        icon = Lucide.MessageCircle,
        label = stringResource(R.string.session_home_feature_council),
    )
    ContinueSourceKind.DEEP_READ -> ContinueFeatureSpec(
        icon = Lucide.BookOpenText,
        label = stringResource(R.string.session_home_feature_deep_read),
    )
    ContinueSourceKind.MINIAPP_DRAFT,
    ContinueSourceKind.MINIAPP_RUNNER,
    -> ContinueFeatureSpec(
        icon = Lucide.Grid2x2,
        label = stringResource(R.string.session_home_feature_mini_apps),
    )
    ContinueSourceKind.IMAGE_GENERATION -> ContinueFeatureSpec(
        icon = Lucide.Image,
        label = stringResource(R.string.setting_page_built_in_tools_image_generation),
    )
    ContinueSourceKind.NOVEL_WORKSPACE -> ContinueFeatureSpec(
        icon = Lucide.Pen,
        label = stringResource(R.string.session_home_feature_novel),
    )
}

/** Keep the title visible when idle; only a real running summary may crossfade in. */
@Composable
private fun ContinueCandidateSubtitle(
    candidate: ContinueCandidate,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
) {
    val hasRunningSummary = candidate.isRunning && candidate.summary.isNotBlank()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showSummary by remember(candidate.sourceKind, candidate.sourceId) {
        mutableStateOf(false)
    }
    LaunchedEffect(
        lifecycle,
        candidate.sourceKind,
        candidate.sourceId,
        candidate.title,
        candidate.summary,
        candidate.isRunning,
    ) {
        showSummary = false
        if (hasRunningSummary) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                showSummary = false
                while (true) {
                    kotlinx.coroutines.delay(if (showSummary) 2_000L else 4_000L)
                    showSummary = !showSummary
                }
            }
        }
    }
    val subtitle = if (showSummary && hasRunningSummary) candidate.summary else candidate.title
    Crossfade(
        targetState = subtitle,
        animationSpec = tween(220),
        label = "continue-candidate-subtitle",
        modifier = modifier.fillMaxWidth(),
    ) { text ->
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = 15.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            color = tokens.accent,
        )
        Text(
            text = stringResource(R.string.amber_redesign_conversations),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
            color = tokens.ink2,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(tokens.line),
        )
    }
}

/** 继续卡片 + 功能入口：真实数据驱动，空数据时只保留功能入口。 */
@Composable
internal fun HomeFeatureRail(
    resumeCandidate: ContinueCandidate?,
    onOpenResume: (ContinueCandidate) -> Unit,
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

    val railShape = AmberContinuousShape(cornerRadius = 22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(
                elevation = 7.dp,
                shape = railShape,
                ambientColor = tokens.ink.copy(alpha = 0.18f),
                spotColor = tokens.ink.copy(alpha = 0.12f),
            )
            .clip(railShape)
            .background(tokens.surface)
            .border(0.5.dp, tokens.line.copy(alpha = 0.72f), railShape),
    ) {
        resumeCandidate?.let { candidate ->
            val feature = continueFeatureSpec(candidate.sourceKind)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenResume(candidate) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(tokens.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = feature.icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = tokens.ink2,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = feature.label,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ContinueCandidateSubtitle(
                        candidate = candidate,
                        color = tokens.ink2,
                        fontSize = 10.5.sp,
                    )
                }
                Text(
                    text = stringResource(R.string.session_home_continue),
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accent,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                        .background(tokens.accent.copy(alpha = 0.14f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(0.5.dp)
                    .background(tokens.line.copy(alpha = 0.34f))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            features.forEach { feature ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .clickable(onClick = feature.onClick)
                        .heightIn(min = 48.dp)
                        .padding(vertical = 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = feature.icon,
                        contentDescription = feature.label,
                        modifier = Modifier.size(20.dp),
                        tint = tokens.ink2,
                    )
                    Text(
                        text = feature.label,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = tokens.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------- session row --- */

/** P8-08：继续路由 → 应用内页面（点击准确路由到任务焦点）。 */
internal fun ContinueRoute.toScreen(): Screen = when (this) {
    is ContinueRoute.CouncilRoom -> Screen.CouncilRoom(conversationId = conversationId)
    is ContinueRoute.DeepRead -> Screen.DeepRead(
        topicId = topicId,
        title = title,
        sourceUrl = sourceUrl,
    )
    is ContinueRoute.Chat -> Screen.Chat(
        id = conversationId,
        messageId = messageId,
        toolCallId = toolCallId,
    )
    is ContinueRoute.ImageGeneration -> Screen.Chat(
        id = conversationId,
        messageId = messageId,
        toolCallId = toolCallId,
    )
    is ContinueRoute.MiniAppRunner -> Screen.MiniAppRunner(appId = appId)
    is ContinueRoute.NovelWorkspace -> Screen.NovelMarkdown(
        projectId = projectId,
        branchSlug = branchSlug,
        jobId = jobId,
    )
}

/** Chat-backed Continue routes must still own a persisted conversation at click time. */
internal suspend fun canOpenContinueRoute(
    route: ContinueRoute,
    conversationExists: suspend (String) -> Boolean,
): Boolean {
    val conversationId = when (route) {
        is ContinueRoute.Chat -> route.conversationId
        is ContinueRoute.ImageGeneration -> route.conversationId
        else -> return true
    }
    return conversationExists(conversationId)
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
            text = stringResource(R.string.session_home_continue),
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

/** 单条继续候选：功能名 + 标题/真实运行阶段，整行点击进入任务。 */
@Composable
private fun ContinueCandidateRow(
    candidate: ContinueCandidate,
    onOpen: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val feature = continueFeatureSpec(candidate.sourceKind)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.bg)
                .clickable(onClick = onOpen)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = feature.label,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ContinueStatusChip(status = candidate.status)
                }
                ContinueCandidateSubtitle(
                    candidate = candidate,
                    color = tokens.ink3,
                    fontSize = 10.5.sp,
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
            fontFamily = JetBrainsMonoFamily,
            fontSize = 9.5.sp,
            lineHeight = 12.sp,
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
    tileColor: Color,
    isFirst: Boolean,
    isLast: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
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

    val rowShape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (isFirst) 14.dp else 0.dp,
        topEnd = if (isFirst) 14.dp else 0.dp,
        bottomStart = if (isLast) 14.dp else 0.dp,
        bottomEnd = if (isLast) 14.dp else 0.dp,
    )
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
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 10.sp,
                        letterSpacing = 0.4.sp,
                        color = if (isDelete) tokens.accentInk else tokens.accent,
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(rowShape),
    ) {
            Column(modifier = Modifier.fillMaxWidth().background(tokens.surface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.surface)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(tileColor.copy(alpha = 0.13f))
                            .border(1.dp, tileColor.copy(alpha = 0.30f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                conversation.isPinned -> Lucide.Pin
                                conversation.title.contains("阅读", ignoreCase = true) -> Lucide.BookOpenText
                                conversation.title.contains("小说", ignoreCase = true) -> Lucide.Pen
                                else -> Lucide.MessageCircle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                            tint = tileColor,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = conversation.title.ifBlank { newMessageLabel },
                            fontSize = 13.5.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.2).sp,
                            color = tokens.ink,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = sessionTimeLabel(conversation.updateAt),
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Normal,
                                color = tokens.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "·",
                                fontFamily = JetBrainsMonoFamily,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = tokens.ink4,
                            )
                            SessionCountBadge(count = conversation.messageCount)
                        }
                    }

                }
                if (!isLast) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 60.dp, end = 14.dp)
                            .height(1.dp)
                            .background(tokens.line),
                    )
                }
        }
    }
}

/** 消息数与时间保持相同的字号、字重和灰度。 */
@Composable
private fun SessionCountBadge(count: Int) {
    val tokens = LocalAmberTokens.current
    Text(
        text = "${if (count > 99) "99+" else count} 条",
        fontFamily = JetBrainsMonoFamily,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
        color = tokens.ink3,
        maxLines = 1,
    )
}

private fun homeConversationTileColor(
    conversation: Conversation,
    tokens: app.amber.feature.ui.theme.AmberTokens,
): Color {
    if (conversation.isPinned) return tokens.accent
    val palette = listOf(
        Color(0xFF5E9C6E),
        Color(0xFF4F86D6),
        Color(0xFF9277C4),
        Color(0xFFC2607A),
    )
    return palette[Math.floorMod(conversation.id.hashCode(), palette.size)]
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
            text = stringResource(R.string.session_home_empty_hint),
            fontSize = 13.5.sp,
            color = tokens.ink3,
            textAlign = TextAlign.Center,
        )
    }
}
