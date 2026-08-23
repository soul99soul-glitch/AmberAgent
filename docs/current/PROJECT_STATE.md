# AmberAgent Current Project State

Last updated: 2026-08-24

## Repository state

- Canonical repository: `https://github.com/soul99soul-glitch/AmberAgent`.
- `main` is the only long-lived product branch; Android and iOS do not use permanent platform branches.
- `apps/android/` and `apps/ios/` are independent application projects in the same repository.
- The public `main` branch is a sanitized source snapshot. Legacy histories and recovery archives are not published as branches or tags.
- Short-lived branches and platform-scoped worktrees are used for normal development.

## Architecture status

- The repository is still in a transition period: both applications retain historical shared implementations.
- iOS still builds `Shared.framework` from the Gradle project under `apps/ios/`.
- Root `core/` defines the extraction boundary; it is not yet an independently published shared runtime.
- Code moves into `core/` only after a stable, platform-independent contract has real consumers on both platforms.

## Verification status

The current snapshot has passed these scoped checks:

- iOS Gradle configuration and the focused OpenAI provider JVM tests.
- iOS simulator-arm64 `Shared.framework` linking and focused Xcode simulator tests.
- Android Gradle configuration and focused Novel workspace JVM tests.
- Repository integrity checks for the monorepo layout and Git links.

These checks do not establish real-device behavior, real-provider behavior, OS background execution, or kill-and-relaunch recovery.

## Publication rules

- Credentials, signing material, local device identifiers, private workspace paths, recovery archives, and private service details must not be committed.
- Release assets must be rebuilt from the current sanitized source; historical binaries are not trusted as clean inputs.
- Validation must distinguish unit tests, compilation, simulator execution, device execution, real-provider calls, and system background behavior.

## Next steps

1. Continue Android and iOS work on short-lived, platform-scoped branches.
2. Select one narrow shared contract already consumed by both applications.
3. Move only that vertical slice into `core/`, with platform-specific verification and an explicit rollback boundary.
4. Remove duplicate platform implementations only after the shared slice is stable.

## Known risks

- Directory consolidation does not mean shared behavior is already unified.
- iOS timing-sensitive tests have shown occasional variance and should be interpreted separately from functional correctness.
- Build and simulator success do not substitute for device, provider, background-system, or relaunch evidence.
