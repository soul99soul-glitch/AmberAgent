package app.amber.core.ai.tools

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsToolsTest {
    @Test
    fun `installed skill collection includes html css mjs and mcp config`() {
        val dir = Files.createTempDirectory("installed-skill").toFile()
        try {
            dir.resolve("SKILL.md").writeText(skillMd("guizang-social-card-skill"))
            dir.resolve("references").mkdirs()
            dir.resolve("references/platform-specs.md").writeText("# platform")
            dir.resolve("assets").mkdirs()
            dir.resolve("assets/template.html").writeText("<!doctype html><html></html>")
            dir.resolve("assets/style.css").writeText(".poster{display:block}")
            dir.resolve("scripts").mkdirs()
            dir.resolve("scripts/validate-social-deck.mjs").writeText("export default true")
            dir.resolve("mcp.json").writeText("{}")
            dir.resolve("assets/cover.png").writeBytes(byteArrayOf(1, 2, 3))

            val files = collectSkillFilesFromDirectory(dir)

            assertEquals(
                setOf(
                    "SKILL.md",
                    "references/platform-specs.md",
                    "assets/template.html",
                    "assets/style.css",
                    "scripts/validate-social-deck.mjs",
                    "mcp.json",
                ),
                files.keys,
            )
            assertTrue(files.containsKey("mcp.json"))
            assertFalse(files.containsKey("assets/cover.png"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `zip skill import keeps html css and mjs assets under github archive root`() {
        val zipBytes = skillZip(
            "guizang-ppt-skill-main/SKILL.md" to skillMd("guizang-ppt-skill"),
            "guizang-ppt-skill-main/assets/template.html" to "<!doctype html><html></html>",
            "guizang-ppt-skill-main/assets/template.css" to ".slide{height:100vh}",
            "guizang-ppt-skill-main/scripts/validate-swiss-deck.mjs" to "export default true",
            "guizang-ppt-skill-main/assets/cover.png" to "not text",
        )

        val files = unzipSkillFiles(zipBytes)

        assertTrue(files.containsKey("SKILL.md"))
        assertTrue(files.containsKey("assets/template.html"))
        assertTrue(files.containsKey("assets/template.css"))
        assertTrue(files.containsKey("scripts/validate-swiss-deck.mjs"))
        assertFalse(files.containsKey("assets/cover.png"))
    }

    @Test
    fun `social card adapter uses deck compatible full html contract`() {
        val prompt = buildSkillMobileRuntimePrompt(
            skillName = "guizang-social-card-skill",
            filePath = null,
            body = "Create social cards.",
        )

        assertTrue(prompt.contains("guizang-social-card-skill SPECIAL MOBILE ADAPTER"))
        assertTrue(prompt.contains("renderer:\"full_html\""))
        assertTrue(prompt.contains("<div id=\"deck\">"))
        assertTrue(prompt.contains("<section class=\"slide social-card poster xhs\">"))
        assertTrue(prompt.contains("Preview and export are separate"))
        assertFalse(prompt.contains("card-set"))
    }

    @Test
    fun `ppt adapter still uses full html deck contract`() {
        val prompt = buildSkillMobileRuntimePrompt(
            skillName = "guizang-ppt-skill",
            filePath = "references/layouts.md",
            body = "Create a deck.",
        )

        assertTrue(prompt.contains("guizang-ppt-skill SPECIAL MOBILE ADAPTER"))
        assertTrue(prompt.contains("renderer:\"full_html\""))
        assertTrue(prompt.contains("<div id=\"deck\">"))
        assertTrue(prompt.contains("<section class=\"slide ...\""))
        assertTrue(prompt.contains("Skill: guizang-ppt-skill  (references/layouts.md)"))
    }

    @Test
    fun `generic skills do not receive guizang specific adapters`() {
        val prompt = buildSkillMobileRuntimePrompt(
            skillName = "meeting-prep",
            filePath = null,
            body = "Prepare meetings.",
        )

        assertFalse(prompt.contains("guizang-ppt-skill SPECIAL MOBILE ADAPTER"))
        assertFalse(prompt.contains("guizang-social-card-skill SPECIAL MOBILE ADAPTER"))
    }

    private fun skillMd(name: String): String = """
        ---
        name: $name
        description: test skill
        ---

        Body.
    """.trimIndent()

    private fun skillZip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
