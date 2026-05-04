package me.rerere.ai.ui

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val REASONING_CONTENT_PRESENT_METADATA_KEY = "reasoning_content_present"

fun reasoningContentPresentMetadata() = buildJsonObject {
    put(REASONING_CONTENT_PRESENT_METADATA_KEY, true)
}

fun UIMessagePart.Reasoning.hasExplicitReasoningContentField(): Boolean =
    metadata?.get(REASONING_CONTENT_PRESENT_METADATA_KEY)
        ?.jsonPrimitive
        ?.booleanOrNull == true
