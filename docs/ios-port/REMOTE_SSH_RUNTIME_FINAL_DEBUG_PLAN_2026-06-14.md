# Remote SSH Runtime Final Debug Plan

Date: 2026-06-14

## Goal

Bring the iOS Remote SSH Runtime MVP to the agreed final-complete state through debugging, verification, and release gating only.

The current implementation phase is complete. From this point forward, work should be limited to fixing defects, correcting build/configuration issues, tightening tests, and validating the runtime in realistic environments. No new terminal runtime features should be added in this plan.

## Final Completion Definition

The design is considered complete when a user can:

1. Create an SSH profile with host, port, username, and password.
2. Run `Test SSH Connection` and see the server SHA256 host fingerprint.
3. Explicitly trust the host fingerprint.
4. Run `echo amber-terminal-smoke` through the Remote SSH runtime.
5. See correct stdout, stderr behavior, exit code, timeout, cancel, and final job status.
6. Re-run the same profile only if the host fingerprint still matches.
7. Receive a hard failure if the fingerprint changes.

The stable iOS build must keep the existing MVP scope:

- Password auth only.
- Remote SSH `exec` only.
- No PTY.
- No interactive shell.
- No package installer UI.
- No private key auth.
- No Mosh.
- No iSH.
- No workspace sync/scp.
- No Model Council external CLI wiring yet.

## Current State

Completed:

- Runtime kind and capability matrix already include `remote_ssh`.
- iOS Settings UI can configure SSH profiles.
- Passwords are stored in Keychain.
- Host fingerprints are explicitly trusted before command execution.
- `IOSTerminalRuntime` has `start/read/wait/stop` job semantics.
- Output tail is capped at 128KB in runtime and backend buffers.
- Cancelled and timed-out jobs are terminal states.
- Subagent review findings for password leak, terminal-state overwrite, timeout state, Keychain service, and UI password clearing have been addressed.
- Swift source typecheck currently passes.
- Existing Android/Kotlin terminal/model-council compile checks pass.

Known remaining blocker:

- `xcodegen` is not installed locally, so generated Xcode project/package resolution and simulator build have not yet been verified.

## Non-Goals

These items are explicitly out of scope for this completion plan:

- Adding PTY or SwiftTerm UI.
- Adding shell sessions.
- Adding private key/passphrase auth.
- Adding Mosh or iSH.
- Adding remote file sync.
- Adding package-management UX.
- Connecting iOS Remote SSH to Model Council external CLI.
- Redesigning Settings UI beyond bug fixes needed for the current flow.

If any of these becomes necessary, it should start a new plan after this MVP is closed.

## Debug Plan

### Phase 1: Build Wiring Debug

Objective: prove that the project configuration links SwiftNIO SSH correctly.

Tasks:

1. Install or make available `xcodegen`.
2. Run XcodeGen from `iosApp/project.yml`.
3. Verify `swift-nio`, `swift-nio-ssh`, `NIOCore`, `NIOPosix`, and `NIOSSH` resolve in the generated Xcode project.
4. Build the iOS simulator target.
5. Fix only project/package/API compile issues.

Acceptance:

- Generated project builds the stable `iosApp` simulator target.
- No GPL/experimental runtime code is pulled into stable by accident.
- `remote_mosh` and `ish_experimental` remain gated.

### Phase 2: Real SwiftNIO SSH Runtime Debug

Objective: validate the real backend path, not just the fallback/mock path.

Tasks:

1. Compile with `canImport(NIOSSH)` active.
2. Fix any SwiftNIO SSH API mismatches.
3. Confirm host fingerprint derivation matches OpenSSH-style `SHA256:<base64-no-padding>`.
4. Confirm `Test SSH Connection` aborts after host key validation and before password auth when host is untrusted or mismatched.
5. Confirm trusted-host command execution reaches password auth and session `exec`.

Acceptance:

- First-time unknown host returns `.needsTrust(fingerprint:)` without sending password.
- Trusted matching host can execute a command.
- Mismatched host returns hard failure before command execution.
- Backend timeout closes the active channel when possible.

### Phase 3: SSH Fixture Debug

Objective: validate runtime behavior against a controlled SSH server.

Use a local or CI SSH fixture with password auth enabled.

