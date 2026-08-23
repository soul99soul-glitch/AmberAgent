package app.amber.feature.novel.model

import app.amber.feature.novel.serialization.decodeSwiftAssociatedCase
import app.amber.feature.novel.serialization.swiftAssociatedObject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** The typed approval payload carried by an iOS Ask User prompt. */
@Serializable
data class NovelAskUserPrompt(
    val question: String,
    val options: List<String>,
    val chapterRevision: NovelChapterRevisionProposal? = null,
    val manuscriptRevert: NovelManuscriptRevertProposal? = null,
    val workspacePlot: NovelWorkspacePlotProposal? = null,
)

@Serializable
data class NovelChapterRevisionProposal(
    val chapterID: NovelChapterId,
    val chapterOrdinal: Int,
    val chapterTitle: String,
    val startParagraph: Int,
    val endParagraph: Int,
    val oldText: String,
    val newText: String,
    val reason: String? = null,
)

@Serializable
data class NovelManuscriptRevertProposal(
    val chapterCount: Int,
    val chapterIDs: List<NovelChapterId>,
    val chapterTitles: List<String>,
    val chapterOrdinals: List<Int>,
    val targetCheckpointID: NovelCheckpointId,
    val expectedHeadRevision: Long,
    val expectedWorkingRevision: Long,
    val reason: String? = null,
)

@Serializable
data class NovelWorkspacePlotProposal(
    val path: String,
    val body: String,
    val reason: String? = null,
)

@Serializable
data class NovelAskUserResponse(
    val promptMessageID: NovelMessageId,
    val answer: String,
)

/**
 * Swift Codable-compatible interaction attached to a session message.
 *
 * The `_0` wrapper is the synthesized Swift shape for an unlabeled associated
 * value (`case askUser(NovelAskUserPrompt)`).
 */
@Serializable(with = NovelSessionMessageInteraction.Serializer::class)
sealed class NovelSessionMessageInteraction {
    data class AskUser(val prompt: NovelAskUserPrompt) : NovelSessionMessageInteraction()

    data class AskUserAnswer(val response: NovelAskUserResponse) : NovelSessionMessageInteraction()

    object Serializer : KSerializer<NovelSessionMessageInteraction> {
        override val descriptor: SerialDescriptor =
            buildClassSerialDescriptor("NovelSessionMessageInteraction")

        override fun serialize(encoder: Encoder, value: NovelSessionMessageInteraction) {
            val json = encoder as JsonEncoder
            val element = when (value) {
                is AskUser -> swiftAssociatedObject(
                    "askUser",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(
                                NovelAskUserPrompt.serializer(),
                                value.prompt,
                            ),
                        )
                    },
                )
                is AskUserAnswer -> swiftAssociatedObject(
                    "askUserAnswer",
                    buildJsonObject {
                        put(
                            "_0",
                            json.json.encodeToJsonElement(
                                NovelAskUserResponse.serializer(),
                                value.response,
                            ),
                        )
                    },
                )
            }
            json.encodeJsonElement(element)
        }

        override fun deserialize(decoder: Decoder): NovelSessionMessageInteraction {
            val json = decoder as JsonDecoder
            val (caseName, associated) = decodeSwiftAssociatedCase(json.decodeJsonElement())
            val payload = associated["_0"]
                ?: throw IllegalArgumentException("Associated interaction payload missing _0")
            return when (caseName) {
                "askUser" -> AskUser(
                    json.json.decodeFromJsonElement(NovelAskUserPrompt.serializer(), payload),
                )
                "askUserAnswer" -> AskUserAnswer(
                    json.json.decodeFromJsonElement(NovelAskUserResponse.serializer(), payload),
                )
                else -> throw IllegalArgumentException("Unknown session message interaction: $caseName")
            }
        }
    }
}
