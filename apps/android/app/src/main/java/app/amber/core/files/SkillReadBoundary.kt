package app.amber.core.files

import java.io.File

/**
 * P2-03 Skill file read boundary (docs/plans/2026-08-13-android-ios-capability-
 * parity-closure-plan.md §P2-03).
 *
 * `use_skill` may only hand the model content the skill manifest declares
 * readable. Default: only SKILL.md. Additional text files (references/, assets/)
 * require an explicit `read-resources:` manifest entry. The following are
 * permanently blocked regardless of manifest:
 *
 *  - connection configuration (`mcp.json` and friends) — endpoints/headers must
 *    never reach the model, logs or tool results;
 *  - secret reference data and credential caches (`.env`, `*credential*`,
 *    `*secret*`, `*token*`, `*auth*`, `*password*`, `*api_key*`, ...);
 *  - private keys / certificates / keystores (`.pem`, `.key`, `.crt`, `.cer`,
 *    `.p12`, `.pfx`, `.jks`, `.keystore`);
 *  - hidden files / directories (any path segment starting with `.`).
 *
 * Path resolution is realpath-based: the requested path is canonicalized and
 * must stay inside the canonical skill directory, so symlinks cannot escape.
 * Binary files are rejected and every read is size-capped.
 */
object SkillReadBoundary {

    /** Hard per-file byte cap for model-readable skill content. */
    const val MAX_SKILL_FILE_BYTES = 4 * 1024 * 1024

    /** Bytes scanned for binary detection. */
    private const val BINARY_SCAN_BYTES = 8192

    private val FORBIDDEN_FILE_NAMES = setOf(
        "mcp.json", "mcp.yaml", "mcp.yml", "mcp.config.json",
        ".env", ".env.local", ".env.production",
        "credentials", "credentials.json", ".credentials",
        "id_rsa", "id_ed25519",
    )

    /** Secret material that can never be imported into a skill either. */
    private val FORBIDDEN_CANDIDATE_NAMES = setOf(
        ".env", ".env.local", ".env.production",
        "credentials", "credentials.json", ".credentials",
        "id_rsa", "id_ed25519",
    )

    private val FORBIDDEN_EXTENSIONS = setOf(
        ".pem", ".key", ".crt", ".cer", ".p12", ".pfx", ".jks", ".keystore",
        ".der", ".csr", ".p8", ".asc", ".gpg",
    )

    private val FORBIDDEN_NAME_PATTERNS = listOf(
        "credential", "secret", "token", "password", "passwd",
        "api_key", "apikey", "client_secret", "private_key",
    )

    sealed class ReadResult {
        /** Content is safe to expose to the model. */
        data class Ok(val content: String) : ReadResult()

        /** Blocked: [reason] is a stable machine-readable code for tests. */
        data class Rejected(val reason: String, val detail: String) : ReadResult()
    }

    /**
     * Resolve and read one skill file for the model.
     *
     * @param skillDir canonical skill directory (never a symlink path).
     * @param relativePath path as the model requested (from SKILL.md links).
     * @param manifestReadResources paths the manifest explicitly authorizes
     *   (`read-resources:` frontmatter entry).
     */
    fun readForModel(
        skillDir: File,
        relativePath: String,
        manifestReadResources: Set<String>,
    ): ReadResult {
        val normalized = normalizeRequestPath(relativePath)
            ?: return ReadResult.Rejected("invalid_path", "Path '$relativePath' is not a valid relative path")
        if (normalized.equals("SKILL.md", ignoreCase = true)) {
            return readChecked(skillDir, "SKILL.md")
        }
        val fileError = checkFileAllowed(normalized, manifestReadResources)
        if (fileError != null) return ReadResult.Rejected(fileError.reason, fileError.detail)
        return readChecked(skillDir, normalized)
    }

