# Scorched-Earth Rebrand Audit

Target: `git grep -i "rikkahub"` and `git grep "me\.rerere"` → 0 (excl. LICENSE attribution + git history).

Baseline at `5d0435e7` (refactor/scorched-earth-rebrand branch root):

| metric | count |
|---|---|
| `rikkahub` case-insensitive (all files) | 1,076 |
| `me.rerere` (all files) | 2,719 |
| files declaring `package me.rerere.*` | 217 |
| files importing `me.rerere.*` | 540 |
| `rikkahub` references in `.md` docs | 373 |

## 1. Kotlin package paths — `me.rerere.*` (217 files)

| sub-package | count | new path |
|---|---|---|
| `me.rerere.rikkahub.*` (app/) | 81 | `app.amber.agent.*` |
| `me.rerere.ai.*` (ai/) | 32 | `app.amber.ai.*` |
| `me.rerere.search.*` (search/) | 21 | `app.amber.search.*` |
| `me.rerere.tts.*` (tts/) | 18 | `app.amber.tts.*` |
| `me.rerere.common.*` (common/) | 15 | `app.amber.common.*` |
| `me.rerere.document.*` (document/) | 6 | `app.amber.document.*` |
| `me.rerere.highlight.*` (highlight/) | 4 | `app.amber.highlight.*` |
| `me.rerere.*` (baselineprofile/) | 2 | `app.amber.baselineprofile.*` |
| tests (androidTest + test) in 18 places | 18 | mirror src package |

Total module-by-module distribution (including test sources, full count = 91/46/23/22/17/8/6/3/1):

```
app: 91   ai: 46   search: 23   tts: 22
common: 17   document: 8   highlight: 6   web: 3   core: 1
```

App-module residue (81 files in `me.rerere.rikkahub.*`) is exactly the ADR-0001 §3 frozen set — to be unfrozen wholesale in Task 3.

## 2. AndroidManifest + applicationId (Task 2 scope)

| location | current | target |
|---|---|---|
| `app/build.gradle.kts` `applicationId` | `me.rerere.amberagent` | `app.amber.agent` |
| `app/build.gradle.kts` `namespace` | `me.rerere.rikkahub` | `app.amber.agent` |
| 8 sub-module `build.gradle.kts` `namespace` | `me.rerere.{ai,common,...}` | `app.amber.{ai,common,...}` |
| `app/src/main/AndroidManifest.xml` `<application android:name=>` | `.RikkaHubApp` | `.AmberAgentApp` (and class rename) |
| `<activity .RouteActivity>` etc. | dot-prefix relative to namespace | unchanged (auto-follows namespace) |

`applicationIdSuffix` strings currently `.notion` / `.debug` (no `rikkahub` substring) — no change.

## 3. Database / DataStore / file naming (Task 4 scope)

| asset | current | target |
|---|---|---|
| Room DB file | `chat.db` (per [AppDatabase.kt]) | `amber_agent.db` |
| Room schema dir | `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/*.json` (30 versions) | DELETE entire dir (reset to v1 with new FQN dir) |
| DataStore stores | `UIPrefs/ChatPrefs/AssistantPrefs/ProviderPrefs/...` (per [PreferencesStore.kt]) | `amber_*` prefix |
| Backup magic | `rikkahub_backup_v*` (per [PreferenceStoreV1Migration.kt]) | DELETE migration entirely; new magic `amber_agent_backup_v1` |
| WorkManager unique names | grep pending — likely `rikkahub_*` prefix | `amber_*` |
| Notification channel IDs | grep pending — see [createNotificationChannel] in `RikkaHubApp.kt` | `amber_*` |
| ContentProvider authority | already `${applicationId}.fileprovider` placeholder | auto-updates with applicationId |

## 4. Native (Rust JNI) symbols (Task 3 scope)

JNI naming requires Rust symbol = `Java_<package>_<class>_<method>`. Kotlin package change → Rust rename required.

| Rust crate | current Java_ symbol prefix | new prefix |
|---|---|---|
| `native/highlight-parser` | `Java_me_rerere_highlight_nativebridge_HighlighterNative_*` (2) | `Java_app_amber_highlight_nativebridge_HighlighterNative_*` |
| `native/markdown-parser` | `Java_me_rerere_rikkahub_ui_components_richtext_nativebridge_MarkdownParserNative_*` (2) | `Java_app_amber_agent_ui_components_richtext_nativebridge_MarkdownParserNative_*` |
| `native/office-parsers` | `Java_me_rerere_document_nativebridge_OfficeParserNative_*` (4) | `Java_app_amber_document_nativebridge_OfficeParserNative_*` |
| `native/regex-transformer` | `Java_me_rerere_rikkahub_data_model_nativebridge_RegexTransformerNative_*` (1) | `Java_app_amber_agent_data_model_nativebridge_RegexTransformerNative_*` |

