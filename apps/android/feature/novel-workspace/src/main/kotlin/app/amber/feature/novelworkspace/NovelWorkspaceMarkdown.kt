package app.amber.feature.novelworkspace

/**
 * Front matter + lightweight YAML codec shared with the iOS workspace exporter/importer.
 *
 * Mirrors `NovelWorkspaceMarkdown` and the render helpers in `NovelWorkspaceBackup` (iOS).
 * Keep parsing lenient and rendering byte-compatible: trees must round-trip across platforms.
 */
object NovelWorkspaceMarkdown {

    data class ParsedFile(
        val fields: Map<String, String> = emptyMap(),
        val lists: Map<String, List<String>> = emptyMap(),
        /** Items written as inline maps: `- {with: 赵大, type: 结拜兄弟}`. */
        val maps: Map<String, List<Map<String, String>>> = emptyMap(),
        val body: String = "",
    )

    fun parseFile(text: String): ParsedFile {
        val trimmed = text.trim()
        if (!trimmed.startsWith("---")) {
            return ParsedFile(body = trimmed)
        }
        val rest = trimmed.drop(3).trimStart('\n', '\r')
        val end = rest.indexOf("\n---")
        if (end < 0) {
            return ParsedFile(body = trimmed)
        }
        val front = rest.substring(0, end)
        val body = rest.substring(end + "\n---".length).trim()
        val fields = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, MutableList<String>>()
        val maps = LinkedHashMap<String, MutableList<Map<String, String>>>()
        var currentList: String? = null
        for (rawLine in front.split('\n')) {
            val line = rawLine.trimEnd('\r')
            val listItem = line.trim()
            if (line.startsWith("  - ") || line.startsWith("- ")) {
                val key = currentList ?: continue
                val value = listItem.drop(2).trim()
                val inlineMap = parseInlineMap(value)
                if (inlineMap != null) {
                    maps.getOrPut(key) { mutableListOf() }.add(inlineMap)
                } else {
                    lists.getOrPut(key) { mutableListOf() }.add(unquote(value))
                }
                continue
            }
            currentList = null
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (value.isEmpty()) {
                currentList = key
                lists.getOrPut(key) { mutableListOf() }
            } else {
                fields[key] = unquote(value)
            }
        }
        return ParsedFile(fields = fields, lists = lists, maps = maps, body = body)
    }

