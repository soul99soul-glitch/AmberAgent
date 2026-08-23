//! json-expr: Rust port of `common/src/main/java/me/rerere/common/http/JsonExpression.kt`
//! (TD.Rust.2).
//!
//! Implements the same small DSL used by provider balance-option paths:
//! - Path navigation: `field`, `field.sub`, `array[0]`
//! - String literals: `"text"` with `\n \r \t \\ \"` escapes
//! - Numbers: `1`, `3.14`
//! - Unary `+ -`
//! - Binary `+ - * / x` (x = star alias)
//! - String concatenation `++` (operands coerced to string)
//!
//! Result coercion:
//! - Missing paths / null → empty string
//! - JSON strings → unwrapped
//! - JSON numbers → minimally-formatted (3.0 → "3", per JVM behavior with %.2f then toDouble round-trip)
//! - JSON objects/arrays → JSON string representation
//!
//! JNI surface:
//! - `evaluateNative(rootJson, expr) -> String?` — null on parse/eval error
//! - `isValidNative(expr) -> Boolean`

use std::panic::{catch_unwind, AssertUnwindSafe};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::Value;

// ===========================================================================
// Tokens
// ===========================================================================

#[derive(Debug, Clone, PartialEq)]
enum TokenType {
    Ident,
    Number,
    String,
    Dot,
    LBracket,
    RBracket,
    LParen,
    RParen,
    Plus,
    Minus,
    Star,
    Slash,
    Concat,
    Eof,
}

#[derive(Debug, Clone)]
struct Token {
    ty: TokenType,
    lexeme: String,
}

// ===========================================================================
// Lexer
// ===========================================================================

struct Lexer<'a> {
    src: &'a [u8],
    i: usize,
}

impl<'a> Lexer<'a> {
    fn new(s: &'a str) -> Self {
        Self { src: s.as_bytes(), i: 0 }
    }

    fn next(&mut self) -> Result<Token, ParseError> {
        self.skip_ws();
        if self.i >= self.src.len() {
            return Ok(Token { ty: TokenType::Eof, lexeme: String::new() });
        }
        let c = self.src[self.i] as char;
        let start = self.i;
        match c {
            '.' => { self.i += 1; Ok(Token { ty: TokenType::Dot, lexeme: ".".into() }) }
            '[' => { self.i += 1; Ok(Token { ty: TokenType::LBracket, lexeme: "[".into() }) }
            ']' => { self.i += 1; Ok(Token { ty: TokenType::RBracket, lexeme: "]".into() }) }
            '(' => { self.i += 1; Ok(Token { ty: TokenType::LParen, lexeme: "(".into() }) }
            ')' => { self.i += 1; Ok(Token { ty: TokenType::RParen, lexeme: ")".into() }) }
            '+' => {
                if self.peek() == Some(b'+') {
                    self.i += 2;
                    Ok(Token { ty: TokenType::Concat, lexeme: "++".into() })
                } else {
                    self.i += 1;
                    Ok(Token { ty: TokenType::Plus, lexeme: "+".into() })
                }
            }
            '-' => { self.i += 1; Ok(Token { ty: TokenType::Minus, lexeme: "-".into() }) }
            '*' => { self.i += 1; Ok(Token { ty: TokenType::Star, lexeme: "*".into() }) }
            '/' => { self.i += 1; Ok(Token { ty: TokenType::Slash, lexeme: "/".into() }) }
            'x' | 'X' => { self.i += 1; Ok(Token { ty: TokenType::Star, lexeme: c.to_string() }) }
            '"' => self.string_tok(),
            c if c.is_ascii_digit() => self.number_tok(),
            c if is_ident_start(c) => self.ident_tok(),
            _ => Err(ParseError(format!("Unexpected character '{}' at {}", c, start))),
        }
    }

    fn skip_ws(&mut self) {
        while self.i < self.src.len() && (self.src[self.i] as char).is_whitespace() {
            self.i += 1;
        }
    }

    fn peek(&self) -> Option<u8> {
        self.src.get(self.i + 1).copied()
    }

