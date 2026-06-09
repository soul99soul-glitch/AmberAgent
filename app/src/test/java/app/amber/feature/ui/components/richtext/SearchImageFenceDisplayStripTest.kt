package app.amber.feature.ui.components.richtext

import org.junit.Test
import kotlin.test.assertEquals

class SearchImageFenceDisplayStripTest {
    @Test
    fun stripsOnlySearchImageFences() {
        val content = """
            Intro

            ```search-images
            https://img.example/one.jpg
            https://img.example/two.jpg|caption
            ```

            ```kotlin
            val text = "search-images"
            ```

            Done
        """.trimIndent()

        val stripped = stripSearchImageFencesForDisplay(content)

        assertEquals(
            """
            Intro

            ```kotlin
            val text = "search-images"
            ```

            Done
            """.trimIndent(),
            stripped,
        )
    }

    @Test
    fun ignoresBareSearchImagesText() {
        val content = "This mentions search-images but is not a fence."

        assertEquals(content, stripSearchImageFencesForDisplay(content))
    }
}
