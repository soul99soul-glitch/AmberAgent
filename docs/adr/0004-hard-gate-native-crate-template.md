# ADR-0004: HARD GATE Template for Native Crate Production Wiring

> Status: **Accepted**
> Date: 2026-05-28
> Context: Phase A.5, derived from PR #9 §8.3

## Decision

Every Rust native crate that connects to a production caller must pass
all 5 gate items before the switch-on PR can merge.

## The 5 Gate Items

### 1. Feature Flag / Kill Switch

- Local: `NativePathPrefs` DataStore key (e.g. `native_path_markdown_html`)
- Remote: `NativePathBootstrap.REMOTE_KILL_SWITCH_KEY` via Remote Config
- Default: JVM path (production safety); personal-use builds may default to `true`
- ADR-0001 §3 freezes all native path preference keys permanently

**Implementation**: `NativePathPrefs.kt`, `PreferencesKeys.kt`

### 2. Gradual Rollout Cohorts

- Personal-use mode: default `true` (all native, no gradual rollout)
- Enterprise/Play Store mode: staging 100% → prod 1% → 10% → 50% → 100%
- Cohort selection via `install_time hash % 100` or Remote Config audiences

### 3. Crashlytics Native-Panic Tagging

- Every `*NativeSwitch` reports to `NativePathBootstrap.recordPanic(component, stage, error)`
- Tags: `Native.<component>.<error_kind>` for filtering in Crashlytics dashboard
- Load failures → `recordLoad(component, error)` with breadcrumb log

**Implementation**: `NativePathBootstrap.kt` lines 159-195

### 4. JVM-vs-Rust Divergence Sampling

- `sampleRate` (0.0-1.0) controls dual-run frequency
- Both JVM and Rust paths execute; outputs compared via component-specific equality
- Divergences reported to `recordDiff(component, stage, equal, jvmSummary, nativeSummary)`
- Off by default (`sampleRate = 0`); enable during rollout ramp

**Implementation**: `NativePathBootstrap.kt` lines 157, 175-192

### 5. Single-Step Revert Plan

- Feature flag flip: set DataStore key to `false` or push Remote Config
- Code revert: `git revert <merge-commit>` removes the dispatch branch
- Native .so stays in APK (harmless dead code) until next release strips it

## Applying the Template

For a new crate `foo`:

1. Add `NATIVE_PATH_FOO = booleanPreferencesKey("native_path_foo")` to `PreferencesKeys.kt`
2. Add `foo: Boolean` field to `NativePathPrefs.NativePathState`
3. Create `FooNativeSwitch` implementing the dispatch + fallback pattern
4. Wire in `NativePathBootstrap.install()` with `FooConfigImpl`
5. Register cargo build in `app/build.gradle.kts` via `registerCargoBuild()`
6. Verify: flag on → native path, flag off → JVM path, native panic → fallback + Crashlytics
