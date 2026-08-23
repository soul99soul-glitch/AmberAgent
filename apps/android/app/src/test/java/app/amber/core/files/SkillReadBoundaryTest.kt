package app.amber.core.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-03 skill file read boundary (parity plan §P2-03).
 *
 * Acceptance covered:
 *  - symlink escape is rejected (realpath containment);
 *  - mcp.json content never reaches a tool result (permanent blocklist, even
 *    when the manifest would authorize it);
 *  - hidden files, keys/certs/credential material and secret caches blocked;
 *  - binary and oversized files rejected;
 *  - default = SKILL.md only; references/assets need a manifest read-resources
 *    entry.
 */
class SkillReadBoundaryTest {

    private fun skillDir(manifest: String, extra: Map<String, String> = emptyMap()): File {
        val dir = Files.createTempDirectory("skill-read-boundary").toFile()
        dir.resolve("SKILL.md").writeText(manifest)
        extra.forEach { (path, content) ->
            val target = dir.resolve(path)
            target.parentFile?.mkdirs()
            target.writeText(content)
        }
        return dir
    }

    private fun okResult(result: SkillReadBoundary.ReadResult): String {
        assertTrue("expected Ok but was ${result.javaClass.simpleName}", result is SkillReadBoundary.ReadResult.Ok)
        return (result as SkillReadBoundary.ReadResult.Ok).content
    }

    private fun rejection(result: SkillReadBoundary.ReadResult): SkillReadBoundary.ReadResult.Rejected {
        assertTrue("expected Rejected but was ${result.javaClass.simpleName}", result is SkillReadBoundary.ReadResult.Rejected)
        return result as SkillReadBoundary.ReadResult.Rejected
    }

    @Test
    fun `skill file is readable by default`() {
        val dir = skillDir("---\nname: demo\n---\n# Demo\nbody")
        val result = SkillReadBoundary.readForModel(dir, "SKILL.md", emptySet())
        assertTrue(result is SkillReadBoundary.ReadResult.Ok)
        assertTrue(okResult(result).contains("body"))
    }

    @Test
    fun `reference file needs a manifest read-resources entry`() {
        val dir = skillDir(
            "---\nname: demo\nread-resources: references/guide.md\n---\n# Demo",
            mapOf("references/guide.md" to "guide content"),
        )
        val manifest = SkillFrontmatterParser.readResources(SkillFrontmatterParser.parse("---\nname: demo\nread-resources: references/guide.md\n---\n"))

        val authorized = SkillReadBoundary.readForModel(dir, "references/guide.md", manifest)
        assertTrue(authorized is SkillReadBoundary.ReadResult.Ok)

        val unauthorized = SkillReadBoundary.readForModel(dir, "references/other.md", manifest)
        val rejected = rejection(unauthorized)
        assertEquals("not_authorized", rejected.reason)
        // The rejection hint must only expose readable files.
        val readable = SkillReadBoundary.listReadableFiles(dir, manifest)
        assertEquals(listOf("SKILL.md", "references/guide.md"), readable)
    }

    @Test
    fun `mcp json is permanently blocked even when the manifest authorizes it`() {
        val dir = skillDir(
            "---\nname: demo\nread-resources: mcp.json\n---\n# Demo",
            mapOf("mcp.json" to """{"mcpServers":{"local":{"type":"streamable_http","url":"http://127.0.0.1:9000","headers":{"Authorization":"Bearer secret"}}}}"""),
        )
        val manifest = SkillFrontmatterParser.readResources(
            SkillFrontmatterParser.parse("---\nname: demo\nread-resources: mcp.json\n---\n")
        )
        val result = rejection(SkillReadBoundary.readForModel(dir, "mcp.json", manifest))
        assertEquals("forbidden_file", result.reason)
        assertTrue(result.detail.contains("mcp.json"))
        // And it never appears in the readable-file hint list.
        assertTrue(SkillReadBoundary.listReadableFiles(dir, manifest).none { it.contains("mcp.json") })
    }

