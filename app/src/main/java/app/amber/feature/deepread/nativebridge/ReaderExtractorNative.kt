package app.amber.feature.deepread.nativebridge

internal object ReaderExtractorNative {
    init {
        try {
            System.loadLibrary("reader_extractor")
        } catch (_: UnsatisfiedLinkError) {
            // Native library not available — callers fall back to Jsoup
        }
    }

    @JvmStatic
    external fun extract(html: String, baseUrl: String): ExtractedArticle?
}

data class ExtractedArticle(
    @JvmField val title: String,
    @JvmField val contentHtml: String,
    @JvmField val contentText: String,
    @JvmField val sectionCount: Int,
)
