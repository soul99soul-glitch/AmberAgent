Review the Council Room feature's merged changes for logic closure and call chain integrity. Focus on:

1. **State machine completeness**: Are all status transitions covered? Is there any path where the room gets stuck in a non-terminal state?

2. **Call chain integrity**: Trace the full path from `userMessage` → `maybeStartAutoRun` → `runAutoOrchestration` → `synthesize`. Are there any broken links or missing callbacks?

3. **Race conditions**: The code uses `jobsLock` + per-conversation `Mutex`. Are there any gaps where concurrent operations could corrupt state?

4. **Edge cases**: What happens when `close()` is called mid-generation? What happens when `resumeAfterUserAnswer` races with `close()`? What happens when `runAutoOrchestration` throws an exception?

5. **Tool-augmented host turns**: The `AppCouncilHostToolProvider` bridges `ProviderManager.streamText` + `AgentToolDispatcher.executeBatch`. Are there any leaks or infinite loops in the tool round-trip?

6. **UI state consistency**: The `CouncilAskUserCard` uses local `remember` state for `answered`. Is this correct across recompositions and process death?

Key files to examine:
- `feature/modelcouncil/src/main/kotlin/app/amber/feature/modelcouncil/CouncilRoomManager.kt` (main orchestration)
- `feature/modelcouncil/src/main/kotlin/app/amber/feature/modelcouncil/CouncilRoomExecutor.kt` (generation execution)
- `app/src/main/java/app/amber/feature/modelcouncil/AppCouncilHostToolProvider.kt` (host tools)
- `app/src/main/java/app/amber/feature/ui/pages/councilroom/CouncilTimelineTab.kt` (UI)
- `feature/modelcouncil/api/src/main/kotlin/app/amber/feature/modelcouncil/CouncilRoom.kt` (data model)

Please provide a detailed analysis with specific line references and severity ratings for any issues found.