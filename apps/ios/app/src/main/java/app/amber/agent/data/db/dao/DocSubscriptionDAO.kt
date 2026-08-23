package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.entity.DocSubscriptionEntity

@Dao
interface DocSubscriptionDAO {
    @Query("SELECT * FROM doc_subscription WHERE enabled = 1")
    fun getEnabledFlow(): Flow<List<DocSubscriptionEntity>>

    @Query("SELECT * FROM doc_subscription WHERE enabled = 1")
    suspend fun getEnabled(): List<DocSubscriptionEntity>

    @Query("SELECT * FROM doc_subscription")
    suspend fun getAll(): List<DocSubscriptionEntity>

    @Query("SELECT * FROM doc_subscription WHERE id = :id")
    suspend fun getById(id: String): DocSubscriptionEntity?

    @Query("SELECT * FROM doc_subscription WHERE doc_url = :url LIMIT 1")
    suspend fun getByUrl(url: String): DocSubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DocSubscriptionEntity)

    @Query("UPDATE doc_subscription SET threshold = :threshold, updated_at = :now WHERE id = :id")
    suspend fun updateThreshold(id: String, threshold: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE doc_subscription SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE doc_subscription SET
            last_content_hash = :hash,
            last_char_count = :charCount,
            last_heading_list_json = :headingsJson,
            last_checked_at = :checkedAt,
            updated_at = :checkedAt
        WHERE id = :id
    """)
    suspend fun updateBaseline(
        id: String,
        hash: String,
        charCount: Int,
        headingsJson: String,
        checkedAt: Long,
    )

    @Query("UPDATE doc_subscription SET last_checked_at = :at, updated_at = :at WHERE id = :id")
    suspend fun markChecked(id: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE doc_subscription SET last_changed_at = :at, updated_at = :at WHERE id = :id")
    suspend fun markChanged(id: String, at: Long = System.currentTimeMillis())

    @Query("DELETE FROM doc_subscription WHERE id = :id")
    suspend fun deleteById(id: String)
}