Plus matching Kotlin `external fun` declarations + `System.loadLibrary` calls (lib names unchanged — those are the `.so` artefacts, not package-qualified).

## 5. Broadcast Intent actions / shortcuts / manifest strings (Task 5 scope)

Found in initial pass:

- `app/src/main/AndroidManifest.xml: android:name=".RikkaHubApp"` (1)
- Manifest dot-prefix activity / service / receiver entries (auto-update via namespace change)
- `scripts/amberagent_auto_jank.sh: am start -n "$PACKAGE/me.rerere.rikkahub.RouteActivity"` (1)
- `app/src/main/res/xml/shortcuts.xml` — already updated in commit f274634a but path still uses `app.amber.feature.*` which will need to follow this rebrand
- Broadcast actions `me.rerere.rikkahub.action.*` — pending grep against actual `<intent-filter>` blocks

## 6. Config / build scripts (Task 6 + spread across all tasks)

| file | what to do |
|---|---|
| `config/legacy-package-allowlist.txt` | DELETE (Task 6) |
| `config/check-legacy-package.sh` | DELETE (Task 6) |
| `.github/workflows/legacy-package-guard.yml` (presumed) | DELETE (Task 6) |
| `config/check-refactor-state.sh` | strip `me.rerere` invariants (Task 6) |
| `docs/adr/0001-legacy-frozen-surfaces.md` | DELETE (Task 6) |
| `docs/adr/0006-brand-rebrand-complete.md` | CREATE (Task 6) |
| `gradle/libs.versions.toml` | see §8 (out of scope) |

## 7. Docs (Task 5/7 cleanup)

373 `rikkahub` mentions in `.md`. Bulk find/replace + manual review of:
- `README.md`
- `docs/architecture.md`
- `docs/refactor-completion-report.md`
- `docs/adr/*.md` (multi-doc series)
- `docs/refactor-progress.md` (history — may keep historical references with disclaimer)

Plus Obsidian working-memory file (external to repo) + global `~/.claude/.../memory/MEMORY.md` (separately maintained, Task 7).

## 8. Out-of-scope exceptions (`rikkahub` references that stay)

These are NOT renamed in this rebrand — flagged explicitly so the final `grep rikkahub` doesn't surprise:

| file | reference | reason |
|---|---|---|
| `gradle/libs.versions.toml` | `com.github.rikkahub.jlatexmath-android` | upstream Maven coord (user's own fork repo URL on GitHub) — renaming requires re-publishing the fork |
| `gradle/libs.versions.toml` | `com.github.rikkahub:markdown` | same |
| `gradle/libs.versions.toml` | `com.github.rikkahub:hugeicons-compose` | same |
| `gradle/libs.versions.toml` | `com.github.rikkahub:sqlite-android` | same |
| `.github/FUNDING.yml` | `patreon: rikkahub` | user's personal patreon handle, not app codename |
| `LICENSE` | "Forked from rikkahub" attribution | required by Apache 2.0 attribution |
| `git log` / `git history` | commit messages with `rikkahub` | git history is immutable |
| `docs/refactor-progress.md` historical entries | mentions in chronological log | history; gets a disclaimer header noting pre-rebrand entries |

If the user wants these scrubbed too, that's a separate decision (republish 4 forks under `app-amber/*` GitHub org).

## 9. Order of operations (next 6 commits)

1. Task 2 — `applicationId` + namespace rename (1 commit) → assembleDebug ✓
2. Task 3 — 217 `package me.rerere` → `package app.amber.*` (1 commit, large) + Rust JNI + delete V1Migration → assembleDebug ✓
3. Task 4 — DB file + DataStore + WorkManager + Notification channel + backup magic (1 commit) → fresh install ✓
4. Task 5 — Intent actions + shortcuts + res strings + BuildConfig (1 commit) → `grep rikkahub` excl. §8 = 0
5. Task 6 — Delete guardrails + ADR-0001 + write ADR-0006 (1 commit)
6. Task 7 — Final verify, full APK, MEMORY.md update (1 commit)
