package app.amber.feature.ui.components.ds

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build

/** The display can show the extended-range press glow. */
fun Activity.supportsAmberHdr(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && display?.isHdr == true

/**
 * Keeps the window in HDR color mode for the whole app at the idle headroom. Switching
 * the mode per screen changes the layer format (extended-range vs sRGB), which makes dark
 * themes visibly shift when leaving the home page; staying in one mode avoids that.
 * Screens only raise the headroom briefly (home press glow) and return it to idle.
 */
fun Activity.enableAmberHdrWindow() {
    if (!supportsAmberHdr()) return
    window.colorMode = ActivityInfo.COLOR_MODE_HDR
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        window.desiredHdrHeadroom = AMBER_HDR_IDLE_HEADROOM
    }
}
