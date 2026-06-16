package app.amber.feature.task

/**
 * iOS Skill scanner — scans documents/skills/ subdirectories for SKILL.md and
 * parses frontmatter (name/description). Mirrors what Android SkillManager does.
 *
 * HONESTY: scans the iOS app's Documents/skills/ directory for real SKILL.md
 * files. If no skills are installed (fresh app), the list will be empty.
 */
object IosSkillFactory {

    data class SkillMetadata(
        val dirName: String,
        val name: String,
        val description: String,
        val enabled: Boolean,
    )

    data class SkillScanIssue(
        val dirName: String,
        val issue: String,
    )

    fun listSkills(documentsDir: String): List<SkillMetadata> {
        val skillsDir = TaskFile(documentsDir + "/skills")
        if (!skillsDir.exists()) return emptyList()
        val result = mutableListOf<SkillMetadata>()
        for (subDir in skillsDir.listDirectories()) {
            val skillFile = subDir.child("SKILL.md")
            if (!skillFile.exists()) continue
            val content = skillFile.readText() ?: continue
            val (name, desc) = parseFrontmatter(content)
            if (name.isNotBlank()) {
                result.add(SkillMetadata(
                    dirName = subDir.path.substringAfterLast('/'),
                    name = name,
                    description = desc,
                    enabled = true,
                ))
            }
        }
        return result
    }

    fun listIssues(documentsDir: String): List<SkillScanIssue> {
        val skillsDir = TaskFile(documentsDir + "/skills")
        if (!skillsDir.exists()) return emptyList()
        val result = mutableListOf<SkillScanIssue>()
        for (subDir in skillsDir.listDirectories()) {
            val dirName = subDir.path.substringAfterLast('/')
            val skillFile = subDir.child("SKILL.md")
            if (!skillFile.exists()) {
                result.add(SkillScanIssue(dirName, "缺少 SKILL.md"))
                continue
            }
            val content = skillFile.readText()
            if (content == null) {
                result.add(SkillScanIssue(dirName, "SKILL.md 读取失败"))
                continue
            }
            val (name, _) = parseFrontmatter(content)
            if (name.isBlank()) {
                result.add(SkillScanIssue(dirName, "SKILL.md 缺少 name"))
            }
        }
        return result
    }

    private fun parseFrontmatter(content: String): Pair<String, String> {
        if (!content.startsWith("---")) return "" to ""
        val end = content.indexOf("---", 3)
        if (end < 0) return "" to ""
        val yaml = content.substring(3, end).trim()
        var name = ""
        var description = ""
        for (line in yaml.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("name:")) {
                name = trimmed.removePrefix("name:").trim().trim('"').trim('\'')
            } else if (trimmed.startsWith("description:")) {
                description = trimmed.removePrefix("description:").trim().trim('"').trim('\'')
            }
        }
        return name to description
    }
}
