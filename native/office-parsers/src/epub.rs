//! EPUB → markdown-flavoured plain text converter. Output **must** match
//! `document/EpubParser.kt` byte-for-byte (modulo whitespace normalisation
//! that's already trimmed/collapsed at end of pipeline).
//!
//! Reference JVM impl: `document/src/main/java/me/rerere/document/EpubParser.kt`.
//!
//! Pipeline:
//!   1. Open .epub as zip
//!   2. Read META-INF/container.xml → <rootfile full-path="..."> = OPF path
//!   3. Parse OPF: manifest = id → (href, mediaType); spine = ordered idrefs
//!   4. For each spine itemref:
//!      - skip if media-type doesn't contain "html"
//!      - resolve href relative to OPF dir
//!      - parse XHTML body, emit markdown-flavoured text
//!      - append "\n\n" between items
//!   5. Final: collapse 3+ consecutive \n to "\n\n", trim

use std::fs::File;
use std::io::{BufReader, Read};
use std::collections::HashMap;

use quick_xml::events::{BytesStart, Event};
use quick_xml::reader::Reader;
use zip::read::ZipArchive;

use crate::error::{OfficeParseError, Result};

pub fn parse_to_markdown(path: &str) -> String {
    match try_parse(path) {
        Ok(s) => s,
        Err(e) => e.to_epub_message(),
    }
}

#[derive(Debug, Clone)]
struct ManifestItem {
    href: String,
    media_type: String,
}

fn try_parse(path: &str) -> Result<String> {
    let file = File::open(path)?;
    let buf = BufReader::new(file);
    let mut archive = ZipArchive::new(buf)?;

    let opf_path = match find_opf_path(&mut archive)? {
        Some(p) => p,
        None => return Err(OfficeParseError::EpubOpfMissing),
    };

    let opf_dir = match opf_path.rfind('/') {
        Some(idx) => opf_path[..idx].to_string(),
        None => String::new(),
    };

    let opf_bytes = read_zip_entry(&mut archive, &opf_path)?;
    let (manifest, spine) = parse_opf(&opf_bytes)?;

    let mut out = String::new();
    for item_id in &spine {
        let item = match manifest.get(item_id) {
            Some(i) => i,
            None => continue,
        };
        if !item.media_type.contains("html") {
            continue;
        }
        let item_path = if opf_dir.is_empty() {
            item.href.clone()
        } else {
            format!("{}/{}", opf_dir, item.href)
        };
        let xhtml_bytes = match read_zip_entry(&mut archive, &item_path) {
            Ok(b) => b,
            Err(_) => continue,
        };
        let content = parse_xhtml(&xhtml_bytes);
        if !content.trim().is_empty() {
            out.push_str(&content);
            out.push_str("\n\n");
        }
    }

    let trimmed = collapse_newlines(&out).trim().to_string();
    if trimmed.is_empty() {
        Ok("No readable content found in EPUB file".to_string())
    } else {
        Ok(trimmed)
    }
}

fn find_opf_path<R: std::io::Read + std::io::Seek>(
    archive: &mut ZipArchive<R>,
) -> Result<Option<String>> {
    let container_bytes = match read_zip_entry(archive, "META-INF/container.xml") {
        Ok(b) => b,
        Err(_) => return Ok(None),
    };
    let mut reader = Reader::from_reader(container_bytes.as_slice());
    reader.config_mut().trim_text(false);
    let mut buf = Vec::new();
    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) | Event::Empty(ref e) => {
                if local_name(e.name().into_inner()) == b"rootfile" {
                    if let Some(path) = attr_val(e, b"full-path")? {
                        return Ok(Some(path));
                    }
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }
    Ok(None)
}

