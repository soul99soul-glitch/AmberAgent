package app.amber.feature.ui.pages.sessionhome

import app.amber.feature.ui.utils.ChatPerfHomeEntry
import android.os.Build
import androidx.activity.compose.LocalActivity

import app.amber.feature.ui.utils.amberTraceMeasure
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.agent.LAST_CONVERSATION_ID_PREF
import app.amber.feature.modelcouncil.CouncilRoomOpResult
import app.amber.feature.ui.adaptive.LocalTwoPaneLayout
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.ds.AMBER_HDR_IDLE_HEADROOM
import app.amber.feature.ui.components.ds.supportsAmberHdr
import app.amber.feature.ui.components.ds.AMBER_HDR_PRESS_HEADROOM
import app.amber.feature.ui.components.ds.AmberContinuousShape
import app.amber.feature.ui.components.ds.AmberDepthStyle
import app.amber.feature.ui.components.ds.LocalAmberHdrPress
import app.amber.feature.ui.components.ds.amberPressHighlight
import app.amber.feature.ui.components.ds.amberRim
import app.amber.feature.ui.components.ds.amberShadow
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.context.LocalSettings
import app.amber.feature.ui.context.LocalToaster
import app.amber.feature.ui.hooks.rememberSharedPreferenceString
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.home.ContinueCandidate
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.paging.compose.collectAsLazyPagingItems
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pen
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext

