package app.amber.core.utils

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// Re-exports from the shared :core:agent-utils module.
// Kept here as aliases so existing app.amber.core.utils.* callers
// continue to compile during the package migration.
val JsonInstant = app.amber.core.agent.utils.JsonInstant
val JsonInstantPretty = app.amber.core.agent.utils.JsonInstantPretty

// Same behavior as app.amber.core.agent.utils.jsonPrimitiveOrNull; kept
// as a local extension so legacy `app.amber.core.utils.jsonPrimitiveOrNull`
// imports continue to resolve.
val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