    fn string_tok(&mut self) -> Result<Token, ParseError> {
        let start = self.i;
        self.i += 1;
        let mut sb = String::new();
        let mut escaped = false;
        let mut closed = false;
        while self.i < self.src.len() {
            let ch = self.src[self.i] as char;
            self.i += 1;
            if escaped {
                match ch {
                    '\\' => sb.push('\\'),
                    '"' => sb.push('"'),
                    'n' => sb.push('\n'),
                    'r' => sb.push('\r'),
                    't' => sb.push('\t'),
                    other => sb.push(other),
                }
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                closed = true;
                break;
            } else {
                sb.push(ch);
            }
        }
        if !closed {
            return Err(ParseError(format!("Unterminated string starting at {}", start)));
        }
        Ok(Token { ty: TokenType::String, lexeme: sb })
    }

    fn number_tok(&mut self) -> Result<Token, ParseError> {
        let start = self.i;
        while self.i < self.src.len() && (self.src[self.i] as char).is_ascii_digit() {
            self.i += 1;
        }
        if self.i < self.src.len() && self.src[self.i] as char == '.' {
            self.i += 1;
            while self.i < self.src.len() && (self.src[self.i] as char).is_ascii_digit() {
                self.i += 1;
            }
        }
        let text = std::str::from_utf8(&self.src[start..self.i])
            .map_err(|e| ParseError(e.to_string()))?
            .to_string();
        Ok(Token { ty: TokenType::Number, lexeme: text })
    }

    fn ident_tok(&mut self) -> Result<Token, ParseError> {
        let start = self.i;
        self.i += 1;
        while self.i < self.src.len() {
            let c = self.src[self.i] as char;
            if is_ident_part(c) {
                self.i += 1;
            } else {
                break;
            }
        }
        let text = std::str::from_utf8(&self.src[start..self.i])
            .map_err(|e| ParseError(e.to_string()))?
            .to_string();
        Ok(Token { ty: TokenType::Ident, lexeme: text })
    }
}

fn is_ident_start(c: char) -> bool {
    c == '_' || c.is_alphabetic()
}

fn is_ident_part(c: char) -> bool {
    c == '_' || c.is_alphanumeric()
}

// ===========================================================================
// AST
// ===========================================================================

#[derive(Debug)]
enum Expr {
    Binary { left: Box<Expr>, op: TokenType, right: Box<Expr> },
    Unary { op: TokenType, expr: Box<Expr> },
    Number(f64),
    StringLit(String),
    Path(Vec<PathPart>),
}

#[derive(Debug)]
enum PathPart {
    Field(String),
    Index(i64),
}

// ===========================================================================
// Parser
// ===========================================================================

#[derive(Debug)]
struct ParseError(String);

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

struct Parser<'a> {
    lexer: Lexer<'a>,
    current: Token,
}

impl<'a> Parser<'a> {
    fn new(mut lexer: Lexer<'a>) -> Result<Self, ParseError> {
        let current = lexer.next()?;
        Ok(Self { lexer, current })
    }

    fn parse(&mut self) -> Result<Expr, ParseError> {
        let expr = self.parse_concat()?;
        self.expect(TokenType::Eof)?;
        Ok(expr)
    }

    fn parse_concat(&mut self) -> Result<Expr, ParseError> {
        let mut left = self.parse_additive()?;
        while self.matches(TokenType::Concat) {
            let right = self.parse_additive()?;
            left = Expr::Binary { left: Box::new(left), op: TokenType::Concat, right: Box::new(right) };
        }
        Ok(left)
    }

    fn parse_additive(&mut self) -> Result<Expr, ParseError> {
        let mut left = self.parse_multiplicative()?;
        loop {
            let op = if self.matches(TokenType::Plus) {
                TokenType::Plus
            } else if self.matches(TokenType::Minus) {
                TokenType::Minus
            } else {
                break;
            };
            let right = self.parse_multiplicative()?;
            left = Expr::Binary { left: Box::new(left), op, right: Box::new(right) };
        }
        Ok(left)
    }

    fn parse_multiplicative(&mut self) -> Result<Expr, ParseError> {
        let mut left = self.parse_unary()?;
        loop {
            let op = if self.matches(TokenType::Star) {
                TokenType::Star
            } else if self.matches(TokenType::Slash) {
                TokenType::Slash
            } else {
                break;
            };
            let right = self.parse_unary()?;
            left = Expr::Binary { left: Box::new(left), op, right: Box::new(right) };
        }
        Ok(left)
    }

