package app.amber.core.model

import kotlin.uuid.Uuid

/** Stable identity retained at database, secret, and durable-run compatibility boundaries. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
val AMBER_AGENT_ID: Uuid =
    Uuid.parse("7def1f55-3dd9-4a09-a95a-7d0c2554b346")
