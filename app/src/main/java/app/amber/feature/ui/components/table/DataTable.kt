@file:Suppress("unused")

package app.amber.feature.ui.components.table

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.components.richtext.MarkdownBlock
import kotlin.math.max

/**
 * DataTable（自定义布局 + 横向滚动 + 行内等高）
 * - 使用 SubcomposeLayout 两阶段测量，避免 Lookahead 下的重复测量异常
 * - 高度自适应内容（不提供纵向滚动）
 * - 宽度可超出视口，外层内置 horizontalScroll
 */
@Composable
fun DataTable(
    headers: List<@Composable () -> Unit>,
    rows: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 4.dp,
    cellBorder: BorderStroke? = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    headerBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    zebraStriping: Boolean = false,
    columnMinWidths: List<Dp> = emptyList(),
    columnMaxWidths: List<Dp> = emptyList(),
    cellAlignment: Alignment = Alignment.CenterStart,
) {
    val hScroll = rememberScrollState()
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.small)
            .horizontalScroll(hScroll)
    ) {
        SubcomposeLayout { constraints ->
            val columnCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0)
            val rowCount = rows.size
            if (columnCount == 0) return@SubcomposeLayout layout(0, 0) {}

            // ---------- 参数 & 中间结果容器 ----------
            val infinity = Constraints.Infinity
            val unbounded = Constraints(0, infinity, 0, infinity)
            val minWidthsPx = IntArray(columnCount) { i -> columnMinWidths.getOrNull(i)?.roundToPx() ?: 0 }
            val maxWidthsPx = IntArray(columnCount) { i -> columnMaxWidths.getOrNull(i)?.roundToPx() ?: Int.MAX_VALUE }
            val colWidths = IntArray(columnCount) { 0 }
            val headerContent = arrayOfNulls<Placeable>(columnCount)
            val bodyContent = arrayOfNulls<Placeable>(rowCount * columnCount)

            // ---------- 内容只测量一次：估列宽、算行高 ----------
            fun subcomposeHeaderContent(c: Int): Placeable {
                val measurables = subcompose("h1_$c") {
                    CellContentBox(
                        padding = cellPadding,
                        alignment = cellAlignment,
                    ) {
                        headers.getOrNull(c)?.invoke()
                    }
                }
                val constraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                    Constraints(0, maxWidthsPx[c], 0, infinity)
                } else {
                    unbounded
                }
                val p = measurables.first().measure(constraints)
                colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
                return p
            }

            fun subcomposeBodyContent(r: Int, c: Int): Placeable {
                val measurables = subcompose("b1_${r}_$c") {
                    CellContentBox(
                        padding = cellPadding,
                        alignment = cellAlignment,
                    ) {
                        rows[r].getOrNull(c)?.invoke()
                    }
                }
                val constraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                    Constraints(0, maxWidthsPx[c], 0, infinity)
                } else {
                    unbounded
                }
                val p = measurables.first().measure(constraints)
                colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
                return p
            }

            for (c in 0 until columnCount) headerContent[c] = subcomposeHeaderContent(c)
            for (r in 0 until rowCount) for (c in 0 until columnCount) bodyContent[r * columnCount + c] =
                subcomposeBodyContent(r, c)

            val rowHeights = IntArray(rowCount) { r ->
                var h = 0
                for (c in 0 until columnCount) {
                    h = max(h, bodyContent[r * columnCount + c]!!.height)
                }
                h
            }
            val headerHeight = headerContent.maxOf { it?.height ?: 0 }

            val tableWidth = colWidths.sum()
            val tableHeight = headerHeight + rowHeights.sum()
            val finalWidth = tableWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
            val finalHeight = tableHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
            val currentLayoutDirection = layoutDirection
            val tableFrame = subcompose("table_frame") {
                TableFrame(
                    columnWidths = colWidths.copyOf(),
                    rowHeights = rowHeights,
                    headerHeight = headerHeight,
                    border = cellBorder,
                    headerBackground = headerBackground,
                    zebraStriping = zebraStriping,
                    zebraBackground = surfaceContainer,
                )
            }.first().measure(Constraints.fixed(tableWidth, tableHeight))

            // ---------- 放置 ----------
            layout(finalWidth, finalHeight) {
                tableFrame.placeRelative(0, 0)

                fun Placeable.placeAligned(x: Int, y: Int, cellWidth: Int, cellHeight: Int) {
                    val offset = cellAlignment.align(
                        size = IntSize(width, height),
                        space = IntSize(cellWidth, cellHeight),
                        layoutDirection = currentLayoutDirection,
                    )
                    placeRelative(x + offset.x, y + offset.y)
                }

                var x = 0
                for (c in 0 until columnCount) {
                    headerContent[c]?.placeAligned(x, 0, colWidths[c], headerHeight)
                    x += colWidths[c]
                }
                var y = headerHeight
                for (r in 0 until rowCount) {
                    x = 0
                    for (c in 0 until columnCount) {
                        bodyContent[r * columnCount + c]?.placeAligned(x, y, colWidths[c], rowHeights[r])
                        x += colWidths[c]
                    }
                    y += rowHeights[r]
                }
            }
        }
    }
}

