package app.amber.feature.novel.runtime

/**
 * Frozen Prompt catalog versions for V1 interop with iOS [NovelPromptCatalog].
 *
 * These strings are part of the durable generation/injection receipt contract.
 * Do not rename without a deliberate cross-platform product decision.
 */
object NovelPromptVersions {
    /** v3: characters is an array of {title, content} (one person per entry). */
    const val QUICK_START = "novel.quick-start.v3"
    const val DISCUSSION = "novel.discussion.v1"
    const val PROSE_CONTINUATION = "novel.prose-continuation.v1"
    const val PROSE_WHOLE_CHAPTER = "novel.prose-whole-chapter.v1"
    const val STATE_DELTA = "novel.state-delta.v1"
    const val MANUAL_SYNC = "novel.manual-sync.v2"
    const val WHOLE_CHAPTER_POLISH = "novel.whole-chapter-polish.v2"
    const val WHOLE_CHAPTER_REGENERATION = "novel.whole-chapter-regeneration.v1"
    const val POLISH_DRIFT = "novel.polish-drift.v1"
    const val CONTINUITY_AUDIT = "novel.continuity-audit.v1"
    const val DISCUSSION_ARCHIVE = "novel.discussion-archive.v1"

    val all: List<String> = listOf(
        QUICK_START,
        DISCUSSION,
        PROSE_CONTINUATION,
        PROSE_WHOLE_CHAPTER,
        STATE_DELTA,
        MANUAL_SYNC,
        WHOLE_CHAPTER_POLISH,
        WHOLE_CHAPTER_REGENERATION,
        POLISH_DRIFT,
        CONTINUITY_AUDIT,
        DISCUSSION_ARCHIVE,
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
