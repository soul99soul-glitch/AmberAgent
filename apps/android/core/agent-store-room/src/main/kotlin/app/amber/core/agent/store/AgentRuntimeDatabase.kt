package app.amber.core.agent.store

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentRunEntity::class,
        AgentEventEntity::class,
        TraceSpanEntity::class,
        PermissionIntentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AgentRuntimeDatabase : RoomDatabase() {
    abstract fun agentRuntimeDao(): AgentRuntimeDao
}
