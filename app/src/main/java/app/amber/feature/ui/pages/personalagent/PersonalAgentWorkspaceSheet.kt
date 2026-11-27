package app.amber.feature.ui.pages.personalagent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.amber.agent.R
import app.amber.core.model.Conversation
import app.amber.core.repository.ConversationRepository
import app.amber.feature.ui.theme.LocalAmberTokens
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.Inbox
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.LayoutDashboard
import com.composables.icons.lucide.Link2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.MessageSquare
import com.composables.icons.lucide.MessagesSquare
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Smartphone
import com.composables.icons.lucide.Sparkles
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Large bottom workspace & history drawer sheet opened by the top-left `≡` button,
 * aligned with iOS `AmberDuetWorkspaceDrawerSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun PersonalAgentWorkspaceSheet(
    currentConversationId: Uuid,
    soulDisplayName: String,
    pairedGatewayName: String?,
    hasMessages: Boolean,
    proactiveBadgeCount: Int,
    onDismiss: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgentSoul: () -> Unit,
    onNewTopic: () -> Unit,
    onOpenPhoneAutomation: () -> Unit,
    onOpenTodayBoard: () -> Unit,
    onOpenProactiveCenter: () -> Unit,
    onOpenPairedGateway: () -> Unit,
    onOpenAllSessions: () -> Unit,
    onToggleShelf: () -> Unit,
    onSelectConversation: (Uuid) -> Unit,
) {
    val tokens = LocalAmberTokens.current
    val conversationRepository: ConversationRepository = koinInject()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var recentConversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }

    LaunchedEffect(Unit) {
        recentConversations = runCatching {
            conversationRepository.getRecentConversationSummaries(limit = 5)
        }.getOrDefault(emptyList())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = tokens.bg,
        contentColor = tokens.ink,
        scrimColor = Color.Black.copy(alpha = if (tokens.isDark) 0.56f else 0.34f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .semantics { testTagsAsResourceId = true }
                .navigationBarsPadding(),
        ) {
            // Sheet Top Bar: Search | 导航与历史 | Settings (terracotta)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            onDismiss()
                            onOpenSearch()
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Search,
                        contentDescription = null,
                        tint = tokens.ink,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.personal_agent_drawer_search),
                        color = tokens.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.personal_agent_drawer_title),
                        color = tokens.ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable {
                            onDismiss()
                            onOpenSettings()
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Settings,
                        contentDescription = null,
                        tint = tokens.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.personal_agent_menu_settings),
                        color = tokens.accentLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                // 1. Agent Primary Card
                AgentPrimaryCard(
                    soulDisplayName = soulDisplayName,
                    pairedGatewayName = pairedGatewayName,
                    onOpenAgentSoul = {
                        onDismiss()
                        onOpenAgentSoul()
                    },
                    onNewTopic = {
                        onDismiss()
                        onNewTopic()
                    },
                )

                // 2. Common Features & Settings
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.personal_agent_drawer_section_features),
                        color = tokens.ink3,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.surface,
                        border = BorderStroke(0.5.dp, tokens.floatingOutline),
                        tonalElevation = 0.dp,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            DrawerFeatureRow(
                                icon = Lucide.Smartphone,
                                title = stringResource(R.string.personal_agent_drawer_phone_automation),
                                subtitle = stringResource(R.string.personal_agent_drawer_phone_automation_desc),
                                onClick = {
                                    onDismiss()
                                    onOpenPhoneAutomation()
                                },
                            )
                            DrawerHairline()
                            DrawerFeatureRow(
                                icon = Lucide.LayoutDashboard,
                                title = stringResource(R.string.personal_agent_menu_today_board),
                                subtitle = stringResource(R.string.personal_agent_drawer_today_board_desc),
                                onClick = {
                                    onDismiss()
                                    onOpenTodayBoard()
                                },
                            )
                            DrawerHairline()
                            DrawerFeatureRow(
                                icon = Lucide.Inbox,
                                title = stringResource(R.string.proactive_center_title),
                                subtitle = if (!pairedGatewayName.isNullOrBlank()) {
                                    stringResource(R.string.proactive_center_entry_desc)
                                } else {
                                    stringResource(R.string.proactive_center_entry_unpaired_desc)
                                },
                                badgeCount = proactiveBadgeCount,
                                modifier = Modifier.testTag("personal-agent-menu-proactive-center"),
                                onClick = {
                                    onDismiss()
                                    onOpenProactiveCenter()
                                },
                            )
                            DrawerHairline()
                            DrawerFeatureRow(
                                icon = Lucide.Link2,
                                title = stringResource(R.string.personal_agent_menu_paired_gateway),
                                subtitle = if (!pairedGatewayName.isNullOrBlank()) {
                                    stringResource(
                                        R.string.personal_agent_menu_paired_gateway_connected,
                                        pairedGatewayName,
                                    )
                                } else {
                                    stringResource(R.string.personal_agent_menu_paired_gateway_unpaired)
                                },
                                onClick = {
                                    onDismiss()
                                    onOpenPairedGateway()
                                },
                            )
                            DrawerHairline()
                            DrawerFeatureRow(
                                icon = Lucide.MessagesSquare,
                                title = stringResource(R.string.personal_agent_drawer_all_sessions),
                                subtitle = stringResource(R.string.personal_agent_drawer_all_sessions_desc),
                                modifier = Modifier.testTag("personal-agent-sessions"),
                                onClick = {
                                    onDismiss()
                                    onOpenAllSessions()
                                },
                            )
                            if (hasMessages) {
                                DrawerHairline()
                                DrawerFeatureRow(
                                    icon = Lucide.Layers,
                                    title = stringResource(R.string.personal_agent_drawer_shelf),
                                    subtitle = stringResource(R.string.personal_agent_drawer_shelf_desc),
                                    onClick = {
                                        onDismiss()
                                        onToggleShelf()
                                    },
                                )
                            }
                        }
                    }
                }

                // 3. Recent Topics (5 real sessions)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.personal_agent_drawer_recent_topics),
                            color = tokens.ink3,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    onDismiss()
                                    onOpenAllSessions()
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.personal_agent_drawer_manage_all),
                                color = tokens.accentLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                imageVector = Lucide.ChevronRight,
                                contentDescription = null,
                                tint = tokens.accent,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.surface,
                        border = BorderStroke(0.5.dp, tokens.floatingOutline),
                        tonalElevation = 0.dp,
                    ) {
                        if (recentConversations.isEmpty()) {
                            Text(
                                text = stringResource(R.string.personal_agent_drawer_recent_empty),
                                color = tokens.ink3,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                recentConversations.forEachIndexed { index, summary ->
                                    val isCurrent = summary.id == currentConversationId
                                    val fallbackTitle = stringResource(R.string.personal_agent_drawer_untitled_topic)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable {
                                                onDismiss()
                                                if (!isCurrent) {
                                                    onSelectConversation(summary.id)
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                summary.isPinned -> Lucide.Pin
                                                isCurrent -> Lucide.MessageSquare
                                                else -> Lucide.Clock
                                            },
                                            contentDescription = null,
                                            tint = if (isCurrent) tokens.accent else tokens.ink3,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = summary.title.ifBlank { fallbackTitle },
                                            color = tokens.ink,
                                            fontSize = 14.sp,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (isCurrent) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(tokens.accent.copy(alpha = 0.12f))
                                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.personal_agent_drawer_current_badge),
                                                    color = tokens.accentLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                        }
                                    }
                                    if (index < recentConversations.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 40.dp),
                                            thickness = 0.6.dp,
                                            color = tokens.line,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AgentPrimaryCard(
    soulDisplayName: String,
    pairedGatewayName: String?,
    onOpenAgentSoul: () -> Unit,
    onNewTopic: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tokens.surface,
        border = BorderStroke(0.5.dp, tokens.floatingOutline),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier.clickable(onClick = onOpenAgentSoul),
                contentAlignment = Alignment.BottomEnd,
            ) {
                AmberPersonaMedallion(diameter = 46.dp)
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(tokens.accent)
                        .border(1.5.dp, tokens.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Sparkles,
                        contentDescription = null,
                        tint = tokens.accentInk,
                        modifier = Modifier.size(9.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.personal_agent_drawer_primary_title,
                            soulDisplayName.ifBlank { "Amber" },
                        ),
                        color = tokens.ink,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.accent.copy(alpha = 0.12f))
                            .clickable(onClick = onOpenAgentSoul)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.personal_agent_drawer_customize_persona),
                            color = tokens.accentLabel,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = if (!pairedGatewayName.isNullOrBlank()) {
                        stringResource(R.string.personal_agent_drawer_connected_subtitle, pairedGatewayName)
                    } else {
                        stringResource(R.string.personal_agent_drawer_default_subtitle)
                    },
                    color = tokens.ink3,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                onClick = onNewTopic,
                shape = CircleShape,
                color = tokens.accent,
                contentColor = tokens.accentInk,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Plus,
                        contentDescription = null,
                        tint = tokens.accentInk,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = stringResource(R.string.personal_agent_drawer_new_topic),
                        color = tokens.accentInk,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeCount: Int = 0,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = LocalAmberTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tokens.ink2,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            color = tokens.ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(tokens.accent)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        color = tokens.accentInk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Text(
                    text = subtitle,
                    color = tokens.ink3,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            tint = tokens.ink3,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun DrawerHairline() {
    val tokens = LocalAmberTokens.current
    HorizontalDivider(
        modifier = Modifier.padding(start = 46.dp),
        thickness = 0.6.dp,
        color = tokens.line,
    )
}
