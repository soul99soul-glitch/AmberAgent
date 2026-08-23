//! XLSX → markdown-flavoured plain text converter. Phase 3 D-1 — net-new
//! parser (the JVM side has no `XlsxParser`; the existing
//! `WorkspaceArtifactTools.xlsx` branch uses a custom in-app helper that we
//! preserve as the fallback path).
//!
//! Output shape mirrors `pptx.rs` for visual consistency:
//!
//! ```text
//! ## Sheet: <name>
//!
//! | col0 | col1 | col2 |
//! | a    | b    | c    |
//!
//! ## Sheet: <name2>
//! ...
//! ```
//!
//! Behaviour notes:
//!
//! - All non-empty sheets emitted in the order calamine reports them
//!   (matches the workbook's internal sheet sequence).
//! - Cell rendering: integers print without trailing `.0`; floats keep their
//!   decimal portion; bools as `true`/`false`; dates as their raw serial
//!   number (calamine `dates` feature is OFF so we don't drag chrono into
//!   the .so — render comes from `Data::DateTimeIso` / `Data::DurationIso`
//!   string variants when present, raw float otherwise).
//! - Empty cells become a single ASCII space inside the pipe column for
//!   shape stability (matches markdown table convention).
//! - Sheets with zero non-empty rows produce just the heading + a blank
//!   line — same shape pptx.rs uses for slides with no content.

use calamine::{open_workbook_auto, Data, Reader};

use crate::error::{OfficeParseError, Result};

pub fn parse_to_markdown(path: &str) -> String {
    match try_parse(path) {
        Ok(s) => s,
        Err(e) => e.to_xlsx_message(),
    }
}

fn try_parse(path: &str) -> Result<String> {
    let mut workbook =
        open_workbook_auto(path).map_err(|e| OfficeParseError::Xlsx(e.to_string()))?;
    let sheet_names: Vec<String> = workbook.sheet_names().to_vec();
    if sheet_names.is_empty() {
        return Err(OfficeParseError::XlsxNoSheets);
    }

    let mut out = String::with_capacity(4096);
    let mut emitted_any = false;
    for name in &sheet_names {
        let range = match workbook.worksheet_range(name) {
            Ok(r) => r,
            Err(_) => continue,
        };
        if range.is_empty() {
            // Heading-only block keeps the structure visible; user can see
            // that the sheet existed but contained no data. The "(empty)"
            // marker prevents an LLM consumer from hallucinating content
            // into a heading-followed-by-blank pattern (D-1 R1 P3-c).
            if emitted_any {
                out.push('\n');
            }
            out.push_str(&format!("## Sheet: {} (empty)\n\n", name));
            emitted_any = true;
            continue;
        }
        if emitted_any {
            out.push('\n');
        }
        out.push_str(&format!("## Sheet: {}\n\n", name));
        render_range(&range, &mut out);
        emitted_any = true;
    }
    Ok(out)
}

fn render_range(range: &calamine::Range<Data>, out: &mut String) {
    let cols = range.width();
    if cols == 0 {
        return;
    }
    for row in range.rows() {
        out.push('|');
        for cell in row.iter().take(cols) {
            out.push(' ');
            out.push_str(&render_cell(cell));
            out.push_str(" |");
        }
        out.push('\n');
    }
}

fn render_cell(cell: &Data) -> String {
    match cell {
        Data::Empty => " ".to_string(),
        Data::String(s) => sanitize_cell(s),
        Data::Float(f) => {
            // Round-trippy: print integers without trailing `.0`, floats
            // with their full Debug representation (Rust default rounding).
            if f.fract() == 0.0 && f.is_finite() && f.abs() < 1e15 {
                format!("{}", *f as i64)
            } else {
                format!("{}", f)
            }
        }
        Data::Int(i) => format!("{}", i),
        Data::Bool(b) => format!("{}", b),
        // Codex review P2-4: Excel's date semantics are workbook-mode
        // dependent (1900 vs 1904 epoch, plus the 1900 leap-year bug).
        // calamine 0.26 doesn't expose the workbook mode flag without
        // enabling the `dates` feature (which pulls chrono into the .so),
        // so any in-house ISO conversion produces "confidently wrong"
        // dates for some workbooks. Emit the raw serial with an
        // identifying suffix so an LLM consumer / human reader sees the
        // value AND can recognise its semantics, instead of an ISO date
        // that's silently off by 1–2 days. Wire a real conversion only
        // when a JVM-side xlsx parser lands that we can cross-check.
        Data::DateTime(dt) => format!("{} (excel-serial)", dt.as_f64()),
        // `Data::DateTimeIso` / `Data::DurationIso` are emitted by calamine
        // only for ODS / SpreadsheetML files, not standard xlsx. We pass
        // them through verbatim so a misnamed `.xlsx` (actually ODS opened
        // via `open_workbook_auto`) still renders something sensible.
        Data::DateTimeIso(s) => sanitize_cell(s),
        Data::DurationIso(s) => sanitize_cell(s),
        Data::Error(e) => format!("#ERR:{:?}", e),
    }
}

// Excel-serial → ISO date conversion was removed (Codex review P2-4):
// Excel's date semantics depend on workbook mode (1900 vs 1904 epoch) and
// include the famous 1900-02-29 leap-year bug. calamine 0.26 doesn't expose
// the workbook mode flag without the `dates` feature (which pulls chrono).
// Any in-house conversion would produce off-by-1–2-day dates for some
// workbooks. We now emit the raw serial with an "(excel-serial)" suffix
// so consumers see the value AND its semantics — see the `DateTime` arm of
// `render_cell` above.

/// Replace characters that would corrupt the markdown pipe table shape.
/// `|` becomes `\|`; newlines become a single space (preserves the row layout).
fn sanitize_cell(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for ch in s.chars() {
        match ch {
            '|' => out.push_str("\\|"),
            '\n' | '\r' => out.push(' '),
            _ => out.push(ch),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn integer_cell_no_trailing_zero() {
        assert_eq!(render_cell(&Data::Float(42.0)), "42");
        assert_eq!(render_cell(&Data::Int(7)), "7");
    }

    #[test]
    fn fractional_float_preserves_decimal() {
        let s = render_cell(&Data::Float(3.14));
        assert!(s.starts_with("3.14"), "unexpected: {s}");
    }

    #[test]
    fn empty_cell_renders_as_space() {
        assert_eq!(render_cell(&Data::Empty), " ");
    }

    #[test]
    fn pipe_in_string_escaped() {
        assert_eq!(render_cell(&Data::String("a|b".into())), "a\\|b");
    }

    #[test]
    fn newline_in_string_becomes_space() {
        assert_eq!(render_cell(&Data::String("line1\nline2".into())), "line1 line2");
        assert_eq!(render_cell(&Data::String("a\r\nb".into())), "a  b");
    }

    #[test]
    fn bool_renders_lowercase() {
        assert_eq!(render_cell(&Data::Bool(true)), "true");
        assert_eq!(render_cell(&Data::Bool(false)), "false");
    }

    #[test]
    fn cjk_string_preserved() {
        assert_eq!(render_cell(&Data::String("你好世界".into())), "你好世界");
    }

    // Note: DateTime rendering (Data::DateTime) defers to `dt.as_f64() +
    // " (excel-serial)"` suffix. Testing it would require constructing a
    // calamine `ExcelDateTime` whose API varies across versions — we trust
    // calamine's own f64 round-trip and skip the test. The format change
    // is documented in `render_cell`.
}
