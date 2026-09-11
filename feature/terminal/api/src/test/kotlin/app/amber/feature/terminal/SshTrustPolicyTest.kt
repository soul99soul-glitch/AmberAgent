package app.amber.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * W17: SSH trust policy regressions. First connect without an accepted
 * fingerprint is Untrusted (never silently TOFU); a mismatch blocks before
 * authentication; equality is fingerprint-normalized.
 */
@OptIn(ExperimentalUuidApi::class)
class SshTrustPolicyTest {

    private fun profile(fingerprint: String? = null) = SshProfile(
        id = Uuid.random().toString(),
        name = "受控服务器",
        host = "203.0.113.10",
        port = 22,
        username = "amber",
        authMethod = SshAuthMethod.PASSWORD,
        acceptedHostKeyFingerprint = fingerprint,
        createdAtMs = 0,
        updatedAtMs = 0,
    )

    @Test
    fun `no accepted fingerprint is untrusted even when a probe exists`() {
        val trust = SshTrustPolicy.evaluate(profile(), "aa".repeat(32))
        assertEquals(SshHostTrust.Untrusted, trust)
    }

    @Test
    fun `accepted fingerprint without probe is untrusted`() {
        val trust = SshTrustPolicy.evaluate(profile("aa".repeat(32)), null)
        assertEquals(SshHostTrust.Untrusted, trust)
    }

    @Test
    fun `matching probe is trusted`() {
        val trust = SshTrustPolicy.evaluate(profile("aa".repeat(32)), "aa".repeat(32))
        assertEquals(SshHostTrust.Trusted("aa".repeat(32)), trust)
    }

    @Test
    fun `fingerprint comparison ignores case and separators`() {
        // Real colon-separated pairs with mixed case, plus whitespace.
        val raw = " " + List(32) { "AA" }.joinToString(":") + " "
        val trust = SshTrustPolicy.evaluate(profile("aa".repeat(32)), raw)
        assertTrue(trust is SshHostTrust.Trusted)
    }

    @Test
    fun `empty or illegal accepted fingerprint is never trusted`() {
        assertEquals(SshHostTrust.Untrusted, SshTrustPolicy.evaluate(profile(""), "aa".repeat(32)))
        assertEquals(SshHostTrust.Untrusted, SshTrustPolicy.evaluate(profile("zz"), "zz"))
        assertEquals(SshHostTrust.Untrusted, SshTrustPolicy.evaluate(profile("aa".repeat(31)), "aa".repeat(32)))
        // Illegal accepted fingerprint with an identical probed value still blocks.
        assertEquals(SshHostTrust.Untrusted, SshTrustPolicy.evaluate(profile("not-hex"), "not-hex"))
    }

    @Test
    fun `illegal probed fingerprint is untrusted even with valid accepted value`() {
        assertEquals(SshHostTrust.Untrusted, SshTrustPolicy.evaluate(profile("aa".repeat(32)), ""))
        assertEquals(SshHostTrust.Untrusted, SshTrustPolicy.evaluate(profile("aa".repeat(32)), "zz"))
    }

    @Test
    fun `profile validates host username and port`() {
        fun profileOf(host: String, username: String, port: Int) = SshProfile(
            id = "p1",
            name = "n",
            host = host,
            port = port,
            username = username,
            authMethod = SshAuthMethod.PASSWORD,
            createdAtMs = 0,
            updatedAtMs = 0,
        )
        assertTrue(runCatching { profileOf("", "amber", 22) }.isFailure)
        assertTrue(runCatching { profileOf("example.com", "", 22) }.isFailure)
        assertTrue(runCatching { profileOf("example.com", "amber", 0) }.isFailure)
        assertTrue(runCatching { profileOf("example.com", "amber", 65_536) }.isFailure)
        assertTrue(runCatching { profileOf("example.com", "amber", 22) }.isSuccess)
    }

    @Test
    fun `different probe is a mismatch that blocks authentication`() {
        val trust = SshTrustPolicy.evaluate(profile("aa".repeat(32)), "bb".repeat(32))
        assertEquals(
            SshHostTrust.Mismatch("aa".repeat(32), "bb".repeat(32)),
            trust,
        )
    }

    @Test
    fun `normalize strips separators and lowercases`() {
        // 64 hex chars in pairs with colon separators plus stray whitespace.
        val raw = " " + List(32) { "ab" }.joinToString(":") + " "
        assertEquals("ab".repeat(32), SshTrustPolicy.normalizeFingerprint(raw))
    }

    @Test
    fun `normalize rejects non sha256 hex fingerprints`() {
        assertTrue(
            runCatching { SshTrustPolicy.normalizeFingerprint("zz") }.isFailure,
        )
    }

    @Test
    fun `display fingerprint uses OpenSSH SHA256 base64 form`() {
        assertEquals(
            "SHA256:qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqo",
            SshTrustPolicy.displayFingerprint("aa".repeat(32)),
        )
    }
}
