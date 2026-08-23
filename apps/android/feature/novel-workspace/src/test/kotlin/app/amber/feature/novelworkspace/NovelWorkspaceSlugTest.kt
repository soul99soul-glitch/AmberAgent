package app.amber.feature.novelworkspace

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelWorkspaceSlugTest {

    @Test
    fun `non-ascii titles keep their characters`() {
        assertEquals("山呼", NovelWorkspaceSlug.slug("山呼"))
        assertEquals("入汴", NovelWorkspaceSlug.slug(" 入汴 "))
    }

    @Test
    fun `ascii titles lowercase`() {
        assertEquals("chapter-one", NovelWorkspaceSlug.slug("Chapter One"))
    }

    @Test
    fun `forbidden characters collapse to single dashes`() {
        assertEquals("a-b-c", NovelWorkspaceSlug.slug("a/b\\c"))
        assertEquals("a-b", NovelWorkspaceSlug.slug("a:?%*|\"<> b"))
        assertEquals("line-1-line-2", NovelWorkspaceSlug.slug("line 1\nline 2"))
    }

    @Test
    fun `leading and trailing dashes are trimmed`() {
        assertEquals("山呼", NovelWorkspaceSlug.slug("--山呼--"))
    }

    @Test
    fun `empty input yields empty slug`() {
        assertEquals("", NovelWorkspaceSlug.slug(""))
        assertEquals("", NovelWorkspaceSlug.slug("///"))
    }

    @Test
    fun `reserved path deduplicates with numeric suffixes`() {
        val used = mutableSetOf<String>()
        assertEquals("山呼", NovelWorkspaceSlug.reservedPath("山呼", used, "fallback"))
        assertEquals("山呼-2", NovelWorkspaceSlug.reservedPath("山呼", used, "fallback"))
        assertEquals("山呼-3", NovelWorkspaceSlug.reservedPath("山呼", used, "fallback"))
    }

    @Test
    fun `reserved path keeps directory prefix while deduplicating leaves`() {
        val used = mutableSetOf<String>()
        assertEquals("setting/world/a", NovelWorkspaceSlug.reservedPath("setting/world/a", used, "x"))
        assertEquals("setting/world/a-2", NovelWorkspaceSlug.reservedPath("setting/world/a", used, "x"))
    }

    @Test
    fun `empty slug falls back to id prefix then untitled`() {
        val used = mutableSetOf<String>()
        assertEquals(
            "3F2A0C1A",
            NovelWorkspaceSlug.reservedPath("", used, "3F2A0C1A-9B4E-4E4E-9E9E-000000000000"),
        )
        assertEquals("untitled", NovelWorkspaceSlug.reservedPath("", used, ""))
    }
}
