package app.amber.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** Compose exports these tags verbatim as accessibility resource IDs on RouteActivity's tree. */
internal object ProfileTestTags {
    const val PERSONAL_AGENT_HOST_PILL = "personal-agent-host-pill"
    const val PERSONAL_AGENT_SESSIONS = "personal-agent-sessions"
    const val PERSONAL_AGENT_MORE = "personal-agent-more"
    const val HOME_HEADER_SETTINGS = "home-header-settings"
    const val CHAT_COMPOSER_INPUT = "chat-composer-input"
    const val CHAT_COMPOSER_SEND = "chat-composer-send"
    const val CHAT_TIMELINE_LIST = "chat-timeline-list"
    const val CHAT_CONFIGURATION_HINT = "chat-configuration-hint"
    const val SESSION_HOME_SEARCH = "home-header-search"
    const val SESSION_HOME_CONVERSATIONS_HEADER = "home-conversations-header"
    const val SESSION_HOME_LIST = "home-session-list"
    const val SESSION_HOME_ROW = "home-session-row"
    const val SESSION_HOME_EMPTY = "home-session-empty"
}

internal fun targetAppId(): String =
    InstrumentationRegistry.getArguments().getString("targetAppId")
        ?: error("targetAppId not passed as instrumentation runner arg")

/** HOME means the device's persisted launch preference was explicitly configured for this journey. */
internal fun isExplicitHomeLaunchMode(): Boolean =
    InstrumentationRegistry.getArguments()
        .getString("launchStartMode")
        ?.equals("HOME", ignoreCase = true) == true

internal fun MacrobenchmarkScope.waitForPersonalAgentReady(packageName: String): UiObject2 {
    waitForNode(tag(packageName, ProfileTestTags.PERSONAL_AGENT_HOST_PILL))
    waitForNode(tag(packageName, ProfileTestTags.PERSONAL_AGENT_MORE))
    waitForNode(tag(packageName, ProfileTestTags.CHAT_COMPOSER_SEND))
    return waitForNode(tag(packageName, ProfileTestTags.CHAT_COMPOSER_INPUT).enabled(true))
}

/**
 * A configured fixture should echo the requested marker. Each iteration uses its own marker so
 * a previous reply or the composer itself cannot satisfy the timeline assertion. A clean device
 * without a provider instead exercises the configuration gate and retained draft.
 */
internal fun MacrobenchmarkScope.exercisePersonalAgentInputAndTimeline(packageName: String) {
    val replyMarker = "AmberReady" + SystemClock.elapsedRealtimeNanos()
    val probe = "Reply with exactly this word: $replyMarker"
    val input = tag(packageName, ProfileTestTags.CHAT_COMPOSER_INPUT)
    val send = tag(packageName, ProfileTestTags.CHAT_COMPOSER_SEND)
    val timeline = tag(packageName, ProfileTestTags.CHAT_TIMELINE_LIST)

    waitForPersonalAgentReady(packageName).click()
    waitForNode(input).text = probe
    waitForNode(tag(packageName, ProfileTestTags.CHAT_COMPOSER_INPUT).text(probe))
    waitForNode(send.enabled(true)).click()

    val configGate = tag(packageName, ProfileTestTags.CHAT_CONFIGURATION_HINT)
    if (device.hasObject(configGate) && device.findObject(input)?.text == probe) {
        waitForNode(input).text = ""
        device.waitForIdle()
    } else {
        waitForNode(
            timeline.hasDescendant(By.text(replyMarker)),
            timeoutMs = REPLY_TIMEOUT_MS,
        )
    }
    scrollTimeline(packageName)
}

