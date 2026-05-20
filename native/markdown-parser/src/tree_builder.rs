//! Turn pulldown-cmark event stream into a heap-allocated `Node` tree
//! suitable for downstream packing. We need an explicit tree (not just
//! event stream) because the Kotlin renderer walks
//! `astTree.children[i].children[j].startOffset / endOffset` recursively.
//!
//! Trees are built once per `parse_to_packed()` call; the Rust side is
//! stateless and does not retain trees across JNI calls.

use pulldown_cmark::{Event, HeadingLevel, Options, Parser, Tag, TagEnd};

use crate::type_mapping::NodeTypeCode;

#[derive(Debug, Clone)]
pub struct Node {
    pub type_code: NodeTypeCode,
    pub start: usize,
    pub end: usize,
    /// Optional payload (heading level, code fence language, link href, etc.)
    pub extras: Vec<u8>,
    pub children: Vec<Node>,
}

impl Node {
    fn new(type_code: NodeTypeCode, start: usize, end: usize) -> Self {
        Self {
            type_code,
            start,
            end,
            extras: Vec::new(),
            children: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct TreeMeta {
    pub has_html_blocks: bool,
}

#[derive(Debug, Clone)]
pub struct Tree {
    pub root: Node,
    pub meta: TreeMeta,
}

pub fn build_tree(text: &str) -> Tree {
    let options = make_options();
    let parser = Parser::new_ext(text, options);
    let parser = parser.into_offset_iter();

    let mut stack: Vec<Node> = vec![Node::new(NodeTypeCode::Root, 0, text.len())];
    let mut has_html_blocks = false;

    for (event, range) in parser {
        match event {
            Event::Start(tag) => {
                let mut node = make_block_or_inline_node(&tag, range.start, range.end);
                attach_tag_extras(&tag, &mut node);
                stack.push(node);
            }
            Event::End(tag_end) => {
                let finished = stack.pop().expect("unbalanced markdown stack");
                // Update end offset to the actual span end the event reports.
                let mut adjusted = finished;
                adjusted.end = range.end;
                // Sanity: type code must agree
                let expected = make_block_or_inline_type(&start_tag_for_end(&tag_end));
                if adjusted.type_code != expected {
                    // Best-effort; we trust pulldown-cmark to balance properly
                    log::warn!(
                        "markdown-parser: tag mismatch at offset {}: open={:?} close={:?}",
                        range.start,
                        adjusted.type_code,
                        expected,
                    );
                }
                stack.last_mut().expect("no parent").children.push(adjusted);
            }
            Event::Text(_cow) => {
                let leaf = Node::new(NodeTypeCode::Text, range.start, range.end);
                stack.last_mut().expect("no parent").children.push(leaf);
            }
            Event::Code(_cow) => {
                let leaf = Node::new(NodeTypeCode::InlineCode, range.start, range.end);
                stack.last_mut().expect("no parent").children.push(leaf);
            }
            Event::Html(_cow) | Event::InlineHtml(_cow) => {
                let leaf = Node::new(NodeTypeCode::InlineHtml, range.start, range.end);
                stack.last_mut().expect("no parent").children.push(leaf);
                // Track block-level HTML to set has_html_blocks meta flag —
                // matches the JVM `astTree.containsHtmlBlocks()` extension.
                has_html_blocks = true;
            }
            Event::FootnoteReference(_cow) => {
                let leaf = Node::new(NodeTypeCode::FootnoteRef, range.start, range.end);
                stack.last_mut().expect("no parent").children.push(leaf);
            }
            Event::SoftBreak => {
                let leaf = Node::new(NodeTypeCode::SoftBreak, range.start, range.end);
                stack.last_mut().expect("no parent").children.push(leaf);
            }
            Event::HardBreak => {
                let leaf = Node::new(NodeTypeCode::HardBreak, range.start, range.end);
                stack.last_mut().expect("no parent").children.push(leaf);
            }
            Event::Rule => {
                let leaf = Node::new(NodeTypeCode::HorizontalRule, range.start, range.end);
                stack.last_mut().expect("no parent").children.push(leaf);
            }
            Event::TaskListMarker(checked) => {
                let mut leaf = Node::new(NodeTypeCode::TaskListMarker, range.start, range.end);
                leaf.extras.push(if checked { 1u8 } else { 0u8 });
                stack.last_mut().expect("no parent").children.push(leaf);
            }
            // pulldown-cmark may emit other events in extension builds; ignore
            // for now (spec-conformant set covers our app's needs).
            _ => {}
        }
    }

    let root = stack.pop().expect("root missing");
    debug_assert!(stack.is_empty(), "unbalanced parser stack — bug in pulldown-cmark wrapper");

    Tree {
        root,
        meta: TreeMeta { has_html_blocks },
    }
}

fn make_options() -> Options {
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_TABLES);
    opts.insert(Options::ENABLE_FOOTNOTES);
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TASKLISTS);
    opts.insert(Options::ENABLE_SMART_PUNCTUATION);
    opts.insert(Options::ENABLE_HEADING_ATTRIBUTES);
    opts.insert(Options::ENABLE_MATH);
    // ENABLE_GFM is intentionally NOT enabled — we want CommonMark + GFM
    // additions, but not all GFM relaxed rules to match JetBrains' GFMFlavour
    // closely.
    opts
}

fn make_block_or_inline_node(tag: &Tag<'_>, start: usize, end: usize) -> Node {
    Node::new(make_block_or_inline_type(tag), start, end)
}

fn make_block_or_inline_type(tag: &Tag<'_>) -> NodeTypeCode {
    match tag {
        Tag::Paragraph => NodeTypeCode::Paragraph,
        Tag::Heading { .. } => NodeTypeCode::Heading,
        Tag::BlockQuote(_) => NodeTypeCode::Blockquote,
        Tag::CodeBlock(_) => NodeTypeCode::CodeBlock,
        Tag::List(Some(_)) => NodeTypeCode::ListOrdered,
        Tag::List(None) => NodeTypeCode::ListUnordered,
        Tag::Item => NodeTypeCode::ListItem,
        Tag::Table(_) => NodeTypeCode::Table,
        Tag::TableHead => NodeTypeCode::TableHead,
        Tag::TableRow => NodeTypeCode::TableRow,
        Tag::TableCell => NodeTypeCode::TableCell,
        Tag::HtmlBlock => NodeTypeCode::HtmlBlock,
        Tag::FootnoteDefinition(_) => NodeTypeCode::FootnoteDef,
        Tag::Emphasis => NodeTypeCode::Emphasis,
        Tag::Strong => NodeTypeCode::Strong,
        Tag::Strikethrough => NodeTypeCode::Strikethrough,
        Tag::Link { .. } => NodeTypeCode::Link,
        Tag::Image { .. } => NodeTypeCode::Image,
        Tag::MetadataBlock(_) => NodeTypeCode::HtmlBlock, // best-effort
        // pulldown-cmark adds tags over time; map unknown to Paragraph as a
        // safe block container — the renderer treats unknown types as plain.
        _ => NodeTypeCode::Paragraph,
    }
}

fn attach_tag_extras(tag: &Tag<'_>, node: &mut Node) {
    match tag {
        Tag::Heading { level, .. } => {
            node.extras.push(heading_level_byte(*level));
        }
        Tag::CodeBlock(kind) => {
            match kind {
                pulldown_cmark::CodeBlockKind::Fenced(lang) => {
                    encode_string(lang.as_ref(), &mut node.extras);
                }
                pulldown_cmark::CodeBlockKind::Indented => {
                    encode_string("", &mut node.extras);
                }
            }
        }
        Tag::List(Some(start)) => {
            // Start number for ordered lists
            for byte in (*start as u64).to_le_bytes() {
                node.extras.push(byte);
            }
        }
        Tag::Link { dest_url, title, .. } => {
            encode_string(dest_url.as_ref(), &mut node.extras);
            encode_string(title.as_ref(), &mut node.extras);
        }
        Tag::Image { dest_url, title, .. } => {
            encode_string(dest_url.as_ref(), &mut node.extras);
            encode_string(title.as_ref(), &mut node.extras);
        }
        _ => {}
    }
}

fn heading_level_byte(level: HeadingLevel) -> u8 {
    match level {
        HeadingLevel::H1 => 1,
        HeadingLevel::H2 => 2,
        HeadingLevel::H3 => 3,
        HeadingLevel::H4 => 4,
        HeadingLevel::H5 => 5,
        HeadingLevel::H6 => 6,
    }
}

fn encode_string(s: &str, out: &mut Vec<u8>) {
    let bytes = s.as_bytes();
    // 2-byte little-endian length prefix + utf-8 bytes
    out.extend_from_slice(&(bytes.len() as u16).to_le_bytes());
    out.extend_from_slice(bytes);
}

/// Given a TagEnd, return the corresponding Start Tag in a no-payload form
/// so we can sanity-check stack pop type-matching.
fn start_tag_for_end(end: &TagEnd) -> Tag<'static> {
    match end {
        TagEnd::Paragraph => Tag::Paragraph,
        TagEnd::Heading(level) => Tag::Heading {
            level: *level,
            id: None,
            classes: vec![],
            attrs: vec![],
        },
        TagEnd::BlockQuote(_) => Tag::BlockQuote(None),
        TagEnd::CodeBlock => Tag::CodeBlock(pulldown_cmark::CodeBlockKind::Indented),
        TagEnd::HtmlBlock => Tag::HtmlBlock,
        TagEnd::List(true) => Tag::List(Some(1)),
        TagEnd::List(false) => Tag::List(None),
        TagEnd::Item => Tag::Item,
        TagEnd::FootnoteDefinition => Tag::FootnoteDefinition("".into()),
        TagEnd::Table => Tag::Table(vec![]),
        TagEnd::TableHead => Tag::TableHead,
        TagEnd::TableRow => Tag::TableRow,
        TagEnd::TableCell => Tag::TableCell,
        TagEnd::Emphasis => Tag::Emphasis,
        TagEnd::Strong => Tag::Strong,
        TagEnd::Strikethrough => Tag::Strikethrough,
        TagEnd::Link => Tag::Link {
            link_type: pulldown_cmark::LinkType::Inline,
            dest_url: "".into(),
            title: "".into(),
            id: "".into(),
        },
        TagEnd::Image => Tag::Image {
            link_type: pulldown_cmark::LinkType::Inline,
            dest_url: "".into(),
            title: "".into(),
            id: "".into(),
        },
        TagEnd::MetadataBlock(kind) => Tag::MetadataBlock(*kind),
        _ => Tag::Paragraph,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_builds_empty_root() {
        let tree = build_tree("");
        assert!(matches!(tree.root.type_code, NodeTypeCode::Root));
        assert_eq!(tree.root.children.len(), 0);
    }

    #[test]
    fn single_paragraph_one_child() {
        let tree = build_tree("hello world");
        assert_eq!(tree.root.children.len(), 1);
        assert!(matches!(
            tree.root.children[0].type_code,
            NodeTypeCode::Paragraph
        ));
    }

    #[test]
    fn heading_extras_carry_level() {
        let tree = build_tree("## hi");
        let h = &tree.root.children[0];
        assert!(matches!(h.type_code, NodeTypeCode::Heading));
        assert_eq!(h.extras.first().copied(), Some(2u8));
    }

    #[test]
    fn html_block_sets_meta_flag() {
        let tree = build_tree("<div>raw</div>\n\nplain\n");
        assert!(tree.meta.has_html_blocks);
    }
}