Test cases:

1. `echo amber-terminal-smoke`
2. command writing to stderr
3. non-zero exit
4. long output over 128KB
5. command timeout, such as `sleep 10` with a short timeout
6. cancel while command is running
7. wrong password
8. host fingerprint mismatch
9. server unavailable

Acceptance:

- stdout/stderr collection is understandable in `outputTail`.
- exit code is preserved.
- long output stays bounded.
- timeout results in `timed_out`.
- cancel results in `cancelled`.
- late backend callbacks do not overwrite final state.
- wrong password and unavailable server produce clear errors.

### Phase 4: Settings Flow Debug

Objective: ensure the user-facing MVP flow is hard to misuse.

Tasks:

1. Verify new profile creation does not accidentally overwrite an existing profile.
2. Verify editing an existing profile keeps the same profile id only when intended.
3. Verify clearing password removes the Keychain secret.
4. Verify empty password blocks `Test SSH Connection` and command execution.
5. Verify mismatch does not persist the edited profile or password.
6. Verify default profile selection survives app restart.
7. Verify deleting or changing profiles cannot leave a stale default profile id.

Acceptance:

- Profile persistence is predictable.
- Password persistence is explicit.
- Host trust is never implicit.
- Smoke test failure messages tell the user what to fix next.

### Phase 5: Regression And Release Gate

Objective: close the MVP with a small but reliable regression suite.

Required checks:

```bash
./gradlew :feature:terminal:api:compileKotlinJvm :feature:terminal:compileDebugKotlin :feature:modelcouncil:compileDebugKotlin
```

```bash
xcrun swiftc ... -typecheck iosApp/iosApp/*.swift
```

After XcodeGen is available:

```bash
cd iosApp
xcodegen generate
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17' build
```

Optional when fixture server is available:

```bash
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17' test
```

Acceptance:

- All required compile/typecheck gates pass.
- SSH fixture tests pass when fixture is available.
- Manual smoke test passes on simulator/device.
- No new feature scope is introduced during debugging.

## Manual Smoke Script

1. Open Settings.
2. Select `Remote SSH` as default runtime.
3. Create a profile:
   - host
   - port
   - username
   - password
4. Tap `Test SSH Connection`.
5. Confirm the displayed fingerprint out of band.
6. Tap `Trust Host`.
7. Tap `Run Terminal Smoke Test`.
8. Confirm:
   - status: `completed`
   - output contains `amber-terminal-smoke`
   - exit code is `0`

Negative smoke:

1. Change the SSH server host key or point the same profile at another server.
2. Run `Test SSH Connection`.
3. Confirm fingerprint mismatch is shown.
4. Confirm the profile is not silently trusted.
5. Confirm remote command execution fails until trust is explicitly reset.

## Debug-Only Change Rules

Allowed:

- Fix compile errors.
- Fix SwiftNIO SSH API misuse.
- Fix incorrect runtime status transitions.
- Fix host key trust bugs.
- Fix Keychain persistence bugs.
- Add or tighten tests.
- Add build/test documentation.
- Improve error text when it directly helps debug the MVP flow.

Not allowed:

- Adding new runtime capabilities.
- Expanding authentication methods.
- Adding a terminal UI.
- Adding background session persistence.
- Adding file transfer.
- Adding package-management features.
- Broad refactors unrelated to this MVP.

## Remaining Risks

1. SwiftNIO SSH API compatibility is not fully proven until XcodeGen/package build runs.
2. Timeout and cancellation can close channels best-effort, but remote process cleanup depends on server behavior.
3. Real SSH integration still needs fixture coverage for stderr, auth failure, and host key mismatch.
4. iOS Keychain behavior should be checked on simulator and physical device.

These are debug and validation risks, not reasons to expand MVP scope.

## Final Close Criteria

Close the Remote SSH Runtime MVP when all are true:

- Stable iOS app target builds through generated Xcode project.
- Manual smoke test passes against at least one real SSH server.
- Negative host-key mismatch smoke test passes.
- Timeout and cancel behavior are observed and correct.
- Keychain save/load/clear is verified.
- No Mosh/iSH/private-key/PTY/shell/file-sync work is included.
- The final implementation has passed one focused review after the debug fixes.
