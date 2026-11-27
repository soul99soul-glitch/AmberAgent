package app.amber.feature.ui.pages.personalagent

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.PersonalAgentMainSessionStore
import app.amber.agent.Screen
import app.amber.core.service.LocalAsyncTaskCoordinator
import app.amber.feature.macgateway.CloudProactiveRepository
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.macgateway.ProactiveDeepLinkInbox
import app.amber.feature.macgateway.ProactiveForegroundStageTracker
import app.amber.feature.ui.components.ai.SubAgentDockState
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.chat.ChatPage
import app.amber.feature.ui.pages.chat.ChatStageMode
import app.amber.feature.ui.pages.proactive.LocalProactiveCardDetailOpener
import app.amber.feature.ui.pages.proactive.ProactiveDetailSheet
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Default Personal Agent home stage aligned with iOS & HarmonyOS Amber Duet.
 *
 * Reuses [ChatPage] in [ChatStageMode.PersonalAgent] and resolves one stable main conversation ID
 * per host (local vs paired gateway) via [PersonalAgentMainSessionStore].
 */
@Composable
fun PersonalAgentPage(
    enterTransition: Transition<EnterExitState>? = null,
) {
    val navController = LocalNavController.current
    val mainSessionStore: PersonalAgentMainSessionStore = koinInject()
    val macGatewayRepository: MacGatewayRepository = koinInject()
    val proactiveRepository: CloudProactiveRepository = koinInject()
    val proactiveDeepLinkInbox: ProactiveDeepLinkInbox = koinInject()
    val localAsyncTaskCoordinator: LocalAsyncTaskCoordinator = koinInject()
    val subAgentDockState: SubAgentDockState = koinInject()
    val gatewayState by macGatewayRepository.state.collectAsStateWithLifecycle()
    val pendingDetail by proactiveDeepLinkInbox.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    if (!gatewayState.loaded) {
        Box(modifier = Modifier.fillMaxSize().amberCanvas())
        return
    }

    val activeConnection = gatewayState.connection
    val isCloudActive = gatewayState.isCloudDialogueActive && activeConnection != null
    if (isCloudActive) {
        LifecycleResumeEffect(activeConnection) {
            val owner = Any()
            ProactiveForegroundStageTracker.enter(owner, activeConnection)
            onPauseOrDispose { ProactiveForegroundStageTracker.leave(owner) }
        }
    }
    val resolvedId = remember(isCloudActive, activeConnection?.gatewayId) {
        if (isCloudActive) {
            mainSessionStore.cloudMainConversationId(activeConnection.gatewayId)
        } else {
            mainSessionStore.localMainConversationId()
        }
    }
    val resolvedUuid = remember(resolvedId) { Uuid.parse(resolvedId) }

    val proactiveBadgeCountFlow = remember(activeConnection?.gatewayId, activeConnection?.deviceId, proactiveRepository) {
        if (activeConnection != null) {
            proactiveRepository
                .cards(activeConnection.gatewayId, activeConnection.deviceId)
                .map { cards -> cards.count { it.feedbackState == null } }
                .distinctUntilChanged()
        } else {
            flowOf(0)
        }
    }
    val proactiveBadgeCount by proactiveBadgeCountFlow.collectAsStateWithLifecycle(initialValue = 0)

    val activeTaskCountFlow = remember(macGatewayRepository, localAsyncTaskCoordinator, subAgentDockState) {
        combine(
            macGatewayRepository.cloudDialogueState
                .map { state -> state.tasks.count { it.active } }
                .distinctUntilChanged(),
            localAsyncTaskCoordinator.uiState
                .map { state -> state.tasks.count { it.active } }
                .distinctUntilChanged(),
            subAgentDockState.uiState
                .map { state -> if (state.enabled) state.tasks.count { it.status.keepsDockVisible } else 0 }
                .distinctUntilChanged(),
        ) { cloudActiveCount, localActiveCount, subAgentCount ->
            cloudActiveCount + localActiveCount + subAgentCount
        }.distinctUntilChanged()
    }
    val activeTaskCount by activeTaskCountFlow.collectAsStateWithLifecycle(initialValue = 0)

    val openSessions = remember(navController) {
        {
            navController.navigate(Screen.SessionHome) {
                launchSingleTop = true
            }
        }
    }

    CompositionLocalProvider(
        LocalProactiveCardDetailOpener provides { signalId ->
            proactiveDeepLinkInbox.submit(signalId, activeConnection?.gatewayId, activeConnection?.deviceId)
        },
    ) {
        key(resolvedId) {
            ChatPage(
                id = resolvedUuid,
                text = null,
                files = emptyList(),
                enterTransition = enterTransition,
                stage = ChatStageMode.PersonalAgent(
                    topChrome = { headerState ->
                        val topicLabel = headerState.title.ifBlank {
                            headerState.modelShortName?.takeIf { it.isNotBlank() }
                                ?: headerState.soulDisplayName.ifBlank { "Amber" }
                        }
                        PersonalAgentTopChrome(
                            topicLabel = topicLabel,
                            generating = headerState.generating,
                            recapEligible = headerState.recapEligible,
                            proactiveBadgeCount = proactiveBadgeCount,
                            onOpenWorkspace = headerState.onOpenWorkspace,
                            onOpenAgentSoul = headerState.onOpenAgentSoul,
                            onToggleModelMenu = headerState.onToggleModelMenu,
                            onOpenRecap = headerState.onOpenRecap,
                            onOpenProactiveCenter = {
                                navController.navigate(Screen.CloudProactiveCenter) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    },
                    bottomChrome = { imeVisible ->
                        PersonalAgentBottomBar(
                            visible = !imeVisible,
                            selectedTab = PersonalAgentModeTab.CHAT,
                            activeTaskCount = activeTaskCount,
                            isCloudSelected = isCloudActive,
                            cloudAvailable = gatewayState.canSelectCloud,
                            gatewayName = activeConnection?.name,
                            onSelectChat = {},
                            onOpenTasks = {
                                navController.navigate(Screen.TodayBoard) {
                                    launchSingleTop = true
                                }
                            },
                            onOpenCenter = {
                                navController.navigate(Screen.CloudProactiveCenter) {
                                    launchSingleTop = true
                                }
                            },
                            onSelectCloud = { enabled ->
                                scope.launch {
                                    macGatewayRepository.setCloudDialogueEnabled(enabled)
                                }
                            },
                            onOpenGatewayPairing = {
                                navController.navigate(Screen.SettingExperimentalMacGateway) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    },
                    workspaceSheet = { visible, hasMessages, soulDisplayName, onDismiss, onToggleShelf, onOpenAgentSoul ->
                        if (visible) {
                            PersonalAgentWorkspaceSheet(
                                currentConversationId = resolvedUuid,
                                soulDisplayName = soulDisplayName,
                                pairedGatewayName = activeConnection?.name,
                                hasMessages = hasMessages,
                                proactiveBadgeCount = proactiveBadgeCount,
                                onDismiss = onDismiss,
                                onOpenSearch = {
                                    navController.navigate(Screen.MessageSearch) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenSettings = {
                                    navController.navigate(Screen.Setting) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenAgentSoul = onOpenAgentSoul,
                                onNewTopic = {
                                    navController.navigate(Screen.Chat(id = Uuid.random().toString()))
                                },
                                onOpenPhoneAutomation = {
                                    navController.navigate(Screen.SettingPhoneUseDisplay) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenTodayBoard = {
                                    navController.navigate(Screen.TodayBoard) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenProactiveCenter = {
                                    navController.navigate(Screen.CloudProactiveCenter) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenPairedGateway = {
                                    navController.navigate(Screen.SettingExperimentalMacGateway) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenAllSessions = openSessions,
                                onToggleShelf = onToggleShelf,
                                onSelectConversation = { targetId ->
                                    navController.navigate(Screen.Chat(id = targetId.toString()))
                                },
                            )
                        }
                    },
                ),
            )
        }

        pendingDetail?.let { request ->
            if (request.matches(activeConnection)) {
                key(activeConnection?.gatewayId, activeConnection?.deviceId, request) {
                    ProactiveDetailSheet(
                        request = request.copy(gatewayId = activeConnection?.gatewayId, deviceId = activeConnection?.deviceId),
                        conversationId = activeConnection?.let { mainSessionStore.cloudMainConversationId(it.gatewayId) },
                        repository = proactiveRepository,
                        onDismiss = { proactiveDeepLinkInbox.consume(request) },
                    )
                }
            } else {
                LaunchedEffect(request) { proactiveDeepLinkInbox.consume(request) }
            }
        }
    }
}
