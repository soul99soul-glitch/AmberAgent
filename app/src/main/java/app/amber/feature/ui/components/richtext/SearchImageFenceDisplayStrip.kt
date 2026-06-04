package app.amber.feature.ui.components.richtext

private val SEARCH_IMAGE_FENCE_OPEN_REGEX = Regex("""^(```|~~~)[ \t]*search-images(?:[ \t].*)?$""")

internal fun stripSearchImageFencesForDisplay(content: String): String {
    if (!content.contains("search-images")) return content

    val outputLines = mutableListOf<String>()
    var skipping = false
    var closeMarker = ""
    var stripped = false

    content.split('\n').forEach { rawLine ->
        val line = rawLine.removeSuffix("\r")
        if (!skipping) {
            val match = SEARCH_IMAGE_FENCE_OPEN_REGEX.matchEntire(line.trim())
            if (match != null) {
                skipping = true
                closeMarker = match.groupValues[1]
                stripped = true
            } else {
                outputLines += rawLine
            }
        } else if (line.trim() == closeMarker) {
            skipping = false
            closeMarker = ""
        }
    }

    if (!stripped) return content
    return outputLines.joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
}