@Composable
private fun CellContentBox(
    padding: Dp,
    alignment: Alignment,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.padding(padding),
        contentAlignment = alignment,
    ) {
        content()
    }
}

@Composable
private fun TableFrame(
    columnWidths: IntArray,
    rowHeights: IntArray,
    headerHeight: Int,
    border: BorderStroke?,
    headerBackground: Color,
    zebraStriping: Boolean,
    zebraBackground: Color,
) {
    Box(
        modifier = Modifier
            .drawBehind {
                val tableWidth = columnWidths.sum().toFloat()
                if (headerHeight > 0) {
                    drawRect(
                        color = headerBackground,
                        size = Size(tableWidth, headerHeight.toFloat()),
                    )
                }
                if (zebraStriping) {
                    var y = headerHeight.toFloat()
                    rowHeights.forEachIndexed { index, height ->
                        if (index % 2 == 1 && height > 0) {
                            drawRect(
                                color = zebraBackground,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, y),
                                size = Size(tableWidth, height.toFloat()),
                            )
                        }
                        y += height
                    }
                }
                if (border != null) {
                    val strokeWidth = border.width.toPx()
                    var x = 0f
                    drawLine(border.brush, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = strokeWidth)
                    columnWidths.forEach { width ->
                        x += width
                        drawLine(border.brush, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = strokeWidth)
                    }

                    var y = 0f
                    drawLine(border.brush, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(tableWidth, y), strokeWidth = strokeWidth)
                    y += headerHeight
                    drawLine(border.brush, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(tableWidth, y), strokeWidth = strokeWidth)
                    rowHeights.forEach { height ->
                        y += height
                        drawLine(border.brush, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(tableWidth, y), strokeWidth = strokeWidth)
                    }
                }
            },
    )
}

// -------------------- 示例 --------------------
@Preview(showBackground = true)
@Composable
private fun DataTablePreview() {
    Surface {
        val headers = listOf<@Composable () -> Unit>(
            { Text("Semester", style = MaterialTheme.typography.labelLarge) },
            { Text("Attendance", style = MaterialTheme.typography.labelLarge) },
            { Text("Notes / Example", style = MaterialTheme.typography.labelLarge) },
        )

        val rows = listOf<List<@Composable () -> Unit>>(
            listOf<@Composable () -> Unit>(
                { Text("Fall 2024") },
                { Text("Excellent", style = MaterialTheme.typography.bodyMedium) },
                { Text("x² + y² = 1") },
            ),
            listOf(
                { Text("Fall 2024") },
                { Text("Good", style = MaterialTheme.typography.bodyMedium) },
                { Text("∑ k = n(n+1)/2", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            ),
            listOf(
                { Text("Fall 2024") },
                { Text("Fair", style = MaterialTheme.typography.bodyMedium) },
                { MarkdownBlock("这行更高会把整行拉齐! 这是一个很长的文本用来测试换行功能!  \n>haha") },
            ),
        )

        DataTable(
            headers = headers,
            rows = rows,
            columnMinWidths = listOf(60.dp, 100.dp, 80.dp),
            columnMaxWidths = listOf(120.dp, 100.dp, 200.dp),
            zebraStriping = false,
        )
    }
}
