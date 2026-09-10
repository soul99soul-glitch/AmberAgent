package app.amber.feature.home

import app.amber.agent.data.db.dao.MiniAppDAO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Projects apps that have never been opened or received a newer saved version
 * since the last runner visit. The DAO returns only identity/timestamps, so
 * Home never scans the app HTML or version payloads.
 */
class MiniAppRunnerContinueSource(
    private val miniAppDao: MiniAppDAO,
) : ContinueCandidateSource {
    override fun observe(): Flow<List<ContinueCandidate>> =
        miniAppDao.observeContinueCandidates().map { rows ->
            rows.map { row ->
                ContinueCandidate(
                    sourceKind = ContinueSourceKind.MINIAPP_RUNNER,
                    sourceId = row.id,
                    route = ContinueRoute.MiniAppRunner(appId = row.id),
                    title = row.title.ifBlank { "未命名小应用" },
                    summary = if (row.lastRunAt == null) {
                        "已生成，尚未打开"
                    } else {
                        "有新版本尚未打开"
                    },
                    lastUpdatedAt = Instant.ofEpochMilli(row.latestVersionCreatedAt),
                    status = ContinueStatus.DRAFT,
                )
            }
        }
}
