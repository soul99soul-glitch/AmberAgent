package app.amber.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Canonical testTag IDs expected across the Personal Agent and legacy SessionHome journeys.
 *
 * Tags marked "existing in Compose" are already attached via `Modifier.testTag(...)` in
 * `app/src/main`, but require `Modifier.semantics { testTagsAsResourceId = true }` at the root
 * (tracked for W4) before UiAutomator `By.res(...)` can observe them directly.
 * Until then, every lookup prioritizes `By.res(tag)` and falls back to the currently wired
 * `contentDescription` / class / localized-string selector — and ALWAYS fails fast if nothing
 * matches.
 */
internal object ProfileTestTags {
    // Existing Compose testTags (pending root testTagsAsResourceId = true in W4):
    const val PERSONAL_AGENT_HOST_PILL = "personal-agent-host-pill"
    const val PERSONAL_AGENT_SESSIONS = "personal-agent-sessions"
    const val PERSONAL_AGENT_MORE = "personal-agent-more"
    const val HOME_HEADER_SETTINGS = "home-header-settings"

    // Missing Compose testTags requested for W4:
    const val CHAT_COMPOSER_INPUT = "chat-composer-input"
    const val CHAT_COMPOSER_SEND = "chat-composer-send"
    const val CHAT_TIMELINE_LIST = "chat-timeline-list"
    const val SESSION_HOME_SEARCH = "home-header-search"
    const val SESSION_HOME_CONVERSATIONS_HEADER = "home-conversations-header"
    const val SESSION_HOME_LIST = "home-session-list"
}

internal fun targetAppId(): String =
    InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId not passed as instrumentation runner arg")

/**
 * Optional runner arg (`-Pandroid.testInstrumentationRunnerArguments.launchStartMode=HOME`)
 * for devices pre-configured with `launch_start_mode = HOME` where cold start lands directly
 * on `Screen.SessionHome`.
 */
internal fun isExplicitHomeLaunchMode(): Boolean =
    InstrumentationRegistry.getArguments()
        .getString("launchStartMode")
        ?.equals("HOME", ignoreCase = true) == true

/**
 * Verifies that cold start landed on the default Personal Agent stage and reached the
 * first-input-ready state (top-bar Personal Agent chrome visible + composer input enabled +
 * send control composed). Fails fast if any required UI node is missing.
 */
internal fun MacrobenchmarkScope.waitForPersonalAgentReady(packageName: String): UiObject2 {
    val sessionsLabel = requireAppString(packageName, "personal_agent_sessions")
    val localLabel = requireAppString(packageName, "personal_agent_host_local")
    val cloudLabel = requireAppString(packageName, "personal_agent_host_cloud")
    val localHostA11y = requireAppString(packageName, "personal_agent_host_switch_a11y", localLabel)
    val cloudHostA11y = requireAppString(packageName, "personal_agent_host_switch_a11y", cloudLabel)

    waitForFirst(
        description = "Personal Agent host switcher pill (${ProfileTestTags.PERSONAL_AGENT_HOST_PILL})",
        timeoutMs = STAGE_READY_TIMEOUT_MS,
        selectors = tagSelectors(packageName, ProfileTestTags.PERSONAL_AGENT_HOST_PILL) + listOf(
            By.pkg(packageName).desc(localHostA11y),
            By.pkg(packageName).desc(cloudHostA11y),
        ),
    )

    waitForFirst(
        description = "Personal Agent sessions entry (${ProfileTestTags.PERSONAL_AGENT_SESSIONS})",
        timeoutMs = STAGE_READY_TIMEOUT_MS,
        selectors = tagSelectors(packageName, ProfileTestTags.PERSONAL_AGENT_SESSIONS) + listOf(
            By.pkg(packageName).desc(sessionsLabel),
        ),
    )

    val composerInput = waitForComposerInput(packageName)
    if (!composerInput.isEnabled) {
        throw AssertionError("Personal Agent composer input is present but disabled")
    }

    val sendLabel = requireAppString(packageName, "send")
    val stopLabel = requireAppString(packageName, "stop")
    waitForFirst(
        description = "Personal Agent send/stop control (${ProfileTestTags.CHAT_COMPOSER_SEND})",
        timeoutMs = ACTION_TIMEOUT_MS,
        selectors = tagSelectors(packageName, ProfileTestTags.CHAT_COMPOSER_SEND) + listOf(
            By.pkg(packageName).desc(sendLabel),
            By.pkg(packageName).desc(stopLabel),
        ),
    )

    return composerInput
}