    /**
     * `{with: 赵大, type: 结拜兄弟}` → {with=赵大, type=结拜兄弟}.
     * Null for anything that is not a braced comma-separated `key: value` list;
     * the iOS parser keeps such lines as plain list strings, so trees stay portable.
     */
    fun parseInlineMap(value: String): Map<String, String>? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val inner = trimmed.drop(1).dropLast(1).trim()
        if (inner.isEmpty()) return null
        val result = LinkedHashMap<String, String>()
        for (part in inner.split(',')) {
            val colon = part.indexOf(':')
            if (colon < 0) return null
            val key = part.substring(0, colon).trim()
            if (key.isEmpty()) return null
            result[key] = unquote(part.substring(colon + 1).trim())
        }
        return result
    }

    /** One-level nested mapping parser used for manifest.yaml ("source.projectID" style keys). */
    fun parseMapping(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var prefix = ""
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r')
            if (line.startsWith("  ")) {
                val trimmed = line.trim()
                val colon = trimmed.indexOf(':')
                if (colon < 0) continue
                val key = trimmed.substring(0, colon)
                val value = trimmed.substring(colon + 1).trim()
                result[prefix + key] = unquote(value)
            } else if (line.endsWith(":")) {
                prefix = line.dropLast(1) + "."
            } else {
                val colon = line.indexOf(':')
                if (colon < 0) continue
                prefix = ""
                val key = line.substring(0, colon)
                val value = line.substring(colon + 1).trim()
                result[key] = unquote(value)
            }
        }
        return result
    }

    /** "## " section map; content before the first heading is dropped. */
    fun sections(body: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var current = ""
        val lines = mutableListOf<String>()
        fun flush() {
            if (current.isEmpty()) return
            result[current] = lines.joinToString("\n").trim()
        }
        for (line in body.split('\n')) {
            if (line.startsWith("## ")) {
                flush()
                current = line.drop(3).trim()
                lines.clear()
            } else {
                lines.add(line.trimEnd('\r'))
            }
        }
        flush()
        return result
    }

    fun bullets(text: String): List<String> = text.split('\n')
        .map { it.trim().trimEnd('\r') }
        .map { if (it.startsWith("- ")) it.drop(2) else it }
        .filter { it.isNotEmpty() }

    /** Split a plot/current body into summary + "## 近期已写" highlights (null when absent). */
    fun splitHighlights(body: String): Pair<String, List<String>?> {
        val newlineMarker = body.indexOf("\n## 近期已写")
        val markerStart = if (newlineMarker >= 0) newlineMarker else body.indexOf("## 近期已写")
        if (markerStart < 0) return body to null
        val summary = body.substring(0, markerStart).trim()
        val rest = if (newlineMarker >= 0) {
            body.substring(markerStart + 1 + "## 近期已写".length)
        } else {
            body.substring(markerStart + "## 近期已写".length)
        }
        val highlights = rest.split('\n')
            .map { it.trim().trimEnd('\r') }
            .map { if (it.startsWith("- ")) it.drop(2) else it }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        return summary to highlights.ifEmpty { null }
    }

    fun unquote(value: String): String {
        if (value.length < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
            return value
        }
        return value.drop(1).dropLast(1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    /**
     * Render front matter + body. Field order is the caller's (host-defined) order —
     * the iOS exporter renders insertion order, so callers pass fields in the exact
     * iOS sequence to keep both platforms byte-identical. The optional [aliases] block
     * is appended last; body follows after a blank line.
     */
    fun render(fields: List<Pair<String, String>>, aliases: List<String> = emptyList(), body: String): String {
        val lines = mutableListOf("---")
        for ((key, value) in fields) {
            lines.add("$key: ${yamlScalar(value)}")
        }
        if (aliases.isNotEmpty()) {
            lines.add("aliases:")
            for (item in aliases) {
                lines.add("  - ${yamlScalar(item)}")
            }
        }
        lines.add("---")
        val fence = lines.joinToString("\n")
        val trimmedBody = body.trim()
        return if (trimmedBody.isEmpty()) "$fence\n" else "$fence\n\n$trimmedBody\n"
    }

    fun yamlScalar(value: String): String {
        if (value.isEmpty()) return "\"\""
        val needsQuotes = value.startsWith(" ") ||
            value.endsWith(" ") ||
            value.any { it in ":#{}[],&*?|>!%@`'\"\n" }
        if (!needsQuotes) return value
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$escaped\""
    }

    /**
     * Sorted mapping renderer for manifest.yaml. Dotted keys become one nested block;
     * numeric fields are emitted bare (matching the iOS exporter).
     */
    fun yamlMapping(pairs: Map<String, String>): String {
        val lines = mutableListOf<String>()
        val nested = LinkedHashMap<String, MutableList<Pair<String, String>>>()
        val top = mutableListOf<Pair<String, String>>()
        for ((key, value) in pairs.toSortedMap()) {
            val dot = key.indexOf('.')
            if (dot >= 0) {
                val parent = key.substring(0, dot)
                val child = key.substring(dot + 1)
                nested.getOrPut(parent) { mutableListOf() }.add(child to value)
            } else {
                top.add(key to value)
            }
        }
        for ((key, value) in top) {
            when {
                key == "formatVersion" && value.toIntOrNull() != null -> lines.add("$key: ${value.toInt()}")
                key == "exportedAt" -> lines.add("$key: $value")
                else -> lines.add("$key: ${yamlScalar(value)}")
            }
        }
        for ((parent, children) in nested.toSortedMap()) {
            lines.add("$parent:")
            for ((child, value) in children.sortedBy { it.first }) {
                if ((child == "projectRevision" || child == "schemaVersion") && value.toIntOrNull() != null) {
                    lines.add("  $child: ${value.toInt()}")
                } else {
                    lines.add("  $child: ${yamlScalar(value)}")
                }
            }
        }
        return lines.joinToString("\n") + "\n"
    }
}
