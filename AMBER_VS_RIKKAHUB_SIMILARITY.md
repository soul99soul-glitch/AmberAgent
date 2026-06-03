# AmberAgent vs rikkahub similarity report

## Comparison baseline
- Downstream: `main` @ `470d6f29` (2026-05-21, "Improve deep read templates and board model requests")
- Upstream:   `upstream/master` @ `733c54c0` (2026-05-21, "chore(build): 更新版本号到2.2.5")
- Merge base: `1541ec39` (2026-05-01, ~20 days before tip)
- Downstream commits since MB: **460**
- Upstream commits since MB:   **79**
- Quick raw divergence: `git rev-list --left-right --count main...upstream/master` → **460 ahead / 79 behind**

> All comparisons below are downstream `main` vs the merge base (= "what AmberAgent added on top of rikkahub at the fork point"). This is the right view for asking "how much is still rikkahub". Comparing `main` to `upstream/master` directly would mix in upstream's own 79 commits of progress.

## Brand-filter heuristic

A file's diff is classified **brand-only** if every non-context line (every `+` / `-` line, after stripping `diff`/`index`/`---`/`+++`/`@@` headers) contains a case-insensitive match for `rikkahub` or `amberagent`. This catches:

- `me.rerere.rikkahub` ↔ `me.rerere.amberagent` (package & namespace renames)
- `RikkaHub` ↔ `AmberAgent`, `rikkahub` ↔ `amberagent` (display/string variants)
- `applicationId`/`namespace` lines in gradle, `app_name` strings, manifest labels
- launcher icon binary swaps (counted as brand-only but contribute zero LOC)

This is a coarse heuristic — it will under-count brand noise that is mixed into a logic-heavy diff (a single brand line in a 500-line file makes the file substantive). To compensate, I also count brand-keyword lines inside substantive files and subtract them. The two-pronged count gives a tight upper bound on brand noise.

## Headline numbers

| Metric | Value |
|---|---|
| Total tracked files in `main` | **1,581** |
| Total tracked files at merge base (fork point) | 889 |
| Total tracked files in `upstream/master` | 1,450 |
| Files **still identical** to fork-point rikkahub | **640 / 889 = 72.0%** of fork-point tree, **40.5%** of current tree |
| Files modified by AmberAgent | 239 |
| Files newly added by AmberAgent | **702** |
| Files deleted by AmberAgent | 10 |
| Total LOC delta (raw) | **+183,479 / −13,467** (≈196.9k touched lines) |
| ↳ Brand-only files contribution | +17 / −17 text lines + 20 launcher PNGs (no text LOC) |
| ↳ Brand-keyword lines inside substantive files | ~867 lines (mixed +/−) |
| **Brand-only LOC fraction of total churn** | **~0.46%** |
| **Substantive LOC (brand renames filtered out)** | **≈ +182,595 / −13,388** |

**Translation:** of every 200 lines AmberAgent has touched, roughly 1 line is brand-rename noise. The fork is doing real product work, not just relabeling.

## Where the substantive work lives — by module

| Module | Status | Files M | Files A | Files D | LOC ± (raw) | Brand-keyword lines (subset) |
|---|---|---:|---:|---:|---:|---:|
| `app/` | **massively expanded** (200 added subsystems + 196 rewrites) | 196 | 600 | 10 | +151,911 / −13,070 | 836 |
| `ai/` | enhanced (provider tweaks + tool additions) | 20 | 6 | 0 | +2,911 / −178 | 2 |
| `common/` | mostly pristine (1 new file) | 0 | 1 | 0 | +301 / 0 | 0 |
| `highlight/` | mostly pristine (1 mod + 1 add) | 1 | 1 | 0 | +251 / 0 | 0 |
| `search/` | light additions | 8 | 5 | 0 | +657 / −47 | 2 |
| `tts/` | pristine (1-line brand fix) | 1 | 0 | 0 | +1 / −1 | 0 |
| `native/` | **NEW module (Rust)** added by fork | 0 | 22 | 0 | +5,258 / 0 | 0 |
| `web/` | pristine | 0 | 0 | 0 | 0 / 0 | 0 |
| `web-ui/` | trivial (brand strings only) | 5 | 0 | 0 | +8 / −8 | 2 |
| `locale-tui/` | pristine | 0 | 0 | 0 | 0 / 0 | 0 |
| `document/` | mostly pristine (1 add) | 1 | 1 | 0 | +149 / 0 | 0 |
| `docs/` | **massively expanded** (UI mockups, design docs) | 0 | 41 | 0 | +16,095 / 0 | 0 |
| `scripts/` | 1 new build script | 0 | 1 | 0 | +302 / 0 | 0 |
| `.agents/` | new skill packs added | 0 | 24 | 0 | +5,595 / 0 | 0 |
| `.claude/` | pristine | 0 | 0 | 0 | 0 / 0 | 0 |
| `.github/` | pristine | 0 | 0 | 0 | 0 / 0 | 0 |
| `gradle/` | minor version bumps | 1 | 0 | 0 | +12 / −5 | 0 |

(Per-module totals sum to +183,451 / −13,309; the remaining ~178 LOC live in root-level files like `README*.md`, `settings.gradle.kts`, `CLAUDE.md`, `.gitignore`.)