    fn parse_unary(&mut self) -> Result<Expr, ParseError> {
        if self.matches(TokenType::Plus) {
            let e = self.parse_unary()?;
            return Ok(Expr::Unary { op: TokenType::Plus, expr: Box::new(e) });
        }
        if self.matches(TokenType::Minus) {
            let e = self.parse_unary()?;
            return Ok(Expr::Unary { op: TokenType::Minus, expr: Box::new(e) });
        }
        self.parse_primary()
    }

    fn parse_primary(&mut self) -> Result<Expr, ParseError> {
        match self.current.ty.clone() {
            TokenType::Number => {
                let n: f64 = self.current.lexeme.parse().map_err(|e: std::num::ParseFloatError| ParseError(e.to_string()))?;
                self.advance()?;
                Ok(Expr::Number(n))
            }
            TokenType::String => {
                let s = self.current.lexeme.clone();
                self.advance()?;
                Ok(Expr::StringLit(s))
            }
            TokenType::Ident => self.parse_path(),
            TokenType::LParen => {
                self.advance()?;
                let e = self.parse_concat()?;
                self.expect(TokenType::RParen)?;
                Ok(e)
            }
            other => Err(ParseError(format!("Expected primary expression, got {:?}", other))),
        }
    }

    fn parse_path(&mut self) -> Result<Expr, ParseError> {
        let mut parts: Vec<PathPart> = Vec::new();
        if self.current.ty != TokenType::Ident {
            return Err(ParseError("Expected identifier for path".into()));
        }
        parts.push(PathPart::Field(self.current.lexeme.clone()));
        self.advance()?;
        loop {
            if self.matches(TokenType::Dot) {
                if self.current.ty != TokenType::Ident {
                    return Err(ParseError(format!("Expected identifier after '.', got {:?}", self.current.ty)));
                }
                parts.push(PathPart::Field(self.current.lexeme.clone()));
                self.advance()?;
            } else if self.matches(TokenType::LBracket) {
                if self.current.ty != TokenType::Number {
                    return Err(ParseError(format!("Expected number index, got {:?}", self.current.ty)));
                }
                let n: f64 = self.current.lexeme.parse().map_err(|e: std::num::ParseFloatError| ParseError(e.to_string()))?;
                parts.push(PathPart::Index(n as i64));
                self.advance()?;
                self.expect(TokenType::RBracket)?;
            } else {
                break;
            }
        }
        Ok(Expr::Path(parts))
    }

    fn matches(&mut self, ty: TokenType) -> bool {
        if self.current.ty == ty {
            // advance discards the result; only used to consume on a successful match
            let _ = self.advance();
            true
        } else {
            false
        }
    }

    fn expect(&mut self, ty: TokenType) -> Result<(), ParseError> {
        if self.current.ty == ty {
            self.advance()?;
            Ok(())
        } else {
            Err(ParseError(format!("Expected {:?} but got {:?}", ty, self.current.ty)))
        }
    }

    fn advance(&mut self) -> Result<(), ParseError> {
        self.current = self.lexer.next()?;
        Ok(())
    }
}

// ===========================================================================
// Evaluator
// ===========================================================================

#[derive(Debug, Clone)]
enum EvalValue {
    Str(String),
    Num(f64),
}

fn eval(expr: &Expr, root: &Value) -> EvalValue {
    match expr {
        Expr::Number(n) => EvalValue::Num(*n),
        Expr::StringLit(s) => EvalValue::Str(s.clone()),
        Expr::Unary { op, expr: inner } => {
            let v = eval(inner, root);
            let n = to_number(&v);
            match op {
                TokenType::Minus => EvalValue::Num(-n),
                TokenType::Plus => EvalValue::Num(n),
                _ => EvalValue::Str(String::new()),
            }
        }
        Expr::Binary { left, op, right } => {
            let l = eval(left, root);
            let r = eval(right, root);
            match op {
                TokenType::Concat => EvalValue::Str(format!("{}{}", to_string(&l), to_string(&r))),
                TokenType::Plus => EvalValue::Num(to_number(&l) + to_number(&r)),
                TokenType::Minus => EvalValue::Num(to_number(&l) - to_number(&r)),
                TokenType::Star => EvalValue::Num(to_number(&l) * to_number(&r)),
                TokenType::Slash => EvalValue::Num(to_number(&l) / to_number(&r)),
                _ => EvalValue::Str(String::new()),
            }
        }
        Expr::Path(parts) => from_json(resolve_path(parts, root)),
    }
}

