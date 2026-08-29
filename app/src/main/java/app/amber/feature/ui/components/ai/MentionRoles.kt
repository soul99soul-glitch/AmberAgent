package app.amber.feature.ui.components.ai

import app.amber.feature.subagent.SubAgentDefinition
import app.amber.feature.subagent.SubAgentDefinitions
import app.amber.feature.subagent.SubAgentDisplay
import app.amber.feature.subagent.SubAgentDisplayLocalizer
import app.amber.feature.subagent.SubAgentMode

internal enum class MentionRoleKind {
    SUBAGENT,
    COUNCIL,
}

internal data class MentionRoleItem(
    val id: String,
    val name: String,
    val description: String,
    val kind: MentionRoleKind,
)

internal fun buildMentionRoleItems(
    subAgentEnabled: Boolean,
    modelCouncilEnabled: Boolean,
    subAgentMode: SubAgentMode = SubAgentMode.ROSTER,
    customSubAgents: List<SubAgentDefinition> = emptyList(),
    subAgents: List<SubAgentDefinition> = SubAgentDefinitions.builtIns,
    councilDescription: String = "Start a model council discussion",
    displayLocalizer: SubAgentDisplayLocalizer = SubAgentDisplayLocalizer {
        SubAgentDisplay.from(it)
    },
): List<MentionRoleItem> = buildList {
    if (subAgentEnabled) {
        val visibleSubAgents = if (subAgentMode == SubAgentMode.SMART_DYNAMIC) {
            customSubAgents
        } else {
            subAgents + customSubAgents
        }
        visibleSubAgents.forEach { role ->
            val display = displayLocalizer.localize(role)
            add(
                MentionRoleItem(
                    id = role.id,
                    name = display.name,
                    description = display.description,
                    kind = MentionRoleKind.SUBAGENT,
                )
            )
        }
    }
    if (modelCouncilEnabled) {
        add(
            MentionRoleItem(
                id = "council",
                name = "Council",
                description = councilDescription,
                kind = MentionRoleKind.COUNCIL,
            )
        )
    }
}

internal fun filterMentionRoleItems(
    items: List<MentionRoleItem>,
    query: String,
): List<MentionRoleItem> {
    val normalized = query.trim()
    if (normalized.isBlank()) return items
    return items.filter { role ->
        role.id.contains(normalized, ignoreCase = true) ||
            role.name.contains(normalized, ignoreCase = true) ||
            role.description.contains(normalized, ignoreCase = true)
    }
}