    /**
     * Import-side validation (P2-04): structural path check + permanent
     * secret-file blocklist, WITHOUT the manifest allowlist (a skill package
     * may carry more files than the model may read). mcp.json is allowed to
     * travel with a skill — it is a declared MCP dependency governed by the
     * mcp.import capability, not a model-readable resource.
     */
    fun checkCandidatePath(relativePath: String): Rejection? {
        val normalized = normalizeRequestPath(relativePath)
            ?: return Rejection("invalid_path", "Path '$relativePath' is not a valid relative path")
        val segments = normalized.split('/')
        if (segments.any { it.startsWith(".") }) {
            return Rejection("hidden_path", "Hidden file or directory in path '$relativePath'")
        }
        val fileName = segments.last().lowercase()
        if (fileName in FORBIDDEN_CANDIDATE_NAMES) {
            return Rejection("forbidden_file", "File '$relativePath' contains secret material and cannot be imported")
        }
        if (FORBIDDEN_EXTENSIONS.any { fileName.endsWith(it) }) {
            return Rejection("forbidden_file", "File '$relativePath' is a key/certificate/credential file and cannot be imported")
        }
        if (FORBIDDEN_NAME_PATTERNS.any { fileName.contains(it) }) {
            return Rejection("forbidden_file", "File '$relativePath' looks like secret/credential material and cannot be imported")
        }
        return null
    }

    /**
     * Path-level check without touching the filesystem: hidden segments,
     * forbidden names, manifest authorization. Returns null when allowed.
     */
    fun checkFileAllowed(
        relativePath: String,
        manifestReadResources: Set<String>,
    ): Rejection? {
        val normalized = normalizeRequestPath(relativePath)
            ?: return Rejection("invalid_path", "Path '$relativePath' is not a valid relative path")
        if (normalized.equals("SKILL.md", ignoreCase = true)) return null
        val segments = normalized.split('/')
        if (segments.any { it.startsWith(".") }) {
            return Rejection("hidden_path", "Hidden file or directory in path '$relativePath'")
        }
        val fileName = segments.last().lowercase()
        if (fileName in FORBIDDEN_FILE_NAMES) {
            return Rejection("forbidden_file", "File '$relativePath' contains connection config or secret material and is never readable")
        }
        if (FORBIDDEN_EXTENSIONS.any { fileName.endsWith(it) }) {
            return Rejection("forbidden_file", "File '$relativePath' is a key/certificate/credential file and is never readable")
        }
        if (FORBIDDEN_NAME_PATTERNS.any { fileName.contains(it) }) {
            return Rejection("forbidden_file", "File '$relativePath' looks like secret/credential material and is never readable")
        }
        if (normalized !in manifestReadResources) {
            return Rejection(
                "not_authorized",
                "File '$relativePath' is not declared readable by the skill manifest; only files listed in the 'read-resources' frontmatter entry of SKILL.md can be read",
            )
        }
        return null
    }

    /**
     * All model-readable files under [skillDir] (SKILL.md + manifest-authorized
     * text files, excluding hidden/forbidden entries). Used for error hints so
     * the model can pick a legitimately readable path instead of guessing.
     */
    fun listReadableFiles(skillDir: File, manifestReadResources: Set<String>): List<String> =
        walkSkillFiles(skillDir)
            .map { it.second }
            .filter { checkFileAllowed(it, manifestReadResources) == null }
            .sorted()

    data class Rejection(val reason: String, val detail: String)

