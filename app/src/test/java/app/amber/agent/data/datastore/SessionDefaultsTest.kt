package app.amber.core.settings

import app.amber.ai.core.ReasoningLevel
import app.amber.ai.provider.Model
import app.amber.core.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