fn resolve_path<'a>(parts: &[PathPart], root: &'a Value) -> Option<&'a Value> {
    let mut cur = root;
    for p in parts {
        match p {
            PathPart::Field(name) => {
                cur = cur.as_object()?.get(name)?;
            }
            PathPart::Index(idx) => {
                let arr = cur.as_array()?;
                if *idx < 0 || (*idx as usize) >= arr.len() {
                    return None;
                }
                cur = &arr[*idx as usize];
            }
        }
    }
    Some(cur)
}

fn from_json(v: Option<&Value>) -> EvalValue {
    match v {
        None | Some(Value::Null) => EvalValue::Str(String::new()),
        Some(Value::String(s)) => EvalValue::Str(s.clone()),
        Some(Value::Number(n)) => {
            // JVM: doubleOrNull then "%.2f".format then toDouble. Mimic.
            if let Some(f) = n.as_f64() {
                // Round to 2-decimal then re-parse-as-double trick produces
                // exact-match output to JVM's "%.2f → toDouble" coercion path.
                let rounded = (f * 100.0).round() / 100.0;
                EvalValue::Num(rounded)
            } else {
                EvalValue::Str(n.to_string())
            }
        }
        Some(Value::Bool(b)) => EvalValue::Str(b.to_string()),
        Some(other) => EvalValue::Str(other.to_string()),
    }
}

fn to_number(v: &EvalValue) -> f64 {
    match v {
        EvalValue::Num(n) => *n,
        EvalValue::Str(s) => s.parse().unwrap_or(0.0),
    }
}

fn to_string(v: &EvalValue) -> String {
    match v {
        EvalValue::Str(s) => s.clone(),
        EvalValue::Num(n) => format_number(*n),
    }
}

fn format_number(d: f64) -> String {
    if d.is_nan() || d.is_infinite() {
        return d.to_string();
    }
    let as_long = d as i64;
    if d == as_long as f64 {
        as_long.to_string()
    } else {
        d.to_string()
    }
}

// ===========================================================================
// Public API
// ===========================================================================

pub fn evaluate(root_json: &str, expr_src: &str) -> Result<String, String> {
    let root: Value = serde_json::from_str(root_json).map_err(|e| e.to_string())?;
    if !root.is_object() {
        return Err("Root must be a JSON object".into());
    }
    let lexer = Lexer::new(expr_src);
    let mut parser = Parser::new(lexer).map_err(|e| e.0)?;
    let ast = parser.parse().map_err(|e| e.0)?;
    let value = eval(&ast, &root);
    Ok(match value {
        EvalValue::Str(s) => s,
        EvalValue::Num(n) => format_number(n),
    })
}

pub fn is_valid(expr_src: &str) -> bool {
    let lexer = Lexer::new(expr_src);
    let parser = Parser::new(lexer);
    match parser {
        Ok(mut p) => p.parse().is_ok(),
        Err(_) => false,
    }
}

// ===========================================================================
// JNI
// ===========================================================================