/**
 * Default Personal Agent critical user journey:
 * 1. Assert Personal Agent stage is ready for first input.
 * 2. Focus the composer, enter deterministic probe text, and assert the send button activates.
 * 3. Tap Send and assert an explicit post-submit outcome:
 *    - Either submission was accepted (draft cleared / stop affordance appeared), or
 *    - In offline / unconfigured-key test environments, the local configuration gate
 *      (`ChatConfigurationHint`) is visibly displayed without requiring a paid remote model.
 * 4. Scroll the conversation timeline / stage viewport to warm up reply-reading & scroll paths.
 *
 * Every stage fails fast via [AssertionError]; nothing is silently skipped.
 */
internal fun MacrobenchmarkScope.exercisePersonalAgentInputAndTimeline(packageName: String) {
    val sendLabel = requireAppString(packageName, "send")
    val stopLabel = requireAppString(packageName, "stop")
    val configureLabel = requireAppString(packageName, "chat_setup_configure")
    val chooseModelLabel = requireAppString(packageName, "chat_setup_choose_model")

    val initialInput = waitForPersonalAgentReady(packageName)
    initialInput.click()
    device.waitForIdle()

    // Re-query after focus/IME transition so we never act on a stale UiObject2 instance.
    val focusedInput = waitForComposerInput(packageName)
    focusedInput.text = PERSONAL_AGENT_PROBE_MESSAGE
    device.waitForIdle()

    val enabledSendButton = waitForFirst(
        description = "enabled Personal Agent send button (${ProfileTestTags.CHAT_COMPOSER_SEND})",
        timeoutMs = ACTION_TIMEOUT_MS,
        selectors = enabledTagSelectors(packageName, ProfileTestTags.CHAT_COMPOSER_SEND) + listOf(
            By.pkg(packageName).desc(sendLabel).enabled(true),
        ),
    )
    enabledSendButton.click()

    val submitOutcome = waitForPostSubmitOutcome(
        packageName = packageName,
        stopLabel = stopLabel,
        configureLabel = configureLabel,
        chooseModelLabel = chooseModelLabel,
    )

    when (submitOutcome) {
        PostSubmitOutcome.ACCEPTED -> {
            // If a local/mock/paired backend starts streaming, give the timeline a bounded
            // window to render reply tokens without blocking forever on slow/offline links.
            val stopSelector = By.pkg(packageName).desc(stopLabel)
            if (device.hasObject(stopSelector)) {
                device.wait(Until.gone(stopSelector), REPLY_SETTLE_WAIT_MS)
            }
        }
        PostSubmitOutcome.UNCONFIGURED_MODEL_GATE -> {
            // Clear the unsubmitted probe draft so subsequent benchmark/profile iterations
            // start from a clean composer state. Avoid pressing Back on the root PersonalAgent
            // route so we never accidentally exit the app when no IME is open.
            val retainedInput = waitForComposerInput(packageName)
            retainedInput.text = ""
            device.waitForIdle()
        }
    }

    scrollChatTimelineOrStage(packageName)
    SystemClock.sleep(DEFERRED_WORK_MS)
}

/**
 * Enters the explicit legacy `HOME` (`Screen.SessionHome`) journey and asserts it rendered.
 *
 * - When [directHomeLaunch] is `true` (`launchStartMode=HOME`), asserts cold start landed
 *   directly on `SessionHome`.
 * - Otherwise (default `PersonalAgent` cold start), asserts `PersonalAgent` is ready, taps the
 *   existing top-bar Sessions entry (`personal-agent-sessions`), and asserts `SessionHome` opens.
 */
