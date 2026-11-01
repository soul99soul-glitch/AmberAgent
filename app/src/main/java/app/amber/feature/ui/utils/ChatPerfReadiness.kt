package app.amber.feature.ui.utils

/**
 * Target of a measured history entry.
 *
 * [MESSAGE] means a real message body (the newest settled node). [EMPTY_HERO]
 * is the explicit S0 contract: there is no message body, so the empty-state
 * hero text is the visible target instead. A screen-level draw is never
 * reported as [MESSAGE] readiness.
 */
internal enum class ChatPerfTargetKind {
    MESSAGE,
    EMPTY_HERO,
}

internal data class ChatPerfReadinessSample(
    /** A real draw pass reached the measured layer. */
    val draw: Boolean,
    /** Alpha of the measured layer at that draw. */
    val alpha: Float,
    /** The target's lazy items intersect the viewport. */
    val visible: Boolean,
    /** Every composed Markdown document of the target is formatted (no placeholder). */
    val formatted: Boolean,
    /** Every composed target image has settled (success or terminal error). */
    val mediaSettled: Boolean,
)

internal enum class ChatPerfReadinessDecision {
    NONE,
    CONTENT,
    CONTENT_AND_READABLE,
    READABLE,
}

/**
 * Pure readiness gate. It separates two contracts that the old implementation
 * conflated:
 *
 * - content draw: target content is formatted and visibly laid out.
 * - readable draw: the same content is drawn at the route's visible alpha
 *   (>= [READABLE_ALPHA]) and all composed target images have settled.
 *
 * Offscreen, placeholder, alpha 0 / 0.94 and pending media samples therefore
 * cannot produce either marker.
 */
internal class ChatPerfReadinessGate(
    private val readableAlpha: Float = READABLE_ALPHA,
) {
    var kind: ChatPerfTargetKind? = null
        private set

    private var contentRecorded = false
    private var readableRecorded = false

    fun reset(targetKind: ChatPerfTargetKind) {
        kind = targetKind
        contentRecorded = false
        readableRecorded = false
    }

    fun clear() {
        kind = null
        contentRecorded = false
        readableRecorded = false
    }

    fun onSample(sample: ChatPerfReadinessSample): ChatPerfReadinessDecision {
        val target = kind ?: return ChatPerfReadinessDecision.NONE
        if (!sample.draw || !sample.visible || !sample.formatted) {
            return ChatPerfReadinessDecision.NONE
        }
        val contentNow = !contentRecorded
        val readableNow = !readableRecorded &&
            sample.alpha >= readableAlpha &&
            (target == ChatPerfTargetKind.EMPTY_HERO || sample.mediaSettled)
        if (contentNow) contentRecorded = true
        if (readableNow) readableRecorded = true
        return when {
            contentNow && readableNow -> ChatPerfReadinessDecision.CONTENT_AND_READABLE
            readableNow -> ChatPerfReadinessDecision.READABLE
            contentNow -> ChatPerfReadinessDecision.CONTENT
            else -> ChatPerfReadinessDecision.NONE
        }
    }

    companion object {
        /** Plan section 3: one uniform visible-alpha threshold for Treadable. */
        const val READABLE_ALPHA = 0.95f
    }
}

/**
 * Target-generation/visibility state. A visibility report from before the
 * current target was initialized must not flip the new target visible.
 */
internal class ChatPerfVisibilityGate {
    var generation: Int = 0
        private set
    var visible: Boolean = false
        private set

    fun beginTarget(initialVisible: Boolean = false): Int {
        generation++
        visible = initialVisible
        return generation
    }

    fun report(generationAtReport: Int, value: Boolean): Boolean {
        if (generationAtReport != generation) return false
        if (visible == value) return false
        visible = value
        return true
    }
}

/**
 * Per-run readiness sources reported by the target message composition.
 *
 * An empty Markdown map means "nothing has reported formatted content yet" and
 * therefore is *not* ready (a missing probe cannot fake readiness). Media is
 * different: an empty map means the target has no images.
 */
internal class ChatPerfReadinessTracker {
    private val markdownPending = LinkedHashMap<String, LinkedHashMap<Int, Boolean>>()
    private val mediaPending = LinkedHashMap<String, LinkedHashMap<Int, Boolean>>()

    fun clear() {
        markdownPending.clear()
        mediaPending.clear()
    }

    @Synchronized
    fun markMarkdown(nodeKey: String, token: Int, pending: Boolean): Boolean {
        val previous = markdownPending.getOrPut(nodeKey) { LinkedHashMap() }.put(token, pending)
        return previous != pending
    }

    @Synchronized
    fun removeMarkdown(nodeKey: String, token: Int): Boolean {
        val map = markdownPending[nodeKey] ?: return false
        val changed = map.remove(token) != null
        if (map.isEmpty()) markdownPending.remove(nodeKey)
        return changed
    }

    @Synchronized
    fun markMedia(nodeKey: String, token: Int, pending: Boolean): Boolean {
        val previous = mediaPending.getOrPut(nodeKey) { LinkedHashMap() }.put(token, pending)
        return previous != pending
    }

    @Synchronized
    fun removeMedia(nodeKey: String, token: Int): Boolean {
        val map = mediaPending[nodeKey] ?: return false
        val changed = map.remove(token) != null
        if (map.isEmpty()) mediaPending.remove(nodeKey)
        return changed
    }

    /** No probe for the target means "not formatted", never fake-ready. */
    @Synchronized
    fun formatted(nodeKey: String): Boolean {
        val pending = markdownPending[nodeKey] ?: return false
        return pending.isNotEmpty() && pending.values.none { it }
    }

    /** No image reported for the target means the target has no composed image. */
    @Synchronized
    fun mediaSettled(nodeKey: String): Boolean =
        mediaPending[nodeKey]?.values?.none { it } ?: true
}
