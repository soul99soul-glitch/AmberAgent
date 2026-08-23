## Task Lists (GFM Extension)

GitHub Flavored Markdown supports task lists using `- [ ]` for unchecked and `- [x]` for checked items. They are commonly used in assistant answers to suggest checklists or track progress.

### Android Release Checklist

- [x] Increment `versionCode` in `build.gradle.kts`
- [x] Update `versionName` (e.g. `1.2.0`)
- [x] Run `./gradlew :app:lintRelease`
- [x] Run `./gradlew :app:testRelease`
- [ ] Sign the APK / AAB with the production keystore
- [ ] Upload to Google Play internal testing track
- [ ] Run the automated pre-launch report
- [ ] Promote to production (staged rollout: 10% → 50% → 100%)

### Feature Implementation Checklist

- [x] Write failing unit tests
- [x] Implement the feature
- [x] Refactor for clarity
- [ ] Add integration tests
- [ ] Update documentation
- [ ] Open pull request
- [ ] Address code-review comments

### Mixed Checked State

- [x] Task completed
- [ ] Task not yet started
- [x] Another completed task
- [ ] Pending task
- [ ] Final pending task

### Case Sensitivity

Both `[x]` and `[X]` should produce a checked item:

- [x] Lowercase x — checked
- [X] Uppercase X — also checked
- [ ] Space — unchecked

### Task Lists Inside Nested Lists

- Outer item
  - [x] Sub-task done
  - [ ] Sub-task pending
- Another outer item
  - [ ] First sub-task
  - [x] Second sub-task
