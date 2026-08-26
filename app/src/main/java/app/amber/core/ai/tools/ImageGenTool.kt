package app.amber.core.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.provider.ImageGenerationMode
import app.amber.ai.ui.ImageAspectRatio
import app.amber.ai.ui.UIMessagePart
import app.amber.core.settings.getCurrentImageGenerationModel
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.repository.ImageGenerationRepository
import kotlin.uuid.Uuid

/**
 * Factory for the `generate_image` agent tool.
 *
 * Each invocation must be bound to a concrete [conversationId] because the
 * execute lambda writes the resulting images to a per-conversation subdir on
 * disk. The tool is built per-conversation (LocalTools wires this from
 * `getTools(options, conversationId)` only when global Settings resolves to
 * a real image-generation model).
 *
 * Reads the current `Settings.getCurrentImageGenerationModel()` at execute
 * time (not bind time) so the tool always picks up the latest configured
 * model even if the user switches it mid-conversation.
 *
 * P6-02: edit mode. When the resolved provider declares image-edit support
 * the schema exposes `mode` (create|edit) + `source_image_url`, and
 * `mode=edit` routes through [ImageGenerationRepository]'s controlled-source
 * gate. The model usually cannot know local file URLs, so the source is
 * resolved from the user's latest message by [sourceImageResolver] — the UI's
 * 修改 entry sends exactly such a message (text + the image attachment).
 * Unsupported providers keep the historic create-only schema and never get a
 * fake edit path.
 *
 * Extracted from `LocalTools.buildImageGenTool` in M1.4 continuation.
 */
fun createImageGenTool(
    conversationId: Uuid,
    settingsStore: SettingsAggregator,
    imageGenerationRepository: ImageGenerationRepository,
    sourceImageResolver: suspend (conversationId: Uuid) -> String? = { null },
): Tool {
    // Schema is built once per tool instance; the repository resolves the
    // same model the execute path uses, so the two agree unless the user
    // switches provider mid-conversation (execute re-validates anyway).
    val editCapable = imageGenerationRepository.currentModelSupportsImageEdit()
    val description = buildString {
        append(
            """
            Generate photographic, painted, illustrated, or otherwise textured
            raster imagery from a text prompt using the user's configured
            image-generation model. Best fits: landscapes, portraits, photo-
            realistic scenes, paintings (oil / watercolor / ink), concept art,
            illustrations, posters, book / album covers, wallpapers, character
            art, food photography, product mockups — anything where the value
            of the result depends on visual depth, lighting, texture, or
            aesthetic richness that vector code cannot fake. For purely
            structural visualizations — flowcharts, architecture diagrams,
            org charts, sequence / class / state diagrams, mind maps,
            schematics, simple line-art logos, math / data plots — prefer
            emitting an SVG show-widget block instead, since precision and
            editability matter more than visual richness there. (You CAN
            still call this tool for those if the user explicitly asks for
            an artistic / painted / textured rendering of structural content
            — they want art, not a clean diagram.) Prefer detailed English
            prompts that specify subject, style, composition, lighting, and
            mood — image models follow them more reliably. Generated images
            are bound to this conversation; deleting the conversation removes
            them. The user sees them inline and can save / share via
            long-press.
            """.trimIndent().replace("\n", " ")
        )
        if (editCapable) {
            append(
                " " +
                    "To MODIFY an image the user attached or that was just generated in this " +
                    "conversation, call this tool with mode=\"edit\" and the modification " +
                    "instruction as the prompt. The source image is resolved from the user's " +
                    "latest message automatically — do not invent a source_image_url."
            )
        }
    }
    return Tool(
        name = "generate_image",
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Detailed description of the desired image. Include subject, style, composition, lighting, mood. For edits: describe the modification."
                        )
                    })
                    put("aspect_ratio", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray { add("1:1"); add("16:9"); add("9:16") }
                        )
                        put("description", "Image aspect ratio. Default 1:1 (square).")
                    })
                    put("count", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 4)
                        put("description", "Number of variants to generate, 1-4. Default 1. Only request multiple variants when the user explicitly asks for choices.")
                    })
                    if (editCapable) {
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray { add("create"); add("edit") }
                            )
                            put(
                                "description",
                                "create (default) generates a new image; edit modifies the image from the user's latest message."
                            )
                        })
                        put("source_image_url", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "File URL of the image to modify. Only for mode=edit; usually omit it — the app resolves the image attached to the user's latest message automatically."
                            )
                        })
                    }
                },
                required = listOf("prompt")
            )
        },
        execute = { args ->
            val obj = args.jsonObject
            val prompt = obj["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(prompt.isNotEmpty()) { "prompt is required and must not be blank" }
            val aspectRatio = when (obj["aspect_ratio"]?.jsonPrimitive?.contentOrNull) {
                "16:9" -> ImageAspectRatio.LANDSCAPE
                "9:16" -> ImageAspectRatio.PORTRAIT
                "1:1", null -> ImageAspectRatio.SQUARE
                else -> ImageAspectRatio.SQUARE
            }
            val count = (obj["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceIn(1, 4)
            val mode = when (obj["mode"]?.jsonPrimitive?.contentOrNull) {
                "edit" -> ImageGenerationMode.EDIT
                else -> ImageGenerationMode.CREATE
            }
            // The model cannot know local file URLs, so for edits the app-side
            // resolver (user's latest message attachment) is the primary source.
            val sourceImageUrl = obj["source_image_url"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: if (mode == ImageGenerationMode.EDIT) sourceImageResolver(conversationId) else null

            val settings = settingsStore.settingsFlow.value
            val model = settings.getCurrentImageGenerationModel()
                ?: error("No image generation model configured. Please ask the user to configure one in Settings > Models.")

            val files = imageGenerationRepository.generateForConversation(
                modelId = model.id,
                prompt = prompt,
                aspectRatio = aspectRatio,
                numOfImages = count,
                conversationId = conversationId,
                mode = mode,
                sourceImageUrl = sourceImageUrl,
            ).getOrThrow()

            // Build output: Image parts first (so the timeline renders cards
            // up top) followed by a single Text summary so the main chat model
            // has a structured handle on the result for its follow-up reply.
            val parts = mutableListOf<UIMessagePart>()
            files.forEach { saved ->
                parts.add(
                    UIMessagePart.Image(
                        url = "file://${saved.file.absolutePath}",
                        metadata = buildJsonObject {
                            put("source", "generate_image")
                            put("mode", mode.name)
                            put("prompt", prompt)
                            put("aspect_ratio", aspectRatio.name)
                            put("model", saved.modelDisplayName)
                            if (mode == ImageGenerationMode.EDIT && sourceImageUrl != null) {
                                put("edit_source_url", sourceImageUrl)
                            }
                        }
                    )
                )
            }
            val summary = buildJsonObject {
                put("status", "ok")
                put("count", files.size)
                put("model", model.displayName)
                put("prompt", prompt)
                put("aspect_ratio", aspectRatio.name)
                put("mode", mode.name)
                if (mode == ImageGenerationMode.EDIT && sourceImageUrl != null) {
                    put("edit_source_url", sourceImageUrl)
                }
            }
            parts.add(UIMessagePart.Text(summary.toString()))
            parts
        }
    )
}
