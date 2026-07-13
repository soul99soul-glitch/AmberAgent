# Novel V1 Cross-Platform Fixtures

Platform-neutral golden fixtures for iOS/Android `NovelProjectDocumentV1` and `.ambernovel` packages.

## Layout

- `encoding/swift-wire-shapes.json` — isolated Swift Codable wire shapes (IDs, Dates, associated enums).
- `projects/*.project.json` — raw project document payloads (UTF-8 JSON, sorted keys).
- `packages/*.ambernovel.json` — package envelopes (`format=amber.novel.project`, envelopeVersion=1).

## Wire contract notes

- Typed IDs encode as `{"rawValue":"UUID-UPPERCASE"}` objects, not bare strings.
- Bare UUIDs (e.g. `factCompatibilityID`) encode as uppercase UUID strings.
- Swift `Date` is seconds since 2001-01-01 (`unix - 978307200`).
- Associated enums use single-key objects (`{"global":{}}`, `{"custom":{"_0":"Place"}}`).
- SHA-256 is 64-char lowercase hex over raw project bytes (Base64-decoded).
- Base64 is strict, no newlines; re-encoding must match exactly.
- Package `previous` / recovery / lifecycle sidecars are Android-repository-only and are not here.

## Provenance

Phase 0 hand-authored to Swift Codable rules for red/green codec tests.
Full bidirectional proof still requires an iOS decoder/validator harness in Phase 1+.
