package me.rerere.rikkahub

/**
 * Compile-time feature flags for performance-layer optimizations that
 * still need on-device verification.
 *
 * **All flags default to false** so the legacy code path is preserved
 * for every user. Enable a flag by flipping the constant to `true` and
 * rebuilding, then exercise the new path on a real device. If a
 * regression surfaces, flip back OR `git revert <commit>` the specific
 * commit listed in the flag's docstring.
 *
 * Why compile-time `object` instead of DataStore preferences:
 *  - Zero runtime DI / Koin wiring
 *  - Zero UI surface (these are dev / QA flags, not user-facing)
 *  - The `if (FLAG)` branches are dead-code-eliminated by R8 when flag
 *    is false — so the alternate-path code has zero impact on the
 *    user-facing APK size + runtime perf when disabled.
 *
 * To enable a flag for personal-use testing: edit this file and
 * rebuild. To enable in a remote-config rollout: replace the
 * `const val` with a runtime read from FirebaseRemoteConfig.
 */
object PerfFlags {

    /**
     * T2 — Use ChatPageSplit (region-based sub-Composables) instead of the
     * monolithic ChatPage. New path collects state per-region so a
     * streaming-state emission only recomposes the streaming-indicator
     * subtree, not the whole chat tree.
     *
     * Revert if enabled and broken: `git revert <commit-T2-split>`.
     */
    const val USE_SPLIT_CHATPAGE_COMPOSABLES = false

    /**
     * T3 — Use the Rust PackedAstReader as the primary markdown AST
     * consumer. Default JVM (JetBrains ASTNode) path preserved.
     *
     * Revert if enabled and broken: `git revert <commit-T3-rust-renderer>`.
     */
    const val USE_RUST_MARKDOWN_RENDERER = false

    /**
     * T4 — Use DeepReadScreenSplit (region-based sub-Composables) instead
     * of the monolithic DeepReadScreen. Default monolith path preserved.
     */
    const val USE_SPLIT_DEEPREAD_SCREEN = false

    /**
     * T4 — Use MarkdownSplit (smaller per-node renderers) instead of the
     * monolithic Markdown renderer. Independent of [USE_RUST_MARKDOWN_RENDERER].
     */
    const val USE_SPLIT_MARKDOWN = false

    /**
     * T4 — Use GenerativeWidgetCardSplit (per-widget-type sub-Composables)
     * instead of the monolithic GenerativeWidgetCard.
     */
    const val USE_SPLIT_GENERATIVE_WIDGET_CARD = false
}
