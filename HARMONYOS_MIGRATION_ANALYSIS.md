Based on the thorough analysis of the `/Users/mi/Downloads/AI/AmberAgent` codebase, here is a comprehensive report on the implications of launching a HarmonyOS/HarmonyOS NEXT version.

## 1. Current Tech Stack and Module Dependencies

The project is a large-scale, multi-module Android application with a highly modular architecture consisting of approximately **60 Gradle modules**. The build system is Gradle with Kotlin DSL, using Android Gradle Plugin (AGP) 9.2.0 and Kotlin 2.3.21.

**Core Technology Stack:**
- **UI Framework:** Jetpack Compose (Material3, BOM 2026.04.01) with Navigation3 (`androidx.navigation3`).
- **Dependency Injection:** Koin (4.2.1) with Android-specific extensions (`koin-android`, `koin-androidx-compose`, `koin-androidx-workmanager`).
- **Data Persistence:** Room (2.8.4) and DataStore Preferences.
- **Networking:** Ktor client (3.4.3) with OkHttp engine, OkHttp (5.3.2).
- **Serialization:** Kotlinx Serialization (1.11.0).
- **Background Tasks:** WorkManager (2.11.2).
- **Native Code:** Rust libraries compiled to Android `.so` files via JNI (cargo-ndk).
- **Web Frontend:** React Router 7 SPA with Vite and Tailwind CSS v4 (`web-ui/`).
- **Google Services:** Firebase (Analytics, Crashlytics, Remote Config), Google Play Services Auth.

**Evidence:**
- `settings.gradle.kts:48-96` defines all included modules.
- `app/build.gradle.kts:102-309` configures the main Android application with Compose, Rust JNI builds, and Firebase.
- `gradle/libs.versions.toml` defines the full dependency version catalog.

## 2. Android Platform Coupling Points

The application is deeply coupled with the Android platform. Below is a categorized breakdown of the critical coupling points, supported by file and line evidence.

### Compose & UI Layer
- **ComponentActivity & Compose Entry:** `app/src/main/java/app/amber/agent/RouteActivity.kt:10` uses `androidx.activity.ComponentActivity` and `setContent` (`:11`).
- **Compose Navigation:** `RouteActivity.kt:48` uses `androidx.navigation3.runtime.NavKey` and `entryProvider` (`:530`) for the navigation graph.
- **Compose Opt-ins:** `app/build.gradle.kts:289-299` lists numerous `ExperimentalMaterial3Api`, `ExperimentalAnimationApi`, etc.
- **Material3:** `ai/build.gradle.kts:60` and `search/build.gradle.kts:55` depend on `androidx.material3`.

### Activity & Service Lifecycle
- **Application:** `app/src/main/java/app/amber/agent/AmberAgentApp.kt:67` extends `android.app.Application` and initializes Koin, Firebase, and notification channels.
- **Main Activity:** `RouteActivity.kt` is the single-activity entry point using Compose.
- **Background Service (Chat):** `app/src/main/java/app/amber/core/service/ChatService.kt:204` defines a `ChatService`.
- **Foreground Service (Generation):** `app/src/main/java/app/amber/core/service/AgentGenerationForegroundService.kt:21` extends `Service` and calls `startForeground` (`:74`).
- **Foreground Service (Screen Capture):** `app/src/main/java/app/amber/core/automation/ScreenCaptureService.kt:40` extends `Service` for MediaProjection-based screenshots.
- **Accessibility Service:** `app/src/main/java/app/amber/core/automation/AmberAccessibilityService.kt:24` extends `AccessibilityService` for UI automation (tap, swipe, dump UI tree).
- **Notification Listener Service:** `feature/system/src/main/kotlin/app/amber/feature/system/AmberNotificationListenerService.kt:21` extends `NotificationListenerService`.
- **Shortcut Handler Activity:** `app/src/main/java/app/amber/feature/ui/activity/ShortcutHandlerActivity.kt` handles external intents.

### Room & DataStore
- **Room:** `core/agent-store-room/src/main/kotlin/app/amber/core/agent/store/AgentRuntimeDatabase.kt:16` defines `abstract class AgentRuntimeDatabase : RoomDatabase()`. `core/agent-store-room/build.gradle.kts:27` imports `androidx.room.runtime`.
- **DataStore:** `core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt:60` defines `val Context.settingsStore by preferencesDataStore`. `core/app-infra/build.gradle.kts:21` imports `androidx.datastore.preferences`.
- **KSP:** Room uses KSP (`core/agent-store-room/build.gradle.kts:4`) for code generation.

