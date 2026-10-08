# Android review and precise repair

Baseline: main / 6a66f7a including original WIP; snapshot /tmp/amber-android-review-20261002-baseline.
Goal: find actual reproducible bugs, verify independently, repair in phases.
Constraints: Android repo only; preserve WIP; no dead-code removal, commit, push or device install; minimal changes.
Success: every accepted issue has trigger, production path, regression and fix; independent subagent review after every phase; relevant suites and app compilation pass.
Packets: Novel storage; Novel runtime/UI; Jev/context/approval; sync/restore; MiniApp/chat/provider; notifications/CI/other modules.
Process: initial parallel review -> independent confirmation -> findings and phases -> regression-first fixes -> tests -> subagent review -> final regression/report.
Acceptance: concrete impact and execution path; record rejected/by-design candidates separately. No speculative retry/fallback/framework.
Verification: one Gradle runner; focused JVM first, then module suites, app compile and assemble. External/device evidence remains separate.

## Repair phases

1. Novel durability, identity and interactive lifecycle: NS1-10 including confirmed source freshness/Markdown/role-card boundaries, NR01-04 (NR03/04 only after fault reproduction). NS6 source freshness was separately verified against the local source/domain contract.
2. Backup and file ownership: SS01-06. Do not alter documented iCloud trash-before-overwrite contract.
3. Jev execution and notifications: JEV01-08, RCI01-05. HTTP body cancellation and actual byte budget were reproduced before acceptance.
4. MiniApp, providers, Terminal and DeepRead/access tools: MC01-08, T1-7. Every concurrency defect needs controlled ordering test.
5. SET01 shared settings publication (moved earlier after root-cause confirmation), verification fixtures and final module/app regression: separate stale fixture/configuration issues from production defects; independent final review.

Issue ledger: results/*.md retains initial findings, triggers and excluded candidates. Each phase writes a completion record and independent review. Root owns all Gradle execution; workers own disjoint production/test files.
