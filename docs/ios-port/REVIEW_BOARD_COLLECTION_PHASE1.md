# Board Collection Phase 1 Review

Date: 2026-06-17

## Scope

Verify the iOS phase 1 manual Board generation chain:

`BoardView → IosBoardFactory → Collector → BoardAgent → OpenAIKmpProvider → result display`

Phase 1 intentionally excludes WorkManager/BGTaskScheduler, hotlist, chat history, Feishu, app usage, and EventKit calendar collection.

## Independent Subagent Review Result

PASS: an independent read-only review verified the explicit chain closure.

### Chain Evidence

- `iosApp/iosApp/AppShell.swift:322` routes `.board` to `BoardView(settingsStore:sharedSettings:)`.
- `iosApp/iosApp/BoardView.swift:198` wires the “生成今日看板” button to `runManualGeneration()`.
- `iosApp/iosApp/BoardView.swift:254` reads `sharedSettings.agentRuntime.todayBoard`.
- `iosApp/iosApp/BoardView.swift:255` obtains `IosBoardFactory.shared`.
- `iosApp/iosApp/BoardView.swift:256` creates `BoardCollectContext` via `createTimeCollectContext(...)`.
- `iosApp/iosApp/BoardView.swift:261` creates collectors through `factory.createCollectors(setting:)`.
- `iosApp/iosApp/BoardView.swift:263` iterates collectors and calls `collectSignals(...)`.
- `iosApp/iosApp/BoardView.swift:274` creates `BoardAgentInterface` through `factory.createAgent(...)` with `SettingsStore` provider config.
- `iosApp/iosApp/BoardView.swift:280` calls `generateBoard(agent:signals:setting:)`.
- `iosApp/iosApp/BoardView.swift:282` stores generated output in `generationState`.
- `iosApp/iosApp/BoardView.swift:230` renders non-empty `generationState.output`.
- `feature/board/api/src/iosMain/kotlin/app/amber/feature/board/IosBoardFactory.kt:6` returns `TimeAnchorBoardSignalCollector`.
- `feature/board/api/src/iosMain/kotlin/app/amber/feature/board/IosBoardFactory.kt:10` creates `IosBoardAgent`.
- `feature/board/api/src/commonMain/kotlin/app/amber/feature/board/TimeAnchorBoardSignalCollector.kt:24` implements real time-signal collection.
- `feature/board/api/src/iosMain/kotlin/app/amber/feature/board/IosBoardAgent.kt:7` imports `OpenAIKmpProvider`.
- `feature/board/api/src/iosMain/kotlin/app/amber/feature/board/IosBoardAgent.kt:18` instantiates `OpenAIKmpProvider()`.
- `feature/board/api/src/iosMain/kotlin/app/amber/feature/board/IosBoardAgent.kt:40` calls `provider.generateText(...)`.

### Honest State / No Fake Content

- `IosBoardAgent` returns an honest empty state when `signals.isEmpty()`.
- `IosBoardAgent` returns honest setup errors for missing API key or model.
- The prompt instructs the model not to fabricate calendar, message, chat, or app-usage facts.
- `BoardView` explicitly states phase 1 only collects time signals and does not fake calendar, hotlist, or chat data.

## Verification Commands

Passed in this session:

- `git diff --check`
- `./gradlew :feature:board:api:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64`
- `xcodegen generate --spec iosApp/project.yml`
- `ios_build_and_run` for `iosApp/AmberAgent.xcodeproj`, scheme `iosApp`, bundle `app.amber.ios`

Screenshot evidence:

- `iosApp/.xcode-derived-data/board-phase1-launch.png`

## Notes

- `shared/build.gradle.kts` no longer directly exports both `:feature:subagent` and `:feature:subagent:api`; this avoids Kotlin/Native duplicate IR binding during `Shared.framework` linking.
- `SubAgentRunner.swift` uses generated Swift type `KotlinUuid` so the iOS app build can complete.
- `TodayBoardSetting.boardModelId` is not consumed in phase 1; manual Board generation uses the current app-wide `SettingsStore.modelId`.

## Final Audit

PASS for phase 1 explicit closure: manual iOS Board generation uses real time signals and real provider configuration, calls KMP `OpenAIKmpProvider`, and displays the returned result without fake Board content.
