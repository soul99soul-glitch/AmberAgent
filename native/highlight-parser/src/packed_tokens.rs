//! Packed binary token output for highlight events.
//!
//! Wire layout (matches SPIKE_PLAN §5.2 — simplified for first-cut spike):
//!
//! ```text
//! header (8 bytes):
//!   magic   : 4 bytes 'PHLT'
//!   version : u8  (= 1)
//!   flags   : u8  (reserved)
//!   reserved: u16
//!
//! type_pool_count : varint
//! type_pool       : [ length (varint) | utf8 bytes ] × N   (scope names)
//!
//! token_count : varint
//! tokens      : [
//!   kind     : u8       (0 = Plain, 1 = Token)
//!   start    : varint   (byte offset into source)
//!   length   : varint
//!   type_ref : varint   (only present when kind == 1; index into type_pool)
//! ] × token_count
//! ```
//!
//! For the spike we only emit Plain + Token (no Nested) — that's enough to
//! drive the existing Compose renderer because tree-sitter-highlight already
//! flattens nested scopes by reporting the most-specific one active at each
//! span.

use tree_sitter_highlight::HighlightEvent;

use crate::grammars::highlight_names;

const MAGIC: &[u8; 4] = b"PHLT";
const VERSION: u8 = 1;
const KIND_PLAIN: u8 = 0;
const KIND_TOKEN: u8 = 1;

/// Pack a "no-highlight" stream: a single Plain token covering all of `code`.
pub fn pack_plain_only(code: &str) -> Vec<u8> {
    let mut out = write_header();
    // type pool: empty
    write_varint(0, &mut out);
    // tokens: just one Plain
    if code.is_empty() {
        write_varint(0, &mut out);
    } else {
        write_varint(1, &mut out);
        out.push(KIND_PLAIN);
        write_varint(0, &mut out);
        write_varint(code.len() as u64, &mut out);
    }
    out
}

pub fn pack(code: &str, events: Vec<HighlightEvent>) -> Vec<u8> {
    let mut out = write_header();

    // Emit type pool (full HIGHLIGHT_NAMES list — small, ~26 entries).
    // Kotlin side caches this once and indexes by `type_ref`.
    let names = highlight_names();
    write_varint(names.len() as u64, &mut out);
    for name in names {
        write_varint(name.len() as u64, &mut out);
        out.extend_from_slice(name.as_bytes());
    }

    // Walk events. tree-sitter-highlight emits a nested HighlightStart/End
    // stream — we flatten to a sequential token list by tracking the most
    // recent active highlight.
    let mut tokens: Vec<Token> = Vec::with_capacity(events.len());
    let mut highlight_stack: Vec<usize> = Vec::with_capacity(8);
    let mut cursor: usize = 0;

    for event in events {
        match event {
            HighlightEvent::HighlightStart(h) => {
                highlight_stack.push(h.0);
            }
            HighlightEvent::HighlightEnd => {
                highlight_stack.pop();
            }
            HighlightEvent::Source { start, end } => {
                if start > cursor {
                    // Gap between events — treat the gap as Plain.
                    tokens.push(Token::Plain {
                        start: cursor,
                        end: start,
                    });
                }
                let token = if let Some(&idx) = highlight_stack.last() {
                    Token::Token {
                        start,
                        end,
                        type_ref: idx,
                    }
                } else {
                    Token::Plain { start, end }
                };
                tokens.push(token);
                cursor = end;
            }
        }
    }

    if cursor < code.len() {
        tokens.push(Token::Plain {
            start: cursor,
            end: code.len(),
        });
    }

    write_varint(tokens.len() as u64, &mut out);
    for token in &tokens {
        match token {
            Token::Plain { start, end } => {
                out.push(KIND_PLAIN);
                write_varint(*start as u64, &mut out);
                write_varint((*end - *start) as u64, &mut out);
            }
            Token::Token {
                start,
                end,
                type_ref,
            } => {
                out.push(KIND_TOKEN);
                write_varint(*start as u64, &mut out);
                write_varint((*end - *start) as u64, &mut out);
                write_varint(*type_ref as u64, &mut out);
            }
        }
    }

    out
}

#[derive(Debug, Clone)]
enum Token {
    Plain { start: usize, end: usize },
    Token { start: usize, end: usize, type_ref: usize },
}

fn write_header() -> Vec<u8> {
    let mut out = Vec::with_capacity(32);
    out.extend_from_slice(MAGIC);
    out.push(VERSION);
    out.push(0); // flags
    out.push(0); // reserved
    out.push(0);
    out
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
    fn plain_only_layout() {
        let out = pack_plain_only("hello");
        assert_eq!(&out[..4], MAGIC);
        assert_eq!(out[4], VERSION);
        // type_pool count = 0
        // tokens = 1
        // After 8-byte header: byte 8 = 0 (type pool count varint), byte 9 = 1 (token count)
        assert_eq!(out[8], 0);
        assert_eq!(out[9], 1);
        assert_eq!(out[10], KIND_PLAIN);
    }

    #[test]
    fn plain_only_empty_string() {
        let out = pack_plain_only("");
        assert_eq!(&out[..4], MAGIC);
        assert_eq!(out[8], 0); // type pool count
        assert_eq!(out[9], 0); // token count
    }
}