internal fun MacrobenchmarkScope.openExplicitSessionHome(
    packageName: String,
    directHomeLaunch: Boolean = isExplicitHomeLaunchMode(),
) {
    if (!directHomeLaunch) {
        waitForPersonalAgentReady(packageName)
        val sessionsLabel = requireAppString(packageName, "personal_agent_sessions")
        val sessionsButton = waitForFirst(
            description = "Personal Agent sessions entry (${ProfileTestTags.PERSONAL_AGENT_SESSIONS})",
            timeoutMs = ACTION_TIMEOUT_MS,
            selectors = tagSelectors(packageName, ProfileTestTags.PERSONAL_AGENT_SESSIONS) + listOf(
                By.pkg(packageName).desc(sessionsLabel),
            ),
        )
        sessionsButton.click()
    }
    waitForSessionHome(packageName)
}

/**
 * Strictly asserts `Screen.SessionHome` chrome is visible (Search + Settings).
 */
internal fun MacrobenchmarkScope.waitForSessionHome(packageName: String) {
    val searchLabel = requireAppString(packageName, "history_page_search")
    val settingsLabel = requireAppString(packageName, "settings")

    waitForFirst(
        description = "SessionHome search control (${ProfileTestTags.SESSION_HOME_SEARCH})",
        timeoutMs = STAGE_READY_TIMEOUT_MS,
        selectors = tagSelectors(packageName, ProfileTestTags.SESSION_HOME_SEARCH) + listOf(
            By.pkg(packageName).desc(searchLabel),
            By.pkg(packageName).text(searchLabel),
        ),
    )

    waitForFirst(
        description = "SessionHome settings button (${ProfileTestTags.HOME_HEADER_SETTINGS})",
        timeoutMs = ACTION_TIMEOUT_MS,
        selectors = tagSelectors(packageName, ProfileTestTags.HOME_HEADER_SETTINGS) + listOf(
            By.pkg(packageName).desc(settingsLabel),
        ),
    )
}

/**
 * Exercises `SessionHome` session list rendering and conversation navigation.
 *
 * - Strictly asserts the `Conversations` section header appears (scrolling up to 3 times on
 *   compact viewports).
 * - When persisted session rows exist, opens up to [CONVERSATIONS_TO_OPEN] rows, strictly asserts
 *   each `ChatPage` opened (home left + composer ready), scrolls its timeline, and strictly
 *   asserts `Back` returns to `SessionHome`.
 * - When the database has 0 conversations (clean device), strictly asserts the explicit empty
 *   state (`// 0 results` or localized empty hint) is rendered rather than silently succeeding.
 */
