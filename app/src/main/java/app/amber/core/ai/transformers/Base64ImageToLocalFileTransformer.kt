package app.amber.core.ai.transformers

import app.amber.ai.ui.UIMessage
import app.amber.core.files.FilesManager
import org.koin.java.KoinJavaComponent.getKoin

object Base64ImageToLocalFileTransformer : OutputMessageTransformer {
    // Convert during streaming visual path as well so mid-generation checkpoints
    // do not hit the "no base64" assertion and silently skip persistence.
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = convert(messages)

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = convert(messages)

    private suspend fun convert(messages: List<UIMessage>): List<UIMessage> {
        val filesManager = getKoin().get<FilesManager>()
        return messages.map { message ->
            filesManager.convertBase64ImagePartToLocalFile(message)
        }
    }
}
