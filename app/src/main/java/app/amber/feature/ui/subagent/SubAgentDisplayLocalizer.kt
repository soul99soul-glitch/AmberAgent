package app.amber.feature.ui.subagent

import android.content.Context
import androidx.annotation.StringRes
import app.amber.agent.R
import app.amber.feature.subagent.SubAgentDefinition
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentDisplay
import app.amber.feature.subagent.SubAgentDisplayLocalizer

/**
 * Resolves built-in subagent metadata through the app's localized resources.
 *
 * A custom definition is always returned verbatim. The model-facing [SubAgentDefinition],
 * including its id and system prompt, is never modified.
 */
class AppSubAgentDisplayLocalizer(
    private val context: Context,
) : SubAgentDisplayLocalizer {
    override fun localize(definition: SubAgentDefinition): SubAgentDisplay {
        val resources = DISPLAY_RESOURCES[definition.id]
        if (resources == null || definition.id !in SubAgentDefinitions.builtInIds) {
            return SubAgentDisplay.from(definition)
        }
        return SubAgentDisplay(
            name = context.getString(resources.name),
            description = context.getString(resources.description),
            routingHint = context.getString(resources.routingHint),
            phaseLabels = resources.phaseLabels.map { context.getString(it) },
        )
    }
}

private data class SubAgentDisplayResources(
    @StringRes val name: Int,
    @StringRes val description: Int,
    @StringRes val routingHint: Int,
    @StringRes val phaseLabels: List<Int>,
)

/** Stable ids are the only lookup keys; resource values carry the active app locale. */
private val DISPLAY_RESOURCES = mapOf(
    "explorer" to SubAgentDisplayResources(
        name = R.string.subagent_explorer_name,
        description = R.string.subagent_explorer_description,
        routingHint = R.string.subagent_explorer_routing,
        phaseLabels = listOf(
            R.string.subagent_explorer_phase_scan,
            R.string.subagent_explorer_phase_browse,
            R.string.subagent_explorer_phase_organize,
        ),
    ),
    "historian" to SubAgentDisplayResources(
        name = R.string.subagent_historian_name,
        description = R.string.subagent_historian_description,
        routingHint = R.string.subagent_historian_routing,
        phaseLabels = listOf(
            R.string.subagent_historian_phase_archive,
            R.string.subagent_historian_phase_compare,
            R.string.subagent_historian_phase_chronicle,
        ),
    ),
    "oracle" to SubAgentDisplayResources(
        name = R.string.subagent_oracle_name,
        description = R.string.subagent_oracle_description,
        routingHint = R.string.subagent_oracle_routing,
        phaseLabels = listOf(
            R.string.subagent_oracle_phase_examine,
            R.string.subagent_oracle_phase_weigh,
            R.string.subagent_oracle_phase_decide,
        ),
    ),
    "designer" to SubAgentDisplayResources(
        name = R.string.subagent_designer_name,
        description = R.string.subagent_designer_description,
        routingHint = R.string.subagent_designer_routing,
        phaseLabels = listOf(
            R.string.subagent_designer_phase_compose,
            R.string.subagent_designer_phase_color,
            R.string.subagent_designer_phase_polish,
        ),
    ),
    "writer" to SubAgentDisplayResources(
        name = R.string.subagent_writer_name,
        description = R.string.subagent_writer_description,
        routingHint = R.string.subagent_writer_routing,
        phaseLabels = listOf(
            R.string.subagent_writer_phase_ideate,
            R.string.subagent_writer_phase_draft,
            R.string.subagent_writer_phase_tune,
            R.string.subagent_writer_phase_close,
        ),
    ),
    "fixer" to SubAgentDisplayResources(
        name = R.string.subagent_fixer_name,
        description = R.string.subagent_fixer_description,
        routingHint = R.string.subagent_fixer_routing,
        phaseLabels = listOf(
            R.string.subagent_fixer_phase_decompose,
            R.string.subagent_fixer_phase_process,
            R.string.subagent_fixer_phase_output,
        ),
    ),
)
