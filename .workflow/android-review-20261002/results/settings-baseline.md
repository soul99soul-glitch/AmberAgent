# Settings / Soul / Provider / WebMount baseline failure recheck

Read-only code review; no production/test changes and no Gradle execution by this agent. Examined the current Android source plus `/tmp/amber-review-baseline-app-tests.log` and `/tmp/amber-review-baseline-failure-recheck.log` supplied by the root agent. XML failure detail was already overwritten; root retains the original detail.

## Confirmed production issue: aggregate settings publishes mixed and older snapshots

Severity: P1. `core/settings/.../prefs/SettingsAggregator.kt:60-84` combines six independently scheduled shared streams decoded from the same DataStore. A single atomic Preferences write updates each decoded stream independently; `combine` can compose new providers with old chat references, or new chat references with old providers. `applyCrossDomainConsistency` then turns a valid newly configured model selection into `DEFAULT_AUTO_MODEL_ID` in that intermediate projection. The canonical UI/runtime state has consequently ceased to represent one committed DataStore snapshot.

There is a second source of rollback in the same publication path. `writeSettings:231` eagerly assigns `settingsForWrite`, while `toMutableStateFlow` independently assigns pending decoded projections. A pending older projection can overwrite the acknowledged write. Therefore a caller can perform `update`, read `settingsFlow.value`, and receive a value older than the update that just succeeded. The same applies to `updateLaunchCount:285-287`.

This is not a test-only race or by design: the comments explicitly promise a canonical settings flow and atomic cross-domain writes. Soul tools, provider tools and runtime consumers read this StateFlow in production. Baseline Soul tests failed while the focused rerun passed; different provider tests failed in the rerun. That variability is consistent with this independently verified publication race, rather than evidence that the failure is harmless.

### Focused repair

Use a single DataStore notification stream to trigger aggregate publication. Decode all six domains from one current Preferences snapshot and publish under the same `writeMutex` that governs aggregator writes. Reuse the existing `readCurrentSettings` composition/seeding/consistency routine. The collection must read the latest DataStore snapshot **inside** the mutex and assign `_settingsFlow.value` **inside** the mutex; merely replacing `combine` with `dataStore.data.map` while retaining eager writes still permits an older queued projection to overwrite the eager write.

A small concrete implementation is `_settingsFlow = MutableStateFlow(Settings.dummy())`, a collector in the existing AppScope, and `writeMutex.withLock { _settingsFlow.value = readCurrentSettings() }` for its triggers. After a successful write, assign `readCurrentSettings()` in the already held mutex, rather than raw `settingsForWrite`. Apply the same canonical publication to `updateLaunchCount` and a reference restore when it changes rehydration. Retain the existing DataStore IOException/fatal error contract; add no retries, timeout loops, framework or fallback state.

### Deterministic regression fixture

A controllable fake `DataStore<Preferences>` can hold a canonical Preferences value separately from its per-collector notification channels. Its `updateData` updates canonical data, while the test can release pending old/new notifications independently. Start all subscribers with an initial snapshot, commit a settings change affecting providers + a model slot + agentSoulMarkdown, then release only selected/old notifications. Record aggregate StateFlow emissions and assert every emitted aggregate corresponds to one committed snapshot, valid new slots are never transiently converted to auto, and an acknowledged update never reverts to the old soul/provider content after old notifications are released. A canonical-reader collector ignores stale trigger payloads and passes; the current six-stream combine fails deterministically when one domain advances alone. No sleeps or repeated probabilistic runs are required.

The test should also cover two consecutive aggregator updates before queued collector notifications are released. Persisted-transform reads already prevent lost fields in `update(fn)`; this test verifies that the publicly visible state now has the same guarantee.

## Soul failures: shared publication race, no new Soul fallback

`SoulImportTransaction.currentSoul:93` and prepare/apply/rollback prechecks read the aggregate StateFlow. `writeSoulWithCas:226` checks the actual persisted setting through `SettingsAggregator.update(fn)`, whose `readCurrentSettings` already reads one durable snapshot under `writeMutex`. It does not overwrite a newer soul based on the stale flow: it returns Stale when the flow read differs from the canonical persisted value. Thus the observed failures can be false Stale results or stale immediate assertions. Repair aggregate publication, then rerun the existing eight Soul tests. Do not mask the problem with Soul retries or sleeps in tests.

## Provider failures: common race plus an incomplete fixture predicate

`ProviderConfigToolsTest.seed:208-224` waits only for provider IDs and two model slot IDs. Matching IDs does not prove matching provider name/baseUrl/models/enabled/authMode/API key, nor title/ocr/compress/suggestion selections. A seeded provider whose identity remains the same but whose configuration changed can satisfy this predicate with old content. Tighten the fixture to compare complete normalized provider content and every seeded model slot (using the expected auto sentinel for dangling model IDs). Comparing only IDs remains inadequate even after the shared publication issue is corrected.

Specific evidence:

- Baseline `apply rejects base_url with embedded userinfo` failed at the post-call baseUrl assertion. The validator did reject the request; this is not evidence of userinfo acceptance. That assertion can read old provider content through the incomplete seed predicate/publication race.
- Focused rerun `set_model_slot model_id writes the slot` failed at the immediate titleModelId assertion (`ProviderConfigToolsTest:755`). Seed does not assert titleModelId readiness, and the post-update acknowledged publication can regress.
- Focused rerun ambiguous reference failed because `candidates` was absent (`:767`), consistent with a stale/mixed provider list. Its explicit seed IDs predicate helps readiness but cannot prevent a later queued old projection.