    private fun readChecked(skillDir: File, relativePath: String): ReadResult {
        val root = runCatching { skillDir.canonicalFile }.getOrNull()
            ?: return ReadResult.Rejected("missing_skill", "Skill directory not found")
        // realpath containment: canonicalize and verify the resolved target
        // stays inside the canonical skill directory. A symlink pointing
        // outside resolves elsewhere and is rejected here.
        val target = runCatching { root.resolve(relativePath).canonicalFile }.getOrNull()
            ?: return ReadResult.Rejected("invalid_path", "Unable to resolve path '$relativePath'")
        if (!isInside(target, root)) {
            return ReadResult.Rejected(
                "path_escape",
                "Path '$relativePath' resolves outside the skill directory (symlink escape is blocked)",
            )
        }
        if (!target.isFile) {
            return ReadResult.Rejected("not_found", "File '$relativePath' not found in the skill")
        }
        val bytes = readCapped(target)
            ?: return ReadResult.Rejected(
                "too_large",
                "File '$relativePath' exceeds the ${MAX_SKILL_FILE_BYTES / (1024 * 1024)}MB limit",
            )
        if (looksBinary(bytes)) {
            return ReadResult.Rejected("binary", "File '$relativePath' is binary and cannot be read")
        }
        return ReadResult.Ok(bytes.decodeToString())
    }

    private fun readCapped(file: File): ByteArray? = runCatching {
        file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (total > MAX_SKILL_FILE_BYTES - read) return null
                output.write(buffer, 0, read)
                total += read
            }
            output.toByteArray()
        }
    }.getOrNull()

    /** NUL byte or a high ratio of control characters ⇒ binary, not text. */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val scan = bytes.copyOf(minOf(bytes.size, BINARY_SCAN_BYTES))
        if (scan.any { it == 0.toByte() }) return true
        var control = 0
        scan.forEach { byte ->
            val value = byte.toInt() and 0xFF
            if (value < 0x09 || (value in 0x0E..0x1F) || value == 0x7F) control++
        }
        return control > scan.size / 8
    }

    /**
     * Normalizes a model-supplied relative path: forward slashes only, no
     * leading slash (absolute paths are rejected), no `.`/`..` segments, no
     * blank segments.
     */
    private fun normalizeRequestPath(raw: String): String? {
        val trimmed = raw.trim().replace('\\', '/')
        if (trimmed.isBlank() || trimmed.startsWith("/")) return null
        val segments = trimmed.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    private fun isInside(file: File, root: File): Boolean {
        val rootPath = root.path
        val filePath = file.path
        return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    }
}

/**
 * Shared skill-directory file walk (P2-03 / P2-04 / P2-07): canonical root,
 * realpath containment (a symlink resolving outside the skill directory is
 * skipped) and relative paths in walk order. Returns (canonical file, relative
 * path) pairs so callers can apply their own filters (manifest allowlist,
 * hidden segments, text-file heuristic) and read content.
 */
internal fun walkSkillFiles(dir: File): List<Pair<File, String>> {
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    val root = runCatching { dir.canonicalFile }.getOrNull() ?: return emptyList()
    val files = mutableListOf<Pair<File, String>>()
    dir.walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            val rootPath = root.path
            val filePath = canonical.path
            if (filePath != rootPath && !filePath.startsWith(rootPath + File.separator)) return@forEach
            files += canonical to canonical.relativeTo(root).invariantSeparatorsPath
        }
    return files
}

/**
 * Manifest-declared model-readable resources (`read-resources:` frontmatter
 * entry of SKILL.md). Space, comma or newline separated relative paths.
 * Paths that fail normalization (absolute, `..`, hidden segments) are dropped.
 */
fun SkillFrontmatterParser.readResources(frontmatter: Map<String, String>): Set<String> {
    val raw = frontmatter["read-resources"] ?: frontmatter["read_resources"] ?: return emptySet()
    return raw.split(Regex("""[\s,]+"""))
        .mapNotNull { token ->
            val trimmed = token.trim().replace('\\', '/')
            // Absolute paths are rejected (only skill-relative resources).
            if (trimmed.isBlank() || trimmed.startsWith("/")) return@mapNotNull null
            val segments = trimmed.split('/')
            if (segments.any { it.isBlank() || it == "." || it == ".." || it.startsWith(".") }) {
                null
            } else {
                segments.joinToString("/")
            }
        }
        .toSet()
}
