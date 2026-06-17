# Source-only Spike Skeletons

Gate 0 is currently blocked, so this directory contains contracts and skeletons only. These files are not a DevEco project and must not be treated as verified HarmonyOS code.

Purpose:

- Freeze the API shapes that future ArkTS modules should implement.
- Give AI workers deterministic starting points once DevEco tooling is available.
- Keep P0 work focused on SSE, RDB/Preferences, and ArkUI+Markdown.

## Contents

- `streaming/openai_sse_parser.ts`: source-only parser shape for OpenAI-compatible SSE.
- `storage/minimum_chat_schema.sql`: minimum RDB schema for Chat P0.
- `storage/preferences_shape.ts`: source-only preferences object shapes.
- `agent/agent_loop_types.ts`: source-only turn/message/tool event types.
- `ui/streaming_markdown_strategy.md`: P0 rendering strategy for ArkUI+Markdown.

## Rules

- Do not import HarmonyOS SDK modules here.
- Do not mark these as `pass` in the capability matrix.
- When real ArkTS code is created, link it back to the fixture contracts under `harmony/tests/fixtures/`.
