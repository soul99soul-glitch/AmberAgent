package app.amber.baselineprofile

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
    val width = device.displayWidth
    val height = device.displayHeight

    // The home list has no resource id; scroll until its existing section label is visible.
    device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 30)
    repeat(2) {
        if (device.hasObject(By.text(conversations))) return@repeat
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 30)
    }
    val header = device.findObject(By.text(conversations)) ?: return
    val row = device.findObjects(By.clickable(true))
        .filter { it.visibleBounds.top >= header.visibleBounds.bottom && it.visibleBounds.width() > width * 7 / 10 }
        .minByOrNull { it.visibleBounds.top } ?: return
    row.click()
    device.waitForIdle()

    // Chat's message list is the screen's scrollable container. A short session
    // may have nothing to scroll; the journey still covers opening that session.
    if (device.hasObject(By.scrollable(true))) {
        device.swipe(width / 2, height * 3 / 4, width / 2, height / 3, 30)
    }
}

private fun MacrobenchmarkScope.appString(packageName: String, name: String): String? {
    val app = InstrumentationRegistry.getInstrumentation().context.createPackageContext(packageName, 0)
    val id = app.resources.getIdentifier(name, "string", packageName)
    return if (id == 0) null else app.getString(id)
}
