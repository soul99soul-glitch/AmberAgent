package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.compose.rememberAsyncImagePainter
import com.dokar.sonner.ToastType
import com.jvziyaoyao.scale.image.pager.ImagePager
import com.jvziyaoyao.scale.zoomable.pager.rememberZoomablePagerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import kotlin.math.abs

/**
 * Full-screen zoomable image viewer with:
 *  - Enter/exit scale + fade animation (matches Instagram/Twitter style "pop").
 *  - Swipe-down (or up) to dismiss: the image follows the finger vertically, the
 *    backdrop darkens-to-transparent as you drag farther, and crossing a
 *    threshold (a third of the screen) commits dismiss; otherwise the image
 *    springs back.
 *  - Horizontal swipe / pinch-zoom still goes to the underlying [ImagePager] so
 *    paging between multiple images and zoom-to-inspect remain untouched.
 *
 * The vertical-drag detection runs BEFORE the pager's gesture, so once we decide
 * a gesture is "vertical-dominant" we consume the events and the pager sees
 * nothing. If the first move is horizontal-dominant we bail out of detection
 * for that gesture and the pager handles it as usual.
 */
@Composable
fun ImagePreviewDialog(
    images: List<String>,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val filesManager: FilesManager = koinInject()
    val state = rememberZoomablePagerState { images.size }
    val toaster = LocalToaster.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Visible drives AnimatedVisibility's enter/exit. Set to true on first
    // composition so the dialog animates in; flipped to false to play the
    // exit animation before we actually invoke onDismissRequest.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val translate = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    suspend fun animatedDismiss(directionY: Float) {
        // Continue the user's fling outward, then trigger the real dismiss.
        // 1200dp is enough to clear any phone height.
        val targetY = if (directionY >= 0f) 1200f else -1200f
        translate.animateTo(
            targetValue = Offset(translate.value.x, with(density) { targetY.dp.toPx() }),
            animationSpec = tween(durationMillis = 220),
        )
        // Play AnimatedVisibility's exit (fade + scale down), THEN tell the caller
        // to remove the dialog. If we call onDismissRequest immediately the parent
        // composable yanks the Dialog off the tree before the exit anim runs and
        // the user sees an abrupt vanish (reviewer flag M2).
        visible = false
        delay(220)
        onDismissRequest()
    }

    suspend fun springBack() {
        translate.animateTo(
            targetValue = Offset.Zero,
            animationSpec = tween(durationMillis = 200),
        )
    }

    Dialog(
        onDismissRequest = {
            // Compose Dialog dismissal (back press, scrim tap) — play exit anim
            // first so the image fades + scales out instead of vanishing instantly.
            // Have to defer the actual onDismissRequest by the exit anim duration
            // (same reasoning as animatedDismiss above).
            scope.launch {
                visible = false
                delay(220)
                onDismissRequest()
            }
        },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        // Dismiss threshold scales with screen density; 25% of typical phone height.
        val dismissPx = with(density) { 200.dp.toPx() }

        // Backdrop fades as the image is dragged — same trick Twitter / IG use.
        val dragMagnitude = abs(translate.value.y)
        val backdropAlpha = (1f - (dragMagnitude / (dismissPx * 1.6f))).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backdropAlpha))
                .pointerInput(images) {
                    // Custom vertical-dominant gesture detector. We have to share
                    // the touch stream with the pager's horizontal-swipe + pinch-
                    // zoom, so we use awaitEachGesture and only consume events
                    // once the gesture is clearly "more vertical than horizontal".
                    val slopPx = with(density) { 12.dp.toPx() }
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var totalDx = 0f
                        var totalDy = 0f
                        var verticalDominant = false
                        var horizontalDominant = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            // Multi-touch (pinch zoom) → bail out and let pager handle.
                            // Critical: clear verticalDominant so the post-loop branch
                            // doesn't fire animatedDismiss/springBack when the user was
                            // legitimately starting a pinch mid-drag (reviewer M4).
                            if (event.changes.size > 1) {
                                verticalDominant = false
                                horizontalDominant = false
                                // Snap any partial drag offset back so the image
                                // doesn't sit displaced when zoom kicks in.
                                if (translate.value.y != 0f) {
                                    scope.launch { springBack() }
                                }
                                break
                            }
                            val delta = change.positionChange()
                            totalDx += delta.x
                            totalDy += delta.y
                            if (!verticalDominant && !horizontalDominant) {
                                if (abs(totalDy) > slopPx && abs(totalDy) > abs(totalDx) * 1.2f) {
                                    verticalDominant = true
                                } else if (abs(totalDx) > slopPx) {
                                    horizontalDominant = true
                                    break // let pager have the horizontal swipe
                                }
                            }
                            if (verticalDominant) {
                                scope.launch {
                                    translate.snapTo(
                                        Offset(translate.value.x, translate.value.y + delta.y),
                                    )
                                }
                                change.consume()
                            }
                        }
                        if (verticalDominant) {
                            val finalDy = translate.value.y
                            scope.launch {
                                if (abs(finalDy) > dismissPx) {
                                    animatedDismiss(finalDy)
                                } else {
                                    springBack()
                                }
                            }
                        }
                    }
                },
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = scaleIn(
                    initialScale = 0.88f,
                    animationSpec = tween(220),
                ) + fadeIn(tween(220)),
                exit = scaleOut(
                    targetScale = 0.88f,
                    animationSpec = tween(180),
                ) + fadeOut(tween(180)),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Only the image follows the vertical drag — the download
                    // button below sits in the same AnimatedVisibility scope
                    // (so it fades in/out with the dialog) but lives outside
                    // this graphicsLayer Box, so it stays pinned to the
                    // bottom edge of the screen instead of sliding away with
                    // the photo.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationY = translate.value.y
                                // Subtle scale-down as the user drags (peaks
                                // at ~0.85 near the dismiss threshold).
                                val pulledFraction =
                                    (abs(translate.value.y) / (dismissPx * 1.5f)).coerceIn(0f, 1f)
                                val s = 1f - pulledFraction * 0.15f
                                scaleX = s
                                scaleY = s
                            },
                    ) {
                        ImagePager(
                            modifier = Modifier.fillMaxSize(),
                            pagerState = state,
                            imageLoader = { index ->
                                val painter = rememberAsyncImagePainter(images[index])
                                return@ImagePager Pair(painter, painter.intrinsicSize)
                            },
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .zIndex(1f)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(
                            onClick = {
                                lifecycleOwner.lifecycleScope.launch {
                                    runCatching {
                                        toaster.show("正在保存")
                                        val imgUrl = images[state.currentPage]
                                        filesManager.saveMessageImage(context, imgUrl)
                                        toaster.show(message = "已保存图片", type = ToastType.Success)
                                    }.onFailure {
                                        it.printStackTrace()
                                        toaster.show(
                                            message = it.toString(),
                                            type = ToastType.Error,
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(HugeIcons.Download01, null, tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

// `Offset.VectorConverter` comes from androidx.compose.animation.core; the
// import above gives us the stdlib extension property directly, no need to
// hand-roll a TwoWayConverter here.
