# MCP Import Fixtures

Version-controlled golden samples for MCP server import (Phase 0 of the
Android/iOS capability parity plan —
`docs/plans/2026-08-13-android-ios-capability-parity-closure-plan.md`).

## Schema provenance

- File format: Claude-Code / iOS-style `{"mcpServers": {"<name>": {...}}}` map.
- Wire fields: `type` (`streamable_http` | `sse` | unknown), `url`, `headers`.
- Current Android production parser: `parseMcpServersFromJson` in
  `app/src/main/java/app/amber/core/ai/mcp/McpImportParser.kt` (called by the
  MCP settings page import and `McpManagementTools`).
- These fixtures were hand-authored for Phase 0; Phase 2 (P2-05) replaces the
  permissive import with a prepare → approve → apply transaction, at which
  point the "expected behavior" notes below become expectations to enforce.

## Expected behavior today (schema v1 / Phase 0 baseline)

| fixture | expected current behavior |
| --- | --- |
| `http.json` | Parses to one `StreamableHTTPServer` (`type` omitted or `streamable_http` → HTTP). |
| `sse.json` | Parses to one `SseTransportServer`. |
| `unknown-transport.json` | `type: "stdio"` is **not** rejected — the parser falls back to `StreamableHTTPServer` (fail-open). P2-05 must fail closed on unsupported transports. |
| `missing-url.json` | Server is **silently skipped** (no URL → `mapNotNull` drops it). P2-05 must report an explicit error. |
| `duplicate-names.json` | Duplicate keys inside one `mcpServers` object collapse at JSON parse time (only one entry survives), so the raw-file shape never reaches import logic as two servers. Batch-level name-conflict detection (P2-05) must not rely on this collapse, and must not silently skip entries. |
| `with-headers.json` | Header names/values map into `McpCommonOptions.headers`; import preview must show header **names** only, never values (P2-05). |

## Provenance

Phase 0 hand-authored from the production parser's accepted shapes. No live
iOS project is required to run tests against these fixtures.
