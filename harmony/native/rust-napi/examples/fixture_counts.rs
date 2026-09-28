use amber_native_core::amber_count_tokens;

fn main() {
    let long_text = "你好, world! 🦀\n".repeat(4096);
    let fixtures = [
        ("empty", ""),
        ("ascii", "Hello world"),
        ("chinese", "你好，世界"),
        ("emoji", "🦀🚀🙂"),
        ("newline", "line 1\nline 2"),
        ("nul", "a\0b"),
        ("special_literal", "<|endoftext|>"),
        ("long", long_text.as_str()),
    ];
    println!("fixture\tUTF8 bytes\to200k_base\tcl100k_base\tclaude\tgemini");
    for (name, text) in fixtures {
        let counts: Vec<i64> = (1..=4)
            .map(|id| unsafe { amber_count_tokens(id, text.as_ptr(), text.len()) })
            .collect();
        println!(
            "{name}\t{}\t{}\t{}\t{}\t{}",
            text.len(),
            counts[0],
            counts[1],
            counts[2],
            counts[3]
        );
    }
}
