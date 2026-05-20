//! PPTX → markdown-flavoured plain text converter. Output **must** match
//! `document/PptxParser.kt` byte-for-byte to satisfy the spike's equivalence
//! requirement (SPIKE_PLAN §3.3).
//!
//! Reference JVM implementation: `document/src/main/java/me/rerere/document/PptxParser.kt`.
//!
//! Key rules mirrored from JVM:
//!   - Slides come from `ppt/slides/slideN.xml`, sorted by N
//!   - Each slide rendered as `## Slide N\n\n<content>` + optional speaker notes
//!   - Notes come from `ppt/notesSlides/notesSlideN.xml`, only the shape with
//!     `<p:ph type="body">` ancestor counts as "notes" (skipping the visual
//!     preview shape)
//!   - Inside a shape: `<a:p>` paragraphs; bullets via `<a:buChar>` / `<a:buAutoNum>`
//!     in `<a:pPr>`; level via `<a:lvl val="N">`; runs `<a:r>` containing `<a:t>`
//!   - Tables: `<a:tbl>` inside `<p:graphicFrame>` → markdown pipe table

use std::fs::File;
use std::io::{BufReader, Read};

use quick_xml::events::{BytesStart, Event};
use quick_xml::reader::Reader;
use zip::read::ZipArchive;

use crate::error::{OfficeParseError, Result};

#[derive(Debug, Default, Clone)]
struct SlideContent {
    slide_number: i32,
    content: String,
    notes: String,
}

pub fn parse_to_markdown(path: &str) -> String {
    match try_parse(path) {
        Ok(s) => s,
        Err(e) => e.to_pptx_message(),
    }
}

fn try_parse(path: &str) -> Result<String> {
    let file = File::open(path)?;
    let buf = BufReader::new(file);
    let mut archive = ZipArchive::new(buf)?;

    // Enumerate slide entries — file names like "ppt/slides/slide1.xml" /
    // "ppt/slides/slide12.xml". Sort by the embedded number.
    let mut slide_entries: Vec<(i32, String)> = (0..archive.len())
        .filter_map(|i| {
            archive.by_index(i).ok().and_then(|f| {
                let name = f.name().to_string();
                slide_number_from_path(&name).map(|n| (n, name))
            })
        })
        .collect();
    slide_entries.sort_by_key(|(n, _)| *n);

    if slide_entries.is_empty() {
        return Err(OfficeParseError::PptxNoSlides);
    }

    let mut slides: Vec<SlideContent> = Vec::with_capacity(slide_entries.len());
    for (idx, (_, slide_path)) in slide_entries.iter().enumerate() {
        let slide_number = (idx as i32) + 1;
        let slide_content_xml = read_zip_entry(&mut archive, slide_path)?;
        let slide_content = parse_slide_xml(&slide_content_xml).unwrap_or_default();

        let notes_path = format!("ppt/notesSlides/notesSlide{}.xml", slide_number);
        let notes = match read_zip_entry(&mut archive, &notes_path) {
            Ok(bytes) => parse_notes_xml(&bytes).unwrap_or_default(),
            Err(_) => String::new(),
        };

        slides.push(SlideContent {
            slide_number,
            content: slide_content,
            notes,
        });
    }

    Ok(format_output(&slides))
}

