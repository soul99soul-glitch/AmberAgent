# Orchestration

1. Capture git baseline.
2. Create Advanced Execution Capability Gate Matrix from roadmap, iOS files, Android references, and four read-only audit packets.
3. Implement in order:
   - unified task state model
   - SubAgent task loop
   - Council task loop
   - remote execution status/log loop
   - tool approval and permission records
   - chat result refill
   - error and empty states
   - tests
4. After each lane, run the narrowest useful validation.
5. If the same external blocker appears twice, switch to mock/static validation and record the blocker.
6. Finish with diff review, `git diff --check`, build/test attempts, and final report.

Risk controls:
- Use mocked/local deterministic execution for remote and model work.
- Treat high-risk actions as requiring explicit approval records.
- Keep Android code read-only.
- Avoid broad file ownership collisions with existing MiniApp, Workspace, WebMount, and DeepRead work.
