//! Pure-Rust GenerativeWidgetParser. Mirrors JVM
//! `app/src/main/java/me/rerere/rikkahub/data/ai/generative/GenerativeWidgetParser.kt`
//! pipeline:
//!
//! 1. Detect `\`\`\`show-widget` / `\`\`\`widget` / `\`\`\`generative-ui` fences
//! 2. Each fence opens a Widget segment until matching `\`\`\``
//! 3. Streaming mode: trailing unclosed fence → Loading segment instead of Text
//! 4. Text between fences → Text segment
//! 5. Widget JSON spec (when present) parsed for `title` + `actions` + `renderer`
//!
//! Packed binary output ("PWGS" — Packed Widget Generative Segments):
//!
//! ```text
//! header (8 bytes):
//!   magic     : 4 bytes 'PWGS'
//!   version   : u8 (= 1)
//!   flags     : u8 (reserved)
//!   reserved  : u16
//!
//! segment_count : varint
//! segments      : repeated
//!
//! Each segment:
//!   kind        : u8 (0 = Text, 1 = Widget, 2 = Loading)
//!   For Text:
//!     content     : varint length + utf-8 bytes
//!   For Widget:
//!     title_opt   : u8 (0 = null, 1 = present)
//!     title?      : varint length + utf-8 bytes (if title_opt == 1)
//!     widget_code : varint length + utf-8 bytes
//!     complete    : u8 (0 = false, 1 = true)
//!     renderer    : varint length + utf-8 bytes  (default "html")
//!     spec_opt    : u8 (0 = null, 1 = present)
//!     spec?       : varint length + utf-8 bytes (if spec_opt == 1)
//!     action_count: varint
//!     actions     : [ id(varint+utf8) | label(varint+utf8) | instruction(varint+utf8) ]
//!   For Loading: (no payload)
//! ```

use regex::Regex;
use serde_json::Value;

const MAGIC: &[u8; 4] = b"PWGS";
const VERSION: u8 = 1;

const KIND_TEXT: u8 = 0;
const KIND_WIDGET: u8 = 1;
const KIND_LOADING: u8 = 2;

/// Fence opener (with mandatory newline). Mirrors the JVM `markerRegex`:
///   `(?m)^[ \t]*` followed by ```` ``` ```` + (show-widget|widget|generative-ui) +
///   optional `info-string` + `\r?\n`.
fn fence_opener_regex() -> &'static Regex {
    use std::sync::OnceLock;
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"(?m)^[ \t]*```[ \t]*(?:show-widget|widget|generative-ui)[^\r\n]*\r?\n")
            .expect("opener regex compiles")
    })
}

/// Fence closer at start-of-line (3 backticks possibly followed by whitespace).
fn fence_closer_regex() -> &'static Regex {
    use std::sync::OnceLock;
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| {
        Regex::new(r"(?m)^[ \t]*```[ \t]*$").expect("closer regex compiles")
    })
}

#[derive(Debug, Clone)]
pub enum Segment {
    Text(String),
    Widget {
        title: Option<String>,
        widget_code: String,
        complete: bool,
        renderer: String,
        spec_json: Option<String>,
        actions: Vec<WidgetAction>,
    },
    Loading,
}

#[derive(Debug, Clone)]
pub struct WidgetAction {
    pub id: String,
    pub label: String,
    pub instruction: String,
}

pub fn parse_to_packed(content: &str, streaming: bool) -> Vec<u8> {
    let segments = parse(content, streaming);
    pack(&segments)
}

pub fn parse(content: &str, streaming: bool) -> Vec<Segment> {
    let opener = fence_opener_regex();
    let closer = fence_closer_regex();

    let mut segments: Vec<Segment> = Vec::new();
    let mut cursor = 0usize;

    while cursor < content.len() {
        let remaining = &content[cursor..];
        let Some(open_m) = opener.find(remaining) else {
            // No more fences — rest is plain Text.
            push_text_if_nonblank(&mut segments, remaining);
            break;
        };

        // Text up to opener
        let pre_text = &remaining[..open_m.start()];
        push_text_if_nonblank(&mut segments, pre_text);

        let body_start_local = open_m.end();
        let body_start = cursor + body_start_local;
        let after_open = &content[body_start..];

        if let Some(close_m) = closer.find(after_open) {
            // Closed fence — emit complete Widget
            let widget_code = &after_open[..close_m.start()];
            let segment = build_widget_segment(widget_code, true);
            segments.push(segment);
            cursor = body_start + close_m.end();
            // Mirror JVM behaviour: trailing newline after ``` is consumed greedily.
            // Skip a single optional `\r?\n` after the closer if present so the
            // next Text segment doesn't start with a leading blank line.
            if cursor < content.len() && content.as_bytes()[cursor] == b'\r' {
                cursor += 1;
            }
            if cursor < content.len() && content.as_bytes()[cursor] == b'\n' {
                cursor += 1;
            }
        } else {
            // Unclosed fence
            let tail = after_open;
            if streaming {
                // Streaming: emit Loading segment as terminator
                segments.push(Segment::Loading);
            } else {
                // Non-streaming: still treat as incomplete Widget (renderer
                // shows partial) so authors can preview without the closer
                let segment = build_widget_segment(tail, false);
                segments.push(segment);
            }
            cursor = content.len();
        }
    }

    segments
}

