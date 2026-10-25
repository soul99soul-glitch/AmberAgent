package app.amber.feature.ui.components.media

/**
 * Pixel size of a video frame or a dialog slot.
 *
 * Values are in pixels. `width`/`height` are never negative after helpers below
 * return; callers may still see `0` when the available slot is empty.
 */
data class VideoPxSize(val width: Int, val height: Int)

/**
 * Player-reported video metrics used to compute the on-screen box.
 *
 * @param reportedWidth `MediaPlayer.getVideoWidth()` after prepare (or a later
 *   `OnVideoSizeChanged` width). Same for [reportedHeight].
 * @param codedWidth `MediaFormat.KEY_WIDTH` from the video track when known;
 *   `0` if the format is missing. Same for [codedHeight].
 * @param rotationDegrees `MediaFormat.KEY_ROTATION` clockwise degrees when
 *   known; `0` if missing.
 */
data class VideoTrackMetrics(
    val reportedWidth: Int,
    val reportedHeight: Int,
    val codedWidth: Int = 0,
    val codedHeight: Int = 0,
    val rotationDegrees: Int = 0,
)

/** Fallback display aspect when the player has not reported a valid size. */
const val FALLBACK_VIDEO_ASPECT_WIDTH = 16
const val FALLBACK_VIDEO_ASPECT_HEIGHT = 9

/**
 * Normalize a rotation to `{0, 90, 180, 270}`.
 *
 * Negative values wrap (`-90 → 270`). Values outside a quarter-turn snap to the
 * nearest multiple of 90 (`91 → 90`, `44 → 0`). `360` and `0` are the same.
 */
fun normalizeRotationDegrees(rotationDegrees: Int): Int {
    var degrees = rotationDegrees % 360
    if (degrees < 0) degrees += 360
    return ((degrees + 45) / 90 * 90) % 360
}

/**
 * Extra rotation to apply on top of the player's reported width/height.
 *
 * Rotation contract (apply at most once):
 *
 * - `reportedWidth`/`reportedHeight` are the player's `getVideoWidth/Height`.
 *   Some engines already swap axes for 90/270 display rotation; others leave
 *   the coded size and put the angle in `MediaFormat.KEY_ROTATION`.
 * - If coded size is known and already swapped relative to the report
 *   (`reported == coded axes flipped`) while rotation is 90/270, the player
 *   consumed the rotation — return `0` so we do not swap again.
 * - Otherwise 90/270 swap once; 0/180 keep axes.
 * - Missing/illegal coded size (`<= 0`) still applies the metadata rotation
 *   once. Passing `rotationDegrees = 0` is how production says "the report is
 *   already display-oriented".
 */
fun rotationToApplyOnce(
    reportedWidth: Int,
    reportedHeight: Int,
    codedWidth: Int,
    codedHeight: Int,
    rotationDegrees: Int,
): Int {
    val snapped = normalizeRotationDegrees(rotationDegrees)
    if (snapped != 90 && snapped != 270) return 0
    val codedKnown = codedWidth > 0 && codedHeight > 0
    val reportedKnown = reportedWidth > 0 && reportedHeight > 0
    if (codedKnown && reportedKnown &&
        reportedWidth == codedHeight &&
        reportedHeight == codedWidth
    ) {
        return 0
    }
    return snapped
}

/**
 * Display size after applying [rotationDegrees] once to the reported frame.
 *
 * 90/270 swap axes; 0/180 keep them. Invalid (`<= 0`) reports return `0×0` so
 * the caller can fall back to 16:9 via [resolvedVideoContentSize].
 */
fun effectiveVideoSize(
    reportedWidth: Int,
    reportedHeight: Int,
    rotationDegrees: Int,
): VideoPxSize {
    if (reportedWidth <= 0 || reportedHeight <= 0) return VideoPxSize(0, 0)
    return when (normalizeRotationDegrees(rotationDegrees)) {
        90, 270 -> VideoPxSize(reportedHeight, reportedWidth)
        else -> VideoPxSize(reportedWidth, reportedHeight)
    }
}

/**
 * Content size used for layout: valid effective size, or 16:9 when unknown.
 */
fun resolvedVideoContentSize(
    reportedWidth: Int,
    reportedHeight: Int,
    rotationDegrees: Int,
): VideoPxSize {
    val effective = effectiveVideoSize(reportedWidth, reportedHeight, rotationDegrees)
    if (effective.width <= 0 || effective.height <= 0) {
        return VideoPxSize(FALLBACK_VIDEO_ASPECT_WIDTH, FALLBACK_VIDEO_ASPECT_HEIGHT)
    }
    return effective
}

