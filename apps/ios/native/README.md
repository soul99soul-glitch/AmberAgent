# Rust Native Components (amber-agent spike)

Cargo workspace housing 3 Rust crates that compile to Android `.so` libraries
via JNI bindings, replacing CPU-heavy JVM components.

## Layout

```
native/
├── Cargo.toml                    workspace manifest
├── office-parsers/               docx + pptx extraction (replaces JVM XmlPullParser)
├── markdown-parser/              pulldown-cmark + packed binary AST (alternative to JetBrains markdown)
└── highlight-parser/             tree-sitter + 14 grammars (replaces QuickJS+Prism)
```

See `docs/RUST_NATIVE_SPIKE_PLAN.md` at repo root for full spike plan,
acceptance criteria, JNI boundary spec, and packed binary formats.

## Local development

```bash
# Native-only sanity build (no Android linking)
cargo build --release

# Run native unit tests
cargo test --workspace

# Lint
cargo clippy --workspace --all-targets -- -D warnings

# Format check
cargo fmt --check
```

## Android cross-build

Driven by Gradle via the [mozilla/rust-android-gradle](https://github.com/mozilla/rust-android-gradle)
plugin. The plugin runs `cargo-ndk` for each Android ABI and stages the
resulting `.so` files into the consumer module's `jniLibs/` directory.

```bash
# From repo root — assumes Rust toolchain + Android NDK + targets installed
./gradlew :document:cargoBuild           # Component #1
./gradlew :app:cargoBuild                # Component #2
./gradlew :highlight:cargoBuild          # Component #3
```

See `docs/RUST_NATIVE_SPIKE_PLAN.md` §2.1/§2.3 for one-time setup.

## Hard constraints

- **No std/heap-only blowups**: Every JNI call must work with O(input size) memory.
- **Panic-catch at FFI boundary**: Never unwind across JNI; catch in `lib.rs` entry.
- **JVM fallback preserved**: The Kotlin adapter falls back to existing JVM
  implementation if native load fails or returns error. This is non-negotiable
  during spike — see SPIKE_PLAN.md §6.
- **Output equivalence**: Each component must produce output indistinguishable
  from its JVM counterpart for the corpus in `native/<component>/tests/corpus/`.
