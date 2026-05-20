//! DOCX → markdown-flavoured plain text converter. Output **must** match
//! `document/DocxParser.kt` byte-for-byte to satisfy the spike's equivalence
//! requirement (SPIKE_PLAN §3.3).
//!
//! Reference JVM implementation: `document/src/main/java/me/rerere/document/DocxParser.kt`.
//!
//! Key rules mirrored from JVM:
//!   - Headings detected via `<w:pStyle val="HeadingN">` (or lowercase variant)
//!   - Lists detected via `<w:numPr>` containing `<w:ilvl>` / `<w:numId>`
//!   - Bold via `<w:b>`, italic via `<w:i>` (inside `<w:rPr>`)
//!   - Tables → markdown pipe table with `| --- |` separator after first row
//!   - Element matching is on **local name** (we strip the `w:` namespace prefix)
//!     to mirror the JVM `isNamespaceAware = true` parser behavior

use std::fs::File;
use std::io::{BufReader, Read};

use quick_xml::events::{BytesStart, Event};
use quick_xml::reader::Reader;
use zip::read::ZipArchive;

use crate::error::{OfficeParseError, Result};

/// Public entry: open the DOCX file at `path` and produce a markdown-flavoured
/// string. Errors are turned into sentinel strings by the caller in `lib.rs`.
pub fn parse_to_markdown(path: &str) -> String {
    match try_parse(path) {
        Ok(s) => s,
        Err(e) => e.to_docx_message(),
    }
}

fn try_parse(path: &str) -> Result<String> {
    let file = File::open(path)?;
    let buf = BufReader::new(file);
    let mut archive = ZipArchive::new(buf)?;

    // Locate word/document.xml inside the zip. JVM walks the entry list
    // sequentially and parses the first match; we do the same.
    let mut document_xml = match archive.by_name("word/document.xml") {
        Ok(f) => f,
        Err(_) => return Err(OfficeParseError::DocxBodyMissing),
    };

    let mut xml_bytes = Vec::with_capacity(document_xml.size() as usize);
    document_xml.read_to_end(&mut xml_bytes)?;
    drop(document_xml);

    parse_document_xml(&xml_bytes)
}

/// Take the full `document.xml` bytes and produce the same markdown-style
/// rendering the JVM impl emits. Output is `.trim()`-ed at the end, matching
/// `parseDocumentXml` (line 66 in DocxParser.kt).
fn parse_document_xml(xml: &[u8]) -> Result<String> {
    let mut reader = Reader::from_reader(xml);
    let config = reader.config_mut();
    config.trim_text(false);

    let mut buf = Vec::new();
    let mut out = String::with_capacity(xml.len() / 8);
    let mut in_body = false;
    let mut depth: i32 = 0;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                let name = local_name(e.name().into_inner());
                match name {
                    b"body" => in_body = true,
                    b"p" if in_body => {
                        process_paragraph(&mut reader, depth, &mut out)?;
                    }
                    b"tbl" if in_body => {
                        process_table(&mut reader, depth, &mut out)?;
                    }
                    _ => {}
                }
            }
            Event::Empty(ref e) => {
                // Self-closing elements don't change depth; check them too.
                // (Empty body / empty p are degenerate cases; nothing to do.)
                let _ = e;
            }
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"body" {
                    in_body = false;
                }
                depth -= 1;
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    Ok(out.trim().to_string())
}

/// Process a single `<w:p>` paragraph. `paragraph_start_depth` is the depth
/// at which the START_TAG was observed; we read events until we see the
/// matching END_TAG at the same depth, mirroring the JVM `parser.depth`
/// reentrant pattern.
fn process_paragraph<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    paragraph_start_depth: i32,
    result: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = paragraph_start_depth;
    let mut paragraph_content = String::new();
    let mut list_info: Option<ListInfo> = None;
    let mut heading_level: i32 = 0;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                let name = local_name(e.name().into_inner());
                match name {
                    b"r" => extract_run_text(reader, depth, &mut paragraph_content)?,
                    b"pPr" => {
                        let props = extract_paragraph_properties(reader, depth)?;
                        list_info = props.list_info;
                        heading_level = props.heading_level;
                    }
                    _ => {}
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                let name = local_name(e.name().into_inner());
                if name == b"p" && depth == paragraph_start_depth {
                    depth -= 1;
                    break;
                }
                depth -= 1;
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    let trimmed = paragraph_content.trim();
    if !trimmed.is_empty() {
        if let Some(info) = list_info {
            let indent: String = "  ".repeat(info.level.max(0) as usize);
            let marker = if info.is_numbered {
                format!("{}. ", info.number)
            } else {
                "- ".to_string()
            };
            result.push_str(&indent);
            result.push_str(&marker);
            result.push_str(trimmed);
            result.push('\n');
        } else if heading_level > 0 {
            let prefix: String = "#".repeat(heading_level.min(6) as usize);
            result.push_str(&prefix);
            result.push(' ');
            result.push_str(trimmed);
            result.push_str("\n\n");
        } else {
            result.push_str(trimmed);
            result.push_str("\n\n");
        }
    }

    Ok(())
}

