package me.rerere.rikkahub.data.agent.board.hotlist.deepread.template

import kotlinx.serialization.Serializable

object DeepReadTemplateLimits {
    const val MAX_HTML_BYTES = 96 * 1024
}

object DeepReadBuiltInTemplates {
    const val COMPOSE_MAGAZINE = "compose_magazine"
    const val EDITORIAL_SLANT = "editorial_slant"
}

@Serializable
data class DeepReadTemplatePackage(
    val id: String,
    val name: String,
    val description: String = "",
    val html: String,
    val createdByAi: Boolean = false,
    val schemaVersion: Int = 1,
)

data class DeepReadTemplateValidationResult(
    val ok: Boolean,
    val error: String? = null,
)
