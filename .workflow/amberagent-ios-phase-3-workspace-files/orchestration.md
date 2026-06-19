# Orchestration

1. Confirm git state and recent commits.
2. Spawn four read-only audit agents with disjoint scopes.
3. Read required roadmap, iOS files, and Android references.
4. Produce a Phase 3 Workspace/File Capability Gate Matrix.
5. Implement in dependency order:
   - workspace data model and home
   - import/security bookmark/metadata
   - parser adapters
   - conversation selected file context
   - preview/remove/reparse
   - artifact repository
   - chat/tool/permission wiring
   - tests and docs updates
6. Run narrow validations during implementation and final broad checks.
7. Integrate subagent findings and write final report.

Branching Rules:
- If a real Files/iCloud/privacy state is required, record the blocker and continue mock/local verification.
- If the same external failure repeats twice, switch evidence source before retrying.
- If implementation requires Xcode project structural changes or new entitlements, pause and report.

Packet Prompts:
- See spawned subagent prompts in the transcript and result notes.
