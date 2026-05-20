//! Maps pulldown-cmark Tag → packed `NodeTypeCode`. The codes are stable
//! wire-format constants (see SPIKE_PLAN §4.3) and the Kotlin
//! `PackedAstNode.type` decodes them back to JetBrains
//! `MarkdownElementTypes` for renderer compatibility.

/// Stable u8 codes for packed AST node types. Renamed/extended only with a
/// schema version bump in the packed header.
#[repr(u8)]
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub enum NodeTypeCode {
    Root = 0,

    // Block-level
    Paragraph = 1,
    Heading = 2,
    Blockquote = 3,
    CodeBlock = 4,
    ListOrdered = 5,
    ListUnordered = 6,
    ListItem = 7,
    Table = 8,
    TableHead = 9,
    TableRow = 10,
    TableCell = 11,
    HorizontalRule = 12,
    HtmlBlock = 13,
    ThematicBreak = 14,
    FootnoteDef = 40,

    // Inline-level
    Emphasis = 30,
    Strong = 31,
    Strikethrough = 32,
    Link = 33,
    Image = 34,
    InlineCode = 35,
    InlineHtml = 36,
    MathInline = 37,
    MathBlock = 38,
    FootnoteRef = 39,
    TaskListMarker = 41,

    // Leaf
    Text = 100,
    SoftBreak = 101,
    HardBreak = 102,
}

impl NodeTypeCode {
    pub fn as_byte(self) -> u8 {
        self as u8
    }
}
