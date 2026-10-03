# P5 exchange and collected-source implementation

Status: source frozen for independent review. Domain/type/static checks passed; this worker did not build, sign, install or close the phase-wide UI/device gate.

## Resulting behavior

An author can explicitly clone a collected prose reply against the current workspace and open collection for the new candidate. The original collected message remains intact. New optional `clonedFromMessageId` and `rootMessageId` preserve immediate and root lineage through native backup and restoration; each clone receives a distinct canonical message ID. The clone freezes the author's supplied CAS and uses existing `makeNovelCandidateProvenance` and adoption stale checks, rather than adding another baseline mechanism. Cloning clears the old `ordinaryRun` recovery record while retaining all canonical messages. Duplicate adoption of a clone is still rejected, and edits to the manuscript after cloning still reject its adoption.

Actual iOS reference: `NovelBranchOperations.swift:930` invokes `NovelCandidateSemantics.cloneBaseMatches` (`:111–136`), accepting the original candidate checkpoint or the parent of its collection checkpoint. iOS keeps immutable candidates independently after undo. Harmony restores the complete snapshot including collection flags, so undo already makes the original reply collectable again. The Harmony action therefore expresses an explicit author decision to reuse the collected reply in the current workspace; it does not clear the original record or add an artificial global digest gate. Legacy collected prose receives the same action. Polish and non-prose outputs remain excluded by their actual run origin.

Discussion archive records, source IDs, summaries and cursor behavior already persist in the domain. Actual iOS `expandedArchiveIDs` is local Session `@State`, so P5 retains the existing durable archive format and the UI worker implements local expansion/default folding. No persistent collapsed preference was introduced. The collection path already commits deterministic plot pointers with the manuscript. `analysisFinished` supplies the real background material-analysis outcome to the UI; structured evidence extraction remains the existing explicit state-operation path, rather than becoming a new mandatory collection transaction.

## Public/native boundaries and precise fixes

- Removed the incorrect `project.md.polishPreference` fallback for missing `setting/preferences.md`. Project polishing preference and branch writing preference remain independent.
- Native backup copied to a fresh project identity clears `ordinaryRun` from all known project-state, commit, branch and undo records. Canonical messages and history remain present; same-identity restoration keeps response recovery ownership and cursor.
- Actual public file creation, parse, import planning, installation and branch switching preserve chapter draft/confirmed contract metadata, upcoming arc, suggested chapter count, structured event evidence, identity clarifications, project state reasoning preference, project polishing preference, branch writing preference, shared materials and branch overrides including aliases/tags/custom kind/injection mode.
- Native restoration also preserves original quick-start seed, creation mode, discussion archives, session/candidate lineage and runtime ownership. Public workspace follows the actual iOS boundary: it exports the author's workspace, not private chat/session archives or recovery jobs; importing it starts without the original private quick-start seed. Material timestamps in the public format follow manifest timestamps; the new material fields and override identity remain preserved.

## APIs and files

- `creation.cloneCollectedMessage(projectId, messageId, expectedCas?) → Promise<NovelMessage>`
- `canCloneCollectedMessage(project, message, branchId) → boolean` in `collected_candidates.ts` (domain index exported by root).
- Optional `NovelMessage.clonedFromMessageId/rootMessageId`; `makeNovelMessage` and repository migration preserve both.
- Narrow changes only in `creation.ts` clone method, `repository.ts` message decoder, `models.ts` message fields, `workspace_interop.ts` preference import, `native_backup_copy.ts` fresh-identity records, and the new small `collected_candidates.ts` module. No ordinary-run/polish generation lifecycle changes.

## Validation

- First clone tests failed for the absent API, then passed through actual creation/collection/native installation, including second-generation lineage, duplicate adoption, frozen CAS and stale manuscript rejection.
- Public missing-preference test reproduced the wrong polishing-to-writing mapping before the fix.
- Fresh native identity test reproduced retained response cursor before the fix; same-identity restore remains valid after the fix.
- `novel_collected_clone.test.ts` + `novel_p5_exchange.test.ts`: **5/5 passed**.
- Focused collection/exchange/inheritance/native/archive regressions: **38/38 passed**, `/tmp/p5-exchange-focused.log`.
- `tsc --noEmit -p harmony/deepread/tsconfig.json`: passed.
- Scoped ArkTS lint on six production files: **0 errors**, 4858 warnings (existing broad domain lint warning baseline included); `/tmp/p5-exchange-lint.log`.
- `git diff --check`: passed.

Independent review is owned by `p2_exchange_recovery`; phase-wide build/device acceptance is owned by root. This report does not assert those gates.
