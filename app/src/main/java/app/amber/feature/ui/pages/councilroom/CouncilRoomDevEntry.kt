package app.amber.feature.ui.pages.councilroom

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import app.amber.agent.R
import app.amber.agent.Screen
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.repository.ConversationRepository
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.feature.modelcouncil.CouncilParticipant
import app.amber.feature.modelcouncil.CouncilParticipantKind
import app.amber.feature.modelcouncil.CouncilRoomManager
import app.amber.feature.ui.context.LocalNavController
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Debug-only entry point for the Council Room. Opens a Room bound to the most
 * recent conversation (creating a minimal guest roster from the current chat
 * model) and navigates to the page.
 *
 * This is intentionally throwaway wiring — PR4 replaces it with the
 * `council_room_open` tool path triggered from the main chat. Keep it gated
 * behind the Debug page so it never ships in production flows.
 */
@Composable
fun CouncilRoomDevEntry() {
    val manager: CouncilRoomManager = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val settingsStore: SettingsAggregator = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    Button(onClick = {
        scope.launch {
            val settings = settingsStore.settingsFlow.value
            // Reuse the most recent conversation so the room has a host binding.
            val conversation = conversationRepo.getRecentConversations(limit = 1).firstOrNull()
                ?: return@launch
            val guestModelId = settings.chatModelId
            val guests = if (guestModelId != null) {
                listOf(
                    CouncilParticipant(
                        id = "guest-a",
                        name = "Analyst",
                        role = "分析师",
                        kind = CouncilParticipantKind.GUEST,
                        modelId = guestModelId,
                        providerName = "test",
                        modelName = "guest-a",
                    ),
                    CouncilParticipant(
                        id = "guest-b",
                        name = "Skeptic",
                        role = "质疑者",
                        kind = CouncilParticipantKind.GUEST,
                        modelId = guestModelId,
                        providerName = "test",
                        modelName = "guest-b",
                    ),
                )
            } else {
                emptyList()
            }
            val result = manager.openRoom(
                conversationId = conversation.id,
                hostAssistantId = AMBER_AGENT_ID,
                hostName = "Amber",
                objective = "PR3 调试:讨论 AI agent 的多模型协作架构",
                initialGuests = guests,
            )
            // room_already_open is fine — the existing room will be observed.
            // Any other error means we shouldn't navigate to a broken page.
            when (result) {
                is app.amber.feature.modelcouncil.CouncilRoomOpResult.Ok,
                is app.amber.feature.modelcouncil.CouncilRoomOpResult.Err -> {
                    val skip = result is app.amber.feature.modelcouncil.CouncilRoomOpResult.Err &&
                        result.code != "room_already_open"
                    if (skip) {
                        android.util.Log.w("CouncilRoomDev", "openRoom failed: ${result.code}")
                        return@launch
                    }
                }
            }
            navController.navigate(Screen.CouncilRoom(conversationId = conversation.id.toString()))
        }
    }) {
        Text(stringResource(R.string.council_room_debug_title))
    }
}
