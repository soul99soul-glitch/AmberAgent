## GFM Tables — Simple

GitHub Flavored Markdown extends CommonMark with pipe-delimited tables. Here is a simple table with no explicit alignment:

| Library | Purpose | Latest Version |
| --- | --- | --- |
| Koin | Dependency injection | 3.5.3 |
| Room | SQLite ORM | 2.6.1 |
| Coil | Image loading | 2.6.0 |
| Retrofit | HTTP client | 2.11.0 |
| OkHttp | HTTP/WebSocket | 4.12.0 |

A table must have a header row, a delimiter row (dashes), and at least one body row.

### Coroutine Dispatcher Reference

Here is a quick-reference table that an assistant might produce when explaining `CoroutineDispatcher`:

| Dispatcher | Thread pool | Use for |
| --- | --- | --- |
| `Dispatchers.Main` | Main (UI) thread | UI updates, lightweight work |
| `Dispatchers.IO` | Shared IO pool (64 threads) | File, network, database |
| `Dispatchers.Default` | CPU-count threads | CPU-intensive computation |
| `Dispatchers.Unconfined` | None (inherits caller) | Testing, rare cases |

### Android Permissions

| Permission | Protection level | Required for |
| --- | --- | --- |
| `INTERNET` | Normal | Any network access |
| `ACCESS_FINE_LOCATION` | Dangerous | GPS location |
| `CAMERA` | Dangerous | Camera preview/capture |
| `READ_MEDIA_IMAGES` | Dangerous (API 33+) | Reading photos |
| `POST_NOTIFICATIONS` | Dangerous (API 33+) | Showing notifications |

Tables with many columns still need to render in a horizontal-scroll container on narrow screens.
