package app.amber.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal fun MacrobenchmarkScope.waitForHome(packageName: String) {
    val search = appString(packageName, "history_page_search") ?: return
    device.wait(Until.hasObject(By.desc(search)), 5_000)
}

internal fun MacrobenchmarkScope.scrollHomeAndConversation(packageName: String) {
    val conversations = appString(packageName, "amber_redesign_conversations") ?: return
    val search = appString(packageName, "history_page_search")
    val width = device.displayWidth
    val height = device.displayHeight

    // The home list has no resource id; scroll until its existing section label is visible.
    device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 30)
    repeat(2) {
        if (device.hasObject(By.text(conversations))) return@repeat
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 30)
    }
    // Open several sessions so the profile covers more message kinds (long Markdown, tool
    // cards, web previews) than the first row alone happens to contain.
    repeat(CONVERSATIONS_TO_OPEN) { index ->
        val header = device.findObject(By.text(conversations)) ?: return
        val row = device.findObjects(By.clickable(true))
            .filter { it.visibleBounds.top >= header.visibleBounds.bottom && it.visibleBounds.width() > width * 7 / 10 }
            .sortedBy { it.visibleBounds.top }
            .getOrNull(index) ?: return
        row.click()
        // The timeline only composes after the push transition settles and its Markdown is
        // prepared. Wait for home (also scrollable) to leave, then for the message list itself.
        // A short session may have nothing to scroll; the journey still covers opening it.
        search?.let { device.wait(Until.gone(By.desc(it)), TIMELINE_TIMEOUT_MS) }
        if (device.wait(Until.hasObject(By.scrollable(true)), TIMELINE_TIMEOUT_MS)) {
            device.swipe(width / 2, height / 3, width / 2, height * 3 / 4, 30)
            device.waitForIdle()
            device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 30)
            device.waitForIdle()
        }
        // Let deferred work (off-screen widget WebViews, preview thumbnails) run before leaving.
        SystemClock.sleep(DEFERRED_WORK_MS)
        device.pressBack()
        device.wait(Until.hasObject(By.text(conversations)), 5_000)
    }
}

private const val CONVERSATIONS_TO_OPEN = 3
private const val TIMELINE_TIMEOUT_MS = 5_000L
private const val DEFERRED_WORK_MS = 2_500L

private fun MacrobenchmarkScope.appString(packageName: String, name: String): String? {
    val app = InstrumentationRegistry.getInstrumentation().context.createPackageContext(packageName, 0)
    val id = app.resources.getIdentifier(name, "string", packageName)
    return if (id == 0) null else app.getString(id)
}
