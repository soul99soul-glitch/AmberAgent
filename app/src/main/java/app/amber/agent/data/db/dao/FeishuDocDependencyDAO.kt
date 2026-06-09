package app.amber.agent.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.amber.agent.data.db.entity.FeishuDocDependencyEntity

@Dao
interface FeishuDocDependencyDAO {
    @Query("SELECT * FROM feishu_doc_dependency WHERE enabled = 1 ORDER BY created_at DESC")
    suspend fun getEnabled(): List<FeishuDocDependencyEntity>

    @Query("SELECT * FROM feishu_doc_dependency ORDER BY created_at DESC")
    suspend fun getAll(): List<FeishuDocDependencyEntity>

    @Query("SELECT * FROM feishu_doc_dependency WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FeishuDocDependencyEntity?

    @Query("SELECT * FROM feishu_doc_dependency WHERE upstream_url = :url AND enabled = 1")
    suspend fun getDownstreamsOf(url: String): List<FeishuDocDependencyEntity>

    @Query("SELECT * FROM feishu_doc_dependency WHERE downstream_url = :url AND enabled = 1")
    suspend fun getUpstreamsOf(url: String): List<FeishuDocDependencyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dep: FeishuDocDependencyEntity)

    @Query("DELETE FROM feishu_doc_dependency WHERE id = :id")
    suspend fun deleteById(id: String)
}
