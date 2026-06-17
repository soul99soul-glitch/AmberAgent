# HarmonyOS Fixture Corpus

Fixtures here should become the contract between the Android implementation and the ArkTS rewrite.

## Fixture Groups

- `streaming/`: OpenAI-compatible SSE chunks, cancellation, malformed events.
- `messages/`: UIMessage-like normalized JSON for chat and tool results.
- `transformers/`: ThinkTag, template, regex, document-as-prompt outputs.
- `agent_loop/`: turn events, generation chunks, failure and cancellation cases.
- `markdown/`: streaming Markdown blocks with code fences, tables, lists, tool cards.
- `storage/`: conversation/message tree JSON, expected RDB rows, and Preferences shape.
- `contracts/`: human-readable semantic contracts for ArkTS rewrites.
- `crypto/`: encrypted export/import compatibility samples, if secrets are test-safe.
- `native/`: tokenizer/highlighter/document parser input and expected output.

`crypto/` and `native/` are intentionally deferred until Gate 0/native tooling is available or Android-side golden outputs are generated.

Do not commit real API keys, private documents, or user conversation data.