fn parse_opf(xml: &[u8]) -> Result<(HashMap<String, ManifestItem>, Vec<String>)> {
    let mut reader = Reader::from_reader(xml);
    reader.config_mut().trim_text(false);
    let mut buf = Vec::new();
    let mut manifest: HashMap<String, ManifestItem> = HashMap::new();
    let mut spine: Vec<String> = Vec::new();
    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) | Event::Empty(ref e) => match local_name(e.name().into_inner()) {
                b"item" => {
                    let id = attr_val(e, b"id")?.unwrap_or_default();
                    let href = attr_val(e, b"href")?.unwrap_or_default();
                    let media = attr_val(e, b"media-type")?.unwrap_or_default();
                    if !id.is_empty() {
                        manifest.insert(id, ManifestItem { href, media_type: media });
                    }
                }
                b"itemref" => {
                    if let Some(idref) = attr_val(e, b"idref")? {
                        if !idref.is_empty() {
                            spine.push(idref);
                        }
                    }
                }
                _ => {}
            },
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }
    Ok((manifest, spine))
}

/// Walk one XHTML body and emit markdown-flavoured text. Mirrors
/// `EpubParser.parseXhtml` exactly. JVM uses non-namespace-aware parser with
/// `FEATURE_PROCESS_DOCDECL=false`; quick-xml ignores doctype by default and
/// we strip any namespace prefix via local_name().
///
/// Errors during parsing return an empty string so a single broken chapter
/// doesn't poison the whole book — mirrors JVM `catch (e: Exception) { "" }`.
fn parse_xhtml(xml: &[u8]) -> String {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        try_parse_xhtml(xml)
    }));
    match result {
        Ok(Ok(s)) => s,
        Ok(Err(_)) => String::new(),
        Err(_) => String::new(),
    }
}

fn try_parse_xhtml(xml: &[u8]) -> Result<String> {
    // Per-chapter normalisation order matches JVM: build buffer, then
    // collapse \n{3,} → \n\n + trim. Round 1 review P1 fix — the previous
    // version only did this at the outer loop, which differed from JVM
    // when chapters had trailing whitespace inside <body>.
    let raw = collect_xhtml_events(xml)?;
    Ok(collapse_newlines(&raw).trim().to_string())
}

