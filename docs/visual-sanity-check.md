# AmberAgent — Visual Sanity Check TODO

Items that require manual on-device verification because the agent
cannot exercise UI behaviour. Each entry lists the change, what to
check, and known risk areas.

## T1 — DI lazy split (commit pending)

**Files changed**: `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`

**Before**: All 12 Koin modules registered eagerly in startKoin{}.
**After**: 10 modules eager + (iCloudModule, webMountModule) deferred
via `Looper.myQueue().addIdleHandler { loadKoinModules(...); false }`.

**Verify on device**:
1. Cold start the app. App home screen renders without ANR.
2. Navigate to iCloud sync settings. Settings screen loads without
   NoBeanDefFound. The IdleHandler should have fired well before
   this navigation is possible.
3. Open WebMount screen (long-press a chat → save to wiki, or via
   the wiki settings). Loads without binding errors.

**Known risk**: if the user somehow triggers an iCloud/webMount get<>
call before the main-thread Looper reaches idle (e.g. via a foreground
service callback firing in <100ms of onCreate), NoBeanDefFound will
crash the app. This case is extremely unlikely on real devices but
isn't impossible. Mitigation: the Looper.addIdleHandler is synchronous
on the main thread, so any other main-thread work that would touch
these modules is queued behind it.

## T2 — ChatPage Composable split (pending)

(banner template — to be filled when Task 2 lands)

## T3 — Markdown renderer switch (pending)

(banner template — to be filled when Task 3 lands)

## T4 — god class second pass (pending)

(banner template — to be filled when Task 4 lands)
