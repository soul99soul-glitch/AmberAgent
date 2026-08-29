package app.amber.core.ai.generative

import android.content.Context
import app.amber.agent.R

/**
 * User-visible copy emitted by the generative-widget parser and preview renderer.
 *
 * Widget protocol fields and model-provided values are deliberately not part of
 * this bundle. Production callers resolve it once from their app-localized
 * context; [english] is only the small non-Android fallback for parser tests.
 */
class GenerativeWidgetCopy private constructor(
    val incompleteFullHtmlMessage: String,
    val generating: String,
    val chartLabel: String,
    val presentationLabel: String,
    val fullHtmlDeckTitle: String,
    val fullHtmlDeckBadge: String,
    val fullHtmlDeckMeta: String,
    val defaultSeriesName: String,
    val shareLabel: String,
    val miniAppGenerating: String,
    val miniAppJsonTitle: String,
    val repairingPresentation: String,
    val switchingToVisibleOutput: String,
    private val actionPromptFormatter: (String?, String) -> String,
    private val actionPromptWithoutTitleFormatter: (String) -> String,
    private val slidesCountFormatter: (Int) -> String,
    private val slidesGeneratedFormatter: (String) -> String,
    private val rendererGeneratingFormatter: (String) -> String,
    private val slideNumberFormatter: (Int) -> String,
    private val moreSlidesFormatter: (Int) -> String,
    private val slideBrowseHintFormatter: (Int) -> String,
    private val noSpecFormatter: (String) -> String,
    private val renderingFailedFormatter: (String) -> String,
    private val invalidSpecShapeFormatter: (String) -> String,
    private val noRenderableDataFormatter: (String) -> String,
) {
    fun slidesCount(count: Int): String = slidesCountFormatter(count)

    fun slidesGenerated(countLabel: String): String = slidesGeneratedFormatter(countLabel)

    fun rendererGenerating(label: String): String = rendererGeneratingFormatter(label)

    fun slideNumber(number: Int): String = slideNumberFormatter(number)

    fun moreSlides(count: Int): String = moreSlidesFormatter(count)

    fun slideBrowseHint(count: Int): String = slideBrowseHintFormatter(count)

    fun actionPrompt(widgetTitle: String?, instruction: String): String =
        if (widgetTitle.isNullOrBlank()) {
            actionPromptWithoutTitleFormatter(instruction)
        } else {
            actionPromptFormatter(widgetTitle, instruction)
        }

    /** The renderer name is model/protocol data and is intentionally retained. */
    fun noSpec(renderer: String): String = noSpecFormatter(renderer)

    /** The renderer name is model/protocol data and is intentionally retained. */
    fun renderingFailed(renderer: String): String = renderingFailedFormatter(renderer)

    /** The renderer name is model/protocol data and is intentionally retained. */
    fun invalidSpecShape(renderer: String): String = invalidSpecShapeFormatter(renderer)

    /** The renderer name is model/protocol data and is intentionally retained. */
    fun noRenderableData(renderer: String): String = noRenderableDataFormatter(renderer)

    companion object {
        /** Non-production fallback for parser/renderer callers without Android resources. */
        val DEFAULT: GenerativeWidgetCopy = english()

        fun english(): GenerativeWidgetCopy = GenerativeWidgetCopy(
            incompleteFullHtmlMessage = "The visual preview was incomplete, so unfinished HTML content was hidden. Please regenerate or shorten the content.",
            generating = "Generating",
            chartLabel = "chart",
            presentationLabel = "presentation",
            fullHtmlDeckTitle = "Full HTML Deck",
            fullHtmlDeckBadge = "FULL HTML DECK",
            fullHtmlDeckMeta = "Canvas · Motion · fullscreen deck",
            defaultSeriesName = "Value",
            shareLabel = "Share",
            miniAppGenerating = "Generating mini app",
            miniAppJsonTitle = "MiniApp JSON",
            repairingPresentation = "Repairing presentation card...",
            switchingToVisibleOutput = "Switching to visible output mode to generate a visualization...",
            actionPromptFormatter = { title, instruction ->
                "Based on the previous generative UI \u201c$title\u201d, continue processing: $instruction"
            },
            actionPromptWithoutTitleFormatter = { instruction ->
                "Based on the previous generative UI, continue processing: $instruction"
            },
            slidesCountFormatter = { count ->
                if (count >= 24) "24+ pages" else "$count pages"
            },
            slidesGeneratedFormatter = { countLabel -> "Generating slides: $countLabel" },
            rendererGeneratingFormatter = { label -> "Generating $label…" },
            slideNumberFormatter = { number -> "Slide $number" },
            moreSlidesFormatter = { count -> "$count more pages…" },
            slideBrowseHintFormatter = { count -> "$count slides · Click to browse" },
            noSpecFormatter = { renderer -> "$renderer: no spec" },
            renderingFailedFormatter = { renderer -> "$renderer: rendering failed" },
            invalidSpecShapeFormatter = { renderer -> "$renderer: invalid spec shape" },
            noRenderableDataFormatter = { renderer -> "$renderer: no renderable data" },
        )

        /** Resolves all fixed copy through the app's configured Android resources. */
        fun from(context: Context): GenerativeWidgetCopy = GenerativeWidgetCopy(
            incompleteFullHtmlMessage = context.getString(
                R.string.generative_widget_core_incomplete_full_html_message,
            ),
            generating = context.getString(R.string.generative_widget_core_generating),
            chartLabel = context.getString(R.string.generative_widget_core_chart_label),
            presentationLabel = context.getString(R.string.generative_widget_core_presentation_label),
            fullHtmlDeckTitle = context.getString(R.string.generative_widget_core_full_html_deck_title),
            fullHtmlDeckBadge = context.getString(R.string.generative_widget_core_full_html_deck_badge),
            fullHtmlDeckMeta = context.getString(R.string.generative_widget_core_full_html_deck_meta),
            defaultSeriesName = context.getString(R.string.generative_widget_core_default_series_name),
            shareLabel = context.getString(R.string.generative_widget_core_share_label),
            miniAppGenerating = context.getString(R.string.generative_widget_core_mini_app_generating),
            miniAppJsonTitle = context.getString(R.string.generative_widget_core_mini_app_json_title),
            repairingPresentation = context.getString(R.string.generative_widget_core_repairing_presentation),
            switchingToVisibleOutput = context.getString(R.string.generative_widget_core_switching_to_visible_output),
            actionPromptFormatter = { title, instruction ->
                context.getString(R.string.generative_widget_core_action_prompt, title, instruction)
            },
            actionPromptWithoutTitleFormatter = { instruction ->
                context.getString(
                    R.string.generative_widget_core_action_prompt_without_title,
                    instruction,
                )
            },
            slidesCountFormatter = { count ->
                context.getString(
                    R.string.generative_widget_core_slides_count,
                    if (count >= 24) "24+" else count.toString(),
                )
            },
            slidesGeneratedFormatter = { countLabel ->
                context.getString(R.string.generative_widget_core_slides_generated, countLabel)
            },
            rendererGeneratingFormatter = { label ->
                context.getString(R.string.generative_widget_core_renderer_generating, label)
            },
            slideNumberFormatter = { number ->
                context.getString(R.string.generative_widget_core_slide_number, number)
            },
            moreSlidesFormatter = { count ->
                context.getString(R.string.generative_widget_core_more_slides, count)
            },
            slideBrowseHintFormatter = { count ->
                context.getString(R.string.generative_widget_core_slide_browse_hint, count)
            },
            noSpecFormatter = { renderer ->
                context.getString(R.string.generative_widget_core_no_spec, renderer)
            },
            renderingFailedFormatter = { renderer ->
                context.getString(R.string.generative_widget_core_rendering_failed, renderer)
            },
            invalidSpecShapeFormatter = { renderer ->
                context.getString(R.string.generative_widget_core_invalid_spec_shape, renderer)
            },
            noRenderableDataFormatter = { renderer ->
                context.getString(R.string.generative_widget_core_no_renderable_data, renderer)
            },
        )
    }
}
