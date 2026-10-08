# Phase 1 storage/import repair

Owner: novel_storage. Production/test scope: feature/novel-workspace and feature/novel. No Gradle commands are run by this worker; root coordinates the red/green build and independent review.

## Accepted defects and current gate

NS1–NS5 and NS7 accepted by root. NS6 additionally accepted after source semantics verification:

- NovelDocumentValidator.validateChapterDeleteTransition requires removing the manuscript selection, incrementing workingRevision and setting NeedsSync, while the old plot snapshot remains. This is a manuscript/plot mismatch, not a source background run or checkpoint restoration requirement.
- Initial workspace installation puts chapters and the old plot in the same commit; ordinary diff freshness therefore erases this source requirement.
- Root selected a local initial-commit stalePlotBranches set (empty by default). Actual later plot changes clear it through the existing freshness logic. A subsequent PLOT_POINTER commit in that branch ancestry also acknowledges an explicitly approved same-body plot submission, without changing ordinary existing chapter/plot diff semantics.
- Removing the last chapter can still leave an old plot, so the baseline must be stale even with no remaining chapter path.
- Fork behavior and source private runtime restoration are unchanged in this phase.

Twelve existing-API regression tests added, awaiting root's red result and implementation authorization:

- NovelWorkspaceStorageReviewRegressionTest: write failure after legal conflicting paths, no incomplete manifest publication and same-ID retry; checkout manifest retention; repeated rename branch-head ancestry; all hidden path segments rejected; actual decompressed ZIP per-entry and aggregate 100 MB ceiling using size-unknown deflated entries; raw multiline scalar/alias parse; whole multiline field replacement and opaque YAML preservation.
- NovelWorkspaceMigrationReviewRegressionTest: colliding source branch names preserve main branch identity; dot-leading source branch/material/override/discarded data remain visible and exportable; source NeedsSync stays stale until real plot change; deleting last source chapter still requires sync; same-body explicit PLOT_POINTER clears the imported baseline.

ZIP red tests exercise actual inflation rather than header metadata. Root was warned that the broken implementation can consume the default 512 MB test heap; a 1 GB red worker produces ordinary assertion failures while retaining the concrete oversized input.

## Implementation decisions to check during independent review

- Install: manifest is a completion marker; book/ledger/checkout are ready before it is published. Ledger excludes manifest by contract, while checkout must still contain it. Prevalidate all public paths before changing a target. Retain existing refusal to overwrite a completed book.
- Rename: update head pointers that actually mirror the prior global head, preserving independently advanced side branches. Empty legacy heads retain their existing global-head wire shape.
- Migration: allocate branch slugs once, use that map for the manifest. Dot-leading preferred leaves use a visible identity fallback and keep their display title; ordinary golden paths remain byte-compatible.
- ZIP: bounded actual-byte reading across workspace entries, no reliance on ZipEntry.size and no new retry/fallback architecture.
- Markdown: preserve raw newline golden serialization; parse and replace multiline quoted scalar records as one field.

Implementation status and root validation evidence will be appended after the red gate.

## Implemented after root red gate

- Root ran the first eleven regression tests: 11 tests, 11 expected assertion failures in /tmp/amber-review-phase1-red-green.log. The same-body test was added during that run and is included in the green gate.
- Installer validates the full input path list before mutation, defers book manifest until book files, ledger and checkout are complete, and writes checkout manifest before publishing the main manifest. Manifest remains excluded from ledger fileTree as before.
- Rename advances only heads that mirror the former global head. Independently advanced heads retain their ancestry.
- Migrator uses one allocation map for branch slugs. reservedPath uses a visible fallback for dot-leading leaves; display titles and ordinary slug golden output remain unchanged. Public path validation rejects all hidden segments.
- ZIP reads accepted entries in 64 KB chunks, checking actual aggregate inflated bytes against 100 MB before retaining an over-limit chunk. UTF-8 strict validation remains.
- INITIAL commits can carry stalePlotBranches with empty default, omitted from ordinary ledger wire. Source NeedsSync survives even with no remaining chapter. Actual plot changes, or an explicit subsequent PLOT_POINTER while the imported baseline remains outstanding, clear that baseline. Later ordinary edits retain the existing diff-only freshness behavior.
- Scalar parsing groups raw quoted newline values. Front-matter fence recognition ignores fence-looking lines inside them; withFields replaces the complete scalar record and preserves unrelated YAML. Serialization golden behavior remains. A thirteenth regression test covers a title containing a literal newline-delimiter-newline and its replacement.
- Worker ran git diff --check successfully; no Gradle runs. Awaiting root green and independent phase review.

## Compatibility and adjacent confirmed loss fixed

- Root's feature green found two existing compatibility tests: safe nested-hidden list prefixes previously returned an empty visible result, and ScratchVerifyTest deliberately asserted the old rename bug. Store.list now retains empty hidden queries while separately rejecting absolute/parent/backslash/empty-segment paths; writes remain strict. ScratchVerifyTest is retained and asserts the repaired head/parent invariant.
- Added a query-boundary test. Root separately proved NS8 red in /tmp/amber-review-ns8-transport-red.log: ten storage tests, one failure, solely metadataFreeThematicFencesRetainEntireProse.
- NS8 is a production text-loss case: field-free leading thematic fences were treated as a header and the opening prose was discarded by parseFile, which Runtime.mergedContent consumes directly. The approved one-line fix returns the entire original trimmed prose only when fields/lists/maps are all empty; actual metadata behavior stays unchanged.
- Total regression classes now contain fifteen tests (ten storage, five migration), including three source freshness baseline scenarios.

## Independent review correction: exchange freshness roundtrip

- Independent phase review found the public branch syncStatus field could be stale in either direction: a repaired imported NeedsSync book exported its old flag and became stale again; a native edited manuscript exported its old synchronized flag and became falsely fresh.
- Root ran both new roundtrip regressions red: /tmp/amber-review-phase1-roundtrip-transport-size-red.log, seven migration tests with exactly these two failures.
- Export now projects syncStatus into the exported branch.md copy from that branch's valid native ledger head/own ancestry. Unknown branches or absent native ledger remain opaque; already-correct metadata is emitted byte-for-byte. Current public files are not rewritten.
- The two regression tests explicitly assert the original branch.md remains unchanged during export. Total focused regressions: seventeen.
- Awaiting root green and reviewer recheck of the final export correction.

## Exact body retention at field-only edits

- Root accepted an adjacent correctness issue in withFields: trimming an untouched body can erase four-space code indentation and significant Markdown trailing spaces, so it is more than cosmetic normalization.
- Added changingOnlyFieldsPreservesOriginalBodySuffixIncludingCodeIndentation with CRLF front matter, an indented code paragraph and exact trailing newline/space suffix.
- Root confirmed its red result in /tmp/amber-review-phase1-final-boundaries-red.log: eleven storage tests with exactly this one failure.
- withFields now replaces the header and preserves the original leading prefix, closing carriage return and complete body suffix. Ordinary parseFile body-trim policy is unchanged.
- Focused regression count is now eighteen: eleven storage and seven migration. Root owns green and final phase-review closure.
