package app.amber.agent

/**
 * Product surface for the standalone app split (mirrors the iOS
 * AmberNovel / AmberDeepRead targets): one codebase, three products.
 *
 * - `full` — the regular Amber app (applicationId `app.amber.agent`)
 * - `novel` — standalone 小说创作 (applicationId `app.amber.novel`)
 * - `deepread` — standalone 深度阅读 (applicationId `app.amber.deepread`)
 *
 * The value comes from the `STANDALONE_SURFACE` BuildConfig field declared
 * per product flavor in `app/build.gradle.kts`. Standalone products keep the
 * whole codebase compiled in — like the iOS targets — and only differ in
 * entry surface and which background loops are started.
 */
object StandaloneSurfaces {
    const val FULL = "full"
    const val NOVEL = "novel"
    const val DEEP_READ = "deepread"

    val current: String get() = BuildConfig.STANDALONE_SURFACE

    val isStandalone: Boolean get() = current != FULL

    /** Today Board / hot list / deep-read discovery is reachable. */
    val allowsBoard: Boolean get() = current == FULL || current == DEEP_READ

    /** Novel workspace surface is reachable. */
    val allowsNovel: Boolean get() = current == FULL || current == NOVEL

    /** Agent-assistant loops that have no UI in standalone products
     * (cron, reminders, memory dream, skills install, task bubble). */
    val allowsAgentFeatures: Boolean get() = current == FULL
}