/// Read one `<w:r>` run, extracting its text and applying `**bold**` /
/// `*italic*` / `***both***` wrappers based on the contained `<w:rPr>`.
fn extract_run_text<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    run_start_depth: i32,
    out: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = run_start_depth;
    let mut is_bold = false;
    let mut is_italic = false;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                let name = local_name(e.name().into_inner());
                match name {
                    b"rPr" => {
                        let (b, i) = extract_formatting(reader, depth)?;
                        is_bold = b;
                        is_italic = i;
                    }
                    b"t" => {
                        // The next event should be Text; collect ALL text events
                        // inside <w:t>...</w:t> in case the XML reader splits the
                        // run across multiple chunks.
                        let mut text = String::new();
                        let mut inner_depth = depth;
                        loop {
                            match reader.read_event_into(&mut buf)? {
                                Event::Text(t) => {
                                    let unescaped = t.unescape().map_err(quick_xml::Error::from)?;
                                    text.push_str(&unescaped);
                                }
                                Event::Start(_) => inner_depth += 1,
                                Event::End(ref end) => {
                                    inner_depth -= 1;
                                    if local_name(end.name().into_inner()) == b"t" && inner_depth + 1 == depth {
                                        // depth was incremented for <w:t>; matched by its </w:t>
                                        buf.clear();
                                        break;
                                    }
                                }
                                Event::Eof => {
                                    buf.clear();
                                    break;
                                }
                                _ => {}
                            }
                            // Clear after each iteration so buf doesn't grow O(N)
                            // across all events inside <w:t> (review P2 fix).
                            buf.clear();
                        }
                        let formatted = match (is_bold, is_italic) {
                            (true, true) => format!("***{}***", text),
                            (true, false) => format!("**{}**", text),
                            (false, true) => format!("*{}*", text),
                            (false, false) => text,
                        };
                        out.push_str(&formatted);
                        depth -= 1; // <w:t> closed
                    }
                    _ => {}
                }
            }
            Event::Empty(ref e) => {
                // Self-closing inline tags like <w:tab/> / <w:br/> — ignored by
                // JVM impl. Mirror that.
                let _ = e;
            }
            Event::End(ref e) => {
                let name = local_name(e.name().into_inner());
                if name == b"r" && depth == run_start_depth {
                    depth -= 1;
                    break;
                }
                depth -= 1;
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    Ok(())
}

/// Read `<w:rPr>` and return (isBold, isItalic). Mirror of JVM
/// `extractFormatting` (DocxParser.kt:158).
fn extract_formatting<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    rpr_start_depth: i32,
) -> Result<(bool, bool)> {
    let mut buf = Vec::new();
    let mut depth = rpr_start_depth;
    let mut is_bold = false;
    let mut is_italic = false;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                match local_name(e.name().into_inner()) {
                    b"b" => is_bold = true,
                    b"i" => is_italic = true,
                    _ => {}
                }
            }
            Event::Empty(ref e) => {
                match local_name(e.name().into_inner()) {
                    b"b" => is_bold = true,
                    b"i" => is_italic = true,
                    _ => {}
                }
            }
            Event::End(ref e) => {
                let name = local_name(e.name().into_inner());
                if name == b"rPr" && depth == rpr_start_depth {
                    return Ok((is_bold, is_italic));
                }
                depth -= 1;
            }
            Event::Eof => return Ok((is_bold, is_italic)),
            _ => {}
        }
        buf.clear();
    }
}

#[derive(Clone, Debug)]
struct ListInfo {
    level: i32,
    is_numbered: bool,
    number: i32,
}

#[derive(Clone, Debug, Default)]
struct ParagraphProperties {
    list_info: Option<ListInfo>,
    heading_level: i32,
}

