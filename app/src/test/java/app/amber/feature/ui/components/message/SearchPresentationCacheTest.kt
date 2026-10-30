package app.amber.feature.ui.components.message

import app.amber.ai.ui.UIMessagePart
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPresentationCacheTest {
    @Test
    fun samePartsIdentityIsDerivedOnceForEverySiblingSlice() {
        val parts = listOf(UIMessagePart.Text("hello"))
        val first = searchPresentationFor(parts)
        val second = searchPresentationFor(parts)

        assertSame(first, second)
        assertTrue(first.images.isEmpty())
    }
}
