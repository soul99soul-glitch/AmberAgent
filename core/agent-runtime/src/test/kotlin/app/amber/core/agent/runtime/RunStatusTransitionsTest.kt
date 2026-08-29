package app.amber.core.agent.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Assert.assertThrows

class RunStatusTransitionsTest : FunSpec({

    test("every terminal state is write-once") {
        for (terminal in RunStatus.TERMINAL_STATES) {
            terminal.isTerminal shouldBe true
            for (to in RunStatus.entries) {
                if (to == terminal) continue
                RunStatusTransitions.canTransition(terminal, to) shouldBe false
            }
        }
    }

    test("STEP_LIMIT can never become COMPLETED") {
        RunStatusTransitions.canTransition(RunStatus.STEP_LIMIT, RunStatus.COMPLETED) shouldBe false
        assertThrows(IllegalRunTransitionException::class.java) {
            RunStatusTransitions.requireLegal(RunStatus.STEP_LIMIT, RunStatus.COMPLETED)
        }
    }

    test("running reaches every pause and terminal state") {
        val targets = setOf(
            RunStatus.WAITING_USER,
            RunStatus.WAITING_EXTERNAL,
            RunStatus.RESUMABLE,
            RunStatus.OUTCOME_UNKNOWN,
            RunStatus.COMPLETED,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.INTERRUPTED,
            RunStatus.STEP_LIMIT,
        )
        for (to in targets) {
            RunStatusTransitions.canTransition(RunStatus.RUNNING, to) shouldBe true
        }
    }

    test("pauses resume to running") {
        for (pause in RunStatus.PAUSE_STATES) {
            pause.isTerminal shouldBe false
            RunStatusTransitions.canTransition(pause, RunStatus.RUNNING) shouldBe true
        }
    }

    test("pauses can be cancelled but never completed directly") {
        for (pause in RunStatus.PAUSE_STATES) {
            RunStatusTransitions.canTransition(pause, RunStatus.CANCELLED) shouldBe true
            RunStatusTransitions.canTransition(pause, RunStatus.COMPLETED) shouldBe false
            RunStatusTransitions.canTransition(pause, RunStatus.STEP_LIMIT) shouldBe false
        }
    }

    test("created only starts or dies") {
        RunStatusTransitions.canTransition(RunStatus.CREATED, RunStatus.RUNNING) shouldBe true
        RunStatusTransitions.canTransition(RunStatus.CREATED, RunStatus.CANCELLED) shouldBe true
        RunStatusTransitions.canTransition(RunStatus.CREATED, RunStatus.COMPLETED) shouldBe false
        RunStatusTransitions.canTransition(RunStatus.CREATED, RunStatus.WAITING_USER) shouldBe false
    }

    test("self transition is an allowed no-op for every state") {
        for (state in RunStatus.entries) {
            RunStatusTransitions.canTransition(state, state) shouldBe true
        }
    }

    test("outcome unknown settles by retry, abandon or cancel only") {
        RunStatusTransitions.canTransition(RunStatus.OUTCOME_UNKNOWN, RunStatus.RUNNING) shouldBe true
        RunStatusTransitions.canTransition(RunStatus.OUTCOME_UNKNOWN, RunStatus.FAILED) shouldBe true
        RunStatusTransitions.canTransition(RunStatus.OUTCOME_UNKNOWN, RunStatus.CANCELLED) shouldBe true
        RunStatusTransitions.canTransition(RunStatus.OUTCOME_UNKNOWN, RunStatus.COMPLETED) shouldBe false
    }

    test("live states are exactly the non-terminal states") {
        RunStatus.LIVE_STATES shouldBe RunStatus.entries.filterTo(mutableSetOf()) { !it.isTerminal }
    }

    test("wire names round-trip through parse") {
        for (state in RunStatus.entries) {
            RunStatus.parse(state.wireName) shouldBe state
        }
    }

    test("parse accepts the pre-protocol kernel alias") {
        RunStatus.parse("awaiting_permission") shouldBe RunStatus.WAITING_USER
        RunStatus.parse("AWAITING_PERMISSION") shouldBe RunStatus.WAITING_USER
    }

    test("parse is fail-closed on unknown wire names") {
        RunStatus.parse("") shouldBe null
        RunStatus.parse("banana") shouldBe null
        RunStatus.parse("deleted") shouldBe null
    }
})