    @Test
    fun `hidden files and secret material are blocked`() {
        val dir = skillDir(
            "---\nname: demo\nread-resources: .env id_rsa credentials.json tokens.txt\n---\n# Demo",
            mapOf(
                ".env" to "API_KEY=super-secret",
                "id_rsa" to "-----BEGIN PRIVATE KEY-----",
                "credentials.json" to "{}",
                "tokens.txt" to "abc",
            ),
        )
        val manifest = SkillFrontmatterParser.readResources(
            SkillFrontmatterParser.parse("---\nname: demo\nread-resources: .env id_rsa credentials.json tokens.txt\n---\n")
        )

        val env = rejection(SkillReadBoundary.readForModel(dir, ".env", manifest))
        assertEquals("hidden_path", env.reason)
        val key = rejection(SkillReadBoundary.readForModel(dir, "id_rsa", manifest))
        assertEquals("forbidden_file", key.reason)
        val creds = rejection(SkillReadBoundary.readForModel(dir, "credentials.json", manifest))
        assertEquals("forbidden_file", creds.reason)
        val tokens = rejection(SkillReadBoundary.readForModel(dir, "tokens.txt", manifest))
        assertEquals("forbidden_file", tokens.reason)
        val cert = rejection(SkillReadBoundary.readForModel(dir, "server.crt.pem", manifest))
        assertEquals("forbidden_file", cert.reason)
    }

    @Test
    fun `symlink escape is rejected via realpath containment`() {
        val outside = Files.createTempDirectory("outside").toFile()
        outside.resolve("secret.txt").writeText("outside secret content")
        val dir = skillDir("---\nname: demo\nread-resources: link.txt\n---\n# Demo")
        val link = dir.resolve("link.txt")
        // A symlink inside the skill dir pointing outside it.
        Files.createSymbolicLink(link.toPath(), outside.resolve("secret.txt").toPath())

        val result = rejection(
            SkillReadBoundary.readForModel(dir, "link.txt", setOf("link.txt"))
        )
        assertEquals("path_escape", result.reason)
        assertTrue(result.detail.contains("symlink escape"))
    }

    @Test
    fun `dot traversal is rejected before touching the filesystem`() {
        val dir = skillDir("---\nname: demo\n---\n# Demo")
        for (path in listOf("../secret.txt", "a/../../secret.txt", "/absolute/path.txt", "a\\..\\secret.txt")) {
            val result = rejection(SkillReadBoundary.readForModel(dir, path, emptySet()))
            assertEquals("invalid_path", result.reason)
        }
    }

    @Test
    fun `binary files are rejected`() {
        val dir = skillDir("---\nname: demo\nread-resources: assets/data.bin\n---\n# Demo")
        dir.resolve("assets").mkdirs()
        dir.resolve("assets/data.bin").writeBytes(ByteArray(64) { it.toByte() } + byteArrayOf(0) + ByteArray(64))

        val result = rejection(SkillReadBoundary.readForModel(dir, "assets/data.bin", setOf("assets/data.bin")))
        assertEquals("binary", result.reason)
    }

    @Test
    fun `oversized files are rejected`() {
        val dir = skillDir("---\nname: demo\nread-resources: big.md\n---\n# Demo")
        dir.resolve("big.md").writeBytes(ByteArray(SkillReadBoundary.MAX_SKILL_FILE_BYTES + 1) { 'a'.code.toByte() })

        val result = rejection(SkillReadBoundary.readForModel(dir, "big.md", setOf("big.md")))
        assertEquals("too_large", result.reason)
    }

    @Test
    fun `manifest read-resources parser normalizes and drops unsafe entries`() {
        val frontmatter = SkillFrontmatterParser.parse(
            """
            ---
            name: demo
            read-resources: references/a.md, assets/b.css
            ---
            """.trimIndent()
        )
        val resources = SkillFrontmatterParser.readResources(frontmatter)
        assertEquals(setOf("references/a.md", "assets/b.css"), resources)

        val unsafe = SkillFrontmatterParser.readResources(
            mapOf("read-resources" to " ../escape.md .hidden /abs.md ok.md")
        )
        assertEquals(setOf("ok.md"), unsafe)
    }
}
