# Transformer Contract

Message transformers are part of AmberAgent's product semantics. ArkTS rewrites must preserve these behaviors unless an incompatibility is explicitly documented.

## Input Transformers

- Template transformation wraps or rewrites the current user message using assistant template variables.
- Document-as-prompt replaces document parts with bounded extracted text blocks.
- OCR transforms image input into text only when an OCR provider is configured; otherwise fallback must be explicit.

## Output Transformers

- ThinkTag extracts `<think>...</think>` into reasoning parts.
- Regex output transformer applies assistant-scoped regex replacements to text and reasoning.
- Tail-safe output transformers must produce the same visual result on the active tail message as full-message transformation.

## Required Fixtures

- `../transformers/think_tag_input.md`
- `../transformers/think_tag_expected.json`
- `../transformers/template_input.json`
- `../transformers/template_expected.json`
- `../transformers/regex_rules.json`
- `../transformers/regex_input_output.json`
- `../transformers/document_as_prompt_input.json`
- `../transformers/document_as_prompt_expected.md`
- `../transformers/ocr_image_prompt.json`
