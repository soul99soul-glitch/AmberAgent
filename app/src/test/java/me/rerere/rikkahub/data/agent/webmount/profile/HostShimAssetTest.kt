package me.rerere.rikkahub.data.agent.webmount.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2 M2.2 — Host shim asset audit.
 *
 * Verifies the bundled JS shims under `assets/webmount/shims/`:
 *  - Filename stems match a built-in profile id.
 *  - The matching profile actually declares a `scripts.*` entry that
 *    will need the shim (otherwise the shim is dead code).
 *  - JS source isn't empty and contains the function it's supposed to
 *    define (basic guard against accidental file truncation).
 */
class HostShimAssetTest {

    @Test
    fun everyShimMapsToAProfileThatNeedsIt() {
        val shimsDir = locateShimsDir()
        val profilesDir = File(shimsDir.parentFile, "profiles")
        check(profilesDir.isDirectory) { "profiles dir missing next to shims dir at $profilesDir" }
        val profileIds = profilesDir.listFiles { _, name -> name.endsWith(".json") }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()

        val shimFiles = shimsDir.listFiles { _, name -> name.endsWith(".js") }.orEmpty()
        shimFiles.forEach { shim ->
            val stem = shim.nameWithoutExtension
            assertTrue(
                "shim '${shim.name}' has no matching profile JSON (orphan)",
                stem in profileIds,
            )
            val profileFile = File(profilesDir, "$stem.json")
            val raw = profileFile.readText()
            val profile = profileJson.decodeFromString(SiteProfile.serializer(), raw)
            assertTrue(
                "shim '${shim.name}' exists but profile '${profile.id}' has no `scripts.*` entry that needs it",
                profile.scripts.isNotEmpty(),
            )
            val source = shim.readText()
            assertTrue(
                "shim '${shim.name}' is empty",
                source.isNotBlank()
            )
            // The shim must define at least one of the call_page_fn target names.
            val expectedFns = profile.scripts.values
                .map { it.callPageFn.removePrefix("window.") }
                .toSet()
            assertTrue(
                "shim '${shim.name}' doesn't appear to define any of the expected " +
                    "call_page_fn targets: $expectedFns",
                expectedFns.any { source.contains("window.$it") || source.contains("window['$it']") },
            )
        }
    }

    @Test
    fun bilibiliShimIsPresentAndExposesAmberBiliWbi() {
        val shim = File(locateShimsDir(), "bilibili.js")
        assertTrue("bilibili.js shim must exist", shim.exists() && shim.isFile)
        val source = shim.readText()
        assertTrue("must define window.__amberBiliWbi", source.contains("window.__amberBiliWbi"))
        assertTrue(
            "must include MD5 implementation (SubtleCrypto lacks MD5)",
            source.contains("md5") || source.contains("MD5")
        )
        // Quick sanity on the mixin table — must be present + 64 entries.
        val mixinRegex = Regex("var MIXIN = \\[([^]]+)]")
        val match = mixinRegex.find(source)
        assertTrue("MIXIN table not found", match != null)
        val entryCount = match!!.groupValues[1].split(',').count { it.trim().isNotEmpty() }
        assertEquals("MIXIN should have 64 entries", 64, entryCount)
    }

    private fun locateShimsDir(): File {
        val candidates = listOf(
            File("src/main/assets/webmount/shims"),
            File("app/src/main/assets/webmount/shims"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate shims dir from: " + candidates.joinToString { it.absolutePath })
    }
}
