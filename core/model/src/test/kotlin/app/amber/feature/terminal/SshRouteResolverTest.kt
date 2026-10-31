package app.amber.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshRouteResolverTest {
    @Test
    fun `resolveRoute returns 1 to 4 hops in outer-to-inner order`() {
        val j1 = profile("j1", host = "10.0.0.1")
        val j2 = profile("j2", host = "10.0.0.2", jumpProfileId = "j1")
        val j3 = profile("j3", host = "10.0.0.3", jumpProfileId = "j2")
        val target = profile("target", host = "10.0.0.4", jumpProfileId = "j3")
        val all = listOf(target, j3, j2, j1)

        assertEquals(listOf("j1"), SshRouteResolver.resolveRoute(all, "j1").map { it.id })
        assertEquals(listOf("j1", "j2"), SshRouteResolver.resolveRoute(all, "j2").map { it.id })
        assertEquals(listOf("j1", "j2", "j3"), SshRouteResolver.resolveRoute(all, "j3").map { it.id })
        assertEquals(listOf("j1", "j2", "j3", "target"), SshRouteResolver.resolveRoute(all, "target").map { it.id })
    }

    @Test
    fun `resolveRoute rejects more than 3 jump hosts with jump_chain_too_long`() {
        val j1 = profile("j1", host = "10.0.0.1")
        val j2 = profile("j2", host = "10.0.0.2", jumpProfileId = "j1")
        val j3 = profile("j3", host = "10.0.0.3", jumpProfileId = "j2")
        val j4 = profile("j4", host = "10.0.0.4", jumpProfileId = "j3")
        val target = profile("target", host = "10.0.0.5", jumpProfileId = "j4")
        val all = listOf(j1, j2, j3, j4, target)

        val err = runCatching { SshRouteResolver.resolveRoute(all, "target") }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_chain_too_long", err?.error?.code)
    }

    @Test
    fun `resolveRoute detects self-loop 2-node 3-node and tail cycles`() {
        // 1-node self loop
        val selfLoop = listOf(profile("a", jumpProfileId = "a"))
        val err1 = runCatching { SshRouteResolver.resolveRoute(selfLoop, "a") }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_cycle", err1?.error?.code)

        // 2-node cycle: a -> b -> a
        val twoNode = listOf(
            profile("a", jumpProfileId = "b"),
            profile("b", jumpProfileId = "a"),
        )
        val err2 = runCatching { SshRouteResolver.resolveRoute(twoNode, "a") }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_cycle", err2?.error?.code)

        // 3-node cycle: a -> b -> c -> a
        val threeNode = listOf(
            profile("a", jumpProfileId = "b"),
            profile("b", jumpProfileId = "c"),
            profile("c", jumpProfileId = "a"),
        )
        val err3 = runCatching { SshRouteResolver.resolveRoute(threeNode, "a") }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_cycle", err3?.error?.code)

        // Tail cycle: target -> a -> b -> a
        val tailCycle = listOf(
            profile("target", jumpProfileId = "a"),
            profile("a", jumpProfileId = "b"),
            profile("b", jumpProfileId = "a"),
        )
        val errTail = runCatching { SshRouteResolver.resolveRoute(tailCycle, "target") }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_cycle", errTail?.error?.code)
    }

    @Test
    fun `resolveRoute reports missing jump profile with jump_profile_not_found`() {
        val target = profile("target", jumpProfileId = "missing-bastion")
        val err = runCatching {
            SshRouteResolver.resolveRoute(listOf(target), "target")
        }.exceptionOrNull() as? SshRouteException

        assertEquals("jump_profile_not_found", err?.error?.code)
    }

    @Test
    fun `validateAllRoutes catches reverse dependency chain exceeding 3 jumps or forming cycle`() {
        // Existing chain: j2 -> j3 -> target (2 jumps)
        // Updating j1 -> j0 and j2 -> j1 makes target have 4 jumps (j0 -> j1 -> j2 -> j3 -> target)
        val j0 = profile("j0")
        val j1 = profile("j1", jumpProfileId = "j0")
        val j2 = profile("j2", jumpProfileId = "j1")
        val j3 = profile("j3", jumpProfileId = "j2")
        val target = profile("target", jumpProfileId = "j3")

        val err = runCatching {
            SshRouteResolver.validateAllRoutes(listOf(j0, j1, j2, j3, target))
        }.exceptionOrNull() as? SshRouteException
        assertEquals("jump_chain_too_long", err?.error?.code)
    }

    @Test
    fun `dependentProfiles and profilesUsingKey return sorted dependent profile ids`() {
        val j1 = profile("j1", keyId = "key-1")
        val app1 = profile("app-1", jumpProfileId = "j1", keyId = "key-1")
        val app2 = profile("app-2", jumpProfileId = "j1", keyId = "key-2")
        val all = listOf(app2, j1, app1)

        assertEquals(listOf("app-1", "app-2"), SshRouteResolver.dependentProfiles(all, "j1"))
        assertEquals(listOf("app-1", "j1"), SshRouteResolver.profilesUsingKey(all, "key-1"))
    }

    @Test
    fun `SshTrustPolicy invalidates trust when host port or jumpProfileId changes`() {
        val fp = "ab".repeat(32)
        val trusted = profile(
            id = "app",
            host = "10.0.1.8",
            port = 22,
            jumpProfileId = "bastion-1",
            acceptedHostKeyFingerprint = fp,
            acceptedHostKeyHost = "10.0.1.8",
            acceptedHostKeyPort = 22,
            acceptedHostKeyViaJump = "bastion-1",
        )

        assertTrue(SshTrustPolicy.evaluate(trusted, fp) is SshHostTrust.Trusted)
        assertTrue(SshTrustPolicy.evaluate(trusted.copy(host = "10.0.1.9"), fp) is SshHostTrust.Untrusted)
        assertTrue(SshTrustPolicy.evaluate(trusted.copy(port = 2222), fp) is SshHostTrust.Untrusted)
        assertTrue(SshTrustPolicy.evaluate(trusted.copy(jumpProfileId = "bastion-2"), fp) is SshHostTrust.Untrusted)
        assertTrue(SshTrustPolicy.evaluate(trusted.copy(jumpProfileId = null), fp) is SshHostTrust.Untrusted)
    }

    @Test
    fun `SshTrustPolicy normalizeFingerprint accepts 64-hex and SHA256 base64`() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, SshTrustPolicy.normalizeFingerprint(hex))
        val sha256B64 = SshTrustPolicy.displayFingerprint(hex)
        assertTrue(sha256B64.startsWith("SHA256:"))
        assertEquals(hex, SshTrustPolicy.normalizeFingerprint(sha256B64))
    }

    @Test
    fun `routeDigest changes when any hop endpoint or credentialRevision changes`() {
        val j1 = profile("j1", host = "1.2.3.4", credentialRevision = "rev1")
        val target = profile("target", host = "10.0.0.2", jumpProfileId = "j1", credentialRevision = "rev2")

        val d1 = SshRouteResolver.routeDigest(listOf(j1, target))
        val d2 = SshRouteResolver.routeDigest(listOf(j1.copy(host = "1.2.3.5"), target))
        val d3 = SshRouteResolver.routeDigest(listOf(j1.copy(credentialRevision = "rev9"), target))
        assertNotEquals(d1, d2)
        assertNotEquals(d1, d3)
    }

    private fun profile(
        id: String,
        host: String = "example.com",
        port: Int = 22,
        jumpProfileId: String? = null,
        keyId: String? = null,
        acceptedHostKeyFingerprint: String? = null,
        acceptedHostKeyHost: String? = null,
        acceptedHostKeyPort: Int? = null,
        acceptedHostKeyViaJump: String? = null,
        credentialRevision: String = "rev-1",
    ) = SshProfile(
        id = id,
        name = id,
        host = host,
        port = port,
        username = "ubuntu",
        authMethod = SshAuthMethod.PRIVATE_KEY,
        keyId = keyId,
        jumpProfileId = jumpProfileId,
        acceptedHostKeyFingerprint = acceptedHostKeyFingerprint,
        acceptedHostKeyHost = acceptedHostKeyHost,
        acceptedHostKeyPort = acceptedHostKeyPort,
        acceptedHostKeyViaJump = acceptedHostKeyViaJump,
        credentialRevision = credentialRevision,
        createdAtMs = 1L,
        updatedAtMs = 1L,
    )
}