fn extract_paragraph_properties<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    ppr_start_depth: i32,
) -> Result<ParagraphProperties> {
    let mut buf = Vec::new();
    let mut depth = ppr_start_depth;
    let mut list_level = 0i32;
    let mut is_numbered = false;
    let mut heading_level = 0i32;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                let name = local_name(e.name().into_inner());
                match name {
                    b"pStyle" => {
                        if let Some(style) = attr_val(e, b"val")? {
                            if style.starts_with("Heading") || style.starts_with("heading") {
                                // last char is the level digit, matches JVM
                                if let Some(c) = style.chars().last() {
                                    heading_level = c.to_digit(10).map(|d| d as i32).unwrap_or(1);
                                }
                            }
                        }
                    }
                    b"numPr" => {
                        // Enter numPr block — depth-track until its END.
                        let numpr_start_depth = depth;
                        loop {
                            match reader.read_event_into(&mut buf)? {
                                Event::Start(ref inner) => {
                                    depth += 1;
                                    match local_name(inner.name().into_inner()) {
                                        b"ilvl" => {
                                            if let Some(v) = attr_val(inner, b"val")? {
                                                list_level = v.parse::<i32>().unwrap_or(0);
                                            }
                                        }
                                        b"numId" => {
                                            // JVM treats presence of `val` attr as "is numbered".
                                            if attr_val(inner, b"val")?.is_some() {
                                                is_numbered = true;
                                            }
                                        }
                                        _ => {}
                                    }
                                }
                                Event::Empty(ref inner) => {
                                    match local_name(inner.name().into_inner()) {
                                        b"ilvl" => {
                                            if let Some(v) = attr_val(inner, b"val")? {
                                                list_level = v.parse::<i32>().unwrap_or(0);
                                            }
                                        }
                                        b"numId" => {
                                            if attr_val(inner, b"val")?.is_some() {
                                                is_numbered = true;
                                            }
                                        }
                                        _ => {}
                                    }
                                }
                                Event::End(ref inner) => {
                                    if local_name(inner.name().into_inner()) == b"numPr"
                                        && depth == numpr_start_depth
                                    {
                                        depth -= 1;
                                        break;
                                    }
                                    depth -= 1;
                                }
                                Event::Eof => break,
                                _ => {}
                            }
                            buf.clear();
                        }
                    }
                    _ => {}
                }
            }
            Event::Empty(ref e) => {
                let name = local_name(e.name().into_inner());
                if name == b"pStyle" {
                    if let Some(style) = attr_val(e, b"val")? {
                        if style.starts_with("Heading") || style.starts_with("heading") {
                            if let Some(c) = style.chars().last() {
                                heading_level = c.to_digit(10).map(|d| d as i32).unwrap_or(1);
                            }
                        }
                    }
                }
            }
            Event::End(ref e) => {
                let name = local_name(e.name().into_inner());
                if name == b"pPr" && depth == ppr_start_depth {
                    let list_info = if list_level > 0 || is_numbered {
                        Some(ListInfo {
                            level: list_level,
                            is_numbered,
                            number: 1,
                        })
                    } else {
                        None
                    };
                    return Ok(ParagraphProperties {
                        list_info,
                        heading_level,
                    });
                }
                depth -= 1;
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    let list_info = if list_level > 0 || is_numbered {
        Some(ListInfo {
            level: list_level,
            is_numbered,
            number: 1,
        })
    } else {
        None
    };
    Ok(ParagraphProperties {
        list_info,
        heading_level,
    })
}

/// `<w:tbl>` → markdown table emitter. Mirrors `processTable` (DocxParser.kt:182).
fn process_table<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    table_start_depth: i32,
    out: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = table_start_depth;
    let mut rows: Vec<Vec<String>> = Vec::new();

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"tr" {
                    let cells = extract_table_row(reader, depth)?;
                    if !cells.is_empty() {
                        rows.push(cells);
                    }
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"tbl" && depth == table_start_depth {
                    depth -= 1;
                    break;
                }
                depth -= 1;
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    if !rows.is_empty() {
        let max_cols = rows.iter().map(|r| r.len()).max().unwrap_or(0);
        for (idx, row) in rows.iter().enumerate() {
            out.push_str("| ");
            for col in 0..max_cols {
                let cell = row.get(col).map(|s| s.as_str()).unwrap_or("");
                out.push_str(cell);
                out.push_str(" | ");
            }
            out.push('\n');

            if idx == 0 {
                out.push_str("| ");
                for _ in 0..max_cols {
                    out.push_str("--- | ");
                }
                out.push('\n');
            }
        }
    }
    out.push('\n');

    Ok(())
}

