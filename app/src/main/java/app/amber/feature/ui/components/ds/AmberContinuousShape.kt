package app.amber.feature.ui.components.ds

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A continuous-corner (squircle) rectangle in the iOS style.
 *
 * Each corner is flat edge → cubic easing in from zero curvature → circular arc → mirrored
 * cubic, the corner-smoothing construction Figma uses to match iOS. With the iOS smoothing
 * of 0.6 the curve starts 1.6 radii from the vertex, so it reads softer than a circular
 * corner of the same radius instead of tighter. A half-height radius switches to a
 * continuous capsule so pills stay pills.
 */
internal data class AmberContinuousShape(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) : Shape {
    constructor(cornerRadius: Dp) : this(cornerRadius, cornerRadius, cornerRadius, cornerRadius)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val limit = minOf(size.width, size.height) / 2f
        fun px(value: Dp) = with(density) { value.toPx() }.coerceIn(0f, limit)
        val topLeft = px(if (ltr) topStart else topEnd)
        val topRight = px(if (ltr) topEnd else topStart)
        val bottomRight = px(if (ltr) bottomEnd else bottomStart)
        val bottomLeft = px(if (ltr) bottomStart else bottomEnd)
        val radii = listOf(topLeft, topRight, bottomRight, bottomLeft)
        if (radii.all { it == 0f }) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        if (size.width >= size.height && radii.all { it >= size.height / 2f }) {
            val radius = size.height / 2f
            return createCapsulePath(size, radius)?.let { Outline.Generic(it) }
                ?: Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
        }

        val w = size.width
        val h = size.height
        val path = Path()
        // Start mid-top so every corner is emitted by the same routine.
        path.moveTo(w / 2f, 0f)
        appendCorner(path, Offset(w, 0f), Offset(1f, 0f), Offset(0f, 1f), topRight, limit)
        appendCorner(path, Offset(w, h), Offset(0f, 1f), Offset(-1f, 0f), bottomRight, limit)
        appendCorner(path, Offset(0f, h), Offset(-1f, 0f), Offset(0f, -1f), bottomLeft, limit)
        appendCorner(path, Offset(0f, 0f), Offset(0f, -1f), Offset(1f, 0f), topLeft, limit)
        path.close()
        return Outline.Generic(path)
    }
}

/** iOS-matching corner smoothing (Figma's "iOS" preset). */
internal const val AMBER_CORNER_SMOOTHING = 0.6f

/**
 * Distances along the edge for one smoothed corner, all measured in pixels.
 * [extent] is where the curve leaves the straight edge; the first cubic spans a+b+c along
 * the edge and d inward, then a circular arc of [arcRadius] sweeps [arcSweepDegrees].
 */
internal data class AmberCornerParams(
    val extent: Float,
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val arcRadius: Float,
    val arcSweepDegrees: Float,
)

internal fun amberCornerParams(radius: Float, budget: Float, smoothing: Float = AMBER_CORNER_SMOOTHING): AmberCornerParams {
    // Shrink smoothing first when the curve would run past the edge midpoint (Figma's rule).
    val r = radius.coerceAtMost(budget)
    var s = smoothing
    var extent = (1f + s) * r
    if (extent > budget) {
        s = (budget / r - 1f).coerceIn(0f, smoothing)
        extent = minOf((1f + s) * r, budget)
    }
    val arcSweep = 90f * (1f - s)
    val arcChord = sin(Math.toRadians(arcSweep / 2.0)).toFloat() * r * sqrt(2f)
    val alpha = (90f - arcSweep) / 2f
    val p3ToP4 = r * tan(Math.toRadians(alpha / 2.0)).toFloat()
    val beta = 45f * s
    val c = p3ToP4 * cos(Math.toRadians(beta.toDouble())).toFloat()
    val d = c * tan(Math.toRadians(beta.toDouble())).toFloat()
    val b = ((extent - arcChord - c - d) / 3f).coerceAtLeast(0f)
    return AmberCornerParams(extent, a = 2f * b, b = b, c = c, d = d, arcRadius = r, arcSweepDegrees = arcSweep)
}

/**
 * Emits one corner at [vertex]: travelling along [inDir] toward the vertex, leaving along [outDir].
 * The path is expected to sit on the incoming edge; it ends on the outgoing edge.
 */
private fun appendCorner(path: Path, vertex: Offset, inDir: Offset, outDir: Offset, radius: Float, budget: Float) {
    if (radius <= 0f) {
        path.lineTo(vertex.x, vertex.y)
        return
    }
    val p = amberCornerParams(radius, budget)
    fun at(alongIn: Float, alongOut: Float) = vertex + inDir * alongIn + outDir * alongOut

    val start = at(-p.extent, 0f)
    path.lineTo(start.x, start.y)
    val arcStart = at(-(p.extent - p.a - p.b - p.c), p.d)
    val c1 = at(-(p.extent - p.a), 0f)
    val c2 = at(-(p.extent - p.a - p.b), 0f)
    path.cubicTo(c1.x, c1.y, c2.x, c2.y, arcStart.x, arcStart.y)

    // Arc as one cubic: sweep ≤ 90° keeps the error far below a pixel.
    val arcEnd = at(-p.d, p.extent - p.a - p.b - p.c)
    val center = vertex - inDir * p.arcRadius + outDir * p.arcRadius
    val k = 4f / 3f * tan(Math.toRadians(p.arcSweepDegrees / 4.0)).toFloat() * p.arcRadius
    fun tangent(point: Offset): Offset {
        val radial = (point - center) / p.arcRadius
        // Rotate the radial vector toward the travel direction (clockwise on a y-down canvas).
        return Offset(-radial.y, radial.x)
    }
    val t1 = tangent(arcStart)
    val t2 = tangent(arcEnd)
    val a1 = arcStart + t1 * k
    val a2 = arcEnd - t2 * k
    path.cubicTo(a1.x, a1.y, a2.x, a2.y, arcEnd.x, arcEnd.y)

    val d1 = at(0f, p.extent - p.a - p.b)
    val d2 = at(0f, p.extent - p.a)
    val end = at(0f, p.extent)
    path.cubicTo(d1.x, d1.y, d2.x, d2.y, end.x, end.y)
}

