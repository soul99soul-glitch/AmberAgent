package app.amber.core.ai.transformers

import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.utils.appLocale
import app.amber.core.utils.toLocalDate
import app.amber.core.utils.toLocalTime
import java.time.Instant

class TemplateTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = ctx.settings.messageTemplate
        val now = Instant.now()
        val locale = ctx.context.appLocale()
        return messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(
                            text = applyTemplate(
                                template,
                                "message" to part.text,
                                "role" to message.role.name.lowercase(),
                                "time" to now.toLocalTime(locale).toString(),
                                "date" to now.toLocalDate(locale).toString(),
                            ),
                        )
                        else -> part
                    }
                },
            )
        }
    }
}

private fun applyTemplate(template: String, vararg vars: Pair<String, String>): String {
    var result = template
    for ((key, value) in vars) {
        result = result.replace("{{ $key }}", value).replace("{{$key}}", value)
    }
    return result
}
