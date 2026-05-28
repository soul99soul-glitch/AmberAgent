package me.rerere.rikkahub.utils

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Re-exports from the shared :core:agent-utils module.
// Kept here as type aliases so existing me.rerere.rikkahub.utils.JsonInstant
// callers continue to compile during the package migration.
val JsonInstant = app.amber.core.agent.utils.JsonInstant
val JsonInstantPretty = app.amber.core.agent.utils.JsonInstantPretty

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
