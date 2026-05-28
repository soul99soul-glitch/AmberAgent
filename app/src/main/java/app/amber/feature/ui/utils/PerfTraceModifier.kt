package app.amber.feature.ui.utils

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import me.rerere.rikkahub.BuildConfig

private const val AmberPerfTag = "AmberChatPerf"
private const val SlowMeasureThresholdMs = 8L

internal fun Modifier.amberTraceMeasure(section: String): Modifier {
    if (!BuildConfig.DEBUG) return this
    return layout { measurable, constraints ->
        Trace.beginSection(section)
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val placeable = try {
            measurable.measure(constraints)
        } finally {
            Trace.endSection()
        }
        val measureNanos = SystemClock.elapsedRealtimeNanos() - startNanos
        if (
            measureNanos >= SlowMeasureThresholdMs * 1_000_000L &&
            Log.isLoggable(AmberPerfTag, Log.DEBUG)
        ) {
            Log.w(
                AmberPerfTag,
                buildString {
                    append("SLOW_MEASURE section=")
                    append(section)
                    append(" ms=")
                    append(formatTenths(measureNanos))
                    append(" constraints=")
                    append(constraints.minWidth)
                    append('x')
                    append(constraints.minHeight)
                    append("..")
                    append(constraints.maxWidth)
                    append('x')
                    append(constraints.maxHeight)
                    append(" result=")
                    append(placeable.width)
                    append('x')
                    append(placeable.height)
                },
            )
        }
        layout(placeable.width, placeable.height) {
            Trace.beginSection("$section place")
            try {
                placeable.placeRelative(0, 0)
            } finally {
                Trace.endSection()
            }
        }
    }
}

private fun formatTenths(nanos: Long): String {
    val tenths = (nanos + 50_000L) / 100_000L
    return "${tenths / 10}.${tenths % 10}"
}
