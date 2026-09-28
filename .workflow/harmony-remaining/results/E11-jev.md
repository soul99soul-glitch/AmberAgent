# E11 Jev A — source implementation and evidence

Scope: `jev_models.ts`, new `jev_approval.ts` / `jev_web.ts`, Entry `JevSupport.ets`, new `JevApprovalTriage.ets` / `JevWebDecision.ets`, and one `jev_approval_lifecycle.test.cjs` group. Shared index/DI/service and UI remain their respective owners' changes. Loaded the harmony-arkts skill and frozen E11 contract; no SDK API was added.

## Delivered behavior

- Old four purposes retain global mode. New `approvalTriage` / `webAutomation` each have OFF-by-default mode and three false-by-default material allowances; old saved JSON imports safely. Global OFF caps both OFF; global SHADOW caps ACTIVE to SHADOW.
- Pure subject builder uses the saved pending parent and actual child step. It binds canonical conversation/message/part/toolCallId plus initiating user-message identity, exact parent/child inputs, package hashes/execution/index/candidate-test expectation, or pinned import candidate/base hashes. SSH/Mosh execution requires the persisted target/protocol. It never reads a live package, default terminal target or credential. Missing valid saved context yields no subject.
- `bindingJSON` stays local and is hashed by the existing synchronous Entry `sha256HexUtf8`. `currentJevApprovalSubjectHash(conversation,toolCallId,locator)` uses the same current-branch pending scanner as the runtime; UI cannot use stored metadata hash as evidence for itself.
- Three neutral facts use probability <=0.35=no, >=0.65=yes, otherwise unknown. Missing material remains unknown; nonfinite/out-of-range answers remain errors. Saved policy supplies deterministic facts; `mutates=false` alone does not establish readonly. Imports are known mutations.
- Outbound state contains only explicitly constructed tool name/policy scalar fields and, when allowed, one locally extracted finite task-intent category. Raw command/script/arguments/headers/credentials/task sentence/query strings/package files/subject JSON are absent. Unknown or mixed task intent stays unknown. Prompt treats scalar data as data and cannot approve/deny/answer/execute.
- Runtime starts only through the root's saved-pending scheduling consumer. OFF performs no evaluation or application. SHADOW, including actual `jevEvaluate().shadow`, produces only narrow observations and no labels/approval change. ACTIVE merges `jev_approval_v1` into the latest canonical metadata, preserving all other metadata, output and human approval state.
- Completion rechecks controller identity/cancellation, settings, saved subject and policy. The actual queued mutation callback asynchronously reloads the full settings inside the write queue before merging; queue delay cannot let an OFF/changed configuration receive a completed result. Answered/deleted/changed-child/changed-user/changed-target/cancelled results cannot apply.
- `jevEvaluate(state,questions,signal?)` passes the caller's actual `AbortSignalLike` to HTTP and treats cancellation separately from error cooldown. Existing daily budget, cooldown and strict decoder remain authoritative; original two-argument calls remain valid.
- Web delivers a callable finite 1–8 candidate-ID Choice seam with a reserved handback option, no DONE or generated input/tool/URL/JS. Raw candidate inputs/descriptions/page text are excluded. OFF/SHADOW never return an actionable result. `web_automation` is absent from wired purposes; E12 must supply the real observe/revision/lease/permission/dispatcher/completion consumer.

## Source interfaces for root / UI

Domain new exports: `JevNewPurpose`, `JevNewPurposeSettings`, `makeJevNewPurposeSettings`, `JevEvaluateResult`; `JevFact`, `JevApprovalLocator`, `JevApprovalTriage`, `JevApprovalSubject`, `JevApprovalPolicy`, `JevPendingApproval`, `JevApprovalBatch`; `resolveJevPurposeMode`, `jevFactFromProbability`, `buildJevApprovalSubject`, `readJevApprovalTriage`, `listJevPendingApprovals`, `jevTaskIntent`, `buildJevApprovalBatch`, `completeJevApprovalFacts`; `JevWebCandidate`, `JevWebObservation`, `JevWebDecision`, `buildJevWebChoice`.

