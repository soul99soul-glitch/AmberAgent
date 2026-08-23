package app.amber.core.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-02 namespace encoder (parity plan §P2-02): two servers with the same
 * tool name, special/long/case-conflicting names, stable hash suffixes and
 * legacy `mcp__tool` alias resolution.
 */
class McpToolNamespaceTest {

    private fun ref(
        server: String,
        tool: String,
        serverId: String = "srv-${server.hashCode().ushr(1)}",
    ) = McpToolRef(serverId = serverId, serverName = server, toolName = tool)

    // ---- two servers, same tool name ----

    @Test
    fun twoServersSameToolNameGetDistinctNamespacedNames() {
        val refs = listOf(ref("server-a", "search"), ref("server-b", "search"))
        val batch = McpToolNamespace.encodeBatch(refs)

        assertEquals(2, batch.values.toSet().size)
        assertEquals("mcp__server-a__search", batch[refs[0]])
        assertEquals("mcp__server-b__search", batch[refs[1]])

        val resolution = McpToolNamespace.resolve("mcp__server-a__search", refs)
        assertTrue(resolution is McpToolNameResolution.Unique)
        assertEquals(refs[0], (resolution as McpToolNameResolution.Unique).ref)
    }

    // ---- special characters ----

    @Test
    fun specialCharactersAreSanitizedAndStillResolve() {
        val refs = listOf(ref("my server", "read file!"))
        val batch = McpToolNamespace.encodeBatch(refs)

        val name = batch.getValue(refs.first())
        assertEquals("mcp__my_server__read_file_", name)
        assertTrue(name.all { it in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" })

        val resolution = McpToolNamespace.resolve(name, refs)
        assertTrue(resolution is McpToolNameResolution.Unique)
        assertEquals(refs.first(), (resolution as McpToolNameResolution.Unique).ref)
    }

    // ---- overlong names ----

    @Test
    fun overlongNamesAreTruncatedDeterministicallyAndStayResolvable() {
        val longServer = "very-long-mcp-server-name-that-exceeds-segment-limits-1234567890"
        val longTool = "another-very-long-tool-name-that-also-exceeds-any-reasonable-limit-9876543210"
        val refs = listOf(ref(longServer, longTool))
        val batch = McpToolNamespace.encodeBatch(refs)

        val name = batch.getValue(refs.first())
        assertTrue("expanded name must fit provider limits: ${name.length}", name.length <= McpToolNamespace.MAX_NAME_LENGTH)

        // Deterministic across repeated calls / different input order.
        val reversed = McpToolNamespace.encodeBatch(refs.reversed())
        assertEquals(name, reversed[refs.first()])

        val resolution = McpToolNamespace.resolve(name, refs)
        assertTrue(resolution is McpToolNameResolution.Unique)
        assertEquals(refs.first(), (resolution as McpToolNameResolution.Unique).ref)
    }

    // ---- case conflicts stay distinct ----

    @Test
    fun caseOnlyDifferencesKeepDistinctNames() {
        val refs = listOf(ref("Notes", "read"), ref("notes", "read"))
        val batch = McpToolNamespace.encodeBatch(refs)

        assertEquals(2, batch.values.toSet().size)
        assertEquals("mcp__Notes__read", batch[refs[0]])
        assertEquals("mcp__notes__read", batch[refs[1]])
    }

    // ---- sanitization collision gets a stable hash suffix ----

    @Test
    fun sanitizationCollisionGetsStableHashSuffixNotOrderNumbering() {
        // "my server" and "my_server" sanitize to the same segment.
        val refs = listOf(ref("my server", "x"), ref("my_server", "x"))
        val batch = McpToolNamespace.encodeBatch(refs)

        assertEquals(2, batch.values.toSet().size)
        val names = refs.map { batch.getValue(it) }
        assertTrue("collision must be resolved with a hash suffix", names.any { it.contains("__h") })

        // Stable regardless of input order (never load-order numbering).
        val reversed = McpToolNamespace.encodeBatch(refs.reversed())
        assertEquals(batch[refs[0]], reversed[refs[0]])
        assertEquals(batch[refs[1]], reversed[refs[1]])
    }

    // ---- legacy alias resolution ----

    @Test
    fun legacyUniqueAliasRoutesToTheOnlyServer() {
        val refs = listOf(ref("server-a", "search_doc"))
        val resolution = McpToolNamespace.resolve("mcp__search_doc", refs)

        assertTrue(resolution is McpToolNameResolution.Unique)
        assertEquals(refs.first(), (resolution as McpToolNameResolution.Unique).ref)
    }

    @Test
    fun legacyMultiMatchIsRejectedAsAmbiguous() {
        val refs = listOf(ref("server-a", "search_doc"), ref("server-b", "search_doc"))
        val resolution = McpToolNamespace.resolve("mcp__search_doc", refs)

        assertTrue(resolution is McpToolNameResolution.Ambiguous)
        assertEquals(2, (resolution as McpToolNameResolution.Ambiguous).refs.size)
    }

    @Test
    fun unknownNameIsNotFound() {
        val refs = listOf(ref("server-a", "search_doc"))
        assertTrue(McpToolNamespace.resolve("mcp__nope", refs) is McpToolNameResolution.NotFound)
        assertTrue(McpToolNamespace.resolve("not_mcp_at_all", refs) is McpToolNameResolution.NotFound)
    }

    @Test
    fun namespacedNameWinsOverLegacyLookup() {
        // Tool literally named "a__b" must not be shadowed by legacy parsing.
        val refs = listOf(ref("server-a", "search"), ref("server-x", "a__b"))
        val batch = McpToolNamespace.encodeBatch(refs)

        val namespaced = batch.getValue(refs[1])
        val resolution = McpToolNamespace.resolve(namespaced, refs)
        assertTrue(resolution is McpToolNameResolution.Unique)
        assertEquals(refs[1], (resolution as McpToolNameResolution.Unique).ref)
    }

    // ---- display name separation ----

    @Test
    fun displayNameIsDecoupledFromProtocolName() {
        assertEquals("server/tool", McpToolNamespace.displayName("mcp__server__tool"))
        assertEquals("server/tool", McpToolNamespace.displayName("mcp__server__tool__h1234abcd"))
        assertEquals("tool", McpToolNamespace.displayName("mcp__tool"))
        assertEquals("plain", McpToolNamespace.displayName("plain"))
    }
}
