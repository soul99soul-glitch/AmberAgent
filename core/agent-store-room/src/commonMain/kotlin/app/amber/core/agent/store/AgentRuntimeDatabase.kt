package app.amber.core.agent.store

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        AgentRunEntity::class,
        AgentEventEntity::class,
        TraceSpanEntity::class,
        PermissionIntentEntity::class,
        MailboxEnvelopeEntity::class,
        ThreadEdgeEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@ConstructedBy(AgentRuntimeDatabaseConstructor::class)
abstract class AgentRuntimeDatabase : RoomDatabase() {
    abstract fun agentRuntimeDao(): AgentRuntimeDao

    abstract fun mailboxDao(): MailboxDao

    abstract fun threadEdgeDao(): ThreadEdgeDao
}

/**
 * v1 → v2（P1-b）：新增 mailbox_envelope 表。只建新表，不动既有四表——
 * iOS 老设备（agent_run 已在生产账本/恢复/热力图使用）原地升级不丢数据。
 * 双端 builder（iOS `IosDatabaseFactory`、Android `DataSourceModule`）都必须
 * `addMigrations(MIGRATION_1_2)`，否则 Room 在版本不匹配时拒绝打开。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mailbox_envelope` (
                `id` TEXT NOT NULL,
                `author_thread_id` TEXT NOT NULL,
                `recipient_thread_id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `payload` TEXT NOT NULL,
                `trigger_turn` INTEGER NOT NULL,
                `parent_turn_id` TEXT,
                `created_at` INTEGER NOT NULL,
                `delivered_at` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_mailbox_envelope_recipient_thread_id_delivered_at_created_at` " +
                "ON `mailbox_envelope` (`recipient_thread_id`, `delivered_at`, `created_at`)",
        )
    }
}

/**
 * v2 → v3（P1-c）：新增 thread_edge 表（线程编排 spawn 边）。只建新表，不动
 * mailbox_envelope 与既有四表——P1-b 升级过的老设备原地再升一级不丢数据。
 * 双端 builder（iOS `IosDatabaseFactory`、Android `DataSourceModule`）都必须
 * `addMigrations(MIGRATION_2_3)`，否则 Room 在版本不匹配时拒绝打开。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `thread_edge` (
                `child_thread_id` TEXT NOT NULL,
                `parent_thread_id` TEXT NOT NULL,
                `agent_path` TEXT NOT NULL,
                `nickname` TEXT,
                `role_assistant_id` TEXT,
                `fork_turns` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`child_thread_id`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_thread_edge_parent_thread_id` " +
                "ON `thread_edge` (`parent_thread_id`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_thread_edge_agent_path` ON `thread_edge` (`agent_path`)",
        )
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AgentRuntimeDatabaseConstructor : RoomDatabaseConstructor<AgentRuntimeDatabase>
