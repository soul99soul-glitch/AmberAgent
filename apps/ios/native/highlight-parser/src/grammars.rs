//! Bundled tree-sitter grammars and per-language highlight configuration.
//!
//! Each grammar's highlight query is sourced from its crate's `HIGHLIGHTS_QUERY`
//! constant. A common set of recognized scope names is shared across grammars
//! so the Kotlin renderer can map them to existing Prism CSS classes.

use std::sync::OnceLock;

use tree_sitter::Language;
use tree_sitter_highlight::{HighlightConfiguration, HighlightEvent, Highlighter};

/// Scope names that all grammars use. Order matters — it's the index space
/// the highlight events reference. We map these to Prism-style CSS class
/// names on the Kotlin side. See `scope_mapping.rs`.
const HIGHLIGHT_NAMES: &[&str] = &[
    "attribute",
    "comment",
    "constant",
    "constant.builtin",
    "constructor",
    "function",
    "function.builtin",
    "function.macro",
    "function.method",
    "keyword",
    "label",
    "module",
    "number",
    "operator",
    "property",
    "punctuation",
    "punctuation.bracket",
    "punctuation.delimiter",
    "string",
    "string.special",
    "tag",
    "type",
    "type.builtin",
    "variable",
    "variable.builtin",
    "variable.parameter",
];

pub fn highlight_names() -> &'static [&'static str] {
    HIGHLIGHT_NAMES
}

pub struct Grammar {
    pub config: HighlightConfiguration,
}

/// Public: list of language identifiers we accept. Order is stable for the
/// JNI `supportedLanguages()` API.
pub fn supported_languages() -> &'static [&'static str] {
    &[
        "rust",
        // "kotlin" disabled — tree-sitter-kotlin-ng has no bundled highlight query.
        "java",
        "python",
        "javascript",
        "typescript",
        "tsx",
        "go",
        "bash",
        "shell",
        "json",
        "yaml",
        "yml",
        "markdown",
        "md",
        "html",
        "css",
        "sql",
    ]
}

/// Run highlight over the given source for the given grammar and collect
/// events. The Highlighter is created per-call to keep the JNI surface
/// stateless; tree-sitter parser internal state is small relative to the
/// highlight queries that are built into the configuration.
pub fn run_highlight(
    grammar: &'static Grammar,
    code: &str,
) -> Result<Vec<HighlightEvent>, tree_sitter_highlight::Error> {
    let mut highlighter = Highlighter::new();
    let iter = highlighter.highlight(&grammar.config, code.as_bytes(), None, |_lang_name| None)?;
    let mut events = Vec::new();
    for event in iter {
        events.push(event?);
    }
    Ok(events)
}

/// Map language identifier → static Grammar config (lazily initialised once).
///
/// Returns `None` for unknown languages and also for languages whose grammar
/// failed to initialise (logged once and cached).
pub fn for_language(lang: &str) -> Option<&'static Grammar> {
    let normalized = lang.trim().to_lowercase();
    match normalized.as_str() {
        "rust" | "rs" => rust_grammar(),
        // kotlin grammar disabled — see tree-sitter-kotlin-ng note above.
        "java" => java_grammar(),
        "python" | "py" => python_grammar(),
        "javascript" | "js" | "jsx" => javascript_grammar(),
        "typescript" | "ts" => typescript_grammar(),
        "tsx" => tsx_grammar(),
        "go" => go_grammar(),
        "bash" | "sh" | "shell" | "zsh" => bash_grammar(),
        "json" | "jsonc" => json_grammar(),
        "yaml" | "yml" => yaml_grammar(),
        "markdown" | "md" => markdown_grammar(),
        "html" | "htm" => html_grammar(),
        "css" | "scss" | "sass" => css_grammar(),
        "sql" => sql_grammar(),
        _ => None,
    }
}

// ---------------- per-language constructors ----------------
//
// Each grammar crate exposes either `LANGUAGE` (modern) or `language()` (older).
// Recent crates moved to the LanguageFn / LANGUAGE constant pattern; we adapt
// to whichever each version exports. If a grammar crate changes its API,
// only this file needs touching.

