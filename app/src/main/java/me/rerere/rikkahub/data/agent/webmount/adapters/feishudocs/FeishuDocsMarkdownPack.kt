package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object FeishuDocsMarkdownPack {
    fun fromBlocks(
        documentId: String,
        docRef: String,
        blocks: List<FeishuDocBlock>,
        title: String? = null,
        source: String = "openapi_blocks",
        startChar: Int = 0,
        maxChars: Int = 60_000,
        sourceTruncated: Boolean = false,
    ): JsonObject {
        val entries = blocks.mapNotNull { block ->
            val markdown = markdownForBlock(block.blockType, block.headingLevel, block.text).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            MarkdownEntry(
                blockId = block.blockId,
                blockRef = FeishuDocRefs.encodeBlock(documentId, block.blockId),
                type = typeName(block.blockType, block.headingLevel),
                headingLevel = block.headingLevel,
                markdown = markdown,
            )
        }
        return packEntries(
            title = title,
            source = source,
            entries = entries,
            startChar = startChar,
            maxChars = maxChars,
            sourceTruncated = sourceTruncated,
            extra = buildJsonObject {
                put("document_id", documentId)
                put("doc_ref", docRef)
            },
        )
    }

    fun fromBlocksPayload(
        payload: JsonObject,
        startChar: Int = 0,
        maxChars: Int = 60_000,
    ): JsonObject {
        val documentId = payload.s("document_id").orEmpty()
        var textTruncated = false
        val entries = payload.array("blocks").mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            val text = block.s("text").orEmpty()
            textTruncated = textTruncated || block.bool("text_truncated")
            val headingLevel = block.i("heading_level")
            val blockType = block.i("block_type")
            val markdown = markdownForBlock(blockType, headingLevel, text).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            MarkdownEntry(
                blockId = block.s("block_id"),
                blockRef = block.s("block_ref"),
                type = typeName(blockType, headingLevel),
                headingLevel = headingLevel,
                markdown = markdown,
            )
        }
        return packEntries(
            title = payload.s("title"),
            source = "feishu_docs_blocks_payload",
            entries = entries,
            startChar = startChar,
            maxChars = maxChars,
            sourceTruncated = payload.bool("has_more") || textTruncated,
            extra = buildJsonObject {
                if (documentId.isNotBlank()) put("document_id", documentId)
                payload.s("doc_ref")?.let { put("doc_ref", it) }
                if (textTruncated) {
                    put("limitations", buildJsonArray {
                        add(JsonPrimitive("source_blocks_text_truncated"))
                    })
                }
            },
        )
    }

    fun fromSnapshot(
        snapshot: JsonObject,
        startChar: Int = 0,
        maxChars: Int = 60_000,
    ): JsonObject {
        val entries = snapshot.array("visible_blocks").mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            val text = block.s("text").orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val type = block.s("type") ?: "paragraph"
            val level = block.i("level")
            MarkdownEntry(
                blockId = null,
                blockRef = block.s("block_ref"),
                type = type,
                headingLevel = level,
                markdown = markdownForSnapshotBlock(type, level, text),
            )
        }
        return packEntries(
            title = snapshot.s("title"),
            source = "webview_snapshot",
            entries = entries,
            startChar = startChar,
            maxChars = maxChars,
            sourceTruncated = snapshot.array("limitations").any {
                it.jsonPrimitive.contentOrNull == "visible_blocks_truncated"
            },
            extra = buildJsonObject {
                snapshot.s("session_id")?.let { put("session_id", it) }
                snapshot.s("url")?.let { put("url", it) }
                snapshot.s("doc_type")?.let { put("doc_type", it) }
                snapshot.s("doc_token")?.let { put("doc_token", it) }
            },
        )
    }

    private fun packEntries(
        title: String?,
        source: String,
        entries: List<MarkdownEntry>,
        startChar: Int,
        maxChars: Int,
        sourceTruncated: Boolean,
        extra: JsonObject,
    ): JsonObject {
        val builder = StringBuilder()
        val mapped = mutableListOf<BlockMapEntry>()
        entries.forEach { entry ->
            if (builder.isNotEmpty()) builder.append("\n\n")
            val start = builder.length
            builder.append(entry.markdown)
            mapped += BlockMapEntry(entry, start, builder.length)
        }
        val full = builder.toString()
        val safeStart = startChar.coerceIn(0, full.length)
        val safeMax = maxChars.coerceIn(1, 200_000)
        val end = (safeStart + safeMax).coerceAtMost(full.length)
        val markdown = full.substring(safeStart, end)
        val truncated = end < full.length || sourceTruncated
        return buildJsonObject {
            put("ok", true)
            put("title", title ?: "")
            put("source", source)
            extra.forEach { (key, value) -> put(key, value) }
            put("outline", buildJsonArray {
                mapped.filter { it.entry.type == "heading" }.take(80).forEach { map ->
                    add(buildJsonObject {
                        put("text", map.entry.markdown.removePrefix("#").trimStart('#', ' '))
                        map.entry.headingLevel?.let { put("level", it) }
                        map.entry.blockRef?.let { put("block_ref", it) }
                    })
                }
            })
            put("markdown", markdown)
            put("start_char", safeStart)
            put("total_chars", full.length)
            put("truncated", truncated)
            put("next_start", if (end < full.length) JsonPrimitive(end) else JsonNull)
            put("block_map", buildJsonArray {
                mapped.filter { it.end > safeStart && it.start < end }.forEach { map ->
                    add(buildJsonObject {
                        map.entry.blockId?.let { put("block_id", it) }
                        map.entry.blockRef?.let { put("block_ref", it) }
                        put("type", map.entry.type)
                        map.entry.headingLevel?.let { put("heading_level", it) }
                        put("markdown_start", map.start)
                        put("markdown_end", map.end)
                    })
                }
            })
        }
    }

    private fun markdownForBlock(blockType: Int?, headingLevel: Int?, text: String?): String {
        val clean = text.orEmpty().trim()
        if (clean.isBlank()) return ""
        return when {
            headingLevel != null -> "${"#".repeat(headingLevel.coerceIn(1, 6))} $clean"
            blockType == 12 -> "- $clean"
            blockType == 13 -> "1. $clean"
            blockType == 14 -> "```\n$clean\n```"
            else -> clean
        }
    }

    private fun markdownForSnapshotBlock(type: String, level: Int?, text: String): String = when (type) {
        "heading" -> "${"#".repeat((level ?: 2).coerceIn(1, 6))} ${text.trim()}"
        "bullet" -> "- ${text.trim().removePrefix("•").trimStart()}"
        "ordered" -> text.trim()
        "code" -> "```\n${text.trim()}\n```"
        else -> text.trim()
    }

    private fun typeName(blockType: Int?, headingLevel: Int?): String = when {
        headingLevel != null -> "heading"
        blockType == 12 -> "bullet"
        blockType == 13 -> "ordered"
        blockType == 14 -> "code"
        else -> "paragraph"
    }

    private data class MarkdownEntry(
        val blockId: String?,
        val blockRef: String?,
        val type: String,
        val headingLevel: Int?,
        val markdown: String,
    )

    private data class BlockMapEntry(
        val entry: MarkdownEntry,
        val start: Int,
        val end: Int,
    )

    private fun JsonObject.s(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.bool(name: String): Boolean =
        this[name]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true
    private fun JsonObject.array(name: String): JsonArray =
        runCatching { this[name]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
}
