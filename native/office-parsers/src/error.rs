use thiserror::Error;

#[derive(Error, Debug)]
pub enum OfficeParseError {
    #[error("{0}")]
    Xlsx(String),

    #[error("no sheets found in XLSX file")]
    XlsxNoSheets,
}

impl OfficeParseError {
    pub fn to_xlsx_message(&self) -> String {
        match self {
            Self::XlsxNoSheets => "No sheets found in XLSX file".to_string(),
            other => format!("Error parsing XLSX file: {other}"),
        }
    }
}

pub type Result<T> = std::result::Result<T, OfficeParseError>;
