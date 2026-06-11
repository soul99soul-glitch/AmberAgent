# Codex Handoff 2026-06-11: Risk Audit Third Batch

This handoff is for continuing AmberAgent risk-audit fixes from the current
root repository.

## Repository Anchor

- Repo: `/Users/arquiel/Downloads/AI/amberagent`
- Branch: `main`
- Push remote: `origin`
- Remote URL: `https://github.com/soul99soul-glitch/AmberAgent.git`
- Remote anchor before this handoff: `origin/main` at `e517e62c`
- Second-batch fix anchor: `b378872d fix: second batch of risk-audit fixes`
- This handoff document is expected to be committed after `b378872d`.

Use the root `settings.gradle.kts` as the source of truth for active modules.
Do not treat nested or sibling legacy copies such as `main/`, `legacy/`,
`jank-opt/`, `web/`, or `web-ui/` as active code unless the root settings file
includes them.

## Safety Notes

- Do not print, copy into logs, or publicly push `app/google-services.json`.
- The real Firebase config was separately stored in the private secrets repo.
- Known local debug keystore SHA-1:
  `F9:81:DC:61:B9:3F:61:36:25:EE:8C:41:9B:6E:AC:06:B3:0A:EA:FF`
- Current worktree had unrelated local dirt before this handoff:
  `.DS_Store`, `main` submodule pointer, and `ui-graphite` submodule pointer.
  Do not include these unless the user explicitly asks.
- A local Notion backup tarball is present on disk and ignored by `.gitignore`:
  `/amberagent-notion-data-backup-*.tgz`. Do not commit it.

## User Direction

Use Claude Code's original risk judgment as the decision baseline. Do not spend
the next session re-litigating the classification unless a fix attempt exposes a
direct contradiction in current code.

Goal for the next Codex session:

1. Pull the pushed `main`.
2. Verify the first two batches are present.
3. Fix the third batch with small, targeted patches.
4. Update the risk audit document status after the third batch.
5. Run focused tests plus a compile check.
6. Commit and push only intended files.

## Already Completed And Pushed

These commits are already on `origin/main` before the second batch is pushed:

- `b1459613 fix(security): first batch of risk-audit fixes`
- `7de89717 fix(stream): merge parallel tool-call deltas by stream index`
- `312acd68 docs: add 2026-06-11 active-code risk audit`
- `e517e62c chore: gitignore local notion data backup tarballs`

First batch fixed:

- `APP-LOG-001`, `MCP-002`, `AI-CLAUDE-001`: log redaction and logging hygiene.
- `APP-APPROVAL-001`: `wm_site_remove` requires approval but respects the
  global/high-risk auto-approval switches.
- `MCP-001`: dynamic `mcp__*` tools default to approval-required while existing
  auto-approval semantics remain available.
- `TOOL-HTTP-001`: loopback/private `http_request` targets are escalated to
  high risk instead of being silently treated as safe.
- `APP-SHARE-001`: share text double-base64 bug.
- `APP-SHORTCUT-001`: camera shortcut passes `Uri` correctly.
- `WEBVIEW-001`: filtered webview link indices now preserve original indices.
- `APP-IMAGE-001`: base64 image decode null checks.
- `WORKSPACE-001`: uploaded display names are sanitized to basenames.
- `TERMINAL-001`: stopping a terminal tolerates a missing SAF root.
- `COMMON-HTTP-001`: coroutine cancellation now cancels the OkHttp call.

The stream commit fixed `AI-STREAM-001`:

- Parallel OpenAI tool-call streaming deltas are merged by stream index.
- Tests were added in the `ai` module.

The risk document currently exists at:

- `docs/amberagent-risk-audit-2026-06-11.md`

Important: that document was written before the first two batches landed. It
should be treated as the original audit plus evidence, not as current status.

## Completed Locally In Second Batch

Second batch commit:

- `b378872d fix: second batch of risk-audit fixes`

It fixed:

- `APP-DATA-001`: deletion from History now defers attachment/generated-image
  cleanup until the snackbar resolves, so Undo keeps files intact.
- `APP-FAVORITE-001`: final conversation deletion cascades favorites to avoid
  dangling favorite entries.
- `APP-WORK-001`: Cron, Board, and MemoryDream workers no longer enqueue
  same-name unique work with `REPLACE` at worker start. They schedule the next
  run from `finally` using `NonCancellable`, and skip rescheduling when
  externally stopped.
- `AI-OPENAI-001`: `parseResponseOutput()` handles
  `image_generation_call`, so non-streaming and `response.completed` paths keep
  generated images.
- `SETTINGS-001`: per-entry JSON/UUID decode uses safe fallback helpers instead
  of throwing into the flow collector and reaching `halt(1)` for corrupted prefs.
- `DEEPREAD-001`: background DeepRead prefetch skips loopback/private hosts
  unless both global auto-approval and high-risk auto-approval are enabled.
- `MINIAPP-001`: MiniApp bridge dispatches JS requests on a serial coroutine
  scope instead of blocking the JavaBridge thread with `runBlocking`.

Claude Code reported these checks passed after the second batch:

- `:app:compileGraphiteKotlin`
- `:ai` tests
- `:core:settings` tests
- app module unit tests

