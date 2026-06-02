package app.amber.core.settings

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.core.model.Assistant
import app.amber.core.model.reasoningLevelForModel
import app.amber.core.model.withChatModelReasoningMemory
import app.amber.core.model.withReasoningLevelForModel
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class SessionDefaultsTest {
    @Test
    fun `model group defaults apply only to neutral assistant settings`() {
        val settings = Settings(
            modelGroupSessionDefaults = listOf(
                ModelGroupSessionDefault(
                    groupId = "openai_reasoning",
                    reasoningLevel = ReasoningLevel.HIGH,
                    contextMessageSize = 64,
                    maxTokens = 4096,
                )
            )
        )
        val model = Model(modelId = "gpt-5.4")

        val resolved = settings.resolveSessionDefaults(
            assistant = Assistant(),
            model = model,
        )

        assertEquals(ReasoningLevel.HIGH, resolved.reasoningLevel)
        assertEquals(64, resolved.contextMessageSize)
        assertEquals(4096, resolved.maxTokens)
    }

    @Test
    fun `explicit assistant session settings win over model group defaults`() {
        val settings = Settings(
            modelGroupSessionDefaults = listOf(
                ModelGroupSessionDefault(
                    groupId = "openai_reasoning",
                    reasoningLevel = ReasoningLevel.HIGH,
                    contextMessageSize = 64,
                    maxTokens = 4096,
                )
            )
        )
        val model = Model(modelId = "gpt-5.4")

        val resolved = settings.resolveSessionDefaults(
            assistant = Assistant(
                reasoningLevel = ReasoningLevel.OFF,
                contextMessageSize = 12,
                maxTokens = 512,
            ),
            model = model,
        )

        assertEquals(ReasoningLevel.OFF, resolved.reasoningLevel)
        assertEquals(12, resolved.contextMessageSize)
        assertEquals(512, resolved.maxTokens)
    }

    @Test
    fun `neutral assistant reasoning uses model family default when no group override`() {
        assertEquals(
            ReasoningLevel.MEDIUM,
            Settings().resolveSessionDefaults(
                assistant = Assistant(),
                model = Model(modelId = "gpt-5.4"),
            ).reasoningLevel,
        )
        assertEquals(
            ReasoningLevel.HIGH,
            Settings().resolveSessionDefaults(
                assistant = Assistant(),
                model = Model(modelId = "DeepSeek-V4-Pro"),
            ).reasoningLevel,
        )
        assertEquals(
            ReasoningLevel.AUTO,
            Settings().resolveSessionDefaults(
                assistant = Assistant(),
                model = Model(modelId = "glm-5"),
            ).reasoningLevel,
        )
    }

    @Test
    fun `assistant reasoning memory restores per selected model`() {
        val gptModelId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val kimiModelId = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val assistant = Assistant(
            chatModelId = gptModelId,
            reasoningLevel = ReasoningLevel.HIGH,
        )

        val onKimi = assistant.withChatModelReasoningMemory(
            currentModelId = gptModelId,
            currentDefaultReasoningLevel = ReasoningLevel.MEDIUM,
            selectedModelId = kimiModelId,
            selectedDefaultReasoningLevel = ReasoningLevel.AUTO,
        )

        assertEquals(kimiModelId, onKimi.chatModelId)
        assertEquals(ReasoningLevel.AUTO, onKimi.reasoningLevel)

        val onGptAgain = onKimi.withChatModelReasoningMemory(
            currentModelId = kimiModelId,
            currentDefaultReasoningLevel = ReasoningLevel.AUTO,
            selectedModelId = gptModelId,
            selectedDefaultReasoningLevel = ReasoningLevel.MEDIUM,
        )

        assertEquals(gptModelId, onGptAgain.chatModelId)
        assertEquals(ReasoningLevel.HIGH, onGptAgain.reasoningLevel)
        assertEquals(ReasoningLevel.HIGH, onGptAgain.reasoningLevelForModel(gptModelId, ReasoningLevel.MEDIUM))
    }

    @Test
    fun `slash reasoning selection is remembered for current model`() {
        val gptModelId = Uuid.parse("33333333-3333-3333-3333-333333333333")

        val assistant = Assistant().withReasoningLevelForModel(gptModelId, ReasoningLevel.XHIGH)

        assertEquals(ReasoningLevel.XHIGH, assistant.reasoningLevel)
        assertEquals(ReasoningLevel.XHIGH, assistant.reasoningLevelForModel(gptModelId, ReasoningLevel.MEDIUM))
    }
}
