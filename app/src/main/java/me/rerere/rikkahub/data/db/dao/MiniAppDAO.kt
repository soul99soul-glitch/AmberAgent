package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MiniAppAuditLogEntity
import me.rerere.rikkahub.data.db.entity.MiniAppEntity
import me.rerere.rikkahub.data.db.entity.MiniAppGrantEntity
import me.rerere.rikkahub.data.db.entity.MiniAppSharedDataEntity
import me.rerere.rikkahub.data.db.entity.MiniAppVersionEntity

@Dao
interface MiniAppDAO {
    @Query(
        """
        SELECT * FROM mini_app
        ORDER BY pinned DESC, updatedAt DESC
        """
    )
    fun observeAll(): Flow<List<MiniAppEntity>>

    @Query("SELECT * FROM mini_app WHERE id = :id")
    suspend fun getById(id: String): MiniAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniAppEntity)

    @Delete
    suspend fun delete(entity: MiniAppEntity)

    @Query("DELETE FROM mini_app WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        UPDATE mini_app
        SET runCount = runCount + 1
        WHERE id = :id
        """
    )
    suspend fun markRun(id: String)

    @Query(
        """
        UPDATE mini_app
        SET pinned = :pinned, updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Query(
        """
        UPDATE mini_app
        SET title = :title, description = :description, updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun rename(id: String, title: String, description: String, updatedAt: Long)

    @Query(
        """
        UPDATE mini_app
        SET boardSummary = :summary, updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun updateBoardSummary(id: String, summary: String, updatedAt: Long)
}

@Dao
interface MiniAppGrantDAO {
    @Query("SELECT * FROM mini_app_grant WHERE appId = :appId")
    suspend fun listForApp(appId: String): List<MiniAppGrantEntity>

    @Query("SELECT * FROM mini_app_grant WHERE appId = :appId AND permission = :permission")
    suspend fun get(appId: String, permission: String): MiniAppGrantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniAppGrantEntity)

    @Query("DELETE FROM mini_app_grant WHERE appId = :appId")
    suspend fun deleteForApp(appId: String)
}

@Dao
interface MiniAppVersionDAO {
    @Query(
        """
        SELECT * FROM mini_app_version
        WHERE appId = :appId
        ORDER BY versionNumber DESC
        """
    )
    fun observeForApp(appId: String): Flow<List<MiniAppVersionEntity>>

    @Query("SELECT * FROM mini_app_version WHERE appId = :appId AND versionNumber = :versionNumber")
    suspend fun get(appId: String, versionNumber: Int): MiniAppVersionEntity?

    @Query("SELECT COALESCE(MAX(versionNumber), 0) FROM mini_app_version WHERE appId = :appId")
    suspend fun maxVersionNumber(appId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniAppVersionEntity)

    @Query(
        """
        DELETE FROM mini_app_version
        WHERE appId = :appId
        AND versionNumber NOT IN (
            SELECT versionNumber FROM mini_app_version
            WHERE appId = :appId
            ORDER BY versionNumber DESC
            LIMIT :keep
        )
        """
    )
    suspend fun pruneOldVersions(appId: String, keep: Int)

    @Query("DELETE FROM mini_app_version WHERE appId = :appId")
    suspend fun deleteForApp(appId: String)
}

@Dao
interface MiniAppAuditLogDAO {
    @Query(
        """
        SELECT * FROM mini_app_audit_log
        WHERE appId = :appId
        ORDER BY createdAt DESC
        LIMIT :limit
        """
    )
    fun observeForApp(appId: String, limit: Int = 100): Flow<List<MiniAppAuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MiniAppAuditLogEntity)

    @Query("DELETE FROM mini_app_audit_log WHERE appId = :appId")
    suspend fun deleteForApp(appId: String)
}

@Dao
interface MiniAppSharedDataDAO {
    @Query("SELECT * FROM mini_app_shared_data WHERE namespace = :namespace AND `key` = :key")
    suspend fun get(namespace: String, key: String): MiniAppSharedDataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniAppSharedDataEntity)

    @Query("DELETE FROM mini_app_shared_data WHERE namespace = :namespace AND `key` = :key")
    suspend fun delete(namespace: String, key: String)

    @Query("SELECT COALESCE(SUM(LENGTH(value)), 0) FROM mini_app_shared_data WHERE namespace = :namespace")
    suspend fun namespaceBytes(namespace: String): Int

    @Query("DELETE FROM mini_app_shared_data WHERE lastWriterId = :appId OR namespace = :appId")
    suspend fun deleteForApp(appId: String)
}
