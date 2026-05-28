package app.amber.core.ai.transformers

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import app.amber.core.settings.Settings
import app.amber.core.model.Assistant
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.AssistantRegex
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class StreamingTailVisualTransformTest {
    @Test
    fun tailSafeStreamingVisualTransformMatchesFullVisualTransformForTailMessage() = runBlocking {
        val assistant = Assistant(
            id = Uuid.random(),
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "hello",
                    replaceString = "hi",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                    visualOnly = false,
                )
            )
        )
        val messages = listOf(
            UIMessage.system("system"),
            UIMessage.user("question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("hello <think>reasoning")),
            ),
        )
        val transformers = listOf(RegexOutputTransformer, ThinkTagTransformer)
        val full = messages.visualTransforms(
            transformers = transformers,
            context = ContextWrapper(null),
            model = Model(modelId = "test"),
            assistant = assistant,
            settings = Settings(),
        )
        val tail = messages.visualTransformsStreamingTail(
            transformers = transformers,
            context = ContextWrapper(null),
            model = Model(modelId = "test"),
            assistant = assistant,
            settings = Settings(),
        )

        assertEquals(full, tail)
    }
}
