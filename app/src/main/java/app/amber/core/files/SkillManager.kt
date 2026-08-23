package app.amber.core.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.amber.core.settings.prefs.SettingsAggregator

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsAggregator,
) {
    companion object {
        private const val TAG = "SkillManager"
        private const val BUILTIN_SKILLS_ASSET_DIR = "builtin-skills"

        /** P2-04: previous-version meta file inside the skills_previous dir. */
        internal const val META_FILE_NAME = ".promotion.json"
    }

    @Volatile
    private var cachedSkills: List<SkillMetadata>? = null

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSkills(): List<SkillMetadata> {
        cachedSkills?.let { return it }
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?.also { cachedSkills = it }
            ?: emptyList<SkillMetadata>().also { cachedSkills = it }
    }

    fun listSkillIssues(): List<SkillScanIssue> {
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) {
                    return@mapNotNull SkillScanIssue(dir.name, "缺少 SKILL.md")
                }
                runCatching {
                    val frontmatter = SkillFrontmatterParser.parse(skillFile.readText())
                    when {
                        frontmatter["name"].isNullOrBlank() -> SkillScanIssue(dir.name, "SKILL.md 缺少 name")
                        SkillFrontmatterParser.isPlaceholderDescription(frontmatter["description"]) ->
                            SkillScanIssue(dir.name, "SKILL.md 缺少有效 description")
                        else -> null
                    }
                }.getOrElse {
                    SkillScanIssue(dir.name, "SKILL.md 解析失败")
                }
            }
            ?: emptyList()
    }

    suspend fun installBuiltinSkillsIfMissing() = withContext(Dispatchers.IO) {
        val builtinSkillNames = context.assets.list(BUILTIN_SKILLS_ASSET_DIR).orEmpty()
        var installedAny = false
        builtinSkillNames.forEach { skillName ->
            val targetDir = resolveSkillDir(skillName) ?: return@forEach
            val marker = targetDir.resolve("SKILL.md")
            if (marker.exists()) return@forEach

            runCatching {
                targetDir.mkdirs()
                copyAssetDirectory(
                    assetPath = "$BUILTIN_SKILLS_ASSET_DIR/$skillName",
                    targetDir = targetDir,
                )
                installedAny = true
            }.onFailure { error ->
                Log.w(TAG, "installBuiltinSkillsIfMissing: Failed to install $skillName", error)
                targetDir.deleteRecursively()
            }
        }
        if (installedAny) invalidateSkillCache()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        val skillDir = resolveSkillDir(name) ?: return null
        skillDir.mkdirs()
        val skillFile = skillDir.resolve("SKILL.md")
        skillFile.writeText(SkillFrontmatterParser.ensureDescription(content, name))
        invalidateSkillCache()
        return parseSkillFile(skillFile, skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            invalidateSkillCache()
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        }
        deleted
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        invalidateSkillCache()
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeText(
                    if (relativePath == "SKILL.md") SkillFrontmatterParser.ensureDescription(content, skillName)
                    else content
                )
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            invalidateSkillCache()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    // ---- P2-04 skill promotion: keep the replaced version for one rollback ----

    /**
     * Atomic promotion (P2-04): staging → current, and the replaced version is
     * kept under `skills_previous/<name>/` (with [SkillPreviousMeta]) instead of
     * being deleted, so `skill_rollback` can restore it exactly once. A later
     * promotion replaces the previous snapshot (only the latest is kept).
     */
    fun promoteSkill(skillName: String, files: Map<String, String>, meta: SkillPreviousMeta): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        val previousDir = resolvePreviousSkillDir(skillName) ?: return false
        // Whether a real previous version was moved aside (fresh installs keep
        // only a meta marker, so a failed swap must not restore an empty dir).
        var hadTarget = false

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeText(
                    if (relativePath == "SKILL.md") SkillFrontmatterParser.ensureDescription(content, skillName)
                    else content
                )
            }
            if (!stagingDir.resolve("SKILL.md").exists()) return false

            // Move the current version aside (drop any older previous snapshot).
            if (previousDir.exists() && !previousDir.deleteRecursively()) return false
            if (targetDir.exists()) {
                previousDir.parentFile?.mkdirs()
                if (!targetDir.renameTo(previousDir)) return false
                hadTarget = true
            }
            // P2-04 m2: write the meta BEFORE the atomic staging → target
            // replace, so the kept previous snapshot (files + meta) is fully
            // usable before the new version lands — no crash window where the
            // new version is installed but previous lacks its meta.
            previousDir.parentFile?.mkdirs()
            previousDir.mkdirs()
            previousDir.resolve(META_FILE_NAME).writeText(SkillPreviousMeta.encode(meta))
            if (!stagingDir.renameTo(targetDir)) {
                if (hadTarget && previousDir.exists() && !targetDir.exists()) {
                    // Restoring the previous snapshot as the active dir: drop
                    // its meta file so the active skill dir stays clean.
                    previousDir.resolve(META_FILE_NAME).delete()
                    previousDir.renameTo(targetDir)
                }
                return false
            }
            invalidateSkillCache()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "promoteSkill: Failed to promote $skillName", e)
            if (hadTarget && previousDir.exists() && !targetDir.exists()) {
                previousDir.resolve(META_FILE_NAME).delete()
                previousDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            // A failed swap on a fresh install leaves only the meta marker
            // (no real previous files were moved aside) — drop it so
            // skills_previous never advertises a bogus rollback target.
            if (!hadTarget && previousDir.exists() && !targetDir.exists()) {
                previousDir.deleteRecursively()
            }
        }
    }

    /**
     * One explicit rollback (P2-04): restores the kept previous version over
     * the current one and deletes the previous snapshot — a second rollback
     * is therefore impossible. Returns false when there is no previous
     * version or the swap fails.
     */
    fun rollbackSkill(skillName: String): Boolean {
        val previousDir = resolvePreviousSkillDir(skillName) ?: return false
        if (!previousDir.exists()) return false
        val targetDir = resolveSkillDir(skillName) ?: return false
        val swapDir = createTempSkillDir(getSkillsDir(), skillName, "swap") ?: return false

        try {
            if (targetDir.exists() && !targetDir.renameTo(swapDir)) return false
            // P2-04 m1: the kept previous snapshot carries .promotion.json —
            // drop it before restoring so the active skill dir stays clean
            // (no meta residue after the rollback).
            previousDir.resolve(META_FILE_NAME).delete()
            if (!previousDir.renameTo(targetDir)) {
                if (swapDir.exists() && !targetDir.exists()) swapDir.renameTo(targetDir)
                return false
            }
            swapDir.deleteRecursively()
            // Rolled-back skill had no previous files (it was newly promoted):
            // the promotion created it, so rollback removes it entirely.
            if (!targetDir.resolve("SKILL.md").exists()) {
                targetDir.deleteRecursively()
            }
            invalidateSkillCache()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "rollbackSkill: Failed to roll back $skillName", e)
            if (swapDir.exists() && !targetDir.exists()) swapDir.renameTo(targetDir)
            return false
        } finally {
            if (previousDir.exists()) previousDir.deleteRecursively()
        }
    }

    /** Meta of the kept previous version, or null when absent. */
    fun previousSkillMeta(skillName: String): SkillPreviousMeta? {
        val dir = resolvePreviousSkillDir(skillName) ?: return null
        val metaFile = dir.resolve(META_FILE_NAME)
        if (!metaFile.exists()) return null
        return runCatching { SkillPreviousMeta.decode(metaFile.readText()) }.getOrNull()
    }

    private fun resolvePreviousSkillDir(skillName: String): File? {
        if (skillName.isBlank()) return null
        if (skillName.contains('/') || skillName.contains('\\') || skillName == "." || skillName == "..") return null
        val root = context.filesDir.resolve(FileFolders.SKILLS_PREVIOUS)
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val dir = canonicalRoot.resolve(skillName)
        return dir.takeIf { it.parentFile == canonicalRoot }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete().also { deleted ->
            if (deleted) invalidateSkillCache()
        }
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    fun repairMissingDescriptions(): Int {
        val skillsDir = getSkillsDir()
        var repaired = 0
        skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@forEach
                runCatching {
                    val original = skillFile.readText()
                    val updated = SkillFrontmatterParser.ensureDescription(original, dir.name)
                    if (updated != original) {
                        skillFile.writeText(updated)
                        repaired++
                    }
                }.onFailure { error ->
                    Log.w(TAG, "repairMissingDescriptions: Failed to repair ${dir.name}", error)
                }
            }
        if (repaired > 0) invalidateSkillCache()
        return repaired
    }

    private fun invalidateSkillCache() {
        cachedSkills = null
    }

    private fun resolveSkillDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun copyAssetDirectory(assetPath: String, targetDir: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            targetDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                targetDir.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        targetDir.mkdirs()
        children.forEach { child ->
            copyAssetDirectory(
                assetPath = "$assetPath/$child",
                targetDir = targetDir.resolve(child),
            )
        }
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = SkillFrontmatterParser.resolveDescription(content, frontmatter, name)
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = frontmatter["allowed-tools"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}

data class SkillScanIssue(
    val directoryName: String,
    val reason: String,
)

/**
 * P2-04: metadata of the kept previous skill version (digests only — never
 * content). [replacedDigest] is the digest of the version that was moved to
 * previous; [candidateDigest] is the digest of the version that replaced it,
 * so rollback can CAS-check that the current version is still the promoted
 * one before restoring.
 */
@kotlinx.serialization.Serializable
data class SkillPreviousMeta(
    val replacedDigest: String,
    val candidateDigest: String,
    val appliedAtMs: Long,
) {
    companion object {
        private val json = app.amber.core.utils.JsonInstant

        fun encode(meta: SkillPreviousMeta): String = json.encodeToString(serializer(), meta)

        fun decode(raw: String): SkillPreviousMeta =
            json.decodeFromString(serializer(), raw)
    }
}

object SkillFrontmatterParser {
    private val frontmatterEndRegex = Regex("""\r?\n---(?:\r?\n|$)""")
    private val placeholderDescriptions = setOf(
        "|",
        "｜",
        "-",
        "--",
        "---",
        ".",
        "...",
        "…",
        "todo",
        "tbd",
        "none",
        "n/a",
        "null",
        "description",
        "描述",
    )

    fun parse(content: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (!content.startsWith("---")) return result
        val endRange = findFrontmatterEndRange(content) ?: return result
        val yaml = content.substring(3, endRange.first).trim()
        yaml.lines().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
        }
        return result
    }

    fun ensureDescription(content: String, skillNameHint: String? = null): String {
        val frontmatter = parse(content)
        val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
            ?: skillNameHint?.takeIf { it.isNotBlank() }
            ?: "custom-skill"
        val currentDescription = frontmatter["description"]
        if (!isPlaceholderDescription(currentDescription)) return content

        val description = inferDescription(content, name)
        val line = """description: "${description.escapeYamlString()}""""
        val endRange = findFrontmatterEndRange(content)
        if (!content.startsWith("---") || endRange == null) {
            return "---\nname: ${name.escapeYamlString()}\n$line\n---\n\n${content.trimStart()}"
        }

        val yaml = content.substring(3, endRange.first).trim()
        val lines = yaml.lines().toMutableList()
        val descriptionIndex = lines.indexOfFirst { it.substringBefore(':').trim() == "description" }
        if (descriptionIndex >= 0) {
            lines[descriptionIndex] = line
        } else {
            val nameIndex = lines.indexOfFirst { it.substringBefore(':').trim() == "name" }
            val insertIndex = if (nameIndex >= 0) nameIndex + 1 else lines.size
            lines.add(insertIndex, line)
        }

        val body = content.substring(endRange.last + 1).trimStart('\r', '\n')
        return "---\n${lines.joinToString("\n")}\n---\n\n$body"
    }

    fun resolveDescription(content: String, frontmatter: Map<String, String>, skillName: String): String {
        val description = frontmatter["description"]
        return if (isPlaceholderDescription(description)) inferDescription(content, skillName) else description!!.trim()
    }

    fun isPlaceholderDescription(description: String?): Boolean {
        val normalized = description
            ?.trim()
            ?.trim('"', '\'')
            ?.lowercase()
            .orEmpty()
        return normalized.isBlank() || normalized in placeholderDescriptions
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return frontmatterEndRegex.find(content, startIndex = 3)?.range
    }

    private fun inferDescription(content: String, skillName: String): String {
        val body = extractBody(content)
        val candidates = body.lineSequence()
            .map { it.cleanDescriptionCandidate() }
            .filter { it.isNotBlank() }
            .filterNot { it.equals(skillName, ignoreCase = true) }
            .filterNot { it.isGenericSkillHeading() }
            .toList()

        val preferred = candidates.firstOrNull { it.hasInvocationCue() }
            ?: candidates.firstOrNull()
        return preferred
            ?.takeDescriptionChars(120)
            ?: fallbackDescription(skillName)
    }

    private fun fallbackDescription(skillName: String): String {
        return if (skillName.any { it.code > 127 }) {
            "用于处理「$skillName」相关任务。"
        } else {
            "Use when the user asks AmberAgent to work with $skillName."
        }
    }

    private fun String.cleanDescriptionCandidate(): String {
        return trim()
            .removePrefix("\uFEFF")
            .replace(Regex("""^#{1,6}\s*"""), "")
            .replace(Regex("""^[-*+]\s+"""), "")
            .replace(Regex("""^\d+[.)]\s+"""), "")
            .replace(Regex("""^\[[ xX]]\s+"""), "")
            .replace("`", "")
            .replace("*", "")
            .replace("_", "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('|', '｜')
            .trim()
    }

    private fun String.isGenericSkillHeading(): Boolean {
        val lower = lowercase()
        return lower in setOf(
            "instructions",
            "instruction",
            "overview",
            "description",
            "usage",
            "workflow",
            "steps",
            "skill",
            "skill instructions",
            "说明",
            "使用说明",
            "工作流程",
        )
    }

    private fun String.hasInvocationCue(): Boolean {
        val lower = lowercase()
        return listOf(
            "use when",
            "use this skill",
            "when the user",
            "用于",
            "适用于",
            "当用户",
            "当需要",
            "使用场景",
        ).any { it in lower }
    }

    private fun String.takeDescriptionChars(maxChars: Int): String {
        return if (length <= maxChars) this else take(maxChars - 1).trimEnd() + "…"
    }

    private fun String.escapeYamlString(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