fn slide_number_from_path(path: &str) -> Option<i32> {
    let prefix = "ppt/slides/slide";
    if !path.starts_with(prefix) || !path.ends_with(".xml") {
        return None;
    }
    let n_str = &path[prefix.len()..path.len() - ".xml".len()];
    n_str.parse::<i32>().ok()
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

fn format_output(slides: &[SlideContent]) -> String {
    let mut out = String::new();
    for slide in slides {
        out.push_str(&format!("## Slide {}\n\n", slide.slide_number));
        out.push_str(&slide.content);
        if !slide.notes.trim().is_empty() {
            out.push_str("\n### Speaker Notes\n\n");
            out.push_str(&slide.notes);
        }
        out.push('\n');
    }
    out.trim().to_string()
}

// ---------------- slide content ----------------

fn parse_slide_xml(xml: &[u8]) -> Result<String> {
    let mut reader = Reader::from_reader(xml);
    reader.config_mut().trim_text(false);

    let mut buf = Vec::new();
    let mut out = String::new();
    let mut depth: i32 = 0;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                let name = local_name(e.name().into_inner());
                match name {
                    b"sp" => process_shape(&mut reader, depth, &mut out)?,
                    b"graphicFrame" => process_graphic_frame(&mut reader, depth, &mut out)?,
                    _ => {}
                }
            }
            Event::Empty(_) => {}
            Event::End(_) => {
                depth -= 1;
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    Ok(out)
}

fn process_shape<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    shape_start_depth: i32,
    out: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = shape_start_depth;
    let mut text_content = String::new();

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"p" {
                    process_paragraph(reader, depth, &mut text_content)?;
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"sp" && depth == shape_start_depth {
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

    let trimmed = text_content.trim();
    if !trimmed.is_empty() {
        out.push_str(trimmed);
        out.push_str("\n\n");
    }
    Ok(())
}

fn process_paragraph<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    p_start_depth: i32,
    result: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = p_start_depth;
    let mut paragraph_text = String::new();
    let mut has_bullet = false;
    let mut bullet_level = 0i32;
    let mut is_numbered = false;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                let name = local_name(e.name().into_inner());
                match name {
                    b"pPr" => {
                        let (b, lvl, n) = extract_bullet_info(reader, depth)?;
                        has_bullet = b;
                        bullet_level = lvl;
                        is_numbered = n;
                    }
                    b"r" => extract_text_run(reader, depth, &mut paragraph_text)?,
                    _ => {}
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"p" && depth == p_start_depth {
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

    let trimmed = paragraph_text.trim();
    if !trimmed.is_empty() {
        if has_bullet {
            let indent: String = "  ".repeat(bullet_level.max(0) as usize);
            let marker = if is_numbered { "1. " } else { "- " };
            result.push_str(&indent);
            result.push_str(marker);
            result.push_str(trimmed);
            result.push('\n');
        } else {
            result.push_str(trimmed);
            result.push('\n');
        }
    }

    Ok(())
}

fn extract_bullet_info<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    ppr_start_depth: i32,
) -> Result<(bool, i32, bool)> {
    let mut buf = Vec::new();
    let mut depth = ppr_start_depth;
    let mut has_bullet = false;
    let mut level = 0i32;
    let mut is_numbered = false;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                match local_name(e.name().into_inner()) {
                    b"buChar" => {
                        has_bullet = true;
                        is_numbered = false;
                    }
                    b"buAutoNum" => {
                        has_bullet = true;
                        is_numbered = true;
                    }
                    b"lvl" => {
                        if let Some(v) = attr_val(e, b"val")? {
                            level = v.parse::<i32>().unwrap_or(0);
                        }
                    }
                    _ => {}
                }
            }
            Event::Empty(ref e) => {
                // Self-closing variants of bullet markers. JVM only inspects
                // START_TAG so it would MISS a self-closed `<a:buChar/>`. We
                // intentionally mirror that by **not** treating Empty as a
                // bullet trigger — but we still need to allow `<a:lvl val="N"/>`
                // self-closed because it's commonly emitted by PPTX writers.
                if local_name(e.name().into_inner()) == b"lvl" {
                    if let Some(v) = attr_val(e, b"val")? {
                        level = v.parse::<i32>().unwrap_or(0);
                    }
                }
            }
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"pPr" && depth == ppr_start_depth {
                    return Ok((has_bullet, level, is_numbered));
                }
                depth -= 1;
            }
            Event::Eof => return Ok((has_bullet, level, is_numbered)),
            _ => {}
        }
        buf.clear();
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

fn extract_text_run<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    run_start_depth: i32,
    out: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = run_start_depth;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"t" {
                    capture_t_text(reader, depth, out)?;
                    depth -= 1; // <a:t> closed
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"r" && depth == run_start_depth {
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

fn process_graphic_frame<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    frame_start_depth: i32,
    out: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = frame_start_depth;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"tbl" {
                    process_table(reader, depth, out)?;
                }
            }
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"graphicFrame"
                    && depth == frame_start_depth
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
    Ok(())
}

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
        out.push('\n');
    }

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
                    let text = extract_table_cell(reader, depth)?;
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

