package app.amber.feature.icloud

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ICloudDriveNodeRefTest {
    @Test
    fun encodesAndDecodesOpaqueNodeRefs() {
        val node = ICloudDriveNode(
            name = "Daily.md",
            type = "file",
            drivewsid = "FILE::zone::daily",
            docwsid = "doc-1",
            zone = "com.apple.CloudDocs",
            etag = "etag-1",
            sizeBytes = 42L,
            raw = buildJsonObject {},
        )

        val token = ICloudDriveNodeRefs.encode("Notes/Daily.md", node)
        val decoded = ICloudDriveNodeRefs.decode(token)

        assertTrue(token.startsWith("icn_"))
        assertNotNull(decoded)
        assertEquals("Notes/Daily.md", decoded!!.path)
        assertEquals("FILE::zone::daily", decoded.drivewsId)
        assertEquals("doc-1", decoded.docwsId)
        assertEquals("etag-1", decoded.etag)
    }

    @Test
    fun rejectsInvalidNodeRefs() {
        assertNull(ICloudDriveNodeRefs.decode(null))
        assertNull(ICloudDriveNodeRefs.decode(""))
        assertNull(ICloudDriveNodeRefs.decode("not-a-node-ref"))
        assertNull(ICloudDriveNodeRefs.decode("icn_not-base64"))
    }

    @Test
    fun clientTreatsNodeRefAsPathScopedHint() {
        val source = locateClient().readText()

        assertTrue(source.contains("val node = resolveNode(session, resolvedPath.iCloudPath)"))
        assertTrue(source.contains("node.matches(nodeRef)"))
        assertFalse(
            "node_ref must not be used as a direct retrieveNode capability token",
            source.contains("retrieveNode(session, drivewsId)"),
        )
    }

    private fun locateClient(): File {
        val candidates = listOf(
            File("src/main/java/app/amber/agent/data/agent/icloud/ICloudDriveClient.kt"),
            File("app/src/main/java/app/amber/agent/data/agent/icloud/ICloudDriveClient.kt"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Could not locate ICloudDriveClient.kt")
    }
}
