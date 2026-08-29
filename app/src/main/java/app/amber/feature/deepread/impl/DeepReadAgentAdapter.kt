package app.amber.feature.deepread.impl

import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentHandler
import app.amber.feature.deepread.api.DeepReadArtifact
import app.amber.feature.deepread.api.DeepReadDescriptor
import app.amber.feature.deepread.api.DeepReadEventPayload
import app.amber.feature.deepread.api.DeepReadInput
import app.amber.feature.board.hotlist.deepread.DeepReadAgentRunManager
import app.amber.feature.board.hotlist.deepread.DeepReadGenerationStage
import app.amber.feature.board.hotlist.deepread.DeepReadSectionStatus

class DeepReadAgentAdapter(
    private val runManager: DeepReadAgentRunManager,
) : Agent<DeepReadInput, DeepReadArtifact> {

    override val descriptor: AgentDescriptor = DeepReadDescriptor.value

    override val handler = AgentHandler<DeepReadInput, DeepReadArtifact> { input, scope ->
        scope.events.commit(
            DeepReadEventPayload.GenerationPhaseChanged(phase = "collecting")
        )

        val output = try {
            when {
                input.stages.isEmpty() -> runManager.run(
                    topicId = input.topicId,
                    topicTitle = input.title,
                    seedUrl = input.url.ifBlank { null },
                    force = input.force,
                    deferMissingStages = input.deferMissingStages,
                    propagateFailuresWithPartial = input.propagateFailuresWithPartial,
                    // Step 5: thread the run scope's identity + protocol event
                    // writer so kernel rounds carry the durable audit trail
                    // (gated by the kernel's durable-path check).
                    runId = scope.runId.value,
                    events = scope.events,
                ).getOrThrow()

                input.stages.size == 1 -> runManager.runSection(
                    topicId = input.topicId,
                    topicTitle = input.title,
                    stage = DeepReadGenerationStage.valueOf(input.stages.single()),
                    seedUrl = input.url.ifBlank { null },
                    propagateFailuresWithPartial = input.propagateFailuresWithPartial,
                    runId = scope.runId.value,
                    events = scope.events,
                ).getOrThrow()

                else -> error("DeepRead agent supports at most one explicit stage, got ${input.stages}")
            }
        } catch (e: Exception) {
            scope.events.commitError(e, recoverable = false)
            throw e
        }

        scope.events.commit(
            DeepReadEventPayload.GenerationPhaseChanged(
                phase = output.generationPhase.name.lowercase()
            )
        )

        output.sectionStates.forEach { (stage, state) ->
            if (state.status == DeepReadSectionStatus.READY) {
                scope.events.commit(
                    DeepReadEventPayload.SectionCompleted(
                        stage = stage.name,
                        heading = stage.name,
                        contentPreview = "",
                        quality = (output.sectionQualities[stage]?.name ?: "BASIC").lowercase(),
                    )
                )
            }
        }

        DeepReadArtifact(
            summary = output.summary,
            topicType = output.topicType,
            sectionCount = output.sectionStates.count { it.value.status == DeepReadSectionStatus.READY },
            generationComplete = output.generationComplete,
        )
    }
}
