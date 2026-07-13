package app.amber.feature.novel.runtime

/**
 * Frozen Prompt catalog versions for V1 interop with iOS [NovelPromptCatalog].
 *
 * These strings are part of the durable generation/injection receipt contract.
 * Do not rename without a deliberate cross-platform product decision.
 */
object NovelPromptVersions {
    const val QUICK_START = "novel.quick-start.v2"
    const val DISCUSSION = "novel.discussion.v1"
    const val PROSE_CONTINUATION = "novel.prose-continuation.v1"
    const val PROSE_WHOLE_CHAPTER = "novel.prose-whole-chapter.v1"
    const val STATE_DELTA = "novel.state-delta.v1"
    const val MANUAL_SYNC = "novel.manual-sync.v2"
    const val WHOLE_CHAPTER_POLISH = "novel.whole-chapter-polish.v2"
    const val POLISH_DRIFT = "novel.polish-drift.v1"

    val all: List<String> = listOf(
        QUICK_START,
        DISCUSSION,
        PROSE_CONTINUATION,
        PROSE_WHOLE_CHAPTER,
        STATE_DELTA,
        MANUAL_SYNC,
        WHOLE_CHAPTER_POLISH,
        POLISH_DRIFT,
    )
}

/**
 * Default injection budget knobs frozen for V1 (no embedding).
 */
object NovelInjectionDefaults {
    const val ESTIMATED_INPUT_TOKENS = 16_000
    const val CHAPTER_TAIL_CHARS = 6_000
    const val RECENT_SESSION_MESSAGES = 12
}
