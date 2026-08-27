# Repository split provenance

- Source monorepo main: `9658697c429a0f9690d9068bcc9e8432aef21a06`
- Filtered Android main: `ee0c9b372680592f1a42e93e0ab9edaf63a5bab9` before standalone-root fixes
- Source subtree: `apps/android/`, moved to repository root
- Root workflows retained: `android-native-build-check.yml`, `android-release.yml`
- Tool: `git-filter-repo 2.47.0` (`git_filter_repo.py` SHA-256 `67447413e273fc76809289111748870b6f6072f08b17efe94863a92d810b7d94`)

The filtered tree was compared object-for-object with the source subtree. iOS-only monorepo commits became empty and were pruned.

`archive/legacy-android` is a disconnected, filtered local recovery branch derived from source tag `legacy/android-main-2026-08-23` (`8d8f33db1af1e72a54bf620338eb6f88a016a251`). Publishing that branch is a separate decision; it is not required by the Android production build.
