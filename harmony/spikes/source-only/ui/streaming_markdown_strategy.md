# Streaming Markdown Strategy

Gate 0 is blocked, so this is a source-only rendering strategy for the future ArkUI spike.

## Problem

Rendering every token by reparsing the entire assistant message risks jank. Android already contains jank optimization work; HarmonyOS must treat Markdown parsing as part of the P0 Chat go/no-go, not just `List` scrolling.

## Strategy Candidates

1. **Full reparse**
   - Easiest to implement.
   - Acceptable only for short messages or throttled updates.
2. **Batched full reparse**
   - Buffer token deltas for 50-100 ms, then reparse.
   - Good first ArkUI spike strategy.
3. **Stable block parser**
   - Keep closed blocks stable; only reparse the open tail block.
   - Required if batched full reparse janks on long code/table output.

## Required Fixture Coverage

- `../../tests/fixtures/markdown/streaming_markdown_blocks.jsonl`
- `../../tests/fixtures/markdown/tool_card_markdown.md`

## ArkUI Spike Requirements

- Use synthetic token streaming with realistic Markdown.
- Include manual scroll interruption and scroll-follow restore.
- Measure rendering behavior with at least 5k chunks.
- Record whether batching is enough or stable-block parsing is mandatory.
