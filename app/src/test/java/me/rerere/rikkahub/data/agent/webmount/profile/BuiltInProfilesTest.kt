package me.rerere.rikkahub.data.agent.webmount.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2 M2.1 review W-4 — boot-time validation for built-in profiles.
 *
 * Walks every `*.json` under `app/src/main/assets/webmount/profiles/`,
 * parses + validates with the production [profileJson] + [SiteProfile.validate].
 * A typo in any shipping profile fails this test, blocking the commit.
 *
 * Also asserts:
 *  - The on-disk count matches the documented "12 profiles ship" claim.
 *  - Every profile id is unique.
 *  - Every profile's [SiteProfile.id] matches its filename stem (so the
 *    host-shim loader at [HostShimRegistry.shimSource] can find the shim
 *    if/when one is added).
 */
class BuiltInProfilesTest {

    @Test
    fun everyBuiltInProfileParsesAndValidates() {
        val dir = locateProfilesDir()
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()
        assertTrue(
            "no built-in profiles found in $dir — the lint is a no-op",
            files.isNotEmpty()
        )
        val ids = mutableSetOf<String>()
        files.forEach { file ->
            val raw = file.readText()
            val parsed = runCatching {
                profileJson.decodeFromString(SiteProfile.serializer(), raw)
            }.getOrElse {
                throw AssertionError("built-in profile ${file.name} failed to parse: ${it.message}", it)
            }
            runCatching { parsed.validate() }.getOrElse {
                throw AssertionError("built-in profile ${file.name} failed validation: ${it.message}", it)
            }
            assertEquals(
                "profile id must match filename stem (HostShimRegistry contract): ${file.name}",
                file.nameWithoutExtension,
                parsed.id,
            )
            assertTrue(
                "duplicate built-in profile id '${parsed.id}' (defined twice)",
                ids.add(parsed.id),
            )
        }
        // Phase 2 M2.1 ships built-in profiles. Bumping this number requires
        // adding the file + updating the doc/commit message.
        assertEquals(
            "expected 14 built-in profiles to ship (update test if intentional)",
            14,
            files.size,
        )
    }

    @Test
    fun everyBuiltInPermissionParses() {
        val dir = locateProfilesDir()
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }.orEmpty()
        files.forEach { file ->
            val parsed = profileJson.decodeFromString(SiteProfile.serializer(), file.readText())
            parsed.permissions.forEach { wire ->
                val perm = runCatching { ProfilePermission.parse(wire, parsed.id) }.getOrElse {
                    throw AssertionError(
                        "built-in profile ${file.name} has unparseable permission '$wire': ${it.message}",
                        it,
                    )
                }
                assertNotNull(perm)
            }
        }
    }

    private fun locateProfilesDir(): File {
        val candidates = listOf(
            File("src/main/assets/webmount/profiles"),
            File("app/src/main/assets/webmount/profiles"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate built-in profiles dir from: " + candidates.joinToString { it.absolutePath })
    }
}
