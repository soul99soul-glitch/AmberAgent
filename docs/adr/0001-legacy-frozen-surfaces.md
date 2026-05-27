# ADR-0001: Legacy Frozen Surfaces

> Status: **Accepted**
> Date: 2026-05-27
> Context: Phase 0 of Agent Kernel + Surfaces refactoring

## Decision

The following surfaces are **frozen** — any PR that modifies them must explicitly
mark "modifies frozen surface" in the PR description and justify the change.

---

## 1. Room Database File Name

| Surface | Value | Reason |
|---|---|---|
| AppDatabase file | `rikka_hub` | `Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")` in `DataSourceModule.kt`. Changing this loses all user conversations and data on upgrade. |
| AppDatabase version | `30` (as of 2026-05-27) | Migrations must only go forward; `@Database(version = N)` monotonically increases. |
| AppDatabase FQN | `me.rerere.rikkahub.data.db.AppDatabase` | Room schema JSON files (`app/schemas/`) embed this FQN. Renaming breaks `autoMigrations` and `fallbackToDestructiveMigration`. |

### Future: AgentRuntimeDatabase

When Phase A introduces `AgentRuntimeDatabase` (in `app.amber.core.agent.runtime`):
- It gets its own DB file name (e.g. `agent_runtime.db`), **never** reuses `rikka_hub`.
- Version numbering is independent from AppDatabase.
- CI should run N×M migration matrix (last 2 AppDatabase versions × last 2 AgentRuntimeDatabase versions).

---

## 2. DataStore Names

| DataStore | Name string | File |
|---|---|---|
| Settings (main) | `"settings"` | `PreferencesStore.kt` |
| Agent Cron Tasks | `"agent_cron_tasks"` | `AgentCronManager.kt` |

These names become filenames under `datastore/` on disk. Renaming them silently
resets all user preferences to defaults.

---

## 3. Native Path Preferences Keys

| Key | Type | Notes |
|---|---|---|
| `native_path_office` | Boolean | Even if deprecated, key must not be deleted or renamed |
| `native_path_highlight` | Boolean | Same |
| `native_path_regex` | Boolean | Same |
| `native_path_markdown_html` | Boolean | Same |
| `native_path_markdown_ast` | Boolean | Same |
| `native_path_sampling_rate` | Float | Same |
| `native_path_kill_switch` | String (remote) | RC kill switch — `NativePathBootstrap.REMOTE_KILL_SWITCH_KEY` |

**Rule**: Native path preference keys are permanent. If a native path is removed,
the key stays in the schema with its default value. This prevents the kill switch
from being silently renamed and losing the ability to remotely disable native code.

---

## 4. Backup Format

| Surface | Value | Reason |
|---|---|---|
| Archive extension | `.amberbackup` | `SYNC_ARCHIVE_EXTENSION = "amberbackup"` in `SyncModels.kt` |
| S3 prefix | `rikkahub_backups/` | `S3Sync.kt` — existing user backups stored under this key prefix |
| S3 file pattern | `backup_*.zip` | Legacy S3 path (pre-sync-v2); filter in `S3Sync.kt:73` |
| Secret mask sentinel | `__MASKED_BY_AMBERAGENT_BACKUP__` | `BackupSettingsRedactor.kt` — changing this breaks restore of masked backups |
| File URI prefix | `amber-file://` | `SyncArchiveManager.kt:698` — embedded in archive entries |

---

## 5. JNI Symbol Prefix Mapping

Each Rust crate's `#[no_mangle] pub extern "system" fn` names are derived from
the Kotlin class FQN. Renaming the Kotlin class or package **without** updating
the Rust `fn` name causes `UnsatisfiedLinkError` at runtime.

| Crate | Rust JNI prefix | Kotlin class |
|---|---|---|
| `markdown-parser` | `Java_me_rerere_rikkahub_ui_components_richtext_nativebridge_MarkdownParserNative_` | `me.rerere.rikkahub.ui.components.richtext.nativebridge.MarkdownParserNative` |
| `regex-transformer` | `Java_me_rerere_rikkahub_data_model_nativebridge_RegexTransformerNative_` | `me.rerere.rikkahub.data.model.nativebridge.RegexTransformerNative` |
| `highlight-parser` | `Java_me_rerere_highlight_nativebridge_HighlighterNative_` | `me.rerere.highlight.nativebridge.HighlighterNative` |
| `office-parsers` | `Java_me_rerere_document_nativebridge_OfficeParserNative_` | `me.rerere.document.nativebridge.OfficeParserNative` |

---

## 6. Application Identity

| Surface | Value | Notes |
|---|---|---|
| `applicationId` | `me.rerere.amberagent` | Already correct. Do not change — Play Store, Firebase, user installs all key on this. |
| `namespace` | `me.rerere.rikkahub` | Legacy; Phase E may consider changing, but only after all R class references are audited. |

---

## 7. JSON Class Discriminators (PreferenceStoreV1Migration)

`PreferenceStoreV1Migration.kt` (at `data/datastore/migration/`) contains JSON
class discriminator strings from the original serialization schema. These strings
are embedded in user DataStore files on disk.

**This file is permanently frozen.** Do not rename, move, or modify the discriminator
strings. Doing so silently corrupts existing user preferences.

---

## 8. Broadcast Action Strings

| Action | File | Notes |
|---|---|---|
| `STOP_GENERATION` and related | `AgentNotificationActionReceiver.kt` | Used in notification PendingIntents. Changing without updating the receiver breaks notification buttons. |
| `TERMUX_COMMAND_RESULT` and related | `TerminalRuntime.kt` | Inter-process communication with terminal. |

These must be migrated as a synchronized pair (sender + receiver) if ever changed.

---

## 9. Shortcuts

`app/src/main/res/xml/shortcuts.xml` hardcodes Activity FQNs. If Activities are
moved to new packages, shortcuts must be updated in the same commit.
