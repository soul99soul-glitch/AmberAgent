package app.amber.feature.board

import kotlinx.serialization.Serializable

/** Platform-neutral signal emitted before any platform-specific persistence or deduplication. */
@Serializable
data class BoardSignal(
    val sourceType: String,
    val sourceRef: String,
    val title: String,
    val content: String,
    val signalTime: Long,
    val metadataJson: String = "{}",
)
