//! Notes on how tree-sitter scope names map to Prism CSS classes the
//! existing `HighlightText.kt` renderer expects.
//!
//! tree-sitter-highlight uses dotted scope names ("function.builtin",
//! "string.special.url"). Prism uses single-word classes ("function",
//! "string"). The Kotlin adapter does the final mapping; this file documents
//! the contract so the Kotlin side stays in sync with `grammars::HIGHLIGHT_NAMES`.
//!
//! Recommended Kotlin-side mapping (apply to packed `type_ref` lookup):
//!
//! | TS scope               | Prism class       | Notes                          |
//! |------------------------|-------------------|--------------------------------|
//! | attribute              | attr-name         |                                |
//! | comment                | comment           |                                |
//! | constant               | constant          |                                |
//! | constant.builtin       | builtin           |                                |
//! | constructor            | class-name        |                                |
//! | function               | function          |                                |
//! | function.builtin       | builtin           |                                |
//! | function.macro         | macro             |                                |
//! | function.method        | method            |                                |
//! | keyword                | keyword           |                                |
//! | label                  | symbol            |                                |
//! | module                 | namespace         |                                |
//! | number                 | number            |                                |
//! | operator               | operator          |                                |
//! | property               | property          |                                |
//! | punctuation            | punctuation       |                                |
//! | punctuation.bracket    | punctuation       |                                |
//! | punctuation.delimiter  | punctuation       |                                |
//! | string                 | string            |                                |
//! | string.special         | string            |                                |
//! | tag                    | tag               |                                |
//! | type                   | class-name        |                                |
//! | type.builtin           | builtin           |                                |
//! | variable               | variable          |                                |
//! | variable.builtin       | builtin           |                                |
//! | variable.parameter     | parameter         |                                |
//!
//! This file is reference-only; no executable code lives here. The mapping
//! table is duplicated in `highlight/src/main/java/me/rerere/highlight/HighlighterNative.kt`.