internal fun MacrobenchmarkScope.openExplicitSessionHome(
    packageName: String,
    directHomeLaunch: Boolean = isExplicitHomeLaunchMode(),
) {
    if (!directHomeLaunch) {
        waitForPersonalAgentReady(packageName)
        val menu = tag(packageName, ProfileTestTags.PERSONAL_AGENT_MORE)
        waitForNode(menu).click()
        check(device.wait(Until.gone(menu), STAGE_READY_TIMEOUT_MS)) { "Workspace sheet did not open" }
        val sessions = tag(packageName, ProfileTestTags.PERSONAL_AGENT_SESSIONS)
        val entry = device.findObject(sessions) ?: waitForNode(By.pkg(packageName).scrollable(true))
            .scrollUntil(Direction.DOWN, Until.findObject(sessions))
        checkNotNull(entry) { "All conversations entry missing from workspace sheet" }.click()
    }
    waitForNode(tag(packageName, ProfileTestTags.SESSION_HOME_SEARCH))
    waitForNode(tag(packageName, ProfileTestTags.HOME_HEADER_SETTINGS))
    waitForNode(tag(packageName, ProfileTestTags.SESSION_HOME_LIST))
}

internal fun MacrobenchmarkScope.exerciseSessionHomeAndConversation(packageName: String) {
    val list = tag(packageName, ProfileTestTags.SESSION_HOME_LIST)
    val header = tag(packageName, ProfileTestTags.SESSION_HOME_CONVERSATIONS_HEADER)
    val row = tag(packageName, ProfileTestTags.SESSION_HOME_ROW).clickable(true)
    val empty = tag(packageName, ProfileTestTags.SESSION_HOME_EMPTY)
    val home = waitForNode(list)
    if (!device.hasObject(header) && home.scrollUntil(Direction.DOWN, Until.findObject(header)) == null) {
        throw AssertionError("SessionHome conversations header not found")
    }

    // Paging may not have delivered its first rows yet. Only a real empty state means zero rows.
    val deadline = SystemClock.uptimeMillis() + STAGE_READY_TIMEOUT_MS
    while (!device.hasObject(row) && !device.hasObject(empty)) {
        if (SystemClock.uptimeMillis() >= deadline) {
            throw AssertionError("SessionHome displayed neither a conversation nor the empty state")
        }
        home.scroll(Direction.DOWN, 0.5f)
        SystemClock.sleep(POLL_INTERVAL_MS)
    }
    if (device.hasObject(empty)) return

    val rowsToOpen = minOf(CONVERSATIONS_TO_OPEN, device.findObjects(row).size)
    repeat(rowsToOpen) { index ->
        device.findObjects(row).sortedBy { it.visibleBounds.top }.getOrNull(index)?.click()
            ?: throw AssertionError("SessionHome conversation row $index disappeared")
        waitForNode(tag(packageName, ProfileTestTags.CHAT_COMPOSER_INPUT))
        waitForNode(tag(packageName, ProfileTestTags.CHAT_COMPOSER_SEND))
        scrollTimeline(packageName)
        // A tablet keeps the tagged home list on screen alongside the detail pane.
        if (!device.hasObject(list)) device.pressBack()
        waitForNode(list)
    }
}

private fun MacrobenchmarkScope.scrollTimeline(packageName: String) {
    val timeline = waitForNode(tag(packageName, ProfileTestTags.CHAT_TIMELINE_LIST))
    timeline.scroll(Direction.UP, 0.5f)
    timeline.scroll(Direction.DOWN, 0.5f)
}

private fun tag(packageName: String, id: String): BySelector = By.pkg(packageName).res(id)

private fun MacrobenchmarkScope.waitForNode(
    selector: BySelector,
    timeoutMs: Long = STAGE_READY_TIMEOUT_MS,
): UiObject2 = device.wait(Until.findObject(selector), timeoutMs)
    ?: throw AssertionError("Timed out after ${timeoutMs}ms waiting for $selector")

private const val CONVERSATIONS_TO_OPEN = 3
private const val POLL_INTERVAL_MS = 100L
private const val STAGE_READY_TIMEOUT_MS = 10_000L
private const val REPLY_TIMEOUT_MS = 30_000L