### Permissions
- **Permission Broker:** `feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:99` uses `ContextCompat.checkSelfPermission` and defines runtime permissions for `READ_CONTACTS`, `READ_SMS`, `CAMERA`, `RECORD_AUDIO`, `READ_EXTERNAL_STORAGE`, `ACCESS_FINE_LOCATION`, `BLUETOOTH_SCAN`, etc. (`:242-393`).
- **Activity Result Contracts:** `app/src/main/java/app/amber/feature/ui/components/ai/ChatInput.kt:383` uses `rememberLauncherForActivityResult(ActivityResultContracts.TakePicture())`. `app/src/main/java/app/amber/feature/ui/components/ui/permission/RememberPermissionState.kt:64` uses `ActivityResultContracts.RequestMultiplePermissions()`.

### WebView
- **Tool-level WebView:** `app/src/main/java/app/amber/core/ai/tools/WebViewTools.kt` defines 7 tools (`webview_open`, `webview_read`, etc.) that operate on a `WebViewOperationStore` (`:40`).
- **UI-level WebView:** `app/src/main/java/app/amber/feature/ui/components/message/GenerativeWidgetCard.kt:21` imports `android.webkit.WebView` and `WebViewClient` (`:22`). It uses `AndroidView` (`:68`) to embed a `WebView` for rendering HTML widgets and evaluating JavaScript (`:1205`).
- **WebView Page:** `app/src/main/java/app/amber/feature/ui/pages/webview/WebViewPage.kt:45` is a full-screen Compose page wrapping a WebView.

### File System & ContentResolver
- **FilesManager:** `app/src/main/java/app/amber/core/files/FilesManager.kt:3` imports `android.content.Context`, `android.net.Uri`, `android.provider.DocumentsContract`, and `android.provider.OpenableColumns`. It uses `context.contentResolver.openInputStream(uri)` (`:58`).
- **DocumentFile:** `feature/workspace/build.gradle.kts:27` depends on `androidx.documentfile:documentfile:1.0.1` for workspace tree access.
- **MediaStore:** Implicitly used via `Uri` resolution for picking images/videos.

### Background Tasks & WorkManager
- **Agent Cron Worker:** `app/src/main/java/app/amber/feature/cron/AgentCronWorker.kt:30` extends `CoroutineWorker`.
- **Board Worker:** `app/src/main/java/app/amber/feature/board/worker/BoardWorker.kt:36` extends `CoroutineWorker` for "Today Board" updates.
- **Memory Dream Worker:** `app/src/main/java/app/amber/core/memory/dream/MemoryDreamWorker.kt:17` extends `CoroutineWorker`.
- **WorkManager Scheduling:** `app/src/main/java/app/amber/feature/cron/AgentCronManager.kt:40` uses `WorkManager.getInstance(context)` and `OneTimeWorkRequestBuilder` (`:149`).

### Notifications
- **Notification Channels:** `app/src/main/java/app/amber/agent/AmberAgentApp.kt:323` creates 7 notification channels (chat, screen capture, memory, board, etc.).
- **Foreground Notification:** `AgentGenerationForegroundService.kt` builds and displays a `NotificationCompat` (`:12`) and calls `ServiceCompat.startForeground` (`:76`).
- **Posting Notifications:** `app/src/main/java/app/amber/core/utils/NotificationUtil.kt:132` uses `NotificationManagerCompat.from(context).notify(...)`.

### Terminal / Local CLI
- **Android Shell & Alpine:** `feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt:4` imports `android.content.Context`, `android.content.Intent`, `android.content.pm.PackageManager`. It uses `context.startForegroundService(intent)` (`:560`) and runs commands via `ProcessBuilder` against Android Shell or a bundled `proot`/`alpine` environment (`TerminalRuntime.kt:58-65`).
- **Termux Permission:** `TerminalRuntime.kt:250` checks `TERMUX_PERMISSION_RUN_COMMAND` via `PackageManager.PERMISSION_GRANTED`.

