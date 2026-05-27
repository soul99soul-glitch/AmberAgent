package app.amber.feature.deepread.impl

import app.amber.core.agent.runtime.Agent
import app.amber.core.agent.runtime.AgentDescriptor
import app.amber.core.agent.runtime.AgentHandler
import app.amber.core.agent.runtime.RunScope
import app.amber.feature.deepread.api.DeepReadArtifact
import app.amber.feature.deepread.api.DeepReadDescriptor
import app.amber.feature.deepread.api.DeepReadEventPayload
import app.amber.feature.deepread.api.DeepReadInput
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadAgentRunManager
import me.rerere.rikkahub.data.agent.board.hotlist.deepread.DeepReadGenerationPhase

class DeepReadAgentAdapter(
    private val runManager: DeepReadAgentRunManager,
) : Agent<DeepReadInput, DeepReadArtifact> {

    override val descriptor: AgentDescriptor = DeepReadDescriptor.value

    override val handler = AgentHandler<DeepReadInput, DeepReadArtifact> { input, scope ->
        scope.events.commit(
            DeepReadEventPayload.GenerationPhaseChanged(phase = "collecting")
        )

        val result = runManager.run(
            topicId = input.topicId,
            topicTitle = input.title,
            seedUrl = input.url,
            force = input.force,
        )

        val output = result.getOrThrow()

        scope.events.commit(
            DeepReadEventPayload.GenerationPhaseChanged(
                phase = output.generationPhase.name.lowercase()
            )
        )

        output.sectionStates.forEach { (stage, state) ->
            if (state.status.name == "READY") {
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
            sectionCount = output.sectionStates.count { it.value.status.name == "READY" },
            generationComplete = output.generationComplete,
        )
    }
}
