# Independent Settings publication review

Scope: SettingsAggregator changes relative to the frozen Android baseline; SettingsAggregatorPublicationTest, ProviderConfigToolsTest seed changes and WebMountTaskCardTest assertions. Read-only production/test review; no Gradle execution.

## Conclusion

**No blocking regression or over-design found in the assigned publication repair.** The two accepted publication defects are closed in source. Root owns the updated focused/full green gate.

| Concern | Evidence and conclusion |
| --- | --- |
| Mixed durable snapshots | `SettingsAggregator.kt:64-81` replaces combine of six independently timed domain projections with one DataStore-triggered collector. `composeCurrentSettings:240-252` decodes all domains from exactly one Preferences object. Backfill/seeding and cross-domain model consistency remain the same canonical routine. |
| Older projection after acknowledged write | Notification payload is intentionally ignored. The collector takes writeMutex, then reads latest durable Preferences and assigns the canonical StateFlow before releasing that lock (:74-80). A queued old notification cannot carry an old decoded value past a later acknowledged writer. |
| Canonical write acknowledgement | `writeSettings:228-230` publishes readCurrentSettings after successful atomic edit, while its caller retains writeMutex. It no longer publishes the unnormalized argument. Model slots, seeded values and secret rehydration therefore match the collector. |
| Related write paths | `restoreSecretRefs:258-266` and `updateLaunchCount:280-286` use the same durable projection before return. `update(fn):269-277` retains the cold-start dummy gate and reads persisted state before its transform once initialized. No unrelated write algorithm was replaced. |
| Main thread | Collector decode/secret rehydration runs in Dispatchers.Default (:75-78); acknowledged write readback stays inside existing Dispatchers.IO blocks (:93-95,260-266,270-277,281-286). Mutex acquisition and lightweight bookkeeping are the only new Main work. |
| IO/fatal contract | Collection still maps DataStore IOException to empty Preferences, and other failures retain the previous fatal contract. Writer reads continue to propagate persistence errors, rather than silently acknowledge an empty fallback. |
| Minimal implementation | One collector, existing mutex and reused canonical decode routine. No retry, sleep, timeout, new testing API, replacement state owner, generalized framework or dependency added. |

## Regression tests and fixture changes

Root confirmed both deterministic publication cases RED on their business assertions against the original implementation. The new `SettingsAggregatorPublicationTest` holds durable preferences separately from old notification delivery. The canonical-ack case checks an invalid selected model immediately becomes AUTO and latest soul is visible before notifications. The queued-projection case pauses the real publisher continuation/child Job, performs a later durable write, resumes the old notification and checks the acknowledged soul is retained. The fixture's child Job matching and initial mutex-release synchronization address scheduling mechanics without production dispatcher/test hooks.

`ProviderConfigToolsTest.seed` now compares complete serialized provider configuration and all six normalized slots. It no longer declares readiness solely from identity or accepts AUTO for a valid model ID. This repairs a concrete incomplete fixture predicate, without changing provider production behavior.

`WebMountTaskCardTest` collapsed activity assertions use substring matching because production presents a combined status/title/address AnnotatedString. Its settled height is the production 44 dp minimum (`WebMountTaskCard.kt:125`), and intermediate animation assertions compare pixel heights to the measured collapsed pixel height. The expanded/dismiss behavior assertions remain. No visual production workaround was added.

## Normal cancellation versus the inherited fatal contract

The new collector copies the preexisting `SettingsFlowExt.toMutableStateFlow:31-37` runCatching/fatal-halt behavior, including treating scope CancellationException as failure. AppScope lives for the process lifetime; the explicit normal cancellation entry is `AmberAgentApp.onTerminate:474`, normally relevant to emulated processes rather than actual Android process shutdown. This is an inherited limitation, not a regression introduced by the publication repair. I did not broaden this phase into changing every domain flow's fatal policy or add a cancellation-only workaround to the new aggregator while the existing domain collectors retain the same contract. If the project later makes AppScope cancellation a live restart/teardown mechanism, that shared cancellation contract should be changed and exercised together.

## Verification boundary

This review establishes source correctness and meaningful regression intent. It does not claim full green, device verification or externally configured provider/OAuth acceptance. Root owns Settings publication regression, secret prefs roundtrip, Soul/Provider, updated WebMount and the broader authorized checks.

## Core suite fixture follow-up

The final full core run exposed a Robolectric Main scheduling deadlock in SecretPrefsChainRoundTripTest, rather than a new Android settings-persistence defect. Independent baseline diff confirms the remedy changes only this test class and its coroutines-test testImplementation dependency: install/reset UnconfinedTestDispatcher Main; replace manual idle/delay polling with finite withTimeout StateFlow.first; retain all existing secret persistence assertions. Root's core-settings-green log and XML now establish 95 core tests / 0 failures / 0 errors / 0 skips, including 9 real prefs/secret chain cases. No production lock policy or retries were modified. Static follow-up approved; reviewer did not run Gradle.
