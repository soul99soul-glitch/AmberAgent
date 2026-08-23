package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.amber.agent.data.db.entity.ArtifactEntity
import app.amber.agent.data.db.entity.ArtifactReferenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtifactDAO {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(artifact: ArtifactEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReference(reference: ArtifactReferenceEntity)

    @Update
    suspend fun update(artifact: ArtifactEntity)

    @Query("SELECT * FROM artifact WHERE artifact_id = :artifactId")
    suspend fun getById(artifactId: String): ArtifactEntity?

    @Query("SELECT * FROM artifact ORDER BY updated_at_ms DESC")
    suspend fun listAll(): List<ArtifactEntity>

    @Query("SELECT * FROM artifact ORDER BY updated_at_ms DESC")
    fun observeAll(): Flow<List<ArtifactEntity>>

    @Query("SELECT DISTINCT workspace_id FROM artifact ORDER BY workspace_id ASC")
    suspend fun listWorkspaceIds(): List<String>

    @Query(
        "SELECT * FROM artifact WHERE source_kind = :sourceKind AND source_message_id = :sourceMessageId LIMIT 1"
    )
    suspend fun findBySourceMessage(sourceKind: String, sourceMessageId: String): ArtifactEntity?

    @Query("SELECT * FROM artifact WHERE source_kind = :sourceKind AND source_id = :sourceId ORDER BY updated_at_ms DESC")
    suspend fun listBySourceKindAndSourceId(sourceKind: String, sourceId: String): List<ArtifactEntity>

    @Query("DELETE FROM artifact WHERE artifact_id = :artifactId")
    suspend fun deleteById(artifactId: String): Int

    @Query("SELECT COUNT(*) FROM artifact_reference WHERE artifact_id = :artifactId")
    suspend fun countReferences(artifactId: String): Int
}