internal fun MacrobenchmarkScope.exerciseSessionHomeAndConversation(packageName: String) {
    val conversationsLabel = requireAppString(packageName, "amber_redesign_conversations")
    val searchLabel = requireAppString(packageName, "history_page_search")
    val emptyHint = requireAppString(packageName, "session_home_empty_hint")
    val emptyHintTwoPane = requireAppString(packageName, "session_home_empty_hint_two_pane")

    waitForSessionHome(packageName)

    val width = device.displayWidth
    val height = device.displayHeight
    val conversationsSelectors = tagSelectors(packageName, ProfileTestTags.SESSION_HOME_CONVERSATIONS_HEADER) + listOf(
        By.pkg(packageName).text(conversationsLabel),
    )

    // Scroll SessionHome list to warm up list composition and bring the Conversations section into view.
    var header = findFirst(conversationsSelectors)
    if (header == null) {
        repeat(HOME_SCROLL_ATTEMPTS) {
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, SWIPE_STEPS)
            device.waitForIdle()
            header = findFirst(conversationsSelectors)
            if (header != null) return@repeat
        }
    }
    val resolvedHeader = header ?: throw AssertionError(
        "SessionHome '$conversationsLabel' section header (${ProfileTestTags.SESSION_HOME_CONVERSATIONS_HEADER}) " +
            "not found after $HOME_SCROLL_ATTEMPTS scrolls",
    )

    val initialRows = findSessionRowsBelow(resolvedHeader.visibleBounds.bottom, width)
    if (initialRows.isEmpty()) {
        // Clean device with 0 conversations: fail-fast unless the real empty state rendered.
        waitForFirst(
            description = "SessionHome empty-state indicator when no conversation rows exist",
            timeoutMs = ACTION_TIMEOUT_MS,
            selectors = listOf(
                By.pkg(packageName).text(SESSION_HOME_EMPTY_MARKER),
                By.pkg(packageName).text(emptyHint),
                By.pkg(packageName).text(emptyHintTwoPane),
            ),
        )
        // Exercise gentle scroll/return on SessionHome so LazyColumn layout is still profiled.
        device.swipe(width / 2, height / 3, width / 2, height * 3 / 4, SWIPE_STEPS)
        device.waitForIdle()
        return
    }

    val rowsToOpen = minOf(CONVERSATIONS_TO_OPEN, initialRows.size)
    val sendLabel = requireAppString(packageName, "send")
    val stopLabel = requireAppString(packageName, "stop")
    repeat(rowsToOpen) { index ->
        val currentHeader = waitForFirst(
            description = "SessionHome '$conversationsLabel' header before opening row #$index",
            timeoutMs = ACTION_TIMEOUT_MS,
            selectors = conversationsSelectors,
        )
        val row = findSessionRowsBelow(currentHeader.visibleBounds.bottom, width).getOrNull(index)
            ?: throw AssertionError(
                "Expected clickable SessionHome row #$index below '$conversationsLabel' header, found none",
            )
        val homeWasSinglePane = device.hasObject(By.pkg(packageName).desc(searchLabel))
        row.click()

        // Assert ChatPage actually composed and reached an interactive state.
        waitForComposerInput(packageName)
        waitForFirst(
            description = "ChatPage send/stop control after opening row #$index",
            timeoutMs = TIMELINE_TIMEOUT_MS,
            selectors = tagSelectors(packageName, ProfileTestTags.CHAT_COMPOSER_SEND) + listOf(
                By.pkg(packageName).desc(sendLabel),
                By.pkg(packageName).desc(stopLabel),
            ),
        )
        scrollChatTimelineOrStage(packageName)

        // Let deferred work (off-screen widget WebViews, preview thumbnails) run before leaving.
        SystemClock.sleep(DEFERRED_WORK_MS)

        if (homeWasSinglePane && !device.hasObject(By.pkg(packageName).desc(searchLabel))) {
            device.pressBack()
            waitForSessionHome(packageName)
        }
        waitForFirst(
            description = "SessionHome '$conversationsLabel' header after returning from row #$index",
            timeoutMs = ACTION_TIMEOUT_MS,
            selectors = conversationsSelectors,
        )
    }
}

private enum class PostSubmitOutcome {
    ACCEPTED,
    UNCONFIGURED_MODEL_GATE,
}

private fun MacrobenchmarkScope.waitForComposerInput(packageName: String): UiObject2 =
    waitForFirst(
        description = "chat composer text input (${ProfileTestTags.CHAT_COMPOSER_INPUT})",
        timeoutMs = STAGE_READY_TIMEOUT_MS,
        selectors = tagSelectors(packageName, ProfileTestTags.CHAT_COMPOSER_INPUT) + listOf(
            By.pkg(packageName).clazz(EDIT_TEXT_CLASS),
        ),
    )

private fun MacrobenchmarkScope.waitForPostSubmitOutcome(
    packageName: String,
    stopLabel: String,
    configureLabel: String,
    chooseModelLabel: String,
): PostSubmitOutcome {
    val deadline = SystemClock.uptimeMillis() + POST_SUBMIT_WAIT_MS
    val stopSelector = By.pkg(packageName).desc(stopLabel)
    val configureSelector = By.pkg(packageName).text(configureLabel)
    val chooseModelSelector = By.pkg(packageName).text(chooseModelLabel)
    val inputSelectors = tagSelectors(packageName, ProfileTestTags.CHAT_COMPOSER_INPUT) + listOf(
        By.pkg(packageName).clazz(EDIT_TEXT_CLASS),
    )

    while (SystemClock.uptimeMillis() < deadline) {
        if (device.hasObject(stopSelector)) {
            return PostSubmitOutcome.ACCEPTED
        }
        val currentInput = findFirst(inputSelectors)
        if (currentInput != null && currentInput.text.orEmpty() != PERSONAL_AGENT_PROBE_MESSAGE) {
            return PostSubmitOutcome.ACCEPTED
        }
        if (device.hasObject(configureSelector) || device.hasObject(chooseModelSelector)) {
            return PostSubmitOutcome.UNCONFIGURED_MODEL_GATE
        }
        SystemClock.sleep(POLL_INTERVAL_MS)
    }

    throw AssertionError(
        "Submitting '$PERSONAL_AGENT_PROBE_MESSAGE' on Personal Agent neither accepted the message " +
            "(composer still holds draft and no '$stopLabel' control appeared) nor surfaced an " +
            "unconfigured-model gate ('$configureLabel' / '$chooseModelLabel') within ${POST_SUBMIT_WAIT_MS}ms",
    )
}

