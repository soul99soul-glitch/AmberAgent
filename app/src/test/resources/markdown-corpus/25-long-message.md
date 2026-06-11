# Building an Offline-First Android App with Room and Coroutines

This is a long assistant answer (~80 lines) covering architecture, implementation steps, code, and caveats. It mixes prose, ordered lists, code blocks, and a summary table.

## What is "Offline-First"?

An offline-first app treats the local database as the primary source of truth. The UI reads from the database, and a background sync process reconciles the local state with a remote API. Users can read and interact with stale data while offline, and changes are queued and flushed when connectivity returns.

This is different from a "cache-aside" pattern, where the app tries the network first and falls back to cache on failure. Offline-first is more deliberate: local data is always available instantly, and network sync is a background concern.

## Architecture

The standard Android offline-first stack looks like this:

1. **Room** persists data in SQLite and exposes reactive `Flow<List<T>>` queries.
2. **Repository** layer reads from Room, triggers API calls, and writes results back to Room.
3. **ViewModel** collects the repository's Flow and exposes `StateFlow<UiState<T>>` to the UI.
4. **Compose UI** observes the StateFlow and recomposes on changes.
5. **WorkManager** schedules background sync jobs that run even when the app is closed.

## Step-by-Step Implementation

### 1. Define the Entity and DAO

```kotlin
@Entity(tableName = "articles")
data class ArticleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    val publishedAt: Long,
    val isSynced: Boolean = true
)

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY publishedAt DESC")
    fun observeAll(): Flow<List<ArticleEntity>>

    @Upsert
    suspend fun upsertAll(articles: List<ArticleEntity>)

    @Query("DELETE FROM articles WHERE id = :id")
    suspend fun delete(id: String)
}
```

### 2. Write the Repository

```kotlin
class ArticleRepository(
    private val dao: ArticleDao,
    private val api: ArticleApi,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /** Always read from the local DB — the UI never waits for network. */
    val articles: Flow<List<Article>> = dao.observeAll()
        .map { entities -> entities.map(ArticleEntity::toDomain) }
        .flowOn(dispatcher)

    /** Fetch from network and write into the DB; the Flow above auto-updates. */
    suspend fun sync() = withContext(dispatcher) {
        val remote = api.getArticles()
        dao.upsertAll(remote.map(ArticleDto::toEntity))
    }
}
```

### 3. Set Up the ViewModel

```kotlin
@HiltViewModel
class ArticleListViewModel @Inject constructor(
    private val repository: ArticleRepository
) : ViewModel() {

    val uiState: StateFlow<UiState<List<Article>>> = repository.articles
        .map { UiState.Success(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiState.Loading
        )

    init { viewModelScope.launch { repository.sync() } }
}
```

### 4. Compose UI

```kotlin
@Composable
fun ArticleListScreen(viewModel: ArticleListViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (state) {
        is UiState.Loading -> LoadingIndicator()
        is UiState.Error   -> ErrorMessage((state as UiState.Error).message)
        is UiState.Success -> ArticleList((state as UiState.Success).data)
    }
}
```

## Trade-Offs at a Glance

| Concern | Offline-First | Cache-Aside |
| :--- | :--- | :--- |
| Cold-start latency | Low (instant local read) | High (waits for network) |
| Data freshness | Eventually consistent | Closer to real-time |
| Complexity | Higher | Lower |
| Storage usage | Higher | Lower |
| Works offline | Yes | No |

## Common Pitfalls

- **Conflict resolution**: if the user edits data offline and the server has a newer version, you need a merge strategy. Last-write-wins is simplest; vector clocks or CRDTs are more robust.
- **Sync loops**: make sure a successful upsert doesn't re-trigger a sync via the Flow, which then triggers another upsert. Break the cycle by diffing before writing.
- **Large datasets**: `SELECT * FROM articles` on a table with 100 000 rows blocks the IO thread. Use pagination with `PagingSource` from the Paging 3 library.
- **Auth tokens expiring while offline**: queue the sync request with the token at the time of the change, not at the time of the flush.

## Conclusion

Offline-first is the right default for any app where data is central and connectivity is unreliable. The Room + Coroutines + WorkManager stack makes it achievable without custom background service boilerplate. Start simple — a single `sync()` call on app start — and add complexity (incremental sync, conflict resolution, delta updates) only when you hit real problems.
