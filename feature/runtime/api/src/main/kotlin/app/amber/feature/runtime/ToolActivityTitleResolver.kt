package app.amber.feature.runtime

/**
 * Resolves a user-visible activity title without changing the tool's wire
 * name, schema, or execution path.
 */
fun interface ToolActivityTitleResolver {
    fun resolve(toolName: String, rawTitle: String, inputPreview: String): String
}
