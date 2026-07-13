# Novel Creation Android — Project State

> Updated: 2026-07-13

## Status

Android novel creation **MVP + residual closeout** is implemented and packaged.

### Closed loops

- Create/list/rename/delete, restore previous  
- Generate discuss / prose / quickStart / polish (provider outside lock)  
- Collect + supersede + optional state-delta  
- Manual edit → needsSync → SyncManualEdits checkpoint  
- Polish adopt (safe / rewrite+needsSync) + drift fail-closed  
- Fork / undo / rename branch / set main  
- Materials revise, polish preference, fixed model policy fields  
- SAF package import/export, Markdown export from workspace  
- Recovery sidecar flush (~2s or +8KiB), lifecycle background interrupt  
- Drawer entry + Navigation3 + Koin singleton  

### APK

```text
:app:assembleDebug → BUILD SUCCESSFUL

app/build/outputs/apk/debug/app-universal-debug.apk
app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Debug package uses `applicationId` suffix `.graphite` (side-by-side with release).

### Tests

`:feature:novel:testDebugUnitTest` green (generation lifecycle + codec + reducer).

### Still not iOS 1:1 (honest residual)

- Multi-chunk manual sync progress ledger  
- Full polish transaction/assessment records  
- keep-both import ID remapping  
- Full immersion reader chrome / paragraph multi-select sheet  
- Real-device provider + iOS interop evidence  

### Review notes

See `docs/reviews/novel-final-*.md` and phase reviews.
