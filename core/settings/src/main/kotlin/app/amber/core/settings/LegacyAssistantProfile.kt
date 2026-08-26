package app.amber.core.settings

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.CustomBody
import app.amber.ai.provider.CustomHeader
import app.amber.ai.ui.UIMessage
import app.amber.core.model.AMBER_AGENT_ID
import app.amber.core.model.AssistantRegex
import app.amber.core.model.LocalToolOption
import app.amber.core.model.MainAgentToolProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Decoder for settings written by the removed multi-assistant product model. */
@Serializable
internal data class LegacyAssistantProfile(
    val id: Uuid = AMBER_AGENT_ID,
    val chatModelId: Uuid? = null,
    @SerialName("imageGenerationModelId")
    val legacyImageGenerationModelId: Uuid? = null,
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val contextMessageSize: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val toolProfile: MainAgentToolProfile = MainAgentToolProfile.FULL,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    val enabledSkills: Set<String> = emptySet(),
    val enableTimeReminder: Boolean = false,
    val rememberedReasoningLevelsByModelId: Map<String, ReasoningLevel> = emptyMap(),
)
