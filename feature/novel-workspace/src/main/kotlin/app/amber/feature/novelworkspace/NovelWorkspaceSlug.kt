package app.amber.feature.novelworkspace

/**
 * File-name slug rules shared with the iOS workspace exporter.
 *
 * Mirrors `NovelWorkspaceBackup.slug` / `reservedPath` in the iOS repo; both platforms must
 * produce identical leaf names so exported trees round-trip unchanged.
 */
object NovelWorkspaceSlug {

    private val FORBIDDEN_CODE_POINTS: IntArray = "/\\:?%*|\"<>".codePoints().toArray()

    fun slug(raw: String): String {
        val mapped = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            index += Character.charCount(codePoint)
            val replacement = when {
                codePoint == ' '.code -> "-"
                codePoint in FORBIDDEN_CODE_POINTS -> "-"
                Character.isISOControl(codePoint) -> "-"
                else -> null
            }
            if (replacement != null) {
                mapped.append(replacement)
            } else {
                mapped.appendCodePoint(codePoint)
            }
        }
        val collapsed = mapped.toString()
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        if (collapsed.isEmpty()) return ""
        val asciiAlpha = collapsed.all { it in 'a'..'z' || it in 'A'..'Z' || it == '-' }
        return if (asciiAlpha) collapsed.lowercase() else collapsed
    }

    /**
     * Deduplicate a preferred path inside [used]; falls back to the first 8 chars of [fallback],
     * then "untitled". A path prefix (directories before the leaf) is preserved as-is.
     */
    fun reservedPath(preferred: String, used: MutableSet<String>, fallback: String): String {
        val segments = preferred.split('/')
        val leafPreferred = segments.last()
        val prefix = if (segments.size > 1) {
            segments.dropLast(1).joinToString("/") + "/"
        } else {
            ""
        }
        var base = leafPreferred.ifEmpty { fallback.take(8) }
        if (base.isEmpty()) base = "untitled"
        var candidate = base
        var suffix = 2
        while (prefix + candidate in used) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        used.add(prefix + candidate)
        return prefix + candidate
    }
}
