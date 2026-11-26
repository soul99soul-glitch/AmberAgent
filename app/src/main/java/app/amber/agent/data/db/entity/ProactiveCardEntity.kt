package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "proactive_cards",
    primaryKeys = ["gateway_id", "device_id", "event_id"],
    indices = [Index(value = ["gateway_id", "signal_id"])],
)
data class ProactiveCardEntity(
    @ColumnInfo(name = "gateway_id") val gatewayId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "signal_id") val signalId: String,
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "meta_json") val metaJson: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "notified_at") val notifiedAt: Long? = null,
    @ColumnInfo(name = "feedback_state") val feedbackState: String? = null,
)