### Accessibility / MediaProjection / IME
- **AccessibilityService:** `AmberAccessibilityService.kt` is a full implementation of `AccessibilityService` providing `tap`, `swipe`, `dumpUiTree`, `setFocusedText`, `back`, and `home` actions. It is used by `LiveModeManager` and `ScreenAutomationTools`.
- **MediaProjection:** `app/src/main/java/app/amber/core/automation/ScreenCaptureService.kt:12` imports `MediaProjection` and `MediaProjectionManager`. `ScreenCapturePermissionActivity.kt` is a dedicated activity to request the MediaProjection intent.
- **IME (Input Method):** The app uses standard Android soft keyboard input. `ChatInput.kt` mentions `IME` padding (`:230`) but does not implement a custom IME.

### Other Android-Specific Libraries
- **Firebase:** `AmberAgentApp.kt:11-15` initializes Firebase, Crashlytics, and Remote Config. `AppModule.kt:47-57` provides Firebase singletons.
- **Google Play Services:** `gradle/libs.versions.toml:156` includes `play-services-auth` and `googleid` for Google OAuth.
- **Koin Android:** `AmberAgentApp.kt:52-54` uses `androidContext`, `androidLogger`, and `workManagerFactory`.
- **Coil Android:** `RouteActivity.kt:53-58` configures `ImageLoader` with `OkHttpNetworkFetcherFactory` and Android-specific decoders (`AnimatedImageDecoder`, `GifDecoder`).
- **FloatingX:** `common/build.gradle.kts:54-55` uses `floatingx` and `floatingx-compose` for floating windows, which is an Android-specific library.
- **TTS System Provider:** `tts/src/main/java/app/amber/tts/provider/providers/SystemTTSProvider.kt:3` uses `android.content.Context` to access the system TTS engine.

## 3. Business/Agent Core - Reusable Parts

Despite the deep Android coupling, several modules contain **pure Kotlin/JVM logic** that is highly reusable and could be directly compiled for HarmonyOS (which supports Kotlin) or shared via a Kotlin Multiplatform module.

### Pure Kotlin/JVM Modules (Highly Reusable)
These modules have **zero Android dependencies** (`android.library` or `androidx.*` imports) and depend only on standard Kotlin libraries (`kotlinx.coroutines`, `kotlinx.serialization`, `kotlinx.datetime`).

1. **`core:agent-runtime`**
   - **Content:** Defines the `Agent` interface, `AgentRunner`, `AgentRegistry`, `MessagePipeline`, `ModelRouter`, `ToolSession`, and `RunScope`.
   - **Evidence:** `core/agent-runtime/build.gradle.kts:1` uses `plugins { alias(libs.plugins.kotlin.jvm) }`. The source files in `core/agent-runtime/src/main/kotlin/app/amber/core/agent/runtime/` contain no `android.*` imports. `Agent.kt` defines the entire agent abstraction.
   - **Reusability:** This is the **heart of the agent framework**. The runtime contract, event store, and execution pipeline are entirely platform-agnostic. They can be reused with a different platform-specific implementation of `AgentRunner` and `RunScope`.

2. **`core:agent-utils`**
   - **Content:** JSON utilities, instant serialization helpers.
   - **Evidence:** `core/agent-utils/build.gradle.kts:1` is a pure Kotlin JVM module. It only exports `kotlinx.serialization.json`.
   - **Reusability:** Utility classes for serialization are fully portable.

3. **`core:llm`**
   - **Content:** `TokenCounter` and `TokenCounterNative` (the JVM fallback logic).
   - **Evidence:** `core/llm/build.gradle.kts:1` is a pure Kotlin JVM module. Only depends on `kotlinx.coroutines.core`.
   - **Reusability:** Token counting logic is pure algorithmic code and can be reused.

4. **`core:ai-prompts`**
   - **Content:** Default prompt strings (compress, OCR, title, suggestion, learning mode).
   - **Evidence:** `core/ai-prompts/build.gradle.kts:1` is a pure Kotlin JVM module with no dependencies.
   - **Reusability:** These are just string constants. Fully portable.

### Android Library Modules with Reusable Core Logic
These modules are defined as Android libraries but contain substantial business logic that is largely decoupled from the Android UI framework.

1. **`ai` (AI SDK)**
   - **Content:** Provider abstractions for OpenAI, Google, Anthropic, etc. HTTP request building, SSE parsing, model registry, message types (`UIMessage`, `UIMessagePart`), tool definitions.
   - **Evidence:** `ai/build.gradle.kts:54` defines it as an Android library, but it depends on `okhttp`, `kotlinx.serialization`, and `kotlinx.coroutines`. The core `Message.kt`, `Provider.kt`, and provider implementations are HTTP/networking logic, not Android UI.
   - **Reusability:** The **provider abstraction layer**, message models, and HTTP/SSE streaming logic are highly reusable. The only Android-specific parts are OAuth flows (which use `android.content.Context` and `Intent` for browser callbacks) and image loading. The core LLM call logic is platform-agnostic.

