# Novel V1 Cross-Platform Fixtures

Platform-neutral golden fixtures for iOS/Android `NovelProjectDocumentV1` and `.ambernovel` packages.

## Layout

- `encoding/swift-wire-shapes.json` — isolated Swift Codable wire shapes (IDs, Dates, associated enums).
- `projects/*.project.json` — raw project document payloads (UTF-8 JSON, sorted keys).
- `packages/*.ambernovel.json` — package envelopes (`format=amber.novel.project`, envelopeVersion=1).

## Phase 0 iOS-export sample (now the P5-01 golden input)

`packages/ios-export-with-future-fields.ambernovel.json` simulates an iOS
export that carries fields the current Android v1 model did not know at
Phase 0:

- `futureTopLevelField` — unknown top-level field (P5-01 retains it via the
  model-layer extension map + controlled merge in `NovelProjectDocumentWireSerializer`).
- `project.aliases` — suggested character aliases (P5-01 known field).
- `sessions[0].interaction` / `sessions[0].contextualCharacter` — session
  interaction and contextual character mention (P5-01 known fields).
- `project.creationMode: "storyboardV2"` — unknown enum value. Since P5-01,
  `NovelProjectCreationMode` decodes unknown values as `Unknown(rawValue)`
  instead of rejecting the whole package, and re-encodes them verbatim.

Envelope is self-consistent (`projectByteCount` / `projectSHA256` / strict
Base64) and was generated from `projects/full-two-branch.project.json` by
adding the fields above. Project payload is UTF-8 JSON with sorted keys.
Phase 0 behavior (whole-package rejection) is replaced by P5-01 round-trip
fidelity: `NovelPackageCodec.decode` succeeds, and import → edit → export →
reimport preserves both the known fields above and `futureTopLevelField`
(see `NovelPackageV2CompatibilityTest` and the Novel canary in
`ProductionChainCanaryTest`).

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