/**
 * Continuous capsule: semicircular ends whose joins to the straight edges are curvature-continuous.
 * The flank leaves the edge [AmberCapsuleFlank.extent] radii from the tip with zero curvature and
 * meets the nose arc with matching tangent and curvature, so there is no kink at the shoulder.
 */
private fun createCapsulePath(size: Size, radius: Float): Path? {
    val flank = amberCapsuleFlank
    val w = size.width
    val h = size.height
    if (w < 2f * flank.extent * radius) return null

    fun top(p: Offset) = Offset(w + p.x * radius, p.y * radius)
    fun bottom(p: Offset) = Offset(w + p.x * radius, h - p.y * radius)
    // The left cap is the right cap rotated 180° about the centre.
    fun rotated(p: Offset) = Offset(w - p.x, h - p.y)

    val noseRadius = flank.noseRadius * radius
    val noseRect = Rect(w - 2f * noseRadius, radius - noseRadius, w, radius + noseRadius)
    val startAngle = -90f + flank.joinDegrees
    val sweep = 180f - 2f * flank.joinDegrees

    val path = Path()
    fun cubic(a: Offset, b: Offset, c: Offset) = path.cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
    val p0 = top(flank.p0)
    path.moveTo(p0.x, p0.y)
    cubic(top(flank.p1), top(flank.p2), top(flank.p3))
    path.arcTo(noseRect, startAngle, sweep, forceMoveTo = false)
    cubic(bottom(flank.p2), bottom(flank.p1), bottom(flank.p0))
    rotated(top(flank.p0)).let { path.lineTo(it.x, it.y) }
    cubic(rotated(top(flank.p1)), rotated(top(flank.p2)), rotated(top(flank.p3)))
    val leftNose = Rect(0f, radius - noseRadius, 2f * noseRadius, radius + noseRadius)
    path.arcTo(leftNose, startAngle + 180f, sweep, forceMoveTo = false)
    cubic(rotated(bottom(flank.p2)), rotated(bottom(flank.p1)), rotated(bottom(flank.p0)))
    path.close()
    return path
}

/**
 * Top-right flank of the capsule in units of the half height, origin at the top-right vertex.
 * [p0]..[p3] is a cubic from the straight edge to the nose arc (centre `(-noseRadius, 1)`).
 */
internal data class AmberCapsuleFlank(
    val p0: Offset,
    val p1: Offset,
    val p2: Offset,
    val p3: Offset,
    val noseRadius: Float,
    val joinDegrees: Float,
) {
    val extent: Float get() = -p0.x
}

/**
 * Solves the flank so that P0..P2 lie on the edge (zero curvature where it leaves the edge)
 * and the end tangent/curvature equal the nose arc's at [joinDegrees] past its top.
 */
internal fun amberCapsuleFlank(
    // A nose exactly at the half height forces the G2 flank onto the semicircle (sub-pixel
    // difference, no visible easing); much smaller turns the pill into a spindle. 0.97 keeps
    // a semicircular end while the shoulder visibly eases off the straight edge.
    noseRadius: Float = 0.97f,
    joinDegrees: Float = 35f,
    leadFraction: Float = 0.4f,
): AmberCapsuleFlank {
    val phi = Math.toRadians(joinDegrees.toDouble())
    val sinPhi = kotlin.math.sin(phi).toFloat()
    val cosPhi = kotlin.math.cos(phi).toFloat()
    val end = Offset(-noseRadius + noseRadius * sinPhi, 1f - noseRadius * cosPhi)
    // End tangent (cosφ, sinφ); P2 is where that tangent line meets the edge.
    val tangentLength = end.y / sinPhi
    val p2 = Offset(end.x - tangentLength * cosPhi, 0f)
    // Cubic end curvature κ = 2/3 · |P2−P1| · sinφ / |P3−P2|²  ⇒  solve |P2−P1| for κ = 1/noseRadius.
    val controlGap = 1.5f * tangentLength * tangentLength / (noseRadius * sinPhi)
    val p1 = Offset(p2.x - controlGap, 0f)
    val p0 = Offset(p1.x - leadFraction * controlGap, 0f)
    return AmberCapsuleFlank(p0, p1, p2, end, noseRadius, joinDegrees)
}

internal val amberCapsuleFlank = amberCapsuleFlank()
