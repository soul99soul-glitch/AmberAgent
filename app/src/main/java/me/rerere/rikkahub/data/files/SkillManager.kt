package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
        private const val BUILTIN_SKILLS_ASSET_DIR = "builtin-skills"
    }

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listSkills(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        return skillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile, dir)
            }
            ?: emptyList()
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
            }.onFailure { error ->
                Log.w(TAG, "installBuiltinSkillsIfMissing: Failed to install $skillName", error)
                targetDir.deleteRecursively()
            }
        }
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
        return parseSkillFile(skillFile, skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
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

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
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
        return repaired
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
