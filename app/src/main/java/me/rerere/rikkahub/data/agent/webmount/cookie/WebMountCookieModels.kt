package me.rerere.rikkahub.data.agent.webmount.cookie

data class CookieEntry(
    val name: String,
    val value: String,
    val sourceUrl: String,
) {
    val headerValue: String get() = "$name=$value"
}

data class CookieSnapshot(
    val entries: List<CookieEntry>,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    fun containsAll(names: Iterable<String>): Boolean {
        val present = asLastWinsMap()
        return names.all { name -> present[name]?.isUsableCookieValue() == true }
    }

    fun missing(names: Iterable<String>): List<String> {
        val present = asLastWinsMap()
        return names.filterNot { name -> present[name]?.isUsableCookieValue() == true }
    }

    fun valuesByName(name: String): List<String> {
        return entries
            .filter { it.name == name && it.value.isUsableCookieValue() }
            .map { it.value }
    }

    fun asLastWinsMap(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        entries.forEach { entry ->
            if (entry.name.isNotBlank() && entry.value.isUsableCookieValue()) {
                map[entry.name] = entry.value
            }
        }
        return map
    }

    fun toHeader(): String {
        return asLastWinsMap()
            .map { (name, value) -> "$name=$value" }
            .joinToString("; ")
    }

    fun sourceUrls(): List<String> {
        return entries.map { it.sourceUrl }.distinct()
    }

    companion object {
        val EMPTY = CookieSnapshot(emptyList())

        fun fromRawHeaders(headersByUrl: Map<String, String>): CookieSnapshot {
            return CookieSnapshot(
                headersByUrl.flatMap { (url, raw) -> parseRawHeader(url, raw) }
            )
        }

        fun parseRawHeader(sourceUrl: String, raw: String): List<CookieEntry> {
            return raw.split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .mapNotNull { cookie ->
                    val name = cookie.substringBefore("=").trim()
                    val value = cookie.substringAfter("=").trim()
                    name.takeIf { it.isNotBlank() }?.let {
                        CookieEntry(name = it, value = value, sourceUrl = sourceUrl)
                    }
                }
        }
    }
}

data class CookieFieldHint(
    val name: String,
    val domainHints: List<String> = emptyList(),
    val path: String = "/",
    val sameSite: String? = null,
    val httpOnly: Boolean = false,
    val required: Boolean = true,
    val description: String = "",
)

internal fun String.isUsableCookieValue(): Boolean {
    val normalized = trim().lowercase()
    return normalized.isNotBlank() &&
        normalized != "deleted" &&
        normalized != "expired" &&
        normalized != "null" &&
        normalized != "none"
}
