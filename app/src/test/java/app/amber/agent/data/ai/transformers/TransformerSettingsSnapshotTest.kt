package app.amber.core.ai.transformers

import android.content.ContextWrapper
import app.amber.ai.core.MessageRole
import app.amber.ai.provider.Model
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.DisplaySetting
import app.amber.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TransformerSettingsSnapshotTest {
    @Test
    fun placeholderReadsTheTransformerSettingsSnapshot() {
        val value = DefaultPlaceholderProvider.placeholders
            .getValue("nickname")
            .resolver(
                PlaceholderCtx(
                    context = ContextWrapper(null),
                    settings = Settings(displaySetting = DisplaySetting(userNickname = "snapshot-user")),
                    model = Model(modelId = "test"),
                ),
            )

        assertEquals("snapshot-user", value)
    }

    @Test
    fun templateReadsTheTransformerSettingsSnapshot() = runBlocking {
        val output = TemplateTransformer().transform(
            ctx = TransformerContext(
                context = RuntimeEnvironment.getApplication(),
                model = Model(modelId = "test"),
                settings = Settings(messageTemplate = "[{{message}}]"),
            ),
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("hello")),
                ),
            ),
        )

        assertEquals("[hello]", (output.single().parts.single() as UIMessagePart.Text).text)
    }
}