private fun MacrobenchmarkScope.scrollChatTimelineOrStage(packageName: String) {
    val scrollable = waitForFirst(
        description = "scrollable chat timeline or stage viewport (${ProfileTestTags.CHAT_TIMELINE_LIST})",
        timeoutMs = TIMELINE_TIMEOUT_MS,
        selectors = tagSelectors(packageName, ProfileTestTags.CHAT_TIMELINE_LIST) + listOf(
            By.pkg(packageName).scrollable(true),
        ),
    )
    val bounds = scrollable.visibleBounds
    val centerX = bounds.centerX()
    val upperY = bounds.top + (bounds.height() / 3).coerceAtLeast(1)
    val lowerY = bounds.top + (bounds.height() * 2 / 3).coerceAtLeast(upperY + 1)

    device.swipe(centerX, upperY, centerX, lowerY, SWIPE_STEPS)
    device.waitForIdle()
    device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
    device.waitForIdle()
}

private fun MacrobenchmarkScope.findSessionRowsBelow(headerBottom: Int, displayWidth: Int): List<UiObject2> =
    device.findObjects(By.clickable(true))
        .filter { it.visibleBounds.top >= headerBottom && it.visibleBounds.width() > displayWidth * 7 / 10 }
        .sortedBy { it.visibleBounds.top }

private fun MacrobenchmarkScope.waitForFirst(
    description: String,
    timeoutMs: Long,
    selectors: List<BySelector>,
): UiObject2 {
    require(selectors.isNotEmpty()) { "At least one BySelector is required for $description" }
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    while (true) {
        findFirst(selectors)?.let { return it }
        val remaining = deadline - SystemClock.uptimeMillis()
        if (remaining <= 0L) {
            throw AssertionError(
                "Timed out after ${timeoutMs}ms waiting for $description (selectors=$selectors)",
            )
        }
        SystemClock.sleep(minOf(POLL_INTERVAL_MS, remaining))
    }
}

private fun MacrobenchmarkScope.findFirst(selectors: List<BySelector>): UiObject2? {
    for (selector in selectors) {
        device.findObject(selector)?.let { return it }
    }
    return null
}

private fun tagSelectors(packageName: String, tag: String): List<BySelector> = listOf(
    By.res(tag),
    By.res(packageName, tag),
)

private fun enabledTagSelectors(packageName: String, tag: String): List<BySelector> = listOf(
    By.res(tag).enabled(true),
    By.res(packageName, tag).enabled(true),
)

private fun MacrobenchmarkScope.requireAppString(
    packageName: String,
    name: String,
    vararg formatArgs: Any,
): String {
    val app = InstrumentationRegistry.getInstrumentation().context.createPackageContext(packageName, 0)
    val id = app.resources.getIdentifier(name, "string", packageName)
    if (id == 0) {
        throw AssertionError("Required string resource '$name' not found in target package '$packageName'")
    }
    return if (formatArgs.isEmpty()) app.getString(id) else app.getString(id, *formatArgs)
}

private const val EDIT_TEXT_CLASS = "android.widget.EditText"
private const val PERSONAL_AGENT_PROBE_MESSAGE = "Ping Amber"
private const val SESSION_HOME_EMPTY_MARKER = "// 0 results"
private const val CONVERSATIONS_TO_OPEN = 3
private const val HOME_SCROLL_ATTEMPTS = 3
private const val SWIPE_STEPS = 30
private const val POLL_INTERVAL_MS = 100L
private const val STAGE_READY_TIMEOUT_MS = 10_000L
private const val ACTION_TIMEOUT_MS = 5_000L
private const val TIMELINE_TIMEOUT_MS = 5_000L
private const val POST_SUBMIT_WAIT_MS = 4_000L
private const val REPLY_SETTLE_WAIT_MS = 3_000L
private const val DEFERRED_WORK_MS = 1_500L
