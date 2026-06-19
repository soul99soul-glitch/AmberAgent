# Phase 3 Workspace/File Capability Gate Matrix

## Source Facts

- Roadmap Phase 3 requires Workspace home, file import, parsing, conversation file context, Artifact display, permission boundaries, and cleanup management.
- iOS currently has one-shot selected-file context through `ChatView`, `DocumentAccessStore`, `ChatViewModel`, and `IOSLocalToolExecutor`.
- iOS Workspace UI is a placeholder in `PlaceholderViews.WorkspaceView`.
- iOS has no general Workspace file registry, Artifact repository, Workspace path contract, or file-write Artifact approval path.
- Android has `/workspace` path normalization and tools for list/read/write/search/move plus artifact tools, but Android all-files/external-root semantics must not be ported literally to iOS.
- iOS must remain one Amber Assistant; multi-assistant placeholder entry points should not grow.

## Gates

| Capability | Android Anchor | iOS Baseline | Phase 3 Gate | Decision |
| --- | --- | --- | --- | --- |
| Workspace home | `WorkspaceFileSheet`, `WorkspaceFileVM`, `WorkspaceArtifactTools` | Placeholder list only | Real home with recent files, artifacts, empty/error states | Implement |
| Workspace path contract | `WorkspacePaths.normalize`, `WorkspaceManager` | None | App-local `/workspace/...` style safe paths, traversal rejection | Implement iOS-local |
| File import | `copyUriToUploads`, `FilesManager` | Chat-only file picker grant | Import file into app Workspace with metadata and optional one-shot chat context | Implement |
| Security-scoped access | Android SAF | In-memory URL grant, no bookmark persistence | Do not persist broad external access; distinguish denied/stale/missing and clear invalid grants | Implement minimal safe state |
| File parsing | `DocumentAsPromptTransformer`, document parsers | Text/PDF/DOCX selected preview | Text/Markdown/PDF verified; DOCX stays available but not marketed as full Office parity; PPTX/OCR honest degraded | Implement/record |
| Conversation file context | `ConversationContextTools`, document prompt transformer | One-shot pending preview injected into prompt | Selected Workspace file can be previewed, attached, removed, reparsed; persisted text is intentional | Implement |
| Artifacts | Workspace artifact tools, board artifacts | MiniApp board summary, Deep Read board persistence, no generic repo | Generic Artifact save/read/delete/list for chat/output types | Implement |
| Tool read approval | `file_read`, `pdf_read`, `office_read` | `file_read_selected` grant only | Workspace read tool limited to imported/artifact files; no arbitrary external paths | Implement |
| Tool write approval | `file_write`, `archive_extract/create`, external write approvals | Memory/WebMount approvals only | Workspace write/delete require foreground approval and disabled policy support | Implement |
| Preview | `WorkspaceFilePreview` | Pending chip only | Detail preview for text/Markdown/PDF parser output, error states | Implement |
| Cleanup | Workspace delete/share | None | Remove file/artifact and stale/missing states | Implement |
| External all-files | `ExternalFileTools` | iOS blocks tools | No Android-style all-files access on iOS | Do not port |
| OCR | `ocr_image` reports unavailable | Image rejected as no OCR | Honest unavailable unless real OCR added | Do not fake |
| Assistant profiles | Android multi-assistant areas | Placeholder Assistants tab/view | iOS one Amber Assistant only | Remove/hide multi-assistant placeholder entry |
| Sync backup fake settings | Android settings pages | Remote sync toggle backed by no-op gate | Avoid new fake settings; clean only obvious no-op copy if touched | Minimal cleanup |

## Accepted Audit Findings

- Replace Workspace placeholder with real UI and repository.
- Keep external file access selected/imported only; do not scan user folders.
- Add explicit stale/missing/security-scope failure states.
- Surface selected file text persistence honestly.
- Add Artifact CRUD and tool approval coverage.

## Deferred Or Unsupported

- Durable external-folder bookmarks are deferred because they require a broader privacy/product policy.
- PPTX/XLSX/EPUB full parity is deferred; unsupported states must be explicit.
- OCR is unavailable unless a real local Vision/VLM path is implemented.
- Android all-files and raw absolute path tools are not portable to iOS.

## Implementation And Verification Notes

- Implemented app-local Workspace file registry, file import/copy, metadata, parsing status, preview, reparse, remove, Artifact CRUD, Workspace tools, and permission approvals.
- Connected chat file picker to Workspace import while keeping one-shot selected-file prompt injection.
- Connected chat text, generated images, Mini App outputs, MiniApp host artifacts, and Deep Read results to Workspace Artifacts.
- `git diff --check` passed.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64` passed.
- `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build` now reaches link, but the generic destination selects `x86_64` while the current `Shared.framework` and `AmberNative.xcframework/ios-arm64-sim` slices are `arm64`; this fails with undefined `Shared*`/`amber_*` symbols for `x86_64`.
- `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" ARCHS=arm64 ONLY_ACTIVE_ARCH=NO build` passed.
- Targeted Phase 3 XCTest passed on the available iPhone 17 simulator with `ARCHS=arm64 ONLY_ACTIVE_ARCH=NO`: `DocumentAccessStoreTests`, `IOSLocalToolExecutorTests`, and `IOSCapabilityRegistryTests`.
