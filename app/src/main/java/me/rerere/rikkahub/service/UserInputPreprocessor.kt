package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes

/**
 * Pre-flight user-input transformation surface.
 *
 * Applies the current assistant's USER-scoped regex transformers to each
 * `UIMessagePart.Text` of an inbound message *before* it lands in the
 * conversation history. Non-text parts pass through unchanged.
 *
 * Extracted from ChatService in M1.3.2 — this is the seam intended by the
 * blueprint's "MessageTransformPipeline" stage. Currently a single
 * preprocessing step (regex), but the class establishes the location for
 * future input-side transformers (e.g. mention expansion, slash-command
 * expansion, base64-image lift) without re-touching ChatService.
 *
 * Reads settings synchronously via `settingsFlow.value`. Callers from app
 * cold-start paths should resolve settings through their own filterNot guard
 * first (today both callsites in ChatService run inside `sendMessage` /
 * regenerate, which are user-action-driven — post-warmup safe).
 */
class UserInputPreprocessor(
    private val settingsStore: SettingsAggregator,
) {
    fun process(parts: List<UIMessagePart>): List<UIMessagePart> {
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(
                    text = part.text.replaceRegexes(
                        assistant = assistant,
                        scope = AssistantAffectScope.USER,
                        visual = false,
                    )
                )

                else -> part
            }
        }
    }
}
