package app.amber.feature.novel.serialization

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Independent expectations for the Swift reference date and novel package contract.
 */
class SwiftEncodingIncompatibilityTest {
    @Test
    fun date_unixEpochMustBecomeSwiftReferenceSeconds() {
        val unixEpoch = 0.0
        val swiftSeconds = NovelSwiftWireContract.unixToSwiftDateSeconds(unixEpoch)
        assertEquals(-978_307_200.0, swiftSeconds, 0.0)
    }

    @Test
    fun packageContractConstantsMatchIos() {
        assertEquals("amber.novel.project", NovelSwiftWireContract.PACKAGE_FORMAT)
        assertEquals(1, NovelSwiftWireContract.ENVELOPE_VERSION)
        assertEquals(1, NovelSwiftWireContract.PROJECT_SCHEMA_VERSION)
        assertEquals("ambernovel", NovelSwiftWireContract.PACKAGE_EXTENSION)
        assertEquals(
            "application/vnd.amberagent.novel+json",
            NovelSwiftWireContract.PACKAGE_MIME,
        )
        assertEquals(100 * 1024 * 1024, NovelSwiftWireContract.MAX_PROJECT_BYTES)
        assertEquals(140 * 1024 * 1024, NovelSwiftWireContract.MAX_ENVELOPE_BYTES)
    }
}