## What the `app/` blast radius actually is

Within `app/src/main/java/me/rerere/rikkahub/` the 435 added Kotlin files are concentrated as follows:

| Subpackage | Added files | Notes |
|---|---:|---|
| `data/agent/webmount` | 70 | New: bridge JS + Android-side mount runtime |
| `data/agent/board` | 42 | New: "board" / DeepRead surface |
| `data/agent/tools` | 31 | New: agent tool registry |
| `data/agent/miniapp` | 12 | New: miniapp embedding |
| `data/agent/subagent` | 9 | New: sub-agent orchestration |
| `data/agent/icloud` | 6 | New: iCloud sync path |
| `data/agent/office` | 5 | New: office-file integration |
| `data/agent/modelcouncil` | 5 | New: multi-model "council" |
| `data/agent/terminal` | 4 | New: terminal/proot integration |
| `data/agent/task` | 4 | New: task abstraction |
| `data/db` | 48 | Many new DB entities + 13 schema-JSON migrations |
| `data/ai` | 28 | New: agent-AI glue |
| `data/memory` | 17 | New: long-term memory subsystem |
| `data/sync` | 10 | New: sync layer |
| `data/datastore` | 10 | New: DataStore additions |
| `data/context` | 10 | New: agent context |
| `ui/pages` | 46 | Many new screens (DeepRead, board, model-council, miniapp, etc.) |
| `ui/components` | 27 | Supporting UI |
| `di` | 8 | DI wiring for the new subsystems |
| `service` | 7 | New foreground services (incl. ChatService rewrite) |

Largest single files touched (top 5 by ins+del):

1. `app/schemas/.../AppDatabase/30.json` — 2,818 (new DB schema)
2. `app/schemas/.../AppDatabase/29.json` — 2,681
3. `app/schemas/.../AppDatabase/28.json` — 2,565
4. `app/schemas/.../AppDatabase/27.json` — 2,434
5. `docs/ui-mockup-2026-05-05.html` — 2,157

The 10 files **deleted** by the fork are concentrated and intentional — AmberAgent stripped rikkahub's monetization/update path:
- `data/api/RikkaHubAPI.kt`, `SponsorAPI.kt`, `model/Sponsor.kt`
- `ui/pages/setting/SettingDonatePage.kt`, `ui/components/ui/UpdateCard.kt`
- `utils/UpdateChecker.kt`
- the entire `ui/pages/backup/tabs/` directory (ImportExport, Reminder, S3, WebDav tabs)

## New modules in AmberAgent (not in upstream/master and not at fork point)

- **`native/`** — full Rust workspace (`Cargo.lock`, `Cargo.toml`, `highlight-parser`, `markdown-parser`, `office-parsers`, `regex-transformer`). 22 files, +5,258 LOC. This is the only entirely new top-level Gradle/build sibling.

(`docs/`, `.agents/`, `scripts/` and `web-ui/` already existed at the merge base; AmberAgent expanded `docs/` and `.agents/` substantially but did not create them.)

## Modules removed by AmberAgent

- **None** at the top-level. The fork keeps every module it inherited from rikkahub (`app`, `ai`, `common`, `highlight`, `search`, `tts`, `document`, `web`, `web-ui`, `locale-tui`).
- Note: upstream `master` has since added `:speech` (renamed from `:tts`) and `:material3` modules that AmberAgent has *not* taken — that's upstream divergence, not fork removal.

## Bottom-line assessment

By file count, **~72% of the fork-point rikkahub tree is still byte-identical in AmberAgent**, and AmberAgent has added another ~702 files of its own work on top, so only ~40% of the *current* tree is verbatim rikkahub. By LOC churn, the fork has touched ~197k lines vs the fork point and **brand renames account for under 0.5% of that** — the divergence is overwhelmingly real product code, not relabeling.

The divergence is also extremely **lopsided by module**: `ai/`, `common/`, `highlight/`, `search/`, `tts/`, `web/`, `web-ui/`, `locale-tui/`, `document/` are essentially pristine rikkahub (≤1% LOC changes each, mostly brand strings). Effectively all of AmberAgent's work lives in three places:

1. **`app/` (+152k / −13k)** — a large new `data/agent/*` subsystem (webmount, board/DeepRead, tools, miniapp, sub-agent, model-council, terminal, memory, sync, context — ~211 new files just under `data/agent`), 13 new Room migrations, 46 new screens, 7 new services, and the deliberate deletion of rikkahub's sponsor/donate/update/backup features.
2. **`native/` (+5.3k, brand-new)** — a Rust workspace shipping `libmarkdown_parser.so` and `libregex_transformer.so` (with the `org.mozilla.rust-android-gradle` plugin removed and replaced by hand-rolled `Exec` tasks in `app/build.gradle.kts`, presumably for AGP 9 compatibility).
3. **`docs/` + `.agents/` (+21.7k)** — design mockups and agent-skill content, not shipped code.

So: AmberAgent is best described as "rikkahub's AI/provider/search/UI plumbing kept intact, plus a roughly app-sized agent platform bolted onto it." The kernel is still rikkahub; the product on top is its own.

<!-- buddy: *八条腿同时拍胸脯* 这不叫 fork，这叫在别人家的地基上盖了座新楼，地基还没动 -->
