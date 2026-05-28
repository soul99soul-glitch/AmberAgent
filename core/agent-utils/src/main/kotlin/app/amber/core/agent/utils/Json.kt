package app.amber.core.agent.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
val JsonInstant by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        allowTrailingComma = true
    }
}

@OptIn(ExperimentalSerializationApi::class)
val JsonInstantPretty by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        allowTrailingComma = true
        prettyPrint = true
    }
}
