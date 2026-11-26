package app.amber.core.automation.display

import android.content.Context
import android.content.Intent
import android.view.Display
import app.amber.core.automation.AccessibilityController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around the existing physical-display accessibility path.
 * Preserves existing physical screen automation behavior with zero behavioral change.
 */
class PhysicalDisplaySession(
    private val packageName: String,
    override val controller: AccessibilityController,
    private val targetLauncher: suspend (String) -> Boolean,
) : PhoneDisplaySession {

    constructor(
        context: Context,
        packageName: String,
        controller: AccessibilityController,
    ) : this(
        packageName = packageName,
        controller = controller,
        targetLauncher = { pkg ->
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                false
            } else {
                withContext(Dispatchers.Main.immediate) {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                true
            }
        },
    )

    override val mode: PhoneDisplayMode = PhoneDisplayMode.PHYSICAL

    override val displayId: Int = Display.DEFAULT_DISPLAY

    override val generation: Long = 0L

    override suspend fun launchTarget(): Boolean {
        if (packageName.isBlank()) return false
        return targetLauncher(packageName)
    }

    override suspend fun close(reason: String) {
        // Physical session does not own a virtual display or Shizuku bridge; nothing to tear down.
    }
}