fn push_text_if_nonblank(segments: &mut Vec<Segment>, text: &str) {
    if !text.is_empty() && !text.trim().is_empty() {
        segments.push(Segment::Text(text.to_string()));
    }
}

fn build_widget_segment(widget_code: &str, complete: bool) -> Segment {
    // First chunk between opener and first blank line is heuristically the
    // JSON spec / metadata. Try to parse it as JSON; if successful, extract
    // title / renderer / actions. The visible widget_code below is whatever
    // remains.
    let trimmed = widget_code.trim_start_matches('\n').trim_start_matches('\r');
    let (spec_block, body) = split_spec_and_body(trimmed);

    let mut title: Option<String> = None;
    let mut renderer = "html".to_string();
    let mut actions: Vec<WidgetAction> = Vec::new();
    let mut spec_json: Option<String> = None;

    if let Some(spec_raw) = spec_block {
        if let Ok(val) = serde_json::from_str::<Value>(spec_raw) {
            if let Some(obj) = val.as_object() {
                if let Some(t) = obj.get("title").and_then(|v| v.as_str()) {
                    if !t.is_empty() {
                        title = Some(t.to_string());
                    }
                }
                if let Some(r) = obj.get("renderer").and_then(|v| v.as_str()) {
                    if !r.is_empty() {
                        renderer = r.to_string();
                    }
                }
                if let Some(arr) = obj.get("actions").and_then(|v| v.as_array()) {
                    for entry in arr {
                        if let Some(o) = entry.as_object() {
                            let id = o.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                            let label = o
                                .get("label")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .to_string();
                            let instruction = o
                                .get("instruction")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .to_string();
                            if !id.is_empty() || !label.is_empty() || !instruction.is_empty() {
                                actions.push(WidgetAction {
                                    id,
                                    label,
                                    instruction,
                                });
                            }
                        }
                    }
                }
            }
            spec_json = Some(spec_raw.to_string());
        }
    }

    Segment::Widget {
        title,
        widget_code: body.to_string(),
        complete,
        renderer,
        spec_json,
        actions,
    }
}

/// Heuristic: if the widget body starts with a `{` that opens a JSON object,
/// return `(Some(spec_json_str), remaining_body)`. Otherwise the entire body
/// is widget_code and there's no spec.
///
/// Mirrors JVM logic where the JSON spec is optional and is followed by SVG/HTML.
fn split_spec_and_body(body: &str) -> (Option<&str>, &str) {
    let trimmed = body.trim_start();
    if !trimmed.starts_with('{') {
        return (None, body);
    }
    // Find the matching closing brace using a depth counter that respects strings.
    let mut depth = 0i32;
    let mut in_string = false;
    let mut escape = false;
    let bytes = trimmed.as_bytes();
    for (i, &b) in bytes.iter().enumerate() {
        if escape {
            escape = false;
            continue;
        }
        match b {
            b'\\' if in_string => escape = true,
            b'"' => in_string = !in_string,
            b'{' if !in_string => depth += 1,
            b'}' if !in_string => {
                depth -= 1;
                if depth == 0 {
                    let end = i + 1;
                    let spec = &trimmed[..end];
                    let remainder = &trimmed[end..];
                    return (Some(spec), remainder);
                }
            }
            _ => {}
        }
    }
    // Unbalanced JSON — give up, treat whole body as widget_code
    (None, body)
}

// ---------------- packed binary encoding ----------------