Entry approval exports:

```ts
JevApprovalTriageDeps {
  loadConversation(id:string):Promise<Conversation|null>;
  mutateConversation(id:string, update:(latest:Conversation)=>
    Conversation|null|Promise<Conversation|null>):Promise<Conversation|null>;
  loadSettings():Promise<JevSettings>;
  evaluate(state:JsonObject,questions:Record<string,JevQuestion>,signal?:AbortSignalLike):Promise<JevEvaluateResult>;
  policy(action:UIMessagePartTool):JevApprovalPolicy|null;
  recordShadow(observation:JevTriageObservation):Promise<void>;
}
initializeJevApprovalTriage(deps:JevApprovalTriageDeps):void;
scheduleJevApprovalTriage(conversationId:string,signal?:AbortSignalLike):void;
cancelJevApprovalTriage(conversationId:string):void;
currentJevApprovalSubjectHash(conversation:Conversation,toolCallId:string,locator:ToolApprovalPartLocator):string;
```

Both initialization functions only store host ports; new modules import neither AppContainer nor ConversationRunService. Root provides the actual repository/write queue/settings/evaluator, saved `permission_trace.policy`, and bounded metadata-only shadow storage. `JevApprovalTriageRuntime` is also directly injectable for the necessary lifecycle fixture.

Entry Web exports: `JevWebChoiceObservation`, `JevWebDecisionDeps`, `initializeJevWebDecision`, `chooseJevWebCandidate(observation,candidates,signal?)`. Root exports and initialization are connected without claiming an E11 action consumer. UI reads `readJevApprovalTriage(parent, rootComputedCurrentHash)` and retains original parent locator/controls for ordinary, Recipe/Plugin and ask_user approvals.

## Focused verification

Only one necessary lifecycle/privacy group was added. It runs actual domain codec and transpiled actual Entry evaluator/runtime, with controlled host HTTP/store/hash/abort ports; it is not a device or live Jev acceptance test.

- Initial valid RED: 7 tests, 5 PASS / 2 FAIL. Actual HTTP signal was undefined; disabling purpose inside the canonical mutation queue still applied one label.
- After direct signal and queued async settings fixes: 7/7 PASS.
- Last self-review concrete RED in the same group: actual evaluator returned `shadow:true` while captured purpose was ACTIVE, yet one label applied (6 PASS / 1 FAIL). Runtime now honors the actual dispatch shadow result; final 7/7 PASS, exit 0 (tool chunk `178d26`).
- Covers OFF/SHADOW/ACTIVE, narrow shadow records, latest unrelated metadata preservation, raw-input/task/secret exclusion, late human answer/delete/input/user/cancel changes, queued OFF race, saved Recipe/Plugin ask_user parent binding and candidate-test expectation, pinned terminal digest, old-setting defaults/global caps/strict probabilities, and finite Web handback privacy.
- `cd harmony/chat && ./node_modules/.bin/tsc --noEmit`: exit 0 (chunk `477e8d`).
- `node /tmp/e11-jev-types.cjs`: actual local SDK declarations plus domain exports, six owned production modules, **0 diagnostics**, exit 0 after final runtime fix (chunk `2447f1`).
- Official `lint-arkts.sh` on six changed production files: exit 0, no error; cross-module `.ts` resolution emitted warning diagnostics (chunk `5398e8`). No suppressions were added. Last changed `JevApprovalTriage.ets` rechecked alone after the shadow fix: **No defects**, exit 0 (chunk `2047bf`). Initial sandbox EPERM came from the official linter's own log path; the same authorized check succeeded with required tool-directory write access.

No full suite, app build, signing/install/device session, external auth or live evaluation request was performed by A. Source/store publication and actual provider/device acceptance remain separate claims. E12 Web action/runtime acceptance remains intentionally outstanding.