#[no_mangle]
pub extern "system" fn Java_app_amber_core_json_expr_JsonExprNative_evaluateNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    root_json: JString<'local>,
    expr: JString<'local>,
) -> jstring {
    jni_common::init_logger_once!("RustJsonExpr");

    let root_str: String = match env.get_string(&root_json) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("json-expr: get_string(root) failed: {}", e);
            return std::ptr::null_mut();
        }
    };
    let expr_str: String = match env.get_string(&expr) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("json-expr: get_string(expr) failed: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| evaluate(&root_str, &expr_str)));
    match result {
        Ok(Ok(s)) => match env.new_string(s) {
            Ok(j) => j.into_raw(),
            Err(e) => {
                log::error!("json-expr: new_string failed: {}", e);
                std::ptr::null_mut()
            }
        },
        Ok(Err(msg)) => {
            log::info!("json-expr: eval error: {}", msg);
            std::ptr::null_mut()
        }
        Err(panic) => {
            log::error!("json-expr: panic: {}", jni_common::panic_to_string(&panic));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_app_amber_core_json_expr_JsonExprNative_isValidNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    expr: JString<'local>,
) -> jboolean {
    jni_common::init_logger_once!("RustJsonExpr");
    let expr_str: String = match env.get_string(&expr) {
        Ok(s) => String::from(s),
        Err(_) => return JNI_FALSE,
    };
    let result = catch_unwind(AssertUnwindSafe(|| is_valid(&expr_str)));
    match result {
        Ok(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

// ===========================================================================
// Tests
// ===========================================================================

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn simple_field_access() {
        let root = r#"{"name": "Alice"}"#;
        assert_eq!(evaluate(root, "name").unwrap(), "Alice");
    }

    #[test]
    fn nested_field_access() {
        let root = r#"{"user": {"name": "Bob", "age": 30}}"#;
        assert_eq!(evaluate(root, "user.name").unwrap(), "Bob");
        assert_eq!(evaluate(root, "user.age").unwrap(), "30");
    }

    #[test]
    fn array_index() {
        // Note: field names cannot start with `x` or `X` because those chars
        // are tokenized as STAR (the multiplication alias) — same restriction
        // as the JVM JsonExpression DSL.
        let root = r#"{"vals": [10, 20, 30]}"#;
        assert_eq!(evaluate(root, "vals[1]").unwrap(), "20");
    }

    #[test]
    fn balance_path_typical() {
        // Mirror the DeepSeek provider use case
        let root = r#"{"balance_infos": [{"total_balance": "100.50", "currency": "USD"}]}"#;
        assert_eq!(
            evaluate(root, "balance_infos[0].total_balance").unwrap(),
            "100.50"
        );
    }

    #[test]
    fn arithmetic() {
        let root = r#"{"a": 10, "b": 4}"#;
        assert_eq!(evaluate(root, "a + b").unwrap(), "14");
        assert_eq!(evaluate(root, "a - b").unwrap(), "6");
        assert_eq!(evaluate(root, "a * b").unwrap(), "40");
        assert_eq!(evaluate(root, "a / b").unwrap(), "2.5");
    }

    #[test]
    fn concatenation() {
        let root = r#"{"first": "Hello", "second": "World"}"#;
        assert_eq!(evaluate(root, "first ++ \" \" ++ second").unwrap(), "Hello World");
    }

    #[test]
    fn missing_field_is_empty_string() {
        let root = r#"{"x": 1}"#;
        assert_eq!(evaluate(root, "missing").unwrap(), "");
    }

    #[test]
    fn missing_field_in_concat() {
        let root = r#"{"a": "ok"}"#;
        assert_eq!(evaluate(root, "a ++ missing").unwrap(), "ok");
    }

    #[test]
    fn unary_minus() {
        let root = r#"{"a": 5}"#;
        assert_eq!(evaluate(root, "-a").unwrap(), "-5");
    }

    #[test]
    fn parens_override_precedence() {
        let root = r#"{}"#;
        assert_eq!(evaluate(root, "(1 + 2) * 3").unwrap(), "9");
    }

    #[test]
    fn x_alias_for_star() {
        let root = r#"{"a": 4}"#;
        assert_eq!(evaluate(root, "a x 3").unwrap(), "12");
    }

    #[test]
    fn string_literal_with_escapes() {
        let root = r#"{}"#;
        // "abc\n" → abc + newline
        let out = evaluate(root, r#""abc\n""#).unwrap();
        assert_eq!(out, "abc\n");
    }

    #[test]
    fn is_valid_works() {
        assert!(is_valid("user.name"));
        assert!(is_valid("a + 1"));
        assert!(is_valid("a ++ b"));
        assert!(!is_valid("("));
        assert!(!is_valid("a +"));
        assert!(!is_valid("@@"));
    }

    #[test]
    fn integer_format_strips_trailing_zero() {
        // 3.0 should format as "3"
        let root = r#"{"n": 3.0}"#;
        assert_eq!(evaluate(root, "n").unwrap(), "3");
    }
}