2. **`core:model`**
   - **Content:** `Assistant`, `Conversation`, `MessageNode`, `Avatar`, `PromptInjection`, `Lorebook` data models.
   - **Evidence:** `core/model/build.gradle.kts:7` is an Android library but only depends on `ai` and `kotlinx.serialization.json`. It has `compose = true` but only uses `androidx.compose.runtime` (for `@Stable` or `remember` if any, though models are usually just data classes).
   - **Reusability:** The data models are pure Kotlin data classes with kotlinx serialization. They are fully portable if the Compose runtime annotations are removed or replaced.

3. **`search` and `document`**
   - **Content:** Search SDK logic (Exa, Tavily, Bing, DuckDuckGo) and document parsing (PDF, DOCX, PPTX).
   - **Evidence:** `search/build.gradle.kts` and `document/build.gradle.kts` are Android libraries, but the core logic is HTTP networking and file parsing. `document` uses `androidx.appcompat` and `material` for UI components but also has a Rust native fallback (`office-parsers`) for parsing.
   - **Reusability:** The search orchestration and document parsing backends are reusable. The Android UI wrappers are not.

4. **`web-ui` (Frontend)**
   - **Content:** React Router 7 SPA, TypeScript types, Zustand store, API client (`ky`), Markdown rendering.
   - **Evidence:** `web-ui/package.json` shows a standard React/Vite stack.
   - **Reusability:** This is **100% reusable**. The frontend is already a web app. If the HarmonyOS version provides a web-compatible API or runs the Ktor backend, the `web-ui` can be served as-is within a HarmonyOS WebView or a browser.

5. **Rust Native Code (`native/`)**
   - **Content:** `markdown-parser`, `regex-transformer`, `office-parsers`, `highlight-parser`, `sync-crypto`, `reader-extractor`, `json-expr`, `html-diff-normalizer`.
   - **Evidence:** `native/README.md` and `native/Cargo.toml` define the workspace. The Rust code is platform-agnostic.
   - **Reusability:** The Rust code is **fully portable**. However, it must be recompiled for HarmonyOS's target architecture (e.g., `aarch64-linux-ohos`) and the JNI bindings must be replaced with HarmonyOS NAPI (Native API) or a compatible FFI mechanism.

## 4. Layers to Rewrite for Non-Android Native Stack

A migration to HarmonyOS/HarmonyOS NEXT requires a **near-complete rewrite of the Android-specific platform layer**. The following layers must be rewritten or replaced with HarmonyOS equivalents.

### UI Layer (Highest Effort)
- **What:** All Jetpack Compose UI code (`app/src/main/java/app/amber/feature/ui/`).
- **Why:** Jetpack Compose is Android-specific. HarmonyOS NEXT uses **ArkUI/ArkTS** (or ArkUI-X for cross-platform). Even if using a cross-platform framework, the existing Android-specific `AndroidView` interop (for WebView), `LocalContext`, `ActivityResultLauncher`, and Compose `Modifier` system have no direct equivalent.
- **Scope:** ~1000+ Kotlin files in `app/` containing `@Composable`. This includes the entire navigation graph (`RouteActivity.kt`), all pages (Chat, Board, Settings, WebView, etc.), all custom components (ChatInput, Message bubbles, Markdown renderers, Rich text), and all theme logic.
- **Key Evidence:** `app/build.gradle.kts:599-613` lists `androidx.activity.compose`, `androidx.compose.bom`, `androidx.material3`, `androidx.navigation3`.

### Application & Lifecycle Layer
- **What:** `Application`, `Activity`, `Service`, `BroadcastReceiver`.
- **Why:** HarmonyOS uses **Ability** (PageAbility, ServiceAbility) instead of Android's `Activity`/`Service`. The lifecycle callbacks (`onCreate`, `onStartCommand`, `onBind`, `onDestroy`) are different.
- **Scope:**
  - `AmberAgentApp.kt` (Application) -> `Ability` context initialization.
  - `RouteActivity.kt` (ComponentActivity) -> `PageAbility` with ArkUI entry.
  - `ChatService.kt` -> `ServiceAbility` or background task.
  - `AgentGenerationForegroundService.kt` -> `ServiceAbility` with foreground notification.
  - `AmberAccessibilityService.kt` -> HarmonyOS Accessibility Service (if available).
  - `ScreenCaptureService.kt` -> HarmonyOS Screen Capture API (if available).
  - `AmberNotificationListenerService.kt` -> HarmonyOS Notification Listener.
  - `ShortcutHandlerActivity.kt` -> `PageAbility` with intent handling.

