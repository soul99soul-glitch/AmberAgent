#!/usr/bin/env python3
"""
JNI symbol parity guard (P3+ acceptance test for Surface D).

Verifies that every `external fun` declared in Kotlin (Android JNI call sites)
has a matching `#[no_mangle] extern "system" fn Java_<mangled>...` export in
the Rust crates under native/. A mismatch causes `UnsatisfiedLinkError` at
runtime — exactly the bug fixed in this round (Rust exported
`..._supportedLanguages` but Kotlin declared `..._supportedLanguagesNative`).

This is a STATIC contract test: it does not require the Android NDK or a
device. It runs in CI via `python3 scripts/check_jni_parity.py` (exit 0 = OK).

Mapping rules (Kotlin JNI name mangling):
  package app.amber.X.ClassName, external fun methodName
  → Java_app_amber_X_ClassName_methodName

  (Kotlin mangles the declaring class's fully-qualified name with '_' and
   appends '_'+method. Inner/companion objects use the object's name as the
   class component. We reconstruct the expected symbol from each external
   fun's enclosing package + class.)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
KT_ROOTS = [REPO / "app", REPO / "common", REPO / "highlight", REPO / "document", REPO / "core"]
RUST_NATIVE = REPO / "native"


def collect_rust_jni_exports() -> set[str]:
    """All `fn Java_<...>` symbols exported by Rust crates."""
    exports: set[str] = set()
    for rs in RUST_NATIVE.rglob("*.rs"):
        if "/target/" in str(rs):
            continue
        text = rs.read_text(encoding="utf-8")
        for m in re.finditer(r'fn (Java_[A-Za-z0-9_]+)', text):
            exports.add(m.group(1))
    return exports


def collect_kotlin_external_symbols() -> set[tuple[str, str]]:
    """(expected_java_symbol, location) for every `external fun`."""
    symbols: set[tuple[str, str]] = set()
    for root in KT_ROOTS:
        for kt in root.rglob("*.kt"):
            s = str(kt)
            if "/build/" in s:
                continue
            text = kt.read_text(encoding="utf-8")
            pkg_m = re.search(r'^package\s+([\w.]+)', text, re.M)
            if not pkg_m:
                continue
            pkg = pkg_m.group(1)
            # Walk lines; track the most recent TOP-LEVEL object/class declaration
            # that encloses an external fun. JNI bridges in this repo are all
            # top-level `object XxxNative { ... @JvmStatic external fun ... }`.
            # KMP JVM actuals may use `internal actual object` plus
            # `external actual fun`; count those too.
            # Match only declarations at column 0 (top-level) to avoid matching
            # nested result types (DisabledConfig, NativeUnavailable, etc.)
            # and import lines.
            current_class: str | None = None
            for line in text.splitlines():
                stripped = line.lstrip()
                if stripped.startswith("import ") or stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
                    continue
                else:
                    # Top-level declaration = starts at column 0 (no indent)
                    if not line.startswith(" ") and not line.startswith("\t"):
                        cm = re.match(
                            r'(?:internal |private |public |sealed |abstract |open |final |data |actual )*'
                            r'(?:object|class)\s+(\w+)',
                            line,
                        )
                        if cm:
                            current_class = cm.group(1)
                em = re.search(r'\bfun\s+(\w+)', line) if re.search(r'\bexternal\b', line) else None
                if em and current_class:
                    method = em.group(1)
                    expected = "Java_" + pkg.replace(".", "_") + "_" + current_class + "_" + method
                    symbols.add((expected, f"{kt.relative_to(REPO)}:{current_class}.{method}"))
    return symbols


def main() -> int:
    rust_exports = collect_rust_jni_exports()
    kotlin_symbols = collect_kotlin_external_symbols()

    missing: list[str] = []
    for expected, where in sorted(kotlin_symbols):
        if expected not in rust_exports:
            missing.append(f"  {where}\n    expected Rust symbol: {expected}\n    NOT found in Rust #[no_mangle] exports")

    if missing:
        print("FAIL: JNI symbol parity broken — Kotlin external funs without matching Rust export:")
        print("\n".join(missing))
        print(f"\nRust exports ({len(rust_exports)}): see native/*/src/*.rs")
        print("Fix: rename the Rust `fn Java_..._<method>` to match Kotlin, OR fix the Kotlin `external fun` name.")
        return 1

    print(f"OK: {len(kotlin_symbols)} Kotlin external funs all have matching Rust JNI exports.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
