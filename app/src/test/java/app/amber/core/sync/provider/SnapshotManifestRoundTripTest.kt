package app.amber.core.sync.provider

import app.amber.core.sync.core.CURRENT_ARCHIVE_VERSION
import app.amber.core.sync.core.SyncCipherInfo
import app.amber.core.sync.core.SyncKdfInfo
import app.amber.core.sync.core.SyncManifest
import app.amber.core.sync.core.SyncMode
import app.amber.core.agent.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7-01 验收：统一快照 manifest 序列化 round-trip。
 */
class SnapshotManifestRoundTripTest {

    private val manifest = SyncSnapshotManifest(
        snapshotId = "snap-1750000000000-abc12345",
        createdAt = 1750000000000L,
        appVersionName = "1.8.16",
        appVersionCode = 816,
        schemaVersion = CURRENT_SNAPSHOT_SCHEMA_VERSION,
        deviceId = "device-1",
        deviceLabel = "OPPO PMA110",
        archiveVersion = CURRENT_ARCHIVE_VERSION,
        mode = SyncMode.FULL,
        encrypted = true,
        kdfVersion = "PBKDF2WithHmacSHA256",
        sizeBytes = 123456L,
        contentSha256 = "ab".repeat(32),
        includedDomains = setOf("settings", "secrets", "tables", "files"),
        excludedSecretTypes = emptySet(),
    )

    @Test
    fun `serialize and deserialize round trip preserves all fields`() {
        val json = encodeSnapshotManifest(JsonInstant, manifest)
        val decoded = decodeSnapshotManifest(JsonInstant, json)

        assertEquals(manifest, decoded)
        assertEquals("snap-1750000000000-abc12345", decoded.snapshotId)
        assertEquals(1750000000000L, decoded.createdAt)
        assertEquals("1.8.16", decoded.appVersionName)
        assertEquals(816L, decoded.appVersionCode)
        assertEquals("OPPO PMA110", decoded.deviceLabel)
        assertEquals(SyncMode.FULL, decoded.mode)
        assertTrue(decoded.encrypted)
        assertEquals("PBKDF2WithHmacSHA256", decoded.kdfVersion)
        assertEquals(123456L, decoded.sizeBytes)
        assertEquals("ab".repeat(32), decoded.contentSha256)
        assertEquals(setOf("settings", "secrets", "tables", "files"), decoded.includedDomains)
        assertTrue(decoded.excludedSecretTypes.isEmpty())
    }

    @Test
    fun `unknown sidecar fields are ignored for forward compatibility`() {
        val raw = """
            {
              "snapshotId": "snap-1",
              "createdAt": 1750000000000,
              "futureField": {"nested": true},
              "anotherFuture": "x"
            }
        """.trimIndent()
        val decoded = decodeSnapshotManifest(JsonInstant, raw)
        assertEquals("snap-1", decoded.snapshotId)
        assertEquals(1750000000000L, decoded.createdAt)
        // 未提供的字段走默认值。
        assertEquals(CURRENT_SNAPSHOT_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(CURRENT_ARCHIVE_VERSION, decoded.archiveVersion)
    }

    @Test
    fun `archive manifest maps into unified snapshot manifest`() {
        val archive = SyncManifest(
            appVersionName = "1.8.16",
            appVersionCode = 816,
            createdAt = 1750000000000L,
            deviceId = "device-1",
            deviceLabel = "vivo V2509A",
            mode = SyncMode.STANDARD,
            encrypted = true,
            kdf = SyncKdfInfo(iterations = 210_000, saltBase64 = "c2FsdA=="),
            cipher = SyncCipherInfo(ivBase64 = "aXY="),
            payloadSha256 = "aa",
        )
        val mapped = snapshotManifestFromArchive(
            snapshotId = "snap-2",
            archive = archive,
            sizeBytes = 99L,
            contentSha256 = "bb",
        )
        assertEquals("snap-2", mapped.snapshotId)
        assertEquals(1750000000000L, mapped.createdAt)
        assertEquals("vivo V2509A", mapped.deviceLabel)
        assertEquals(SyncMode.STANDARD, mapped.mode)
        assertEquals("PBKDF2WithHmacSHA256", mapped.kdfVersion)
        assertEquals(99L, mapped.sizeBytes)
        assertEquals("bb", mapped.contentSha256)
        // STANDARD：secrets 明确排除。
        assertEquals(setOf("settings", "tables", "files"), mapped.includedDomains)
        assertEquals(setOf("oauth"), mapped.excludedSecretTypes)
    }

    @Test
    fun `domainsForMode reflects full versus standard exclusion`() {
        val full = domainsForMode(SyncMode.FULL)
        assertTrue("secrets" in full.included)
        assertTrue(full.excludedSecrets.isEmpty())

        val standard = domainsForMode(SyncMode.STANDARD)
        assertTrue("secrets" !in standard.included)
        assertEquals(setOf("oauth"), standard.excludedSecrets)
    }

    @Test
    fun `snapshot manifest maps to display archive manifest`() {
        val archive = manifest.toArchiveManifest()
        assertEquals(manifest.appVersionName, archive.appVersionName)
        assertEquals(manifest.appVersionCode, archive.appVersionCode)
        assertEquals(manifest.createdAt, archive.createdAt)
        assertEquals(manifest.deviceLabel, archive.deviceLabel)
        assertEquals(manifest.mode, archive.mode)
        assertEquals(manifest.contentSha256, archive.payloadSha256)
    }

    @Test
    fun `snapshot file names derive from snapshot id`() {
        assertEquals("snap-1.amberbackup", snapshotArchiveName("snap-1"))
        assertEquals("snap-1.snapshot.json", snapshotSidecarName("snap-1"))
    }
}
