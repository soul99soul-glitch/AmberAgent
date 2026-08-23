package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P6-01 — resume cursor for a server-side stored OpenAI Response.
 *
 * One row per runId, bound to the local run via the primary key and to the
 * provider via [providerId] (the recovery worker re-resolves the provider
 * setting from the persisted id at cold start). The sequence is write-ahead
 * persisted: a reconnect skips every event with sequence <= this value.
 *
 * Row exists  <=>  the server holds a stored response for this run that has
 * not been fully consumed locally. Cleared when the terminal event is
 * delivered or when the run is settled server-side by recovery.
 */
@Entity(tableName = "run_resume")
data class RunResumeEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "response_id") val responseId: String,
    @ColumnInfo(name = "sequence") val sequence: Long,
    @ColumnInfo(name = "provider_id") val providerId: String,
)
