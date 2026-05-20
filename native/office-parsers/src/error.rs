//! Error types for office parsers. The JNI surface returns sentinel strings
//! mirroring the JVM implementation, so these errors are converted to text at
//! the boundary rather than crossing FFI as Rust types.

use thiserror::Error;

#[derive(Error, Debug)]
pub enum OfficeParseError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),

    #[error("zip error: {0}")]
    Zip(#[from] zip::result::ZipError),

    #[error("xml parse error: {0}")]
    Xml(#[from] quick_xml::Error),

    #[error("xml attribute error: {0}")]
    XmlAttr(#[from] quick_xml::events::attributes::AttrError),

    #[error("utf8 error: {0}")]
    Utf8(#[from] std::str::Utf8Error),

    #[error("unable to find document content in DOCX file")]
    DocxBodyMissing,

    #[error("no slides found in PPTX file")]
    PptxNoSlides,
}

impl OfficeParseError {
    /// Render as the JVM-compatible sentinel string.
    pub fn to_docx_message(&self) -> String {
        match self {
            Self::DocxBodyMissing => "Unable to find document content in DOCX file".to_string(),
            other => format!("Error parsing DOCX file: {}", other),
        }
    }

    pub fn to_pptx_message(&self) -> String {
        match self {
            Self::PptxNoSlides => "No slides found in PPTX file".to_string(),
            other => format!("Error parsing PPTX file: {}", other),
        }
    }
}

pub type Result<T> = std::result::Result<T, OfficeParseError>;
