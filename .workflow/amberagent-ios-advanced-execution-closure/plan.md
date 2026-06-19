# AmberAgent iOS Advanced Execution Closure

Goal:
补齐 AmberAgent iOS 的 SubAgent、模型议会、远程执行、任务状态、工具审批和权限策略，使它们成为正式高级功能，而不是入口集合或静态说明项。

Success criteria:
- SubAgent can configure roles, accept tasks, restrict tool scope, and return results into chat/task history.
- Model Council can configure seats and budget, run a discussion, show process and conclusion, and degrade on failure.
- Remote execution can configure a connection, run/cancel mock-safe commands, show status, logs, errors, and timeout.
- Tool approval covers high-risk file, web, memory, and remote actions with recorded decisions.
- Task state is persisted and can be viewed, restored, retried, or marked failed.
- Tests or build pass, or environment blockers are recorded with exact commands and errors.

Constraints:
- Do not modify Android business logic.
- Do not execute dangerous commands, touch real SSH keys, real servers, production data, certificates, or release config.
- Avoid MiniApp, Workspace/File, WebMount, and DeepRead mainline unless a minimal shared task/permission connection is required.
- Keep UI design conventions intact and avoid broad Xcode project churn.

Workflow artifact path:
`.workflow/amberagent-ios-advanced-execution-closure/`

Work packets:
- A Android Advanced Capability Auditor: read Android advanced execution implementations and summarize parity expectations.
- B iOS Runtime/Security Auditor: read iOS advanced execution/runtime/security files and identify safe implementation anchors.
- C Product UX Auditor: read relevant iOS views and identify pseudo settings, empty states, and minimal UX closure.
- D Verification Auditor: inspect test/build layout and propose narrow verification targets.

Integration policy:
Main agent owns all edits and final decisions. Subagents are read-only evidence providers. Conflicts are resolved against source code and the user constraints.

Verification:
Start with narrow Swift tests/static checks for touched files, then `git diff --check`, then best-effort `xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build` and related `iosAppTests` when feasible.

Pause gates:
Pause before real SSH/server/API calls, Apple entitlement changes, destructive commands, modifying Xcode project, deleting untracked files, choosing automatic approval policy, or deciding write/remote execution powers beyond safe defaults.