### Data Persistence Layer
- **What:** Room and DataStore.
- **Why:** Room and DataStore are AndroidX libraries. HarmonyOS provides **Relational Database (RDB)** and **Data Preferences**.
- **Scope:**
  - `core:agent-store-room` -> Rewrite using HarmonyOS RDB or an ORM. All `@Entity`, `@Dao`, and `RoomDatabase` must be replaced.
  - `core:app-infra` / `PreferencesStore.kt` -> Replace `preferencesDataStore` with HarmonyOS `Preferences` API.
  - `app/src/main/java/app/amber/core/utils/DatabaseUtil.kt` -> Rewrite cursor window size logic for RDB.
- **Key Evidence:** `core/agent-store-room/src/main/kotlin/app/amber/core/agent/store/AgentRuntimeDatabase.kt:16` (`RoomDatabase`), `core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt:60` (`preferencesDataStore`).

### Permission & System Access Layer
- **What:** `AgentPermissionBroker`, `ActivityResultContracts`, `ContextCompat.checkSelfPermission`.
- **Why:** HarmonyOS has a different permission model and API surface (`ohos.security.permission`).
- **Scope:** All runtime permission checks, the `AgentPermissionBroker` registry, and all `rememberLauncherForActivityResult` calls must be rewritten.
- **Key Evidence:** `feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:99` (`checkSelfPermission`), `AgentPermissionBroker.kt:242-393` (permission registry).

### WebView Layer
- **What:** `android.webkit.WebView` and all WebView tools.
- **Why:** The `android.webkit` package is Android-specific. HarmonyOS uses a WebView component, but the API is different.
- **Scope:**
  - `GenerativeWidgetCard.kt` (`AndroidView` interop with `WebView`) -> Rewrite as ArkUI component with WebView.
  - `WebViewPage.kt` -> ArkUI WebView page.
  - `WebViewTools.kt` (7 tools) -> The `WebViewOperationStore` logic is reusable, but the underlying WebView execution engine must be replaced.
  - `MiniAppBridge.kt` (`WebView` JavaScript bridge) -> Reimplement with HarmonyOS WebView JS bridge.
- **Key Evidence:** `app/src/main/java/app/amber/feature/ui/components/message/GenerativeWidgetCard.kt:21` (`android.webkit.WebView`), `app/src/main/java/app/amber/feature/ui/pages/webview/WebViewPage.kt:45`.

### File System & Media Layer
- **What:** `ContentResolver`, `Uri`, `DocumentsContract`, `DocumentFile`, `MediaStore`.
- **Why:** These are Android-specific content provider APIs. HarmonyOS uses its own file system (`ohos.app.Context` file paths) and media library APIs.
- **Scope:** `FilesManager.kt` (saving uploads, resolving URIs, MIME types), `WorkspaceManager.kt` (document tree access), `ChatInput.kt` (file picking via `ActivityResultContracts`), `ImageViewer` (Coil3 image loading). Coil3 has an Android-specific implementation that needs replacing.
- **Key Evidence:** `app/src/main/java/app/amber/core/files/FilesManager.kt:58` (`contentResolver.openInputStream`), `feature/workspace/build.gradle.kts:27` (`androidx.documentfile`).

### Background Task & Notification Layer
- **What:** WorkManager, `ForegroundService`, `NotificationManagerCompat`, `AlarmManager`.
- **Why:** WorkManager is an AndroidX library. HarmonyOS provides its own background task and reminder mechanisms (e.g., `WorkScheduler` or `BackgroundTask`).
- **Scope:**
  - `AgentCronManager.kt` (WorkManager) -> HarmonyOS background task scheduler.
  - `BoardScheduler.kt` -> HarmonyOS scheduler.
  - `MemoryDreamScheduler.kt` -> HarmonyOS scheduler.
  - `AgentGenerationForegroundService.kt` -> HarmonyOS foreground service (`ServiceAbility` with notification).
  - `ScreenCaptureService.kt` -> HarmonyOS foreground service.
  - `AmberAgentApp.kt:323` (Notification channels) -> Rewrite with HarmonyOS notification API.
