package app.amber.core.repository

import kotlinx.coroutines.flow.Flow
import app.amber.agent.data.db.dao.FavoriteDAO
import app.amber.agent.data.db.entity.FavoriteEntity
import app.amber.core.favorite.NodeFavoriteAdapter
import app.amber.core.model.FavoriteType
import app.amber.core.model.NodeFavoriteTarget
import app.amber.core.sync.core.SyncRestoreWriteGate
import kotlin.uuid.Uuid

class FavoriteRepository(
    private val dao: FavoriteDAO,
    private val restoreWriteGate: SyncRestoreWriteGate? = null,
) {
    fun listAll(): Flow<List<FavoriteEntity>> = dao.listAll()

    fun listByType(type: FavoriteType): Flow<List<FavoriteEntity>> = dao.listByType(type.value)

    suspend fun getByRefKey(refKey: String): FavoriteEntity? = dao.getByRefKey(refKey)

    suspend fun existsByRefKey(refKey: String): Boolean = dao.existsByRefKey(refKey)

    suspend fun deleteByRefKey(refKey: String): Int =
        withFavoriteWrite { dao.deleteByRefKey(refKey) }

    suspend fun deleteById(id: String): Int =
        withFavoriteWrite { dao.deleteById(id) }

    suspend fun upsert(entity: FavoriteEntity) =
        withFavoriteWrite { dao.upsert(entity) }

    suspend fun addNodeFavorite(target: NodeFavoriteTarget): FavoriteEntity {
        val refKey = NodeFavoriteAdapter.buildRefKey(target)
        return withFavoriteWrite {
            val existing = dao.getByRefKey(refKey)
            val favorite = NodeFavoriteAdapter.buildFavoriteEntity(
                target = target,
                existing = existing,
            )
            dao.upsert(favorite)
            favorite
        }
    }

    suspend fun removeNodeFavorite(conversationId: Uuid, nodeId: Uuid): Int {
        return withFavoriteWrite {
            dao.deleteByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
        }
    }

    suspend fun isNodeFavorited(conversationId: Uuid, nodeId: Uuid): Boolean {
        return dao.existsByRefKey(NodeFavoriteAdapter.buildRefKey(conversationId.toString(), nodeId.toString()))
    }

    private suspend fun <T> withFavoriteWrite(block: suspend () -> T): T =
        restoreWriteGate?.withCurrentWriterOrCancel(block) ?: block()
}
