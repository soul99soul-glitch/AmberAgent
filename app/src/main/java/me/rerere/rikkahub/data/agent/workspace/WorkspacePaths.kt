package me.rerere.rikkahub.data.agent.workspace

internal object WorkspacePaths {
    fun normalize(path: String): String {
        val trimmed = path.trim()
        val relative = when {
            trimmed.isBlank() || trimmed == "." || trimmed == "/" -> "."
            trimmed == "/workspace" || trimmed == "workspace" -> "."
            trimmed.startsWith("/workspace/") -> trimmed.removePrefix("/workspace/")
            trimmed.startsWith("workspace/") -> trimmed.removePrefix("workspace/")
            trimmed.startsWith("/") -> throw IllegalArgumentException(
                "Only /workspace paths are allowed: $path"
            )
            else -> trimmed
        }
        val parts = relative
            .split("/")
            .filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." }) { "Path traversal is not allowed: $path" }
        return parts.joinToString("/").ifBlank { "." }
    }

    fun join(parent: String, child: String): String =
        if (parent == "." || parent.isBlank()) child else "$parent/$child"
}
