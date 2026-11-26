package app.amber.feature.ui.pages.personalagent

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.amber.agent.PersonalAgentMainSessionStore
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.feature.macgateway.CloudProactiveRepository
import app.amber.feature.macgateway.MacGatewayRepository
import app.amber.feature.macgateway.ProactiveDeepLinkInbox
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.context.LocalNavController
import app.amber.feature.ui.pages.chat.ChatPage
import app.amber.feature.ui.pages.chat.ChatStageMode
import app.amber.feature.ui.pages.chat.LocalPersonalAgentMoreMenu
import app.amber.feature.ui.pages.proactive.LocalProactiveCardDetailOpener
import app.amber.feature.ui.pages.proactive.ProactiveDetailSheet
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Sparkles
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Default Personal Agent home stage.
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
    val gatewayState by macGatewayRepository.state.collectAsStateWithLifecycle()
    val pendingProactiveSignalId by proactiveDeepLinkInbox.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    if (!gatewayState.loaded) {
        Box(modifier = Modifier.fillMaxSize().amberCanvas())
        return
    }

    val activeConnection = gatewayState.connection
    val isCloudActive = gatewayState.isCloudDialogueActive && activeConnection != null
    val resolvedId = remember(isCloudActive, activeConnection?.gatewayId) {
        if (isCloudActive) {
            mainSessionStore.cloudMainConversationId(activeConnection.gatewayId)
        } else {
            mainSessionStore.localMainConversationId()
        }
    }

    val openSessions = remember(navController) {
        {
            navController.navigate(Screen.SessionHome) {
                launchSingleTop = true
            }
        }
    }

    CompositionLocalProvider(
        LocalProactiveCardDetailOpener provides { signalId ->
            proactiveDeepLinkInbox.submit(signalId)
        },
        LocalPersonalAgentMoreMenu provides { hasMessages, onToggleShelf ->
            PersonalAgentOverflowMenu(
                pairedGatewayName = activeConnection?.name,
                hasMessages = hasMessages,
                onToggleShelf = onToggleShelf,
                onOpenTodayBoard = { navController.navigate(Screen.TodayBoard) },
                onOpenProactiveCenter = { navController.navigate(Screen.CloudProactiveCenter) },
                onOpenPairedGateway = { navController.navigate(Screen.SettingExperimentalMacGateway) },
                onOpenSettings = { navController.navigate(Screen.Setting) },
            )
        },
    ) {
        key(resolvedId) {
            ChatPage(
                id = Uuid.parse(resolvedId),
                text = null,
                files = emptyList(),
                enterTransition = enterTransition,
                stage = ChatStageMode.PersonalAgent(
                    topBarLeading = {
                        PersonalAgentHostPill(
                            isCloudSelected = isCloudActive,
                            cloudAvailable = activeConnection != null,
                            gatewayName = activeConnection?.name,
                            onSelectCloud = { enabled ->
                                scope.launch {
                                    macGatewayRepository.setCloudDialogueEnabled(enabled)
                                }
                            },
                        )
                    },
                    onOpenSessions = openSessions,
                ),
            )
        }

        val detailSignalId = pendingProactiveSignalId
        if (detailSignalId != null) {
            val detailConversationId = activeConnection?.let {
                mainSessionStore.cloudMainConversationId(it.gatewayId)
            } ?: resolvedId
            ProactiveDetailSheet(
                signalId = detailSignalId,
                conversationId = detailConversationId,
                repository = proactiveRepository,
                onDismiss = { proactiveDeepLinkInbox.consume(detailSignalId) },
            )
        }
    }
}

@Composable
private fun PersonalAgentOverflowMenu(
    pairedGatewayName: String?,
    hasMessages: Boolean,
    onToggleShelf: () -> Unit,
    onOpenTodayBoard: () -> Unit,
    onOpenProactiveCenter: () -> Unit,
    onOpenPairedGateway: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    var expanded by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.personal_agent_more)

    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .testTag("personal-agent-more")
                .clickable(role = Role.Button) { expanded = true }
                .semantics { contentDescription = moreLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.EllipsisVertical,
                contentDescription = null,
                tint = tokens.ink,
                modifier = Modifier.size(20.dp),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Lucide.LayoutDashboard,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.personal_agent_menu_today_board),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    expanded = false
                    onOpenTodayBoard()
                },
            )
            DropdownMenuItem(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("personal-agent-menu-proactive-center"),
                leadingIcon = {
                    Icon(
                        imageVector = Lucide.Sparkles,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.proactive_center_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                if (!pairedGatewayName.isNullOrBlank()) {
                                    R.string.proactive_center_entry_desc
                                } else {
                                    R.string.proactive_center_entry_unpaired_desc
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.ink3,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onOpenProactiveCenter()
                },
            )
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Lucide.Link2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Column {
                        Text(
                            text = stringResource(R.string.personal_agent_menu_paired_gateway),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (!pairedGatewayName.isNullOrBlank()) {
                                stringResource(
                                    R.string.personal_agent_menu_paired_gateway_connected,
                                    pairedGatewayName,
                                )
                            } else {
                                stringResource(R.string.personal_agent_menu_paired_gateway_unpaired)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.ink3,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onOpenPairedGateway()
                },
            )
            if (hasMessages) {
                DropdownMenuItem(
                    modifier = Modifier.heightIn(min = 48.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = Lucide.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.personal_agent_menu_shelf),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        expanded = false
                        onToggleShelf()
                    },
                )
            }
            DropdownMenuItem(
                modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = {
                    Icon(
                        imageVector = Lucide.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.personal_agent_menu_settings),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                onClick = {
                    expanded = false
                    onOpenSettings()
                },
            )
        }
    }
}
