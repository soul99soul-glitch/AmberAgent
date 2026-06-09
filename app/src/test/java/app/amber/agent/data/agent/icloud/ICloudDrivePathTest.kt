package app.amber.feature.icloud

import org.junit.Assert.assertEquals
import org.junit.Test

class ICloudDrivePathTest {
    @Test
    fun normalizesVaultRelativePaths() {
        val resolved = ICloudDrivePath.resolve("Obsidian/", "./Notes/Daily.md")

        assertEquals("Obsidian", resolved.vaultPath)
        assertEquals("Notes/Daily.md", resolved.relativePath)
        assertEquals("Obsidian/Notes/Daily.md", resolved.iCloudPath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsParentTraversal() {
        ICloudDrivePath.resolve("Obsidian", "../Secrets.md")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAbsolutePaths() {
        ICloudDrivePath.resolve("Obsidian", "/Documents/Secrets.md")
    }
}