- **Key Evidence:** `app/src/main/java/app/amber/feature/cron/AgentCronManager.kt:40` (`WorkManager.getInstance`), `app/src/main/java/app/amber/core/service/AgentGenerationForegroundService.kt:76` (`ServiceCompat.startForeground`).

### Automation & Screen Capture Layer
- **What:** `AccessibilityService`, `MediaProjection`, `GestureDescription`.
- **Why:** These are Android-specific accessibility and screen capture APIs.
- **Scope:**
  - `AmberAccessibilityService.kt` -> Rewrite as HarmonyOS Accessibility Service (if the API exists and is equivalent). Note: HarmonyOS accessibility APIs may not be as mature as Android's.
  - `ScreenCaptureService.kt` & `ScreenCapturePermissionActivity.kt` -> Replace with HarmonyOS screen capture API (e.g., `ScreenCapture` module in ArkTS).
  - `ScreenAutomationTools.kt` -> The tools that wrap accessibility actions must be rewritten.
- **Key Evidence:** `app/src/main/java/app/amber/core/automation/AmberAccessibilityService.kt:3` (`AccessibilityService`), `app/src/main/java/app/amber/core/automation/ScreenCaptureService.kt:12` (`MediaProjection`).

### Terminal Layer
- **What:** `TerminalRuntime`, `AlpineRuntimeInstaller`, `ProcessBuilder` Android shell.
- **Why:** The Android shell (`/system/bin/sh`) and Termux integration are Android-specific. HarmonyOS does not have Termux.
- **Scope:** The entire `TerminalRuntime` must be rewritten. If HarmonyOS allows shell access (e.g., via `hdc` or a restricted shell), the execution model changes. The bundled `proot`/`alpine` container downloaded in `app/build.gradle.kts:535-561` is an ARM64 Linux binary that may not run on HarmonyOS without a compatibility layer.
- **Key Evidence:** `feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt:4` (`Context`), `TerminalRuntime.kt:560` (`startForegroundService`), `app/build.gradle.kts:535-561` (downloads `proot` and `alpine.tar.gz` for ARM64).

### Dependency Injection Layer
- **What:** Koin Android (`koin-android`, `koin-androidx-compose`, `koin-androidx-workmanager`).
- **Why:** While Koin has a multiplatform version, the Android-specific modules (`androidContext`, `androidLogger`, `workManagerFactory`) are not available.
- **Scope:** `AmberAgentApp.kt:71-75` (Koin initialization) must be rewritten for the HarmonyOS `Ability` context. All `by inject()` calls in Composables must be replaced with the ArkUI equivalent.
- **Key Evidence:** `app/src/main/java/app/amber/agent/AmberAgentApp.kt:52-54` (`androidContext`, `androidLogger`, `workManagerFactory`).

### Google & Firebase Services Layer
- **What:** Firebase Analytics, Crashlytics, Remote Config, Google Play Services Auth, Google OAuth.
- **Why:** Google Mobile Services (GMS) are **not available** on HarmonyOS/HarmonyOS NEXT. They must be replaced with Huawei Mobile Services (HMS).
- **Scope:**
  - Firebase Analytics -> HMS Analytics Kit.
  - Firebase Crashlytics -> HMS Crash Service or manual crash reporting.
  - Firebase Remote Config -> HMS Remote Configuration or an in-house solution.
  - Google OAuth (for Gemini, OpenAI Codex) -> Web-based OAuth or Huawei Account Kit (if supported by the providers). The `GoogleGeminiOAuth.kt` and `OpenAICodexOAuth.kt` flows need to be reworked to use HarmonyOS `WebView` or `Browser` ability for OAuth callbacks instead of `CustomTabsIntent` or `ActivityResultContracts`.
  - Google Drive Sync (`GoogleDriveSyncRepository.kt`) -> Huawei Drive Kit or other cloud storage.
- **Key Evidence:** `app/build.gradle.kts:615-619` (Firebase BOM), `ai/src/main/java/app/amber/ai/provider/providers/google/GoogleGeminiOAuth.kt` (Android OAuth flow).

