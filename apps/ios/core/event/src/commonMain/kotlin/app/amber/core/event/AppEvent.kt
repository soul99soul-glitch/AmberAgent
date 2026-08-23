package app.amber.core.event

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
    data class OpenDeepRead(
        val topicId: String,
        val title: String,
        val sourceUrl: String? = null,
        val forceRegenerate: Boolean = false,
    ) : AppEvent()
}