fn extract_table_row<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    row_start_depth: i32,
) -> Result<Vec<String>> {
    let mut buf = Vec::new();
    let mut depth = row_start_depth;
    let mut cells: Vec<String> = Vec::new();

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"tc" {
                    let text = extract_cell_text(reader, depth)?;
                    cells.push(text);
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"tr" && depth == row_start_depth {
                    depth -= 1;
                    return Ok(cells);
                }
                depth -= 1;
            }
            Event::Eof => return Ok(cells),
            _ => {}
        }
        buf.clear();
    }
}

fn extract_cell_text<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    cell_start_depth: i32,
) -> Result<String> {
    let mut buf = Vec::new();
    let mut depth = cell_start_depth;
    let mut result = String::new();

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"p" {
                    let text = extract_cell_paragraph_text(reader, depth)?;
                    let trimmed = text.trim();
                    if !trimmed.is_empty() {
                        if !result.is_empty() {
                            result.push(' ');
                        }
                        result.push_str(trimmed);
                    }
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"tc" && depth == cell_start_depth {
                    return Ok(result.trim().to_string());
                }
                depth -= 1;
            }
            Event::Eof => return Ok(result.trim().to_string()),
            _ => {}
        }
        buf.clear();
    }
}

fn extract_cell_paragraph_text<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    p_start_depth: i32,
) -> Result<String> {
    let mut buf = Vec::new();
    let mut depth = p_start_depth;
    let mut result = String::new();

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"r" {
                    extract_run_text(reader, depth, &mut result)?;
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"p" && depth == p_start_depth {
                    return Ok(result.trim().to_string());
                }
                depth -= 1;
            }
            Event::Eof => return Ok(result.trim().to_string()),
            _ => {}
        }
        buf.clear();
    }
}

// ---------------- helpers ----------------

/// Strip the namespace prefix from an XML element name. The JVM impl runs
/// with `isNamespaceAware = true` which gives it local names automatically;
/// quick-xml gives us the qualified name so we strip manually.
fn local_name(qualified: &[u8]) -> &[u8] {
    if let Some(pos) = qualified.iter().position(|&b| b == b':') {
        &qualified[pos + 1..]
    } else {
        qualified
    }
}

/// Read attribute by local name. Mirrors `parser.getAttributeValue(null, "val")`.
fn attr_val(e: &BytesStart, key: &[u8]) -> Result<Option<String>> {
    for attr in e.attributes() {
        let attr = attr?;
        if local_name(attr.key.as_ref()) == key {
            return Ok(Some(attr.unescape_value()?.into_owned()));
        }
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_name_strips_prefix() {
        assert_eq!(local_name(b"w:body"), b"body");
        assert_eq!(local_name(b"body"), b"body");
        assert_eq!(local_name(b"ns0:foo"), b"foo");
    }

    #[test]
    fn empty_doc_xml_returns_empty() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        assert_eq!(out, "");
    }

    #[test]
    fn plain_paragraph() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:r><w:t>hello world</w:t></w:r></w:p></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        // JVM emits "hello world\n\n" then trims trailing whitespace.
        assert_eq!(out, "hello world");
    }

    #[test]
    fn bold_paragraph() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r></w:p></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        assert_eq!(out, "**bold**");
    }

    #[test]
    fn italic_paragraph() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:r><w:rPr><w:i/></w:rPr><w:t>em</w:t></w:r></w:p></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        assert_eq!(out, "*em*");
    }

    #[test]
    fn bold_italic_paragraph() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:r><w:rPr><w:b/><w:i/></w:rPr><w:t>x</w:t></w:r></w:p></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        assert_eq!(out, "***x***");
    }

    #[test]
    fn heading() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>title</w:t></w:r></w:p></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        assert_eq!(out, "## title");
    }

    #[test]
    fn bullet_list() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>one</w:t></w:r></w:p></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        // ilvl=0 with no numId → JVM treats as list level 0 (presence of ilvl
        // triggers list-info because numbered check stays false). list_level>0
        // is false, but the bullet branch fires because list_info is Some.
        // Wait — JVM creates list_info only when listLevel>0 || isNumbered.
        // With ilvl=0 only, neither is true, so JVM emits plain "one".
        assert_eq!(out, "one");
    }

    #[test]
    fn numbered_list() {
        let xml = br#"<?xml version="1.0"?><w:document xmlns:w="x"><w:body><w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>one</w:t></w:r></w:p></w:body></w:document>"#;
        let out = parse_document_xml(xml).unwrap();
        // JVM: "1. one\n" (trimmed at end → "1. one")
        assert_eq!(out, "1. one");
    }
}
