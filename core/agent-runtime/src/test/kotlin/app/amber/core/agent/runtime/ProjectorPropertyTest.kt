package app.amber.core.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

data class ProjectedMessage(
    val messageId: String,
    val nodeId: String,
    val content: String,
    val isRegenerate: Boolean,
)

data class FinalEvent(
    val seq: Long,
    val messageId: String,
    val nodeId: String,
    val content: String,
    val regenerateOf: String?,
)

fun project(events: List<FinalEvent>): Map<String, ProjectedMessage> {
    val messages = mutableMapOf<String, ProjectedMessage>()
    for (event in events.sortedBy { it.seq }) {
        messages[event.messageId] = ProjectedMessage(
            messageId = event.messageId,
            nodeId = event.nodeId,
            content = event.content,
            isRegenerate = event.regenerateOf != null,
        )
    }
    return messages
}

class ProjectorPropertyTest : FunSpec({

    val eventArb = Arb.int(1..100).map { seq ->
        FinalEvent(
            seq = seq.toLong(),
            messageId = "msg_${seq % 5}",
            nodeId = "node_${seq % 3}",
            content = "content_$seq",
            regenerateOf = if (seq % 7 == 0) "msg_${(seq - 1) % 5}" else null,
        )
    }

    test("determinism: project(events) == project(events) for any sequence") {
        checkAll(100, Arb.list(eventArb, 1..50)) { events ->
            project(events) shouldBe project(events)
        }
    }

    test("idempotency: project(events) == project(events + events.tail)") {
        checkAll(100, Arb.list(eventArb, 2..50)) { events ->
            val tail = events.drop(1)
            project(events) shouldBe project(events + tail)
        }
    }

    test("truncation safety: partial projection keys are subset of full projection keys") {
        checkAll(100, Arb.list(eventArb, 3..50)) { events ->
            val n = events.size / 2
            val partial = project(events.take(n))
            val full = project(events)
            for (key in partial.keys) {
                (key in full) shouldBe true
            }
        }
    }

    test("regenerate preserves all candidates in node") {
        checkAll(100, Arb.list(eventArb, 2..30)) { events ->
            val projected = project(events)
            val nodeGroups = projected.values.groupBy { it.nodeId }
            for ((_, messages) in nodeGroups) {
                messages.map { it.messageId }.toSet().size shouldBe messages.size
            }
        }
    }
})