fn extract_table_cell<R: std::io::BufRead>(
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
                if local_name(e.name().into_inner()) == b"t" {
                    let mut t_text = String::new();
                    capture_t_text(reader, depth, &mut t_text)?;
                    if !t_text.is_empty() {
                        if !result.is_empty() {
                            result.push(' ');
                        }
                        result.push_str(&t_text);
                    }
                    depth -= 1; // <a:t> closed
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

// ---------------- notes ----------------

fn parse_notes_xml(xml: &[u8]) -> Result<String> {
    let mut reader = Reader::from_reader(xml);
    reader.config_mut().trim_text(false);

    let mut buf = Vec::new();
    let mut out = String::new();
    let mut depth: i32 = 0;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                if local_name(e.name().into_inner()) == b"sp" {
                    process_notes_shape(&mut reader, depth, &mut out)?;
                }
            }
            Event::Empty(_) => {}
            Event::End(_) => {
                depth -= 1;
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }

    Ok(out.trim().to_string())
}

/// Mirror of JVM `isNotesTextShape` + `extractShapeText` combined. JVM's impl
/// reads the parser forward to find `<a:ph>` (consuming events), then if
/// `type="body"`, calls `extractShapeText` on the (advanced) parser cursor.
/// We replicate that single-pass behavior here.
fn process_notes_shape<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    shape_start_depth: i32,
    out: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = shape_start_depth;
    let mut is_body_shape = false;
    let mut ph_decided = false;
    let mut shape_text = String::new();

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(ref e) => {
                depth += 1;
                let name = local_name(e.name().into_inner());

                if !ph_decided && name == b"ph" {
                    let t = attr_val(e, b"type")?;
                    is_body_shape = t.as_deref() == Some("body");
                    ph_decided = true;
                    if !is_body_shape {
                        skip_until_sp_end(reader, shape_start_depth, depth)?;
                        return Ok(());
                    }
                } else if is_body_shape && name == b"t" {
                    capture_t_text(reader, depth, &mut shape_text)?;
                    depth -= 1; // <a:t> closed
                }
            }
            Event::Empty(ref e) => {
                let name = local_name(e.name().into_inner());
                // <a:ph type="body"/> is common — self-closing variant.
                if !ph_decided && name == b"ph" {
                    let t = attr_val(e, b"type")?;
                    is_body_shape = t.as_deref() == Some("body");
                    ph_decided = true;
                    if !is_body_shape {
                        skip_until_sp_end(reader, shape_start_depth, depth)?;
                        return Ok(());
                    }
                }
            }
            Event::End(ref e) => {
                let name = local_name(e.name().into_inner());
                if name == b"sp" && depth == shape_start_depth {
                    if !shape_text.is_empty() {
                        out.push_str(&shape_text);
                    }
                    return Ok(());
                }
                // JVM `extractShapeText` adds "\n" on every </p> close once
                // we're inside a body shape.
                if is_body_shape && name == b"p" {
                    shape_text.push('\n');
                }
                depth -= 1;
            }
            Event::Eof => return Ok(()),
            _ => {}
        }
        buf.clear();
    }
}

fn capture_t_text<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    t_depth: i32,
    out: &mut String,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut inner_depth = t_depth;
    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Text(t) => {
                let unescaped = t.unescape().map_err(quick_xml::Error::from)?;
                out.push_str(&unescaped);
            }
            Event::Start(_) => inner_depth += 1,
            Event::End(ref end) => {
                inner_depth -= 1;
                if local_name(end.name().into_inner()) == b"t" && inner_depth + 1 == t_depth {
                    return Ok(());
                }
            }
            Event::Eof => return Ok(()),
            _ => {}
        }
    }
}

/// Advance the parser past the `</sp>` that closes the shape we're inside.
/// `current_depth` is the depth at the time the caller realized "skip this
/// shape" — there may be unclosed Start frames between current_depth and
/// shape_start_depth that still need their End events to fire.
fn skip_until_sp_end<R: std::io::BufRead>(
    reader: &mut Reader<R>,
    shape_start_depth: i32,
    current_depth: i32,
) -> Result<()> {
    let mut buf = Vec::new();
    let mut depth = current_depth;
    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(_) => depth += 1,
            Event::Empty(_) => {}
            Event::End(ref e) => {
                if local_name(e.name().into_inner()) == b"sp" && depth == shape_start_depth {
                    return Ok(());
                }
                depth -= 1;
            }
            Event::Eof => return Ok(()),
            _ => {}
        }
        buf.clear();
    }
}

// ---------------- helpers ----------------

fn local_name(qualified: &[u8]) -> &[u8] {
    if let Some(pos) = qualified.iter().position(|&b| b == b':') {
        &qualified[pos + 1..]
    } else {
        qualified
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slide_number_parses() {
        assert_eq!(slide_number_from_path("ppt/slides/slide1.xml"), Some(1));
        assert_eq!(slide_number_from_path("ppt/slides/slide42.xml"), Some(42));
        assert_eq!(slide_number_from_path("ppt/slides/_rels/slide1.xml.rels"), None);
        assert_eq!(slide_number_from_path("ppt/slides/slide.xml"), None);
        assert_eq!(slide_number_from_path("other/file.xml"), None);
    }

    #[test]
    fn format_output_basic() {
        let slides = vec![
            SlideContent {
                slide_number: 1,
                content: "Hello\n\n".to_string(),
                notes: "".to_string(),
            },
            SlideContent {
                slide_number: 2,
                content: "World\n\n".to_string(),
                notes: "Some notes".to_string(),
            },
        ];
        let out = format_output(&slides);
        assert!(out.contains("## Slide 1"));
        assert!(out.contains("Hello"));
        assert!(out.contains("## Slide 2"));
        assert!(out.contains("### Speaker Notes"));
        assert!(out.contains("Some notes"));
    }

    #[test]
    fn local_name_strips_prefix() {
        assert_eq!(local_name(b"a:p"), b"p");
        assert_eq!(local_name(b"p:sp"), b"sp");
    }
}
