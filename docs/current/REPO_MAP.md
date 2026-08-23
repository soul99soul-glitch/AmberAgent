# Repository Map

| Path | Owner | Default consumer | Default validation |
| --- | --- | --- | --- |
| `apps/android/` | Android app | Android Agent | Gradle JVM/app gates |
| `apps/ios/iosApp/` | Native iOS | iOS Agent | Xcode focused tests/build |
| `apps/ios/shared/` and related KMP modules | iOS transition bridge | iOS/KMP task only | Gradle KMP + Swift consumer |
| `core/` | Stable shared contracts | Explicit cross-platform task | Golden fixtures on both apps |
| `docs/current/` | Current facts | All tasks | Link and factual review |
| `.migration/snapshots/` | Local recovery only | Migration owner | SHA-256 and disposable restore |

Ordinary platform tasks must not read the sibling platform. Vendor, generated binaries and historical source repos are read only when the task directly requires them.