Root should rerun the full provider contract class after the shared repair and predicate correction; this agent did not independently execute those tests. `runApply` does use a whole-StateFlow target write, and `runSetModelSlot` resolves outside the durable update; broader concurrency issues in those methods are outside this narrowly assigned baseline-failure recheck.

## WebMountTaskCard: confirmed stale test expectations, no production patch

`WebMountTaskCardTest.cardCanCollapseExpandAnimateActivityAndDismiss:177` searches exact text `打开云盘` after collapse. Production `CollapsedSummary` intentionally uses one AnnotatedString `status · title · url`; the title is a substring of that text. Use `substring = true` for the collapsed activity checks (`打开云盘` and later `读取文件`) and keep expanded exact-title assertions where appropriate.

The same test later expects collapsed root height `36.dp`. Production header Row has `heightIn(min = 44.dp)` and summary padding `vertical = 10.dp`; the 36 dp expectation is outdated. Use the actual 44 dp minimum with the default test density/font scale, and make the animation's intermediate-height lower bound the measured settled collapsed height (or the established 44 dp bound), not 36 raw pixels. If the implementation intentionally grows beyond 44 dp due to text/font metrics, test the minimum plus one-line bounded layout instead of inventing a fixed smaller height. Dismiss remains intentionally hidden when collapsed. No product behavior change is justified by this failure.

## Antigravity OAuth: already confirmed configuration gate

Root already verified that blank default BuildConfig client ID/secret prevents the OAuth refresh from reaching the test latch. This is an explicit production configuration gate, not a production race fix. Run the test with synthetic nonsecret Gradle OAuth configuration; do not loosen the production gate.

## Verification status

Source-backed findings above are independently rechecked. No new test execution by this agent. Existing baseline/focused logs establish the named failures and their variability, but do not prove the proposed shared fix. Root must run the deterministic aggregate fixture, core settings round-trip suite, Soul/Provider focused classes, updated WebMount test and OAuth tests with synthetic configuration before claiming closure.

### Phase 5 preparation

Prepared `app/src/test/java/app/amber/core/settings/prefs/SettingsAggregatorPublicationTest.kt` against the unchanged production API. Its fake DataStore holds notifications independently from durable reads. A custom queued Main dispatcher drives actual preference work, then pauses the aggregate publisher continuation by its owning Job tree. This controls the old-projection/latest-write interleaving without sleeps, polling delays, dispatcher injection into production, or repeated probabilistic execution.

Root's `/tmp/amber-review-phase1-green-next-red.log` confirmed the canonical-publication case RED: the immediate acknowledged StateFlow contained the dangling model ID instead of AUTO. The queued-projection case initially timed out because flowOn resumes its child ScopeCoroutine rather than the outer launch Job. The fixture now matches the publisher's public Job.children tree; that fixture correction awaits root's rerun. No production change has been made yet.

Prepared test-only repairs to ProviderConfigToolsTest.seed (serialized provider configuration and all six exact canonical slots), and WebMountTaskCardTest (collapsed substring checks, 44 dp settled height, animation comparisons against measured pixel height). Root owns all Gradle execution and subsequent green verification.

### Authorized implementation

Root's subsequent `/tmp/amber-review-phase1-final-green-settings-red.log` confirms both regression tests failed on their actual business assertions against the original production code (211 tests / 2 failures, other 209 Novel tests green). The queued older projection replaced the latest acknowledged soul; the child Job fix removed the earlier fixture timeout.

After explicit root authorization, implemented the publication repair in SettingsAggregator only. One DataStore notification collector drives a Mutex-protected decode of the latest durable Preferences snapshot. All six domains derive from that same snapshot. Decode/secret rehydration remains off Main using Dispatchers.Default. Successful writeSettings, updateLaunchCount and restoreSecretRefs publish the identical canonical projection under the existing writeMutex before returning. IOException still yields empty Preferences on the collection path, and other collection failures retain the existing fatal halt contract. The cold-start dummy update gate remains in update(fn).

No Soul production retries or fallback logic, new production testing API, framework, Gradle dependency, or unrelated production edits. Both new business regression assertions remain unchanged. `git diff --check` passed. Root green verification and independent phase review are pending; no test execution was performed by this agent.

The initial green attempt exposed a fixture scheduling deadlock: the canonical collector publishes init=false on Default before its initial Main continuation releases writeMutex. The test could mistake that initial completion continuation for a later notification and suspend it while awaiting a writer. The fixture now waits for the fake DataStore's actual initial emit to return before staging the old notification. This establishes a deterministic lock-release barrier, retains the strict acknowledged-latest-then-release-old business assertion, and adds finite test timeouts around writes. No production scheduling or timeout change was needed. Root's rerun is pending.

### Core settings full-suite fixture scheduling

Root's `/tmp/amber-review-core-settings-thread-dump.txt` shows SDK 34 Main blocked in `SecretPrefsChainRoundTripTest` runBlocking, while Default workers are idle. This class was the only core settings test constructing SettingsAggregator. Its previous awaitUntil manually idled the paused Android Looper only until StateFlow matched the predicate; canonical publication could occur on Default before the lock-release continuation resumed on Main. A subsequent blocking update then prevented that continuation from running.

Test-only repair: this class now installs the same UnconfinedTestDispatcher Main fixture already used in app tests, restores Main in teardown, and uses StateFlow.first under a finite test timeout. The test dispatcher runs the collector's Main continuation without depending on the paused SDK Looper, so writers cannot strand its lock-release task. Removed the Looper polling/sleep helper. Added the existing catalog `kotlinx-coroutines-test` as a core settings testImplementation dependency; no production dependency or behavior changed. Other core settings tests do not instantiate the aggregate and need no fixture change. Root reruns the core suite; this agent did not execute Gradle.