If continuing from a fresh machine, rerun focused checks rather than trusting
only this note.

## Third Batch Main List

Start here. These are the items Claude Code explicitly left for the next batch:

- `APP-TOOL-001`: download and zip extract paths silently truncate but can still
  return success. Add a `truncated` signal or fail closed on limit.
- `AI-GOOGLE-001`: Google non-streaming response parsing uses fragile `!!` paths
  for empty candidates/safety-block scenarios. Reuse the streaming path's
  graceful handling.
- `AI-FILE-001`: multimodal file encoding hardcodes MIME types such as mp4/mp3.
  Use MIME guessing similar to image handling.
- `DOC-001`: document-to-prompt and PDF parsing have full-read/resource lifetime
  risks. Add size preflight/capped read and ensure native PDF resources are
  released.
- `TOOL-READ-001`: workspace/iCloud/office/external file tools read full content
  before truncating. Cap reads before allocation/network amplification.
- `SYNC-001`: backup/sync/skill import paths lack size limits and can cause
  memory DoS. Add bounded read/import behavior.
- `OAUTH-001`: OpenAI/Google OAuth tokens are stored in plain
  `SharedPreferences`. Move to encrypted storage with migration.
- `RUNTIME-001`: persisted unfinished run recovery is not wired. Connect
  `replayUnfinished()` at startup and mark recovered runs interrupted.
- `RUNTIME-002`: `InProcessAgentRunner` maps are not cleaned after completion.
  Add completion cleanup.
- `TTS-001`: TTS can return a successful 0-byte MP3 when no audio chunks arrive.
  Return a controlled error instead.
- `SYNC-OLD-001`: old WebDAV/S3 restore can apply settings before later restore
  steps fail. Move settings application after DB/files success.
- `BUILD-002`: embedded terminal runtime build download is not checksum-pinned.
  Pin commit/source and verify SHA-256.
- `DEBUG-001`: debug smoke receiver can expose high-risk test actions. Scope it
  down or add the same high-risk guard at receiver entry.

## Additional True Bugs From Claude's Original Review

Check these after the main list, or fold them into nearby patches if the touched
area overlaps naturally:

- `APP-FILE-001`: `deleteChatFiles()` has partial path protection but can still
  delete arbitrary non-mirror `file:` URIs from corrupted/malicious chat data.
  Add a narrow filesDir/workspace allowlist guard.
- `PERM-001`: `sms_read` permission check is weaker than the provider query
  actually needs. Require `READ_SMS`.
- `PERM-002`: calendar create requests `WRITE_CALENDAR` but first reads calendar
  rows. Add `READ_CALENDAR`.
- `TTS-002`: System TTS initialization has a theoretical callback ordering race.
  Fix assignment/order locally if touching TTS.
- `SYNC-TEST-001`: sync schema coverage can drift. Update tests to read the
  latest schema version if this area is touched.

Claude's original by-design/no-fix calls:

- `BUILD-001`: release build failing without Firebase config is an intentional
  guard; document rather than code-fix unless the user asks.
- `HIGHLIGHT-001`: Highlighter lifecycle concern was judged no actionable bug
  because the highlighter is effectively a Koin singleton and `destroy()` is not
  used in the active path.

## Suggested Order

1. Pull `origin/main`, confirm `b378872d` and this handoff are present.
2. Run a quick baseline:
   `./gradlew :app:compileGraphiteKotlin :ai:test :core:settings:test`
3. Fix narrow correctness bugs first:
   `TTS-001`, `AI-GOOGLE-001`, `AI-FILE-001`, `PERM-001`, `PERM-002`,
   `RUNTIME-002`.
4. Fix bounded-read and restore-order bugs:
   `APP-TOOL-001`, `DOC-001`, `TOOL-READ-001`, `SYNC-001`, `SYNC-OLD-001`.
5. Fix persistence/security hardening:
   `OAUTH-001`, `RUNTIME-001`, `BUILD-002`, `DEBUG-001`, `APP-FILE-001`.
6. Update `docs/amberagent-risk-audit-2026-06-11.md` with current statuses.
7. Run focused tests plus compile, then commit and push.

Keep patches small. This is a bug-fix pass, not a style cleanup or architecture
rewrite.

## Bootstrap Prompt For Company Codex

```text
You are continuing AmberAgent risk-audit fixes in
/Users/arquiel/Downloads/AI/amberagent.

Read:
- AGENTS.md
- docs/CODEX_HANDOFF_2026-06-11_RISK_AUDIT_THIRD_BATCH.md
- docs/amberagent-risk-audit-2026-06-11.md

Use root settings.gradle.kts included modules as the active project boundary.
Ignore legacy/main/jank-opt/web/web-ui copies unless root settings includes
them. Do not print or commit google-services.json or other secrets.

First verify git state and that these commits are present:
- b1459613 first risk-audit security fixes
- 7de89717 stream tool-call merge fix
- b378872d second batch risk-audit fixes

Take Claude Code's original bug classification as baseline. Do not redo the
whole audit. Fix the third batch only, with small targeted patches and focused
tests. Avoid unrelated refactors and do not stage .DS_Store or submodule pointer
changes unless explicitly requested.
```