### Media & TTS Layer
- **What:** Media3 ExoPlayer, System TTS.
- **Why:** Media3 is Android-specific. HarmonyOS uses its own media playback APIs.
- **Scope:** `tts/build.gradle.kts:57-59` imports `androidx.media3`. The `SystemTTSProvider.kt` uses Android's `TextToSpeech` class. This must be replaced with HarmonyOS TTS API or a third-party TTS SDK.
- **Key Evidence:** `tts/build.gradle.kts:57-59` (`media3-exoplayer`, `media3-ui`, `media3-common`).

### Native (Rust) Layer
- **What:** Rust `.so` libraries compiled for Android JNI.
- **Why:** The `.so` files are compiled for Android ABI (`arm64-v8a`). HarmonyOS uses a different ABI and does not support Android JNI directly. It uses NAPI (Native API) or its own FFI.
- **Scope:** The `native/` Rust code is portable, but the build scripts (`app/build.gradle.kts:343-399`) and JNI bindings (`jni-common/`) must be replaced with NAPI bindings. The `app/build.gradle.kts:282-286` excludes `libtokenizer.so` from packaging; similar packaging logic must be rewritten for HarmonyOS HAP/HSP.
- **Key Evidence:** `app/build.gradle.kts:343-399` (cargo-ndk build tasks), `native/README.md` (JNI bindings).

## 5. Rough Workload by Module

Below is a rough estimation of the migration effort, categorized by module. The scale is **Relative Effort** (1 = minimal, 5 = major rewrite). This assumes a migration to **HarmonyOS NEXT (ArkTS/ArkUI)**.

| Module | Effort | Rationale |
|--------|--------|-----------|
| `app` | **5** | Entire UI layer (~1000+ Composables), all Activities, Services, DI setup, Firebase init, notification channels. Must be completely rewritten in ArkTS/ArkUI. |
| `core:agent-runtime` | **1** | Pure Kotlin/JVM. No Android deps. Directly portable. Only needs a new HarmonyOS-specific `RunScope` implementation. |
| `core:agent-utils` | **1** | Pure Kotlin/JVM. No changes needed. |
| `core:llm` | **1** | Pure Kotlin/JVM. No changes needed. |
| `core:ai-prompts` | **1** | Pure Kotlin/JVM. No changes needed. |
| `core:agent-runtime-impl` | **2** | Mostly pure Kotlin but uses `android.util.Log` (`InProcessAgentRunner.kt:3`). Needs a logging abstraction. |
| `core:agent-store-room` | **4** | Room database must be replaced with HarmonyOS RDB or equivalent ORM. All entities, DAOs, and database class need rewriting. KSP is Android-specific. |
| `core:app-infra` | **3** | DataStore Preferences must be replaced with HarmonyOS Preferences. `AppScope.kt` uses `android.util.Log` (`:3`). |
| `core:settings` | **3** | Android library with DataStore. The `Settings` data model is reusable, but the persistence layer (`PreferencesStore.kt`) needs rewriting. Compose runtime dependency is minor. |
| `core:model` | **2** | Android library with Compose runtime dependency. Data models are pure Kotlin. The `compose = true` build feature can be removed if `@Stable` annotations are not used. |
| `ai` | **3** | Core HTTP/SSE logic is reusable. Android-specific parts: OAuth flows (`GoogleGeminiOAuth.kt`, `OpenAICodexOAuth.kt` using `Context`/`Intent`), image loading, and `SystemTTSProvider` call sites. Needs a new network client setup for HarmonyOS (though Ktor/OkHttp may still work). |
| `common` | **3** | Contains OkHttp, serialization, floating window lib (`floatingx`), and `androidx.core.ktx`/`appcompat`. The floating window library is Android-specific and must be replaced. |
| `document` | **3** | Core parsing logic is reusable (especially Rust native office-parsers). Android UI wrappers need replacement. `androidx.appcompat` and `material` dependencies are for UI. |
| `highlight` | **3** | Rust native parser is reusable. Android UI and QuickJS wrapper (`quickjs`) are Android-specific. Compose UI wrappers need replacement. |
| `search` | **2** | Core search logic (HTTP, JSoup) is reusable. `androidx.material3` is used for UI components. UI layer needs rewriting. |
| `tts` | **4** | Media3 ExoPlayer is Android-specific. System TTS uses Android's `TextToSpeech`. Playback engine must be fully replaced with HarmonyOS media APIs. Provider config logic is reusable. |
| `feature:system` | **4** | `AgentPermissionBroker` and `AmberNotificationListenerService` are deeply Android-specific. Permission model and notification listener must be rewritten from scratch. |
| `feature:terminal` | **5** | Entire runtime depends on Android shell, `ProcessBuilder`, `startForegroundService`, Termux permissions, and bundled Linux binaries (`proot`/`alpine`). Terminal is a core feature but almost entirely Android-specific. |
| `feature:webview` | **3** | `WebViewOperationStore` is pure Kotlin and reusable. All UI that embeds `android.webkit.WebView` must be rewritten. JS bridge logic needs porting. |
| `feature:tools` (impl/api/access) | **3** | Tool definitions and business logic are reusable. Wrappers that call Android APIs (clipboard, file system, WebView) need rewriting. |
| `feature:workspace` | **3** | `WorkspaceManager` uses `androidx.documentfile` and `Uri` tree access. Must be rewritten for HarmonyOS file system. |
| `feature:live` | **4** | `LiveModeManager` depends on `AmberAccessibilityService` (Android-specific). The floating bubble (`LiveBubbleWindow`) uses Android window manager. |
| `feature:modelcouncil` | **2** | Core logic is mostly reusable. Depends on Android for scheduling and context. |
| `feature:subagent` | **2** | Core agent logic is reusable. Android-specific parts are minimal. |
| `feature:task` | **2** | Task models are pure Kotlin. Scheduling depends on WorkManager. |
| `feature:history` | **3** | UI and data access layer need rewriting. |
| `feature:icloud` | **3** | Web-based iCloud login logic is reusable. File operations depend on Android file system. |
| `web-ui` | **1** | 100% reusable. React/Vite frontend is platform-agnostic. Only needs a HarmonyOS backend to serve the API or host the static files. |
| `native/` | **3** | Rust code is fully portable. Effort is in rewriting the build system (cargo-ndk -> HarmonyOS NDK) and JNI bindings -> NAPI bindings. |
| **TOTAL** | **~5-6 Engineer-Years** | This is a massive undertaking. The `app` module alone, containing the entire UI and lifecycle glue, represents the bulk of the work. The core agent logic (~20% of code) is reusable, but ~80% of the codebase is Android-specific platform glue. |