fn collect_xhtml_events(xml: &[u8]) -> Result<String> {
    let mut reader = Reader::from_reader(xml);
    reader.config_mut().trim_text(false);
    reader.config_mut().expand_empty_elements = false;
    let mut buf = Vec::new();

    let mut result = String::with_capacity(xml.len() / 4);
    // tag_stack tracks **open** tags by lowercase local name so we can answer
    // "what's my parent?" — mirrors JVM ArrayDeque<String> tagStack.
    let mut tag_stack: Vec<String> = Vec::with_capacity(32);
    let mut in_body = false;
    let mut list_counter: i32 = 0;

    let whitespace_run = regex_lite_whitespace();

    loop {
        let event = match reader.read_event_into(&mut buf) {
            Ok(ev) => ev,
            Err(_) => break, // JVM swallows mid-parse exceptions
        };
        match event {
            Event::Start(ref e) => {
                let tag = local_name(e.name().into_inner()).to_ascii_lowercase_owned();
                tag_stack.push(tag.clone());
                handle_open_tag(
                    &tag,
                    e,
                    &mut in_body,
                    &mut list_counter,
                    &tag_stack,
                    &mut result,
                );
            }
            Event::Empty(ref e) => {
                // Self-closing tags. JVM's XmlPullParser delivers these as
                // START_TAG + END_TAG with the tag PUSHED to tagStack between
                // them. Mirror exactly: push, open, close, pop. This matters
                // for `<li/>` where `handle_open_tag` looks at len-2 of the
                // stack to find the ol/ul parent (Round 1 P2 fix).
                let tag = local_name(e.name().into_inner()).to_ascii_lowercase_owned();
                tag_stack.push(tag.clone());
                handle_open_tag(
                    &tag,
                    e,
                    &mut in_body,
                    &mut list_counter,
                    &tag_stack,
                    &mut result,
                );
                handle_close_tag(&tag, in_body, &mut result);
                tag_stack.pop();
                if tag == "body" {
                    in_body = false;
                }
            }
            Event::Text(t) => {
                if in_body {
                    let raw = match t.unescape() {
                        Ok(c) => c.into_owned(),
                        Err(_) => continue,
                    };
                    // JVM normalises: \n/\r → ' ', \s+ → ' '
                    let normalised = raw
                        .replace('\n', " ")
                        .replace('\r', " ");
                    let collapsed = collapse_runs(&normalised, &whitespace_run);
                    if !collapsed.trim().is_empty() {
                        result.push_str(&collapsed);
                    }
                }
            }
            Event::End(ref e) => {
                let tag = local_name(e.name().into_inner()).to_ascii_lowercase_owned();
                if !tag_stack.is_empty() {
                    tag_stack.pop();
                }
                handle_close_tag(&tag, in_body, &mut result);
                if tag == "body" {
                    in_body = false;
                }
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    Ok(result)
}

fn handle_open_tag(
    tag: &str,
    e: &BytesStart,
    in_body: &mut bool,
    list_counter: &mut i32,
    tag_stack: &Vec<String>,
    result: &mut String,
) {
    match tag {
        "body" => *in_body = true,
        "ol" => *list_counter = 0,
        "li" => {
            // JVM looks at tagStack.dropLast(1).lastOrNull() — i.e., the
            // tag containing this <li>. tag_stack last entry is "li"
            // (we just pushed it). The parent is at len-2.
            let parent = tag_stack
                .get(tag_stack.len().saturating_sub(2))
                .map(|s| s.as_str())
                .unwrap_or("");
            if parent == "ol" {
                *list_counter += 1;
                result.push_str(&format!("{}. ", list_counter));
            } else {
                result.push_str("- ");
            }
        }
        "br" => result.push('\n'),
        "img" => {
            if *in_body {
                if let Ok(Some(alt)) = attr_val(e, b"alt") {
                    if !alt.trim().is_empty() {
                        result.push_str(&format!("[image: {}]", alt));
                    }
                }
            }
        }
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            if *in_body {
                let level = tag.chars().nth(1).and_then(|c| c.to_digit(10)).unwrap_or(1) as usize;
                result.push_str(&"#".repeat(level));
                result.push(' ');
            }
        }
        "strong" | "b" => {
            if *in_body {
                result.push_str("**");
            }
        }
        "em" | "i" => {
            if *in_body {
                result.push('*');
            }
        }
        "hr" => {
            if *in_body {
                result.push_str("\n---\n");
            }
        }
        "blockquote" => {
            if *in_body {
                result.push_str("> ");
            }
        }
        _ => {}
    }
}

fn handle_close_tag(tag: &str, in_body: bool, result: &mut String) {
    match tag {
        "body" => { /* in_body flipped by caller */ }
        "p" | "div" => {
            if in_body {
                result.push_str("\n\n");
            }
        }
        "h1" | "h2" | "h3" | "h4" | "h5" | "h6" => {
            if in_body {
                result.push_str("\n\n");
            }
        }
        "li" => {
            if in_body {
                result.push('\n');
            }
        }
        "ul" | "ol" => {
            if in_body {
                result.push('\n');
            }
        }
        "br" => { /* no-op on close — br is leaf */ }
        "strong" | "b" => {
            if in_body {
                result.push_str("**");
            }
        }
        "em" | "i" => {
            if in_body {
                result.push('*');
            }
        }
        "blockquote" => {
            if in_body {
                result.push('\n');
            }
        }
        _ => {}
    }
}

/// Collapse runs of 3+ '\n' down to '\n\n'. Mirrors JVM
/// `result.replace(Regex("\n{3,}"), "\n\n")`.
///
/// **UTF-8 safety**: Previous impl used `out.push(bytes[i] as char)` which
/// fractured every multi-byte sequence (CJK / Latin-extended / emoji) into
/// 2-3 garbage U+00xx code points — Round 2 review P1 regression. This rewrite
/// walks by `char` so every code point reaches the output intact.
fn collapse_newlines(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut newline_run = 0usize;
    for ch in s.chars() {
        if ch == '\n' {
            newline_run += 1;
        } else {
            if newline_run > 0 {
                let to_emit = if newline_run >= 3 { 2 } else { newline_run };
                for _ in 0..to_emit {
                    out.push('\n');
                }
                newline_run = 0;
            }
            out.push(ch);
        }
    }
    if newline_run > 0 {
        let to_emit = if newline_run >= 3 { 2 } else { newline_run };
        for _ in 0..to_emit {
            out.push('\n');
        }
    }
    out
}

// ---------------- helpers ----------------

/// Strip namespace prefix from XML element name.
fn local_name(qualified: &[u8]) -> &[u8] {
    if let Some(pos) = qualified.iter().position(|&b| b == b':') {
        &qualified[pos + 1..]
    } else {
        qualified
    }
}

fn attr_val(e: &BytesStart, key: &[u8]) -> Result<Option<String>> {
    for attr in e.attributes() {
        let attr = attr?;
        if local_name(attr.key.as_ref()) == key {
            return Ok(Some(attr.unescape_value()?.into_owned()));
        }
    }
    Ok(None)
}

fn read_zip_entry<R: std::io::Read + std::io::Seek>(
    archive: &mut ZipArchive<R>,
    name: &str,
) -> Result<Vec<u8>> {
    let mut entry = archive.by_name(name)?;
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry.read_to_end(&mut bytes)?;
    Ok(bytes)
}

/// Sentinel placeholder for a "\\s+ → ' '" collapse without pulling in `regex`.
/// We use a hand-rolled run-collapse for performance + zero deps.
struct WhitespaceRunCollapser;

fn regex_lite_whitespace() -> WhitespaceRunCollapser {
    WhitespaceRunCollapser
}

/// Collapse runs of **ASCII** whitespace into a single ' '.
/// Mirrors JVM `text.replace("\\s+".toRegex(), " ")` — Java `Pattern`'s `\s`
/// defaults to ASCII (no UNICODE_CHARACTER_CLASS flag in Kotlin .toRegex()),
/// so NBSP / U+3000 / other unicode whitespace must pass through unchanged.
/// (Round 1 review P1 fix: previous impl used Unicode `char::is_whitespace`.)
fn collapse_runs(s: &str, _: &WhitespaceRunCollapser) -> String {
    let mut out = String::with_capacity(s.len());
    let mut last_was_ws = false;
    for ch in s.chars() {
        let is_ascii_ws = matches!(ch, ' ' | '\t' | '\n' | '\x0B' | '\x0C' | '\r');
        if is_ascii_ws {
            if !last_was_ws {
                out.push(' ');
                last_was_ws = true;
            }
        } else {
            out.push(ch);
            last_was_ws = false;
        }
    }
    out
}

trait AsciiLowercaseOwned {
    fn to_ascii_lowercase_owned(&self) -> String;
}

impl AsciiLowercaseOwned for [u8] {
    fn to_ascii_lowercase_owned(&self) -> String {
        // SAFETY: XML names are restricted to ASCII subset for tag identifiers;
        // we still apply utf-8-safe conversion via String::from_utf8_lossy in
        // case of a malformed input (returns U+FFFD replacements, which won't
        // match any tag branch — graceful degrade).
        String::from_utf8_lossy(self)
            .as_ref()
            .to_ascii_lowercase()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_name_strips_prefix() {
        assert_eq!(local_name(b"opf:item"), b"item");
        assert_eq!(local_name(b"item"), b"item");
    }

    #[test]
    fn collapse_newlines_keeps_double() {
        assert_eq!(collapse_newlines("a\nb"), "a\nb");
        assert_eq!(collapse_newlines("a\n\nb"), "a\n\nb");
        assert_eq!(collapse_newlines("a\n\n\nb"), "a\n\nb");
        assert_eq!(collapse_newlines("a\n\n\n\n\nb"), "a\n\nb");
    }

    #[test]
    fn collapse_runs_basic() {
        let c = regex_lite_whitespace();
        assert_eq!(collapse_runs("  a  b  ", &c), " a b ");
        // Unicode whitespace MUST pass through unchanged to match JVM ASCII-only
        // `\s+` semantics (Java Pattern without UNICODE_CHARACTER_CLASS).
        assert_eq!(collapse_runs("a\u{3000}b", &c), "a\u{3000}b");
        assert_eq!(collapse_runs("a\u{00A0}b", &c), "a\u{00A0}b"); // NBSP
        assert_eq!(collapse_runs("", &c), "");
    }

    #[test]
    fn parse_xhtml_heading_paragraph() {
        let xml = br#"<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Title</h1><p>Para</p></body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("# Title"));
        assert!(out.contains("Para"));
    }

    #[test]
    fn parse_xhtml_ol_numbering() {
        let xml = br#"<?xml version="1.0"?><html><body><ol><li>first</li><li>second</li></ol></body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("1. first"));
        assert!(out.contains("2. second"));
    }

    #[test]
    fn parse_xhtml_ul_bullets() {
        let xml = br#"<?xml version="1.0"?><html><body><ul><li>a</li><li>b</li></ul></body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("- a"));
        assert!(out.contains("- b"));
    }

    #[test]
    fn parse_xhtml_bold_italic() {
        let xml = br#"<?xml version="1.0"?><html><body><p><strong>bold</strong> and <em>italic</em></p></body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("**bold**"));
        assert!(out.contains("*italic*"));
    }

    /// Regression test for Round 2 P1: `collapse_newlines` was casting raw UTF-8
    /// bytes to char, mangling multi-byte sequences into U+00xx replacements.
    /// This test asserts a real CJK chapter round-trips with code-points intact.
    #[test]
    fn collapse_newlines_preserves_cjk() {
        // 你好 = E4 BD A0 E5 A5 BD. If the old impl runs, each continuation byte
        // becomes a U+00xx char and the assertion below fails.
        let input = "你好\n\n\n世界";
        let out = collapse_newlines(input);
        assert_eq!(out, "你好\n\n世界");
    }

    #[test]
    fn parse_xhtml_preserves_cjk() {
        let xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\
            <html><body><p>你好世界</p></body></html>".as_bytes();
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("你好世界"), "CJK roundtrip failed: {:?}", out);
    }

    #[test]
    fn parse_xhtml_img_alt() {
        let xml = br#"<?xml version="1.0"?><html><body><p>before <img src="x.png" alt="diagram"/> after</p></body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("[image: diagram]"));
    }

    #[test]
    fn parse_xhtml_hr_blockquote_br() {
        let xml = br#"<?xml version="1.0"?><html><body><p>top<br/>bottom</p><hr/><blockquote>quote</blockquote></body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("top\nbottom"));
        assert!(out.contains("---"));
        assert!(out.contains("> quote"));
    }

    #[test]
    fn parse_xhtml_nested_lists() {
        let xml = br#"<?xml version="1.0"?><html><body>
            <ul><li>outer<ol><li>inner1</li><li>inner2</li></ol></li></ul>
        </body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("- outer"));
        assert!(out.contains("1. inner1"));
        assert!(out.contains("2. inner2"));
    }

    #[test]
    fn parse_xhtml_empty_body_returns_blank() {
        let xml = br#"<?xml version="1.0"?><html><body></body></html>"#;
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.is_empty() || out.trim().is_empty());
    }

    /// 4-byte UTF-8 (supplementary plane: emoji, ancient scripts) regression
    /// test. Catches the same UTF-8-corruption family as the CJK 3-byte test
    /// but for code points U+10000+ (P3 sweep — added defensive coverage).
    #[test]
    fn parse_xhtml_preserves_emoji() {
        let xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\
            <html><body><p>hello 😀 world 🚀</p></body></html>".as_bytes();
        let out = try_parse_xhtml(xml).unwrap();
        assert!(out.contains("😀"), "emoji 😀 corrupted: {:?}", out);
        assert!(out.contains("🚀"), "emoji 🚀 corrupted: {:?}", out);
    }
}
