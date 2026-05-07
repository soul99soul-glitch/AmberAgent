package me.rerere.rikkahub.ui.pages.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Pulse Performance Token Usage hero card.
 *
 * Renders the concentric-ring token meter from the mockup at the top of
 * StatsPage: outer chartreuse arc encodes completion (output) tokens,
 * inner sport-orange arc encodes prompt (input) tokens. Both arcs are
 * scaled against the total of all four token streams (prompt + completion
 * + cached + a small idle budget) so they share one normalisation base
 * and the visual length of each arc reads as "share of total".
 *
 * Center hero numeral shows the GRAND TOTAL token count in
 * sport-orange (the "this is the headline number" treatment matching
 * Conversation List "04" and other Pulse hero numerics).
 *
 * Below the rings, a 3-column stat strip shows IN / OUT / CACHE token
 * counts as ALL-CAPS labels with the actual numbers below — same eyebrow
 * + numeric pattern Pulse uses everywhere for "metric blocks".
 *
 * Why two arcs and not one full ring per metric: a single full ring
 * doesn't communicate "share of total"; two stacked arcs at the same
 * geometric center but different radii give the eye an immediate sense
 * of "input vs output ratio" that bar-chart equivalents don't match at
 * this size. The mockup spec used the same two-ring layout for the same
 * reason.
 */
@Composable
fun TokenUsageHeroCard(
    promptTokens: Long,
    completionTokens: Long,
    cachedTokens: Long,
    modifier: Modifier = Modifier,
) {
    val total = (promptTokens + completionTokens + cachedTokens).coerceAtLeast(1L)
    // Normalise each metric against the total, so an arc's sweep angle
    // visually equals its share. Cap each fraction at 1.0 so a single
    // dominant metric doesn't paint past a full circle (extremely
    // unlikely given total = sum of all three, but defensive).
    val outFraction = (completionTokens.toFloat() / total).coerceIn(0f, 1f)
    val inFraction = (promptTokens.toFloat() / total).coerceIn(0f, 1f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "TOTAL TOKENS · LIFETIME",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.10.em,
                ),
            )
            Box(
                modifier = Modifier.size(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                ConcentricRings(
                    outerFraction = outFraction,
                    innerFraction = inFraction,
                    modifier = Modifier.size(180.dp),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatTokenCount(total),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.04).em,
                            lineHeight = 36.sp,
                        ),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "TOKENS",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.10.em,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    label = "OUT",
                    value = formatTokenCount(completionTokens),
                    accentChartreuse = true,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "IN",
                    value = formatTokenCount(promptTokens),
                    accentChartreuse = false,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "CACHED",
                    value = formatTokenCount(cachedTokens),
                    accentChartreuse = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConcentricRings(
    outerFraction: Float,
    innerFraction: Float,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val outerColor = MaterialTheme.colorScheme.primary    // chartreuse (completion / output)
    val innerColor = MaterialTheme.colorScheme.secondary  // sport-orange (prompt / input)

    Canvas(modifier = modifier) {
        val strokePx = 14.dp.toPx()
        val ringSpacing = 4.dp.toPx()

        // Outer ring track + arc
        val outerInset = strokePx / 2f
        val outerSize = Size(size.width - strokePx, size.height - strokePx)
        val outerTopLeft = Offset(outerInset, outerInset)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = outerTopLeft,
            size = outerSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        drawArc(
            color = outerColor,
            startAngle = -90f,
            sweepAngle = 360f * outerFraction,
            useCenter = false,
            topLeft = outerTopLeft,
            size = outerSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )

        // Inner ring track + arc
        val innerInset = strokePx + ringSpacing + strokePx / 2f
        val innerDiameter = size.width - 2 * (strokePx + ringSpacing)
        val innerSize = Size(innerDiameter, innerDiameter)
        val innerTopLeft = Offset(innerInset, innerInset)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        drawArc(
            color = innerColor,
            startAngle = -90f,
            sweepAngle = 360f * innerFraction,
            useCenter = false,
            topLeft = innerTopLeft,
            size = innerSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    accentChartreuse: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (accentChartreuse) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (accentChartreuse) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.10.em,
                ),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.02).em,
                ),
            )
        }
    }
}

/**
 * Compact token-count formatter: large numbers collapse to K / M with one
 * decimal place, small numbers render as raw integers. Avoids dragging in
 * a full numeric formatter dependency for what's essentially a label.
 */
private fun formatTokenCount(value: Long): String {
    if (value < 1_000) return value.toString()
    if (value < 1_000_000) {
        val thousands = value / 1_000.0
        return "%.1fK".format(thousands)
    }
    val millions = value / 1_000_000.0
    return "%.1fM".format(millions)
}
