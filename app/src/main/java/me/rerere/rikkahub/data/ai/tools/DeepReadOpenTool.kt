package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.event.AppEventBus

fun createDeepReadOpenTool(eventBus: AppEventBus): Tool = Tool(
    name = "deep_read_open",
    description = """
        Open AmberAgent's full-screen Deep Read panel for a topic or URL.
        Use this when the user asks to 深度阅读, 深读, deep read, investigate a link/topic in magazine format, or wants a full-screen research reading view.
        This tool only opens the Deep Read route; the panel then uses the standard hidden-agent Deep Read pipeline, search/scrape tools, segmented writes, verification, and 24h cache.
        If the user provided a URL, pass it as source_url so Deep Read scrapes that source first, then cross-checks with search.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("topic_title", buildJsonObject {
                    put("type", "string")
                    put("description", "User-visible topic title. If only a URL is known, use a short title derived from the URL or omit it.")
                })
                put("source_url", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional HTTP(S) URL provided by the user. Deep Read will scrape this first as a seed source.")
                })
                put("force_regenerate", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Set true only when the user explicitly asks to regenerate or ignore the 24h cache.")
                })
            },
        )
    },
    allowsAutoApproval = true,
    execute = { input ->
        val obj = input.jsonObject
        val forceRegenerate = obj["force_regenerate"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull()
            ?: false
        val event = createDeepReadOpenEvent(
            topicTitle = obj["topic_title"]?.jsonPrimitive?.contentOrNull,
            sourceUrl = obj["source_url"]?.jsonPrimitive?.contentOrNull,
            forceRegenerate = forceRegenerate,
        )

        val opened = if (eventBus.hasCollectors) {
            eventBus.emit(event)
            true
        } else {
            false
        }

        val payload = buildJsonObject {
            put("status", if (opened) "opened" else "not_opened")
            put("topic_id", event.topicId)
            put("title", event.title)
            event.sourceUrl?.let { put("source_url", it) }
            put("cache_ttl_hours", 24)
            put("force_regenerate", event.forceRegenerate)
            put(
                "note",
                if (opened) {
                    "Deep Read panel opened. The panel will stream segmented generation through the hidden-agent pipeline."
                } else {
                    "Deep Read UI was not active, so the panel could not be opened."
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