/**
 * Session 首页 —— Scrollable home of the app (Terminal × Modern graphite design).
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
    val toaster = LocalToaster.current
    val vm: SessionHomeVM = koinViewModel()
    val conversations = vm.conversations.collectAsLazyPagingItems()
    val deleteState = vm.deleteUndoState.collectAsStateWithLifecycle().value
    val deleteFailure = vm.deleteFailure.collectAsStateWithLifecycle().value
    val density = LocalDensity.current
    val imeVisible = WindowInsets.isImeVisible
    var bottomOverlayHeight by remember { mutableIntStateOf(0) }
    var lastUndoCount by remember { mutableIntStateOf(1) }
    if (deleteState.pendingIds.isNotEmpty()) lastUndoCount = deleteState.pendingIds.size
    val hasConversationError = vm.hasConversationError.collectAsStateWithLifecycle().value
    var homeSearchQuery by rememberSaveable { mutableStateOf("") }
    var homeSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val homeSearchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val untitledConversationLabel = stringResource(R.string.parity_home_new_conversation)
    val continueCandidates = vm.continueCandidates.collectAsStateWithLifecycle().value
    val hasContinueError = vm.hasContinueError.collectAsStateWithLifecycle().value
    val lastConversationId = rememberSharedPreferenceString(LAST_CONVERSATION_ID_PREF).value
    val listState = rememberLazyListState()
    val hazeState = rememberHazeState()
    val hasTopOverflow by remember {
        derivedStateOf { listState.canScrollBackward }
    }
    var topFadeActive by remember { mutableStateOf(false) }
    LaunchedEffect(hasTopOverflow) {
        if (hasTopOverflow) {
            topFadeActive = true
        } else {
            // Keep the source attached for the short fade-out so Haze does not
            // drop to a blank sample while the scrim is leaving the screen.
            kotlinx.coroutines.delay(180)
            topFadeActive = false
        }
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fabShape = remember { AmberContinuousShape(20.dp) }
    val fabInteractionSource = remember { MutableInteractionSource() }
    val fabFill = remember(tokens.accent) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(tokens.accent.toArgb(), hsv)
        Brush.verticalGradient(
            listOf(
                Color.hsv(hsv[0], hsv[1], (hsv[2] + 0.035f).coerceAtMost(1f)),
                Color.hsv(hsv[0], hsv[1], (hsv[2] - 0.025f).coerceAtLeast(0f)),
            ),
        )
    }
    val activity = LocalActivity.current
    val window = activity?.window
    // The window stays in HDR mode app-wide (RouteActivity); home only raises the
    // headroom while a card is pressed, so no mode switch happens on navigation.
    val hdrAvailable = activity?.supportsAmberHdr() == true
    var hdrPressCount by remember { mutableIntStateOf(0) }
    val onHdrPress = remember { { pressed: Boolean -> hdrPressCount += if (pressed) 1 else -1 } }
    LaunchedEffect(window, hdrAvailable, hdrPressCount) {
        if (hdrAvailable && Build.VERSION.SDK_INT >= 35) {
            window?.setDesiredHdrHeadroom(
                if (hdrPressCount > 0) AMBER_HDR_PRESS_HEADROOM else AMBER_HDR_IDLE_HEADROOM,
            )
        }
    }
    DisposableEffect(window, hdrAvailable) {
        onDispose {
            // Leaving mid-press must not strand the raised headroom on other screens.
            if (hdrAvailable && Build.VERSION.SDK_INT >= 35) {
                window?.setDesiredHdrHeadroom(AMBER_HDR_IDLE_HEADROOM)
            }
        }
    }
    val deleteFailureMessage = when (deleteFailure?.kind) {
        HomeDeleteFailureKind.Restored -> stringResource(R.string.session_home_delete_failed_restored)
        HomeDeleteFailureKind.AlreadyDeleted -> stringResource(R.string.session_home_delete_cleanup_failed)
        HomeDeleteFailureKind.Unverified -> stringResource(R.string.session_home_delete_unverified)
        null -> null
    }
    LaunchedEffect(deleteFailure, deleteFailureMessage) {
        deleteFailure?.let { failure ->
            deleteFailureMessage?.let { message -> toaster.show(message, type = ToastType.Error) }
            vm.acknowledgeDeleteFailure(failure.sequence)
        }
    }
    val operationError = stringResource(R.string.error_title_operation)
    // Beside a chat (tablet / unfolded landscape) the composer owns the bottom edge, so the
    // floating new-session pill moves into the header.
    val inTwoPane = LocalTwoPaneLayout.current != null
    // 首页是 hub：用 push（保留 SessionHome 在栈底），返回能回到首页；不能用 navigateToChatPage
    // （其内部 clearAndNavigate 会清掉首页）。popUpTo 在手机上是空操作（首页在栈顶才点得到），
    // 两栏时把右栏已开的会话换掉，而不是越叠越深。
    val openChat = homeChatNavigation(navController)

    LaunchedEffect(homeSearchQuery, untitledConversationLabel) {
        vm.setHomeSearchQuery(homeSearchQuery, untitledConversationLabel)
    }

    val openCouncilRoom: () -> Unit = {
        scope.launch {
            try {
                when (val result = vm.createCouncilRoom(context.getString(R.string.council_room_default_objective))) {
                    is CouncilRoomOpResult.Ok -> navController.navigate(
                        Screen.CouncilRoom(conversationId = result.room.conversationId.toString()),
                    )
                    is CouncilRoomOpResult.Err -> {
                        toaster.show(result.message.ifBlank { result.code }, type = ToastType.Error)
                        android.util.Log.w("SessionHomeCouncil", "openRoom failed: ${result.code}")
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                toaster.show(error.message?.takeIf { it.isNotBlank() } ?: operationError, type = ToastType.Error)
                android.util.Log.e("SessionHomeCouncil", "failed to open council room", error)
            }
        }
    }

    val openContinueCandidate: (ContinueCandidate) -> Unit = { candidate ->
        scope.launch {
            val canOpen = try {
                vm.canOpenContinue(candidate.route)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                android.util.Log.e("SessionHomeRoute", "Unable to validate Continue route", error)
                toaster.show(context.getString(R.string.session_home_open_conversation_failed), type = ToastType.Error)
                return@launch
            }
            if (!canOpen) {
                toaster.show(context.getString(R.string.session_home_conversation_missing), type = ToastType.Error)
                return@launch
            }
            when (val screen = candidate.route.toScreen()) {
                is Screen.Chat -> openChat(screen)
                else -> navController.navigate(screen) { launchSingleTop = true }
            }
        }
    }

    CompositionLocalProvider(LocalAmberHdrPress provides if (hdrAvailable) onHdrPress else null) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (hasTopOverflow || topFadeActive) {
                            Modifier.hazeSource(state = hazeState, zIndex = 0f)
                        } else Modifier,
                    )
                    .amberCanvas(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .amberTraceMeasure("Amber Home list measure"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                        bottom = with(density) { bottomOverlayHeight.toDp() } + 12.dp,
                    ),
                ) {
                    item(key = "home_header") {
                        HomeHeader(
                            settings = settings,
                            searchExpanded = homeSearchExpanded,
                            hazeState = hazeState,
                            hazeSourceActive = hasTopOverflow || topFadeActive,
                            onOpenSearch = { homeSearchExpanded = true },
                            onOpenSettings = { navController.navigate(Screen.Setting) },
                            onOpenProfile = { navController.navigate(Screen.Profile) },
                            onNewSession = if (inTwoPane && !imeVisible) {
                                { openChat(Screen.Chat(id = Uuid.random().toString())) }
                            } else null,
                        )
                    }

                    item(key = "home_search") {
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
                    }

                    item(key = "home_features") {
                        HomeFeatureRail(
                            resumeCandidate = continueCandidates.maxByOrNull { it.lastUpdatedAt },
                            onOpenResume = openContinueCandidate,
                            onDeepRead = { navController.navigate(Screen.TodayBoard) },
                            onMiniApps = { navController.navigate(Screen.MiniAppList) },
                            onNovel = { navController.navigate(Screen.NovelProjects) },
                            onWebMount = { navController.navigate(Screen.SettingExperimentalWebMount) },
                            onCouncil = openCouncilRoom,
                        )
                    }

                    if (hasContinueError) {
                        item(key = "home_continue_error") {
                            HomeContinueInlineError(onRetry = vm::retryContinueCandidates)
                        }
                    }

                    homeConversationItems(
                        conversations = conversations,
                        hasConversationError = hasConversationError,
                        rowVersions = deleteState.rowVersions,
                        homeSearchQuery = homeSearchQuery,
                        lastConversationId = lastConversationId,
                        tokens = tokens,
                        onRetry = vm::retryConversations,
                        onOpen = {
                            if (!vm.isConversationHidden(it.id)) openChat(Screen.Chat(id = it.id.toString()))
                        },
                        onDelete = vm::deleteConversation,
                        onTogglePin = vm::updatePinnedStatus,
                    )
                }
            }

            // Header 和列表一起滚动；内容越过顶缘后保留渐隐，停手也不会重新硬切。
            HomeScrollTopFade(
                hazeState = hazeState,
                visible = hasTopOverflow,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (!inTwoPane) {
                // 列表底部渐隐，给 FAB 让出视觉空间（对齐设计稿的 mask 渐隐）。
                // 列表沉浸到小白条下方，渐隐也铺到屏幕底；高度 = navigationBars + 72
                // （FAB 占位 bottom 24 + 胶囊高 48），盖住胶囊顶缘以上的列表行。
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 72.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, tokens.bg),
                            )
                        )
                )

            }

            // Measure the complete overlay, including IME/navigation insets. The
            // list reserves the actual height even with large text or two panes.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { bottomOverlayHeight = it.height }
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (!inTwoPane && !imeVisible) {
                    // Floating new-session button —— iOS 同款胶囊（铅笔 + 新对话）
                    Box(
                        modifier = Modifier
                            .padding(end = 26.dp, bottom = 14.dp)
                            .height(48.dp)
                            .clickable(
                                interactionSource = fabInteractionSource,
                                indication = null,
                                onClick = {
                                    // 新会话：push 保留首页在栈底，与列表点开一致
                                    openChat(Screen.Chat(id = Uuid.random().toString()))
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .amberPressHighlight(
                                    fabInteractionSource,
                                    fabShape,
                                    DpOffset(0.dp, 4.dp),
                                    hdrHighlight = true,
                                    glowTint = tokens.accent,
                                )
                                .amberShadow(fabShape, AmberDepthStyle.Accent)
                                .background(fabFill, fabShape)
                                .amberRim(fabShape, AmberDepthStyle.Accent)
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
                // Opacity only; Compose respects the system animator duration scale.
                // Disable immediately on commit/undo, even during the short exit.
                AnimatedVisibility(
                    visible = deleteState.pendingIds.isNotEmpty(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    enter = fadeIn(tween(160, easing = HomeSessionSwipeMotion.EaseOut)),
                    exit = fadeOut(tween(160, easing = HomeSessionSwipeMotion.EaseOut)),
                ) {
                    HomeDeleteUndoBar(
                        count = lastUndoCount,
                        seconds = deleteState.remainingSeconds,
                        onUndo = vm::undoConversationDeletion,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        enabled = deleteState.pendingIds.isNotEmpty(),
                    )
                }
            }
        }
    }
}

/** The same callback used by real Home rows; diagnostics precede original navigation. */
internal fun homeChatNavigation(navController: app.amber.feature.ui.context.Navigator): (Screen.Chat) -> Unit = { chat ->
    ChatPerfHomeEntry.openFromHome(chat.id)
    navController.navigate(chat) {
        popUpTo(Screen.SessionHome)
        launchSingleTop = true
    }
}
