package app.amber.core.ai.tools

import app.amber.ai.ui.UIMessagePart
import app.amber.core.event.AppEvent
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

fun createDeepReadOpenEvent(
    topicTitle: String?,
    sourceUrl: String?,
    forceRegenerate: Boolean = false,
): AppEvent.OpenDeepRead {
    val normalizedUrl = sourceUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let(::normalizeDeepReadSourceUrl)
    val title = topicTitle
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != normalizedUrl }
        ?: normalizedUrl?.let(::titleFromDeepReadUrl)
        ?: error("topic_title or source_url is required")
    return AppEvent.OpenDeepRead(
        topicId = deepReadChatTopicId(title, normalizedUrl),
        title = title.take(DEEP_READ_TITLE_MAX_CHARS),
        sourceUrl = normalizedUrl,
        forceRegenerate = forceRegenerate,
    )
}

fun parseDeepReadSlashCommand(parts: List<UIMessagePart>): AppEvent.OpenDeepRead? {
    val text = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (text.isBlank()) return null
    val body = when {
        text.startsWith(DEEP_READ_ROUTE_TAG, ignoreCase = true) ->
            text.drop(DEEP_READ_ROUTE_TAG.length).trim()

        text.startsWith("/deepread", ignoreCase = true) -> {
            val rest = text.drop("/deepread".length)
            if (rest.isNotEmpty() && !rest.first().isWhitespace()) return null
            rest.trim()
        }

        else -> return null
    }
    if (body.isBlank()) return null
    val force = DEEP_READ_FORCE_FLAGS.any { it in body.lowercase(Locale.ROOT) }
    val cleaned = DEEP_READ_FORCE_FLAGS.fold(body) { acc, flag ->
        acc.replace(flag, "", ignoreCase = true)
    }.trim()
    if (cleaned.isBlank()) return null
    val sourceUrl = HTTP_URL_REGEX.find(cleaned)?.value
    val title = sourceUrl
        ?.let { cleaned.replace(it, "").trim() }
        ?.takeIf { it.isNotBlank() }
        ?: cleaned.takeIf { sourceUrl == null }
    return runCatching {
        createDeepReadOpenEvent(
            topicTitle = title,
            sourceUrl = sourceUrl,
            forceRegenerate = force,
        )
    }.getOrNull()
}

private fun normalizeDeepReadSourceUrl(raw: String): String {
    val uri = runCatching { URI(raw) }.getOrNull()
        ?: error("source_url must be a valid HTTP(S) URL")
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") { "source_url must be HTTP(S)" }
    require(!uri.host.isNullOrBlank()) { "source_url host is required" }
    return uri.toString()
}

private fun titleFromDeepReadUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host?.removePrefix("www.").orEmpty()
    val slug = uri?.path
        ?.split("/")
        ?.lastOrNull { it.isNotBlank() }
        ?.substringBeforeLast(".")
        ?.replace('-', ' ')
        ?.replace('_', ' ')
        ?.trim()
        .orEmpty()
    return listOf(slug, host)
        .firstOrNull { it.isNotBlank() }
        ?.take(DEEP_READ_TITLE_MAX_CHARS)
        ?: "深度阅读"
}

private fun deepReadChatTopicId(title: String, sourceUrl: String?): String {
    val key = sourceUrl
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?: title.trim().lowercase(Locale.ROOT)
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(24)
    return "chat_deep_read_$digest"
}

private val HTTP_URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val DEEP_READ_FORCE_FLAGS = listOf("--force", "--regen", "--regenerate", "重新生成", "强制刷新")
private const val DEEP_READ_ROUTE_TAG = "[ROUTE:deepread]"
private const val DEEP_READ_TITLE_MAX_CHARS = 120
