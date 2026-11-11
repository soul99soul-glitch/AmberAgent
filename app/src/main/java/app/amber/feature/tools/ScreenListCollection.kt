package app.amber.feature.tools

import kotlinx.coroutines.delay
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.core.automation.ScreenNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded list observations and scroll receipts, never a complete-list claim. */
internal class ScreenListCollection(private val operations: ScreenOperationOwner) {

    /**
     * 只读列表采集：在唯一滚动容器里逐屏读取可见行，仅按"两屏都唯一的稳定 id"
     * 合并相邻页重叠；覆盖范围只代表已观察屏幕，绝不宣称列表完整。
     */
    fun screenCollectListTool(runId: String? = null) = Tool(
        name = "screen_collect_list",
        description = "Read visible rows of one scrollable list, swiping screen by screen, and return " +
            "them in reading order. Only stable identifiers seen uniquely on both adjacent screens are " +
            "merged as overlap; anonymous rows are kept as-is and may repeat. Coverage always means " +
            "'observed screens only' — it never claims the list is complete. Never taps a row. Hands " +
            "back on ambiguity, no progress, app switch, truncation or budget exhaustion.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("container", screenStringProp("Exact text or label of the list's scroll container."))
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("up"); add("down") })
                        put("description", "Finger movement direction.")
                    })
                    put("max_swipes", screenIntegerProp("Swipe budget 0..8. Defaults to 3; 0 reads the current screen only."))
                    put("max_items", screenIntegerProp("Item cap 1..200. Defaults to 50."))
                },
                required = listOf("container", "direction"),
            )
        },
        needsApproval = true,
        allowsAutoApproval = false,
        execute = { input ->
            operations.trackScreenTool(
                "screen_collect_list",
                "读取列表",
                input,
                runId = runId,
                enforceScreenStop = false,
            ) {
                val containerLabel = input.requiredString("container")
                val direction = input.string("direction") ?: "down"
                val maxSwipes = (input.int("max_swipes") ?: 3).coerceIn(0, MAX_COLLECT_SWIPES)
                val maxItems = (input.int("max_items") ?: 50).coerceIn(1, 200)
                val service = requireScreenAccessibility()
                val deadline = System.currentTimeMillis() + COLLECT_BUDGET_MS
                val items = mutableListOf<kotlinx.serialization.json.JsonObject>()
                // 上一屏的行身份；只有"两屏都唯一且完全一致"的行才视为重叠合并。
                // 不能用 viewId 去重：同一列表所有行共用同一个资源 id。
                var previousScreenRows: Set<String> = emptySet()
                var swipeCount = 0
                var stopReason = "max_swipes"
                var lastSignature: String? = null
                var packageName: String? = null

                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (System.currentTimeMillis() >= deadline) { stopReason = "budget_exhausted"; break }
                    val snapshot = withContext(Dispatchers.Main.immediate) { service.captureScreenSnapshot() }
                        ?: run { stopReason = "screen_unavailable"; break }
                    packageName = packageName ?: snapshot.packageName
                    if (snapshot.packageName != packageName) { stopReason = "foreground_package_changed"; break }
                    val containers = snapshot.nodes.filter { it.scrollable && it.label == containerLabel }
                    if (containers.size != 1) {
                        stopReason = if (containers.isEmpty()) "container_not_found" else "container_ambiguous"
                        break
                    }
                    val container = containers.single()
                    val rows = snapshot.nodes
                        .filter { it.ref.startsWith("${container.ref}/") && it.hasOwnLabel && it.label.isNotBlank() }
                        .sortedWith(compareBy({ it.top }, { it.left }))
                    // 行身份 = 文本 + 位置。同一资源 id 会被所有行共享，不能用它判断重复。
                    fun rowKey(node: app.amber.core.automation.ScreenNode): String =
                        "${node.label}|${node.top}|${node.bottom}|${node.left}|${node.right}"
                    val currentKeys = rows.map(::rowKey).toSet()
                    for (row in rows) {
                        if (items.size >= maxItems) { stopReason = "max_items"; break }
                        // 只有相邻两屏都出现的同一行才算滚动重叠；同名不同位置的行各自保留。
                        if (swipeCount > 0 && rowKey(row) in previousScreenRows) continue
                        items += buildJsonObject {
                            put("label", row.label.take(400))
                            if (row.viewId.isNotBlank()) put("identifier", row.viewId)
                            put("screen", swipeCount)
                        }
                    }
                    previousScreenRows = currentKeys
                    if (stopReason == "max_items") break

                    val signature = rows.joinToString("|") { "${it.viewId}:${it.label}" }
                    if (signature == lastSignature) { stopReason = "no_progress"; break }
                    lastSignature = signature
                    if (swipeCount >= maxSwipes) { stopReason = "max_swipes"; break }

                    val receipt = withContext(Dispatchers.Main.immediate) {
                        service.performScreenAction(
                            snapshot,
                            app.amber.core.automation.ScreenAction(
                                kind = if (direction == "up") {
                                    app.amber.core.automation.ScreenActionKind.SCROLL_BACKWARD
                                } else {
                                    app.amber.core.automation.ScreenActionKind.SCROLL_FORWARD
                                },
                                ref = container.ref,
                            ),
                        )
                    }
                    if (!receipt.dispatched) { stopReason = "scroll_${receipt.status}"; break }
                    swipeCount++
                    // 界面确实变了但没有新行：仍算前进（可能只是整屏重复），继续到滑动预算。
                    delay(SCROLL_SETTLE_MS)
                }

                textJson {
                    put("items", buildJsonArray { items.forEach { add(it) } })
                    put("count", items.size)
                    put("swipe_count", swipeCount)
                    put("stop_reason", stopReason)
                    put("coverage", "observed_screens_only")
                }
            }
        },
    )
}

private const val MAX_COLLECT_SWIPES = 8
private const val COLLECT_BUDGET_MS = 60_000L

/** 一次滑动后等界面稳定的时间；稳定不等于业务成功，仅用于下一屏观察。 */
private const val SCROLL_SETTLE_MS = 350L
