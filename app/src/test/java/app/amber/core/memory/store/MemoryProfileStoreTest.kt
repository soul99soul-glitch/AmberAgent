package app.amber.core.memory.store

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProfileStoreTest {

    @Test
    fun `profile round-trips through the json file`() = runBlocking {
        val file = File.createTempFile("memory-profile", ".json")
        val profile = MemoryProfile(
            content = "偏好中文简洁回复的长期用户画像。",
            generatedAt = 1_700_000_000_000L,
            sourceMemoryIds = listOf(1, 2, 3),
        )

        MemoryProfileStore(file).put(profile)
        // A second store instance reads what the first wrote.
        val decoded = MemoryProfileStore(file).get()
        file.delete()
        assertEquals(profile, decoded)
    }

    @Test
    fun `missing or corrupt file reads as null`() = runBlocking {
        val file = File.createTempFile("memory-profile", ".json")
        file.delete()
        assertNull(MemoryProfileStore(file).get())

        file.writeText("{ not json")
        val decoded = MemoryProfileStore(file).get()
        file.delete()
        assertNull(decoded)
    }

    @Test
    fun `freshness requires recent enough and half the sources`() {
        val now = 1_700_000_000_000L
        val profile = MemoryProfile(
            content = "偏好中文简洁回复的长期用户画像。",
            generatedAt = now - 1_000L,
            sourceMemoryIds = listOf(1, 2, 3, 4),
        )

        // Half or more sources still active -> fresh.
        assertTrue(profile.isFresh(now, setOf(1, 2)))
        // Fewer than half -> the library drifted too far.
        assertFalse(profile.isFresh(now, setOf(1)))
        // Past the TTL -> stale regardless of sources.
        assertFalse(
            profile.copy(generatedAt = now - MemoryProfile.PROFILE_TTL_MS - 1)
                .isFresh(now, setOf(1, 2, 3, 4))
        )
        // Blank content is never injectable.
        assertFalse(profile.copy(content = "  ").isFresh(now, setOf(1, 2, 3, 4)))
        // No recorded sources means nothing to drift-check — recent + non-blank is enough.
        assertTrue(profile.copy(sourceMemoryIds = emptyList()).isFresh(now, emptySet()))
    }
}
