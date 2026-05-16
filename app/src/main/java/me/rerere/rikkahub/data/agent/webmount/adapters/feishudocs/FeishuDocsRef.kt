package me.rerere.rikkahub.data.agent.webmount.adapters.feishudocs

import java.nio.charset.StandardCharsets
import java.net.URI
import java.util.Base64
import java.util.Locale

data class FeishuDocRef(
    val documentId: String,
    val docType: String = "docx",
    val sourceUrl: String? = null,
)

data class FeishuBlockRef(
    val documentId: String,
    val blockId: String,
)

object FeishuDocRefs {
    private const val DOC_PREFIX = "fdc_"
    private const val BLOCK_PREFIX = "fdb_"
    private const val VERSION = "v1"
    private const val SEP = "\u001F"

    fun encode(ref: FeishuDocRef): String =
        DOC_PREFIX + encodePayload(
            listOf(
                VERSION,
                ref.docType.ifBlank { "docx" },
                ref.documentId,
                ref.sourceUrl.orEmpty(),
            )
        )

    fun encode(documentId: String, docType: String = "docx", sourceUrl: String? = null): String =
        encode(FeishuDocRef(documentId = documentId, docType = docType, sourceUrl = sourceUrl))

    fun decode(token: String?): FeishuDocRef? {
        if (token.isNullOrBlank() || !token.startsWith(DOC_PREFIX)) return null
        val parts = decodePayload(token.removePrefix(DOC_PREFIX)) ?: return null
        if (parts.size < 4 || parts[0] != VERSION || parts[2].isBlank()) return null
        return FeishuDocRef(
            docType = parts[1].ifBlank { "docx" },
            documentId = parts[2],
            sourceUrl = parts[3].ifBlank { null },
        )
    }

    fun fromUrl(url: String?): FeishuDocRef? {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (!isFeishuDocumentHost(raw)) return null
        val match = DOC_URL_PATTERN.find(raw) ?: return null
        val wireType = match.groupValues[1].lowercase()
        val docType = when (wireType) {
            "doc", "docs", "docx" -> "docx"
            else -> wireType
        }
        return FeishuDocRef(
            documentId = match.groupValues[2],
            docType = docType,
            sourceUrl = raw,
        )
    }

    fun isFeishuDocumentUrl(url: String?): Boolean =
        fromUrl(url) != null

    fun encodeBlock(documentId: String, blockId: String): String =
        BLOCK_PREFIX + encodePayload(listOf(VERSION, documentId, blockId))

    fun decodeBlock(token: String?): FeishuBlockRef? {
        if (token.isNullOrBlank() || !token.startsWith(BLOCK_PREFIX)) return null
        val parts = decodePayload(token.removePrefix(BLOCK_PREFIX)) ?: return null
        if (parts.size < 3 || parts[0] != VERSION || parts[1].isBlank() || parts[2].isBlank()) return null
        return FeishuBlockRef(documentId = parts[1], blockId = parts[2])
    }

    private fun encodePayload(parts: List<String>): String =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(parts.joinToString(SEP).toByteArray(StandardCharsets.UTF_8))

    private fun decodePayload(payload: String): List<String>? =
        runCatching {
            String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split(SEP)
        }.getOrNull()

    private fun isFeishuDocumentHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
            ?: return false
        return host == "feishu.cn" || host.endsWith(".feishu.cn") ||
            host == "larksuite.com" || host.endsWith(".larksuite.com") ||
            host == "larkoffice.com" || host.endsWith(".larkoffice.com") ||
            host == "feishu.net" || host.endsWith(".feishu.net")
    }

    private val DOC_URL_PATTERN = Regex(
        """/(docx|docs|doc|wiki|sheets|base|mindnotes)/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE,
    )
}
