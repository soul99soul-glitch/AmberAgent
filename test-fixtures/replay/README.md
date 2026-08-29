# Amber replay goldens (schema v1)

Cross-platform replay-consistency fixtures from the agent-kernel architecture
audit ("P0：建立双端回放一致性测试"). The same ten scenarios are scripted on
Android and iOS; each side reduces its run to a **normalized event sequence**
and both sides must produce byte-identical goldens. Compared are normalized
events — never UI text.

- Android harness: `app/src/test/java/app/amber/agent/replay/ReplayGoldenTest.kt`
- Goldens: `test-fixtures/replay/v1/<scenario>.golden` (on the `:app` unit-test
  classpath via the root `test-fixtures/` resource dir)

## The ten scenarios

| scenario | script |
| --- | --- |
| `s01_plain_text` | 模型输出普通文本 |
| `s02_single_readonly_tool` | 模型输出一个只读工具 |
| `s03_multiple_tools_one_round` | 模型同时输出多个工具（并行批次） |
| `s04_approval_granted` | 工具要求审批，用户批准 |
| `s05_approval_denied` | 用户拒绝审批 |
| `s06_process_death_mid_tool` | 工具执行中进程死亡 |
| `s07_outcome_unknown_then_retry` | 非幂等工具结果未知 → 用户确认重试 |
| `s08_steer_mid_stream` | 流式中插入 steer |
| `s09_step_limit` | 达到 step limit |
| `s10_subagent_reports_to_parent` | 子代理向父代理回传消息 |

## Normalized vocabulary (v1)

One event per line, in causal order:

```text
run_started
run_resumed
run_paused state=<wireName>
run_terminal status=<wireName>
tool_prepared tool=<name>
tool_started tool=<name>
tool_finished tool=<name> status=<finished|failed|denied>
assistant_message
```

`<wireName>` is the `RunStatus` wire name (`waiting_user`, `completed`,
`outcome_unknown`, `step_limit`, …). Harness-only annotations, likewise
deterministic:

```text
# process_death
steer_injected count=<n>
tool_outcome_unknown tool=<name>
tool_reconciled tool=<name> decision=<retry|abandoned>
```

`steer_injected count=<n>` is emitted by each end from its own
steer-injection point, at the moment queued steer messages are handed to the
kernel for the next provider round.

## Normalization rules

1. **Causal log** — every store mutation (run transition applied by CAS,
   decoded tool/assistant event, harness note) is appended to one
   monotonic-counter log. Run transitions are recorded only when the CAS was
   applied and actually changed the state (`from != to`); rejected/duplicate
   CAS attempts are invisible.
2. **Grouping** — entries are grouped by `runId` in first-appearance order,
   and every run is aliased uniformly: the first run is `root`, the next
   `run_2`, then `run_3`, … A golden with more than one run has one section
   per run, each opened by a `## run <alias>` header (root included); a run
   whose events carry a `parentRunId` gets `parent=<parentAlias>` appended
   to its header. Single-run goldens keep no section header.
3. **Parallel-window collapse** — within a run, maximal windows of
   tool-lifecycle entries are examined for execution-span overlap
   (`tool_started … tool_finished` per `toolCallId`). Overlap ⇒ a parallel
   batch: the window is phase-sorted (`tool_prepared` in log order, then
   `tool_started`/`tool_finished` sorted by `toolCallId`). No overlap ⇒ the
   log order is kept verbatim (sequential rounds stay distinguishable).

## Fidelity notes

1. s10's parent linkage relies on the child run emitting ≥1 event: run rows
   carry `parentRunId=null` in the in-process runner, so the `parent=`
   alias is resolved from the child's event rows, never from the run row.
2. `tool_finished` deliberately drops `errorCategory` in v1: user-denied and
   step-limit-denied both normalize to `status=denied`.
3. Harness notes (`# process_death`, `steer_injected`) are script
   annotations, not runtime events.
4. The P6-01 stored-response recovery branch is not exercised by this
   harness (the recovery service under test is wired without
   storedResponseGateway/capabilityFlags/resumeStore).
5. RequestSnapshot events (Step 5) DO appear in the Android event stream —
   the run writers persist them like any registered Final — but schema v1
   deliberately excludes them from normalization (the recorder's
   `else -> Unit` drops unknown payload types), so adding or removing
   snapshots can never change a golden.
6. Step 5 covers the main generation round only: auxiliary model calls
   (context compaction summaries, the OCR/vision transformer, title and
   memory extraction) do not emit request snapshots yet. That is a
   documented follow-up boundary, not an omission of this suite.

## Regenerating goldens

```bash
./gradlew :app:testDebugUnitTest --tests "app.amber.agent.replay.ReplayGoldenTest" -PupdateGoldens=true
```

Update mode rewrites `test-fixtures/replay/v1/*.golden` from the recorded
runs (the flag is forwarded to the test JVM alongside `goldenDir` in
`app/build.gradle.kts`). Never regenerate blindly: diff every golden and
treat any unexpected change as a kernel-behavior regression until proven
otherwise. Without the flag the tests compare against the committed fixtures.
