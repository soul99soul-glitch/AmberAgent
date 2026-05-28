package app.amber.feature.board.hotlist

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import app.amber.feature.board.hotlist.providers.BuiltInHotListProviders
import app.amber.feature.board.hotlist.providers.CustomHotListProvider
import app.amber.feature.board.TodayBoardSetting
import app.amber.feature.board.TodayBoardBackgroundStrategy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.prefs.SettingsAggregator
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.util.concurrent.TimeUnit

class HotListScheduler(
    context: Context,
    private val settingsStore: SettingsAggregator,
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun sync(settings: Settings = settingsStore.settingsFlow.value) {
        val board = settings.agentRuntime.todayBoard
        if (!board.enabled) {
            cancel()
            return
        }
        if (board.backgroundStrategy == TodayBoardBackgroundStrategy.FOREGROUND_ONLY) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<HotListWorker>(
            board.hotListRefreshIntervalMinutes.coerceAtLeast(30).toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(buildConstraints(board))
            .addTag(TAG_PERIODIC)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun runOnce() {
        val board = settingsStore.settingsFlow.value.agentRuntime.todayBoard
        val request = OneTimeWorkRequestBuilder<HotListWorker>()
            .setConstraints(buildConstraints(board))
            .addTag(TAG_MANUAL)
            .build()
        workManager.enqueueUniqueWork(WORK_MANUAL, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_PERIODIC)
        workManager.cancelUniqueWork(WORK_MANUAL)
    }

    private fun buildConstraints(board: TodayBoardSetting): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(if (board.hotListWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

    companion object {
        const val WORK_PERIODIC = "today_board_hot_list_periodic"
        const val WORK_MANUAL = "today_board_hot_list_manual"
        const val TAG_PERIODIC = "today_board_hot_list_periodic"
        const val TAG_MANUAL = "today_board_hot_list_manual"
    }
}

class HotListWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        val settings = get<SettingsAggregator>().settingsFlow.filterNot { it.init }.first()
        val board = settings.agentRuntime.todayBoard
        if (!board.enabled) return Result.success()

        val repository = get<HotListRepository>()
        val builtInProviders = get<BuiltInHotListProviders>()
            .all()
            .filter { it.id in board.hotListEnabledSources }
        val customProviders = repository.getEnabledSources()
            .map { source ->
                CustomHotListProvider(
                    source = source,
                    client = get<OkHttpClient>(),
                    json = get<Json>(),
                )
            }
        val providers = builtInProviders + customProviders
        if (providers.isEmpty()) {
            repository.replaceTopics(emptyList())
            return Result.success()
        }
        val cachedBeforeFetch = providers
            .mapNotNull { provider ->
                repository.getProviderCache(provider.id)?.let { provider.id to it }
            }
            .toMap()

        val snapshots = coroutineScope {
            providers.map { provider ->
                async {
                    get<HotListSafeFetcher>().fetch(
                        provider = provider,
                        cachedSnapshot = { cachedBeforeFetch[provider.id] },
                        saveResult = { result ->
                            repository.saveProviderResult(provider.id, provider.displayName, result)
                        },
                    )
                }
            }.awaitAll()
        }
        val localizedSnapshots = get<HotListTitleLocalizer>().localize(snapshots, cachedBeforeFetch)
        localizedSnapshots
            .filter { !it.stale }
            .filter { localized ->
                snapshots.firstOrNull { it.providerId == localized.providerId }?.items != localized.items
            }
            .forEach { snapshot ->
                repository.saveProviderResult(
                    snapshot.providerId,
                    snapshot.providerName,
                    HotListResult(snapshot.items, snapshot.fetchedAt),
                )
            }

        localizedSnapshots
            .filter { it.stale && !it.error.isNullOrBlank() }
            .forEach { snapshot ->
                repository.saveProviderFailure(snapshot.providerId, snapshot.providerName, snapshot.error.orEmpty())
            }

        val topics = get<HotListAggregator>().aggregate(
            snapshots = localizedSnapshots.filter { it.items.isNotEmpty() },
            limit = HOT_LIST_TOPIC_CACHE_LIMIT,
        )
        repository.replaceTopics(topics)
        repository.pruneExpiredDeepReads()
        return Result.success()
    }

}
