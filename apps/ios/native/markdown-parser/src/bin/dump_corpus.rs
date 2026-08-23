//! Golden-blob generator for the renderer parity rig.
//! Usage: cargo run -p markdown-parser --bin dump_corpus -- <dir>
//! For every <name>.md in <dir>, writes <name>.pmda (packed AST) beside it.

use std::{env, fs, process};

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() != 2 {
        eprintln!("usage: dump_corpus <corpus-dir>");
        process::exit(2);
    }
    let mut paths: Vec<_> = fs::read_dir(&args[1])
        .expect("read corpus dir")
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "md"))
        .collect();
    paths.sort();
    if paths.is_empty() {
        eprintln!("no .md samples found in {}", args[1]);
        process::exit(1);
    }
    for path in &paths {
        let text = fs::read_to_string(path).expect("read sample");
        let blob = markdown_parser::pack(&markdown_parser::build_tree(&text));
        fs::write(path.with_extension("pmda"), &blob).expect("write blob");
    }
    println!("wrote {} blobs", paths.len());
}
