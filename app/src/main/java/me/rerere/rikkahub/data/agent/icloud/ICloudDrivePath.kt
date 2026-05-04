package me.rerere.rikkahub.data.agent.icloud

object ICloudDrivePath {
    fun normalizeUserPath(path: String): String {
        val trimmed = path.trim().trim('/')
        if (trimmed.isBlank() || trimmed == ".") return ""
        val segments = trimmed.split("/")
            .filter { it.isNotBlank() && it != "." }
        require(segments.none { it == ".." }) { "Path must stay inside the configured iCloud vault" }
        require(!path.startsWith("/")) { "Use a vault-relative path, not an absolute path" }
        return segments.joinToString("/")
    }

    fun resolve(vaultPath: String, relativePath: String): ICloudDriveResolvedPath {
        val vault = normalizeUserPath(vaultPath)
        val relative = normalizeUserPath(relativePath)
        val fullPath = listOf(vault, relative)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return ICloudDriveResolvedPath(
            vaultPath = vault,
            relativePath = relative,
            iCloudPath = fullPath,
        )
    }

    fun join(parent: String, child: String): String =
        listOf(normalizeUserPath(parent), normalizeUserPath(child))
            .filter { it.isNotBlank() }
            .joinToString("/")
}