/// Build a lazily-initialised grammar accessor. Failure (malformed highlight
/// query, ABI mismatch, etc.) yields `None` so the caller degrades to plain
/// rendering instead of panicking — important because release builds use
/// `panic = "abort"` which `catch_unwind` cannot intercept (review P1 fix).
macro_rules! grammar_static {
    (
        $fn_name:ident,
        $lang_fn:expr,
        $highlight_query:expr,
        $injection_query:expr,
        $locals_query:expr
    ) => {
        fn $fn_name() -> Option<&'static Grammar> {
            static G: OnceLock<Option<Grammar>> = OnceLock::new();
            G.get_or_init(|| {
                let language: Language = $lang_fn.into();
                let mut config = match HighlightConfiguration::new(
                    language,
                    stringify!($fn_name),
                    $highlight_query,
                    $injection_query,
                    $locals_query,
                ) {
                    Ok(cfg) => cfg,
                    Err(e) => {
                        log::warn!(
                            "highlight-parser: {} grammar init failed ({}) — falling back to plain",
                            stringify!($fn_name),
                            e,
                        );
                        return None;
                    }
                };
                config.configure(HIGHLIGHT_NAMES);
                Some(Grammar { config })
            })
            .as_ref()
        }
    };
}

grammar_static!(
    rust_grammar,
    tree_sitter_rust::LANGUAGE,
    tree_sitter_rust::HIGHLIGHTS_QUERY,
    tree_sitter_rust::INJECTIONS_QUERY,
    ""
);

// kotlin grammar disabled (see lib doc note + Cargo.toml comment).

grammar_static!(
    java_grammar,
    tree_sitter_java::LANGUAGE,
    tree_sitter_java::HIGHLIGHTS_QUERY,
    "",
    ""
);

grammar_static!(
    python_grammar,
    tree_sitter_python::LANGUAGE,
    tree_sitter_python::HIGHLIGHTS_QUERY,
    "",
    ""
);

grammar_static!(
    javascript_grammar,
    tree_sitter_javascript::LANGUAGE,
    tree_sitter_javascript::HIGHLIGHT_QUERY,
    tree_sitter_javascript::INJECTIONS_QUERY,
    tree_sitter_javascript::LOCALS_QUERY
);

// tree-sitter-typescript bundles two grammars: `LANGUAGE_TYPESCRIPT` for .ts
// and `LANGUAGE_TSX` for .tsx. Each has its own LANGUAGE constant.
grammar_static!(
    typescript_grammar,
    tree_sitter_typescript::LANGUAGE_TYPESCRIPT,
    tree_sitter_typescript::HIGHLIGHTS_QUERY,
    "",
    tree_sitter_typescript::LOCALS_QUERY
);
grammar_static!(
    tsx_grammar,
    tree_sitter_typescript::LANGUAGE_TSX,
    tree_sitter_typescript::HIGHLIGHTS_QUERY,
    "",
    tree_sitter_typescript::LOCALS_QUERY
);

grammar_static!(
    go_grammar,
    tree_sitter_go::LANGUAGE,
    tree_sitter_go::HIGHLIGHTS_QUERY,
    "",
    ""
);

grammar_static!(
    bash_grammar,
    tree_sitter_bash::LANGUAGE,
    tree_sitter_bash::HIGHLIGHT_QUERY,
    "",
    ""
);

grammar_static!(
    json_grammar,
    tree_sitter_json::LANGUAGE,
    tree_sitter_json::HIGHLIGHTS_QUERY,
    "",
    ""
);

grammar_static!(
    yaml_grammar,
    tree_sitter_yaml::LANGUAGE,
    tree_sitter_yaml::HIGHLIGHTS_QUERY,
    "",
    ""
);

grammar_static!(
    markdown_grammar,
    tree_sitter_md::LANGUAGE,
    tree_sitter_md::HIGHLIGHT_QUERY_BLOCK,
    tree_sitter_md::INJECTION_QUERY_BLOCK,
    ""
);

grammar_static!(
    html_grammar,
    tree_sitter_html::LANGUAGE,
    tree_sitter_html::HIGHLIGHTS_QUERY,
    tree_sitter_html::INJECTIONS_QUERY,
    ""
);

grammar_static!(
    css_grammar,
    tree_sitter_css::LANGUAGE,
    tree_sitter_css::HIGHLIGHTS_QUERY,
    "",
    ""
);

grammar_static!(
    sql_grammar,
    tree_sitter_sequel::LANGUAGE,
    tree_sitter_sequel::HIGHLIGHTS_QUERY,
    "",
    ""
);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_languages_resolve() {
        assert!(for_language("rust").is_some());
        // kotlin is intentionally disabled
        assert!(for_language("RUST").is_some()); // case-insensitive
    }

    #[test]
    fn unknown_language_returns_none() {
        assert!(for_language("brainfuck").is_none());
        assert!(for_language("").is_none());
        assert!(for_language("kotlin").is_none());
    }
}
