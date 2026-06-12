package app.amber.core.agent.store

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

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
@ConstructedBy(AgentRuntimeDatabaseConstructor::class)
abstract class AgentRuntimeDatabase : RoomDatabase() {
    abstract fun agentRuntimeDao(): AgentRuntimeDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AgentRuntimeDatabaseConstructor : RoomDatabaseConstructor<AgentRuntimeDatabase>