pub fn pack(segments: &[Segment]) -> Vec<u8> {
    let mut out = Vec::with_capacity(64);
    out.extend_from_slice(MAGIC);
    out.push(VERSION);
    out.push(0); // flags
    out.push(0); // reserved lo
    out.push(0); // reserved hi

    write_varint(segments.len() as u64, &mut out);

    for seg in segments {
        match seg {
            Segment::Text(content) => {
                out.push(KIND_TEXT);
                write_string(content, &mut out);
            }
            Segment::Widget {
                title,
                widget_code,
                complete,
                renderer,
                spec_json,
                actions,
            } => {
                out.push(KIND_WIDGET);
                write_opt_string(title.as_deref(), &mut out);
                write_string(widget_code, &mut out);
                out.push(if *complete { 1u8 } else { 0u8 });
                write_string(renderer, &mut out);
                write_opt_string(spec_json.as_deref(), &mut out);
                write_varint(actions.len() as u64, &mut out);
                for a in actions {
                    write_string(&a.id, &mut out);
                    write_string(&a.label, &mut out);
                    write_string(&a.instruction, &mut out);
                }
            }
            Segment::Loading => {
                out.push(KIND_LOADING);
            }
        }
    }

    out
}

fn write_string(s: &str, out: &mut Vec<u8>) {
    let bytes = s.as_bytes();
    write_varint(bytes.len() as u64, out);
    out.extend_from_slice(bytes);
}

fn write_opt_string(s: Option<&str>, out: &mut Vec<u8>) {
    match s {
        None => out.push(0),
        Some(s) => {
            out.push(1);
            write_string(s, out);
        }
    }
}

fn write_varint(mut value: u64, out: &mut Vec<u8>) {
    loop {
        let byte = (value & 0x7F) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        } else {
            out.push(byte | 0x80);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_text_no_fence() {
        let segs = parse("hello world", false);
        assert_eq!(segs.len(), 1);
        assert!(matches!(&segs[0], Segment::Text(t) if t == "hello world"));
    }

    #[test]
    fn one_closed_fence() {
        let content = "before\n```show-widget\nbody\n```\nafter";
        let segs = parse(content, false);
        // expect: Text("before\n"), Widget(body), Text("after")
        assert_eq!(segs.len(), 3);
        assert!(matches!(&segs[0], Segment::Text(t) if t.contains("before")));
        match &segs[1] {
            Segment::Widget { widget_code, complete, .. } => {
                assert!(widget_code.contains("body"));
                assert!(*complete);
            }
            _ => panic!("expected Widget"),
        }
        assert!(matches!(&segs[2], Segment::Text(t) if t.contains("after")));
    }

    #[test]
    fn streaming_unclosed_emits_loading() {
        let content = "intro\n```show-widget\nincomplete";
        let segs = parse(content, true);
        // expect: Text("intro\n"), Loading
        assert_eq!(segs.len(), 2);
        assert!(matches!(&segs[0], Segment::Text(_)));
        assert!(matches!(&segs[1], Segment::Loading));
    }

    #[test]
    fn nonstreaming_unclosed_emits_incomplete_widget() {
        let content = "intro\n```show-widget\nincomplete";
        let segs = parse(content, false);
        assert_eq!(segs.len(), 2);
        assert!(matches!(&segs[0], Segment::Text(_)));
        match &segs[1] {
            Segment::Widget { complete, .. } => assert!(!complete),
            _ => panic!("expected incomplete Widget"),
        }
    }

    #[test]
    fn widget_spec_json_parsed() {
        let content = "```widget\n{\"title\":\"Demo\",\"renderer\":\"html\",\"actions\":[{\"id\":\"a\",\"label\":\"L\",\"instruction\":\"go\"}]}\n<svg></svg>\n```";
        let segs = parse(content, false);
        assert_eq!(segs.len(), 1);
        match &segs[0] {
            Segment::Widget { title, renderer, actions, spec_json, .. } => {
                assert_eq!(title.as_deref(), Some("Demo"));
                assert_eq!(renderer, "html");
                assert_eq!(actions.len(), 1);
                assert_eq!(actions[0].id, "a");
                assert!(spec_json.is_some());
            }
            _ => panic!("expected Widget"),
        }
    }

    #[test]
    fn pack_header_layout() {
        let bytes = pack(&[]);
        assert_eq!(&bytes[..4], MAGIC);
        assert_eq!(bytes[4], VERSION);
        // 0 segments → varint 0 right after the 8-byte header
        assert_eq!(bytes[8], 0u8);
    }
}