## Summary and Strategic Recommendation

### Portability Matrix

| Layer | Reusability | Replacement Needed |
|-------|-------------|-------------------|
| Agent Runtime Core (Pipelines, Routers, Tools) | **High** | None (Pure Kotlin) |
| AI Provider SDK (HTTP, SSE, Models) | **High** | OAuth flows, image loading |
| Data Models (Settings, Assistants, Messages) | **High** | Remove Compose runtime annotations |
| UI (Compose, Navigation, Theme) | **None** | Full rewrite in ArkUI/ArkTS |
| Storage (Room, DataStore) | **None** | Rewrite with RDB/Preferences |
| Background Tasks (WorkManager) | **None** | HarmonyOS scheduler |
| System Services (Accessibility, MediaProjection, Notifications) | **None** | HarmonyOS equivalents (if exist) |
| Terminal (Android Shell, proot) | **None** | Complete rewrite or removal |
| Native Rust | **High (code)** | Rebuild for HarmonyOS ABI, NAPI bindings |
| Web Frontend | **100%** | None |
| Google/Firebase Services | **None** | HMS or third-party alternatives |

### Recommendation

A **full native HarmonyOS NEXT migration** is a **multi-year engineering effort** for a codebase of this size and complexity. The project would effectively be a **complete rewrite** of the Android application, preserving only the core agent runtime logic and Rust native modules.

**Alternative strategies to consider:**
1. **ArkUI-X / Cross-Platform:** If the team wants to minimize rewrite, investigate ArkUI-X (HarmonyOS's cross-platform framework) to see if any Compose logic can be reused. However, ArkUI-X is still in early stages and may not support the depth of features needed (e.g., custom WebView interop, Accessibility).
2. **Web-First / Hybrid:** Leverage the existing `web-ui` (React) frontend. A HarmonyOS version could primarily be a **web wrapper** (WebView-based) with a lightweight ArkTS native shell for system-level features (notifications, file picker, TTS). This would drastically reduce UI rewrite effort but requires a robust backend API (the Ktor server) to drive the web UI. The core agent logic can run in the native shell or a background service. This is the **most pragmatic short-term path** if a quick HarmonyOS presence is needed.
