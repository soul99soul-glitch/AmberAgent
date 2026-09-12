package app.amber.feature.ui.pages.sessionhome

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * A continuous-corner rectangle.
 *
 * Each corner is two cubic Bézier segments. Their endpoint tangents and second derivatives
 * agree at the midpoint, while the outer endpoints have a zero-curvature tangent aligned to
 * the straight edge. A half-height radius switches to circular caps with short G2 blends;
 * rotating the same unit geometry keeps the rail and pill visually related.
 */
internal data class AmberContinuousShape(
    val cornerRadius: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = with(density) { cornerRadius.toPx() }
            .coerceIn(0f, minOf(size.width, size.height) / 2f)
        if (radius == 0f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        if (radius >= size.height / 2f && size.width >= size.height) {
            return Outline.Generic(createCapsulePath(size, radius))
        }

        val path = Path()

        path.moveTo(radius, 0f)
        path.lineTo(size.width - radius, 0f)
        appendSmoothSegments(
            path = path,
            radius = radius,
            start = Offset(size.width - radius, 0f),
            uAxis = Offset(1f, 0f),
            vAxis = Offset(0f, 1f),
            segments = amberContinuousCornerSegments,
        )
        path.lineTo(size.width, size.height - radius)
        appendSmoothSegments(
            path = path,
            radius = radius,
            start = Offset(size.width, size.height - radius),
            uAxis = Offset(0f, 1f),
            vAxis = Offset(-1f, 0f),
            segments = amberContinuousCornerSegments,
        )
        path.lineTo(radius, size.height)
        appendSmoothSegments(
            path = path,
            radius = radius,
            start = Offset(radius, size.height),
            uAxis = Offset(-1f, 0f),
            vAxis = Offset(0f, -1f),
            segments = amberContinuousCornerSegments,
        )
        path.lineTo(0f, radius)
        appendSmoothSegments(
            path = path,
            radius = radius,
            start = Offset(0f, radius),
            uAxis = Offset(0f, -1f),
            vAxis = Offset(1f, 0f),
            segments = amberContinuousCornerSegments,
        )
        path.close()

        return Outline.Generic(path)
    }
}

private fun createCapsulePath(size: Size, radius: Float): Path {
    val path = Path()
    val rightCenterX = size.width - radius
    val height = size.height

    path.moveTo(radius, 0f)
    path.lineTo(rightCenterX, 0f)
    appendSmoothSegments(
        path = path,
        radius = radius,
        start = Offset(rightCenterX, 0f),
        uAxis = Offset(1f, 0f),
        vAxis = Offset(0f, 1f),
        segments = amberCapsuleTopFlank,
    )
    path.arcTo(
        rect = Rect(rightCenterX - radius, 0f, rightCenterX + radius, height),
        startAngleDegrees = -70f,
        sweepAngleDegrees = 140f,
        forceMoveTo = false,
    )
    appendSmoothSegments(
        path = path,
        radius = radius,
        start = Offset(rightCenterX, 0f),
        uAxis = Offset(1f, 0f),
        vAxis = Offset(0f, 1f),
        segments = amberCapsuleBottomFlank,
    )

    path.lineTo(radius, height)
    appendSmoothSegments(
        path = path,
        radius = radius,
        start = Offset(radius, height),
        uAxis = Offset(-1f, 0f),
        vAxis = Offset(0f, -1f),
        segments = amberCapsuleTopFlank,
    )
    path.arcTo(
        rect = Rect(0f, height - 2f * radius, 2f * radius, height),
        startAngleDegrees = 110f,
        sweepAngleDegrees = 140f,
        forceMoveTo = false,
    )
    appendSmoothSegments(
        path = path,
        radius = radius,
        start = Offset(radius, height),
        uAxis = Offset(-1f, 0f),
        vAxis = Offset(0f, -1f),
        segments = amberCapsuleBottomFlank,
    )
    path.close()
    return path
}

private fun appendSmoothSegments(
    path: Path,
    radius: Float,
    start: Offset,
    uAxis: Offset,
    vAxis: Offset,
    segments: List<AmberCubicSegment>,
) {
    fun point(u: Float, v: Float): Offset = Offset(
        x = start.x + radius * (uAxis.x * u + vAxis.x * v),
        y = start.y + radius * (uAxis.y * u + vAxis.y * v),
    )

    segments.forEach { segment ->
        val control1 = point(segment.control1.x, segment.control1.y)
        val control2 = point(segment.control2.x, segment.control2.y)
        val end = point(segment.end.x, segment.end.y)
        path.cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
    }
}

internal data class AmberCubicSegment(
    val start: Offset,
    val control1: Offset,
    val control2: Offset,
    val end: Offset,
)

internal val amberContinuousCornerSegments = listOf(
    AmberCubicSegment(
        start = Offset(0f, 0f),
        control1 = Offset(0.2f, 0f),
        control2 = Offset(0.6f, 0f),
        end = Offset(0.8f, 0.2f),
    ),
    AmberCubicSegment(
        start = Offset(0.8f, 0.2f),
        control1 = Offset(1f, 0.4f),
        control2 = Offset(1f, 0.8f),
        end = Offset(1f, 1f),
    ),
)

internal val amberCapsuleTopFlank = listOf(
    AmberCubicSegment(
        start = Offset(0f, 0f),
        control1 = Offset(0.03997008f, 0f),
        control2 = Offset(0.17632698f, 0f),
        end = Offset(0.34202014f, 0.06030738f),
    ),
)

internal val amberCapsuleBottomFlank = listOf(
    AmberCubicSegment(
        start = Offset(0.34202014f, 1.9396926f),
        control1 = Offset(0.17632698f, 2f),
        control2 = Offset(0.03997008f, 2f),
        end = Offset(0f, 2f),
    ),
)
