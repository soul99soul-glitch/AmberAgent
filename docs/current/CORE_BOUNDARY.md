# Core Boundary

## Eligible now

- Domain models and stable serialization
- Agent events and pure reducers
- Tool request/result/error contracts
- Streaming delta and tool-call merge logic
- Prompt budgeting and deterministic context trimming
- Cross-platform golden fixtures

## Defer until two consumers agree

- Provider request builders and response parsers
- Memory policy
- Sync protocol
- SubAgent and Council execution semantics
- Native engine façade

## Keep platform-owned

- Compose, SwiftUI, ViewModel and navigation
- Room, DataStore, UserDefaults, Keychain and Keystore implementations
- WorkManager, BGTask, notifications and Live Activity
- Koin or other platform DI
- WebView, accessibility, media pickers, OCR and permissions
- Concrete Rust/JNI/XCFramework linking and loading

## Extraction acceptance

Every migrated slice must have a narrow façade, no platform implementation dependency, Android and iOS fixture parity, focused tests, rollback instructions and removal of the superseded duplicate.