/**
 * On-screen content size from player metrics, applying track rotation at most once.
 */
fun displaySizeFromPlayerMetrics(metrics: VideoTrackMetrics): VideoPxSize {
    val rotation = rotationToApplyOnce(
        reportedWidth = metrics.reportedWidth,
        reportedHeight = metrics.reportedHeight,
        codedWidth = metrics.codedWidth,
        codedHeight = metrics.codedHeight,
        rotationDegrees = metrics.rotationDegrees,
    )
    return resolvedVideoContentSize(metrics.reportedWidth, metrics.reportedHeight, rotation)
}

/**
 * Contain-fit [contentWidth]×[contentHeight] into [availableWidth]×[availableHeight].
 *
 * The result never exceeds the available slot, never changes aspect except by
 * at most one pixel of integer division, and never stretches. Empty available
 * area returns `0×0`. Extreme ratios that would round to 0 px still occupy
 * 1 px on the short axis when the slot is non-empty, so the view stays laid out.
 */
fun containFitVideo(
    contentWidth: Int,
    contentHeight: Int,
    availableWidth: Int,
    availableHeight: Int,
): VideoPxSize {
    val aw = availableWidth.toLong().coerceAtLeast(0)
    val ah = availableHeight.toLong().coerceAtLeast(0)
    if (aw == 0L || ah == 0L) return VideoPxSize(0, 0)
    val content = resolvedVideoContentSize(contentWidth, contentHeight, rotationDegrees = 0)
    val cw = content.width.toLong()
    val ch = content.height.toLong()
    val fittedWidth: Long
    val fittedHeight: Long
    if (cw * ah <= ch * aw) {
        fittedHeight = ah
        fittedWidth = (cw * ah / ch).coerceIn(1L, aw)
    } else {
        fittedWidth = aw
        fittedHeight = (ch * aw / cw).coerceIn(1L, ah)
    }
    return VideoPxSize(fittedWidth.toInt(), fittedHeight.toInt())
}

/**
 * Dialog slot after system insets and extra padding (both sides).
 *
 * Negative insets/padding are treated as 0. A window smaller than the chrome
 * yields `0×0`.
 */
fun videoDialogAvailablePx(
    windowWidth: Int,
    windowHeight: Int,
    insetLeft: Int = 0,
    insetTop: Int = 0,
    insetRight: Int = 0,
    insetBottom: Int = 0,
    extraPaddingPx: Int = 0,
): VideoPxSize {
    val pad = extraPaddingPx.toLong().coerceAtLeast(0)
    val width = windowWidth.toLong() -
        insetLeft.toLong().coerceAtLeast(0) -
        insetRight.toLong().coerceAtLeast(0) -
        pad * 2
    val height = windowHeight.toLong() -
        insetTop.toLong().coerceAtLeast(0) -
        insetBottom.toLong().coerceAtLeast(0) -
        pad * 2
    return VideoPxSize(width.coerceAtLeast(0).toInt(), height.coerceAtLeast(0).toInt())
}

/**
 * Height reserved for the play/pause bar. Never exceeds [availableHeight], so
 * an extremely short dialog still keeps the control reachable.
 */
fun videoControlHeightPx(availableHeight: Int, preferredPx: Int): Int {
    if (availableHeight <= 0 || preferredPx <= 0) return 0
    return preferredPx.coerceAtMost(availableHeight)
}

/**
 * Player box for a dialog: contain-fit the (rotation-aware) content into the
 * inset-padded window. Portrait video is limited by available height, so
 * `availableWidth * (h/w)` cannot push the surface past the dialog.
 *
 * [controlHeightPx] is reserved below the player so the control bar stays
 * inside the same slot. Existing callers pass 0 and get the previous box.
 */
fun fitPlayerInDialog(
    metrics: VideoTrackMetrics,
    windowWidth: Int,
    windowHeight: Int,
    insetLeft: Int = 0,
    insetTop: Int = 0,
    insetRight: Int = 0,
    insetBottom: Int = 0,
    extraPaddingPx: Int = 0,
    controlHeightPx: Int = 0,
): VideoPxSize {
    val available = videoDialogAvailablePx(
        windowWidth = windowWidth,
        windowHeight = windowHeight,
        insetLeft = insetLeft,
        insetTop = insetTop,
        insetRight = insetRight,
        insetBottom = insetBottom,
        extraPaddingPx = extraPaddingPx,
    )
    val reserved = videoControlHeightPx(available.height, controlHeightPx)
    val content = displaySizeFromPlayerMetrics(metrics)
    return containFitVideo(
        content.width,
        content.height,
        available.width,
        (available.height - reserved).coerceAtLeast(0),
    )
}
