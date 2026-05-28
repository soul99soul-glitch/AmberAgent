package app.amber.core.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillPathsTest {
    @Test
    fun `parse supports CRLF frontmatter`() {
        val content = "---\r\nname: test-skill\r\ndescription: test\r\n---\r\n\r\nbody"

        val frontmatter = SkillFrontmatterParser.parse(content)

        assertEquals("test-skill", frontmatter["name"])
        assertEquals("test", frontmatter["description"])
        assertEquals("body", SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun `ensure description replaces pipe placeholder from body`() {
        val content = """
            ---
            name: get-notes
            description: |
            ---

            Use when the user wants to capture or retrieve notes from a conversation.
        """.trimIndent()

        val updated = SkillFrontmatterParser.ensureDescription(content, "get-notes")
        val frontmatter = SkillFrontmatterParser.parse(updated)

        assertEquals(
            "Use when the user wants to capture or retrieve notes from a conversation.",
            frontmatter["description"],
        )
    }

    @Test
    fun `ensure description inserts fallback for empty frontmatter description`() {
        val content = """
            ---
            name: 高德地图
            description: "..."
            ---

        """.trimIndent()

        val updated = SkillFrontmatterParser.ensureDescription(content, "高德地图")
        val frontmatter = SkillFrontmatterParser.parse(updated)

        assertEquals("用于处理「高德地图」相关任务。", frontmatter["description"])
    }

    @Test
    fun `ensure description adds frontmatter when missing`() {
        val content = "用于查询地图路线、地点搜索和导航建议。"

        val updated = SkillFrontmatterParser.ensureDescription(content, "高德地图")
        val frontmatter = SkillFrontmatterParser.parse(updated)

        assertEquals("高德地图", frontmatter["name"])
        assertEquals("用于查询地图路线、地点搜索和导航建议。", frontmatter["description"])
    }

    @Test
    fun `resolve skill dir rejects traversal and nested names`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()

        try {
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "../upload"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo/bar"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo\\bar"))
            assertNotNull(SkillPaths.resolveSkillDir(skillsRoot, "valid-skill"))
        } finally {
            skillsRoot.deleteRecursively()
        }
    }

    @Test
    fun `resolve skill file rejects sibling prefix escape`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()
        val skillDir = File(skillsRoot, "foo").apply { mkdirs() }
        File(skillsRoot, "foobar").apply { mkdirs() }

        try {
            val safeFile = SkillPaths.resolveSkillFile(skillDir, "notes.md")
            val escapedFile = SkillPaths.resolveSkillFile(skillDir, "../foobar/secret.md")

            assertEquals(File(skillDir, "notes.md").canonicalFile, safeFile)
            assertNull(escapedFile)
        } finally {
            skillsRoot.deleteRecursively()
        }
    }
}
