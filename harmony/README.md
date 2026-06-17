# AmberAgent HarmonyOS Workspace

This directory is reserved for the native HarmonyOS NEXT migration track.

Phase 0 intentionally contains only documentation and spike placeholders. Do not add fake DevEco/hvigor project files by hand. Add `hvigorfile.ts`, `build-profile.json5`, `module.json5`, generated signing files, or SDK-generated folders only after they are produced by DevEco Studio or copied from a verified template and successfully built.

## Phase 0 Gates

Before a spike result is marked as verified in `docs/harmony-capability-matrix.md`, record:

1. DevEco Studio version.
2. HarmonyOS NEXT SDK/API level.
3. Whether Full SDK is available for AccessibilityExtensionAbility.
4. Debug signing status.
5. Device or emulator model/API level.
6. Native build path: C++ NAPI only, direct Rust, or Rust via C++ shim.

## Proposed Layout

```text
harmony/
  README.md
  spikes/
    README.md
  native/
    rust-napi/
      README.md
  tests/
    fixtures/
      README.md
```

Future verified DevEco project layout may add:

```text
harmony/
  AppScope/
  entry/
  shared/
    agent-core/
    provider-sdk/
    storage/
    platform/
    ui-kit/
    tool-runtime/
```

## References

- Migration plan: `../docs/harmony-native-migration-plan.md`
- Capability matrix: `../docs/harmony-capability-matrix.md`
- Phase 0 plan: `../docs/harmony-phase0-spike-plan.md`
