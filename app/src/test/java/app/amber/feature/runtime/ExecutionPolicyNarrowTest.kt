package app.amber.feature.runtime

import app.amber.feature.tools.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 6: [ExecutionPolicy.narrow] pins the child-run intersection semantics —
 * each dimension intersects and null is the universe (absorbing, never
 * narrowing), so a child can never widen the parent's sandbox and a permissive
 * parent passes the child through untouched.
 */
class ExecutionPolicyNarrowTest {

    @Test
    fun `path roots keep only child roots contained under a parent root`() {
        val parent = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes", "/tmp/sandbox"))
        val child = ExecutionPolicy(
            allowedPathRoots = listOf("/workspace/notes/drafts", "/tmp/sandbox", "/workspace/data"),
        )
        assertEquals(
            listOf("/workspace/notes/drafts", "/tmp/sandbox"),
            parent.narrow(child).allowedPathRoots,
        )
    }

    @Test
    fun `path roots do not match by string prefix alone`() {
        val parent = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes"))
        val child = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notesevil"))
        assertEquals(listOf<String>(), parent.narrow(child).allowedPathRoots)
    }

    @Test
    fun `a dot-dot child root cannot widen the parent sandbox`() {
        // P1-6 regression: literal-prefix comparison once admitted this child
        // root even though its canonical form (/data/data/x) escapes the
        // parent sandbox entirely.
        val parent = ExecutionPolicy(allowedPathRoots = listOf("/sdcard/Amber/sandbox"))
        val child = ExecutionPolicy(
            allowedPathRoots = listOf("/sdcard/Amber/sandbox/../../../data/data/x"),
        )
        // An emptied intersection must stay an empty deny-all set — never
        // collapse back to null (the universe).
        assertEquals(
            "empty intersection must stay an empty set, never null",
            listOf<String>(),
            parent.narrow(child).allowedPathRoots,
        )
    }

    @Test
    fun `an uncanonicalizable child root is dropped while sibling roots survive`() {
        val parent = ExecutionPolicy(allowedPathRoots = listOf("/workspace"))
        // WorkspacePaths rejects `..` outright, so "/workspace/../data" cannot
        // be canonicalized and must not survive narrowing verbatim.
        val child = ExecutionPolicy(
            allowedPathRoots = listOf("/workspace/../data", "/workspace/notes"),
        )
        assertEquals(listOf("/workspace/notes"), parent.narrow(child).allowedPathRoots)
    }

    @Test
    fun `path roots compare canonically so cosmetic differences still intersect`() {
        // The trailing slash once broke the literal prefix match; canonical
        // comparison admits the child and keeps it verbatim.
        val parent = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes/"))
        val child = ExecutionPolicy(allowedPathRoots = listOf("/workspace/notes/drafts"))
        assertEquals(listOf("/workspace/notes/drafts"), parent.narrow(child).allowedPathRoots)
    }

    @Test
    fun `domains keep only child domains equal to or a subdomain of a parent domain`() {
        val parent = ExecutionPolicy(allowedDomains = listOf("example.com"))
        val child = ExecutionPolicy(allowedDomains = listOf("api.example.com", "example.com", "evil-example.com"))
        assertEquals(
            listOf("api.example.com", "example.com"),
            parent.narrow(child).allowedDomains,
        )
    }

    @Test
    fun `shell survives only when both sides allow it`() {
        val parent = ExecutionPolicy(allowShell = false)
        val child = ExecutionPolicy(allowShell = true)
        assertEquals(false, parent.narrow(child).allowShell)
        assertEquals(false, child.narrow(parent).allowShell)
        assertEquals(true, ExecutionPolicy.permissive().narrow(ExecutionPolicy.permissive()).allowShell)
    }

    @Test
    fun `capabilities intersect and null is the universe`() {
        val parent = ExecutionPolicy(allowedSystemCapabilities = setOf(Capability.FILESYSTEM_READ, Capability.NETWORK_CONNECT))
        val child = ExecutionPolicy(allowedSystemCapabilities = setOf(Capability.FILESYSTEM_READ, Capability.FILESYSTEM_WRITE))
        assertEquals(
            setOf(Capability.FILESYSTEM_READ),
            parent.narrow(child).allowedSystemCapabilities,
        )
        // Parent null = universe: the child's set passes through verbatim.
        assertEquals(
            setOf(Capability.FILESYSTEM_READ, Capability.FILESYSTEM_WRITE),
            ExecutionPolicy.permissive().narrow(child).allowedSystemCapabilities,
        )
        // Child null = universe: the parent's set passes through verbatim.
        assertEquals(
            setOf(Capability.FILESYSTEM_READ, Capability.NETWORK_CONNECT),
            parent.narrow(ExecutionPolicy.permissive()).allowedSystemCapabilities,
        )
    }

    @Test
    fun `null path and domain dimensions absorb the child verbatim`() {
        val child = ExecutionPolicy(
            allowedPathRoots = listOf("/workspace"),
            allowedDomains = listOf("example.com"),
        )
        val narrowed = ExecutionPolicy.permissive().narrow(child)
        assertEquals(listOf("/workspace"), narrowed.allowedPathRoots)
        assertEquals(listOf("example.com"), narrowed.allowedDomains)
    }

    @Test
    fun `a fully narrowed child intersected with an equally narrow parent keeps the policy`() {
        val policy = ExecutionPolicy(
            allowedPathRoots = listOf("/workspace/notes"),
            allowedDomains = listOf("example.com"),
            allowShell = false,
            allowedSystemCapabilities = setOf(Capability.FILESYSTEM_READ),
        )
        assertEquals(policy, policy.narrow(policy))
    }

    @Test
    fun `narrowing never widens an empty intersection into permission`() {
        val parent = ExecutionPolicy(allowedDomains = listOf("a.example"))
        val child = ExecutionPolicy(allowedDomains = listOf("b.example"))
        val narrowed = parent.narrow(child)
        assertTrue(narrowed.allowedDomains!!.isEmpty())
        // An empty allowlist denies everything in the dimension — the opposite
        // of null (universe). The gate must treat them differently.
        assertTrue(ExecutionPolicy(allowedDomains = null).allowedDomains != narrowed.allowedDomains)
    }
}
