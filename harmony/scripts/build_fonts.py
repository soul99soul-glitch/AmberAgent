#!/usr/bin/env python3
"""Build Amber brand fonts for the HarmonyOS entry module.

Pipeline (mirrors Android app/src/main/java/app/amber/feature/ui/theme/Type.kt):
  - Hanken Grotesk (VF) + Noto Sans SC (VF, GB2312 subset) are instanced per weight,
    then merged per weight into ONE file: Latin -> Hanken, hanzi -> Noto Sans SC.
    Registered under weight-bound family names (AmberSans / AmberSansMedium / ...),
    so runtime font-weight matching is never relied upon.
  - JetBrains Mono Medium/SemiBold are instanced for eyebrow/meta accents
    (Regular/Bold statics already ship in rawfile/fonts).
  - Noto Serif SC (static, full charset ~31k glyphs) is subset down to exactly the
    Noto Sans SC GB2312 coverage + kept OpenType tables -> NotoSerifSC.otf.

Inputs are read from the Android res/font directory (single source of truth).
Usage: python3 harmony/scripts/build_fonts.py   (repo root)
"""
import os
import shutil
import sys

from fontTools.ttLib import TTFont
from fontTools.merge import Merger
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.subset import Subsetter, Options

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "res", "font")
OUT = os.path.join(ROOT, "harmony", "entry", "src", "main", "resources", "rawfile", "fonts")
TMP_DIR = os.path.join(OUT, ".tmp")

HANKEN = os.path.join(SRC, "hanken_grotesk.ttf")
NOTO_SANS = os.path.join(SRC, "noto_sans_sc.ttf")
JB_MONO = os.path.join(SRC, "jetbrains_mono.ttf")
NOTO_SERIF = os.path.join(SRC, "noto_serif_sc.otf")

WEIGHTS = {400: "AmberSans.ttf", 500: "AmberSans-Medium.ttf",
           600: "AmberSans-SemiBold.ttf", 700: "AmberSans-Bold.ttf"}
MONO_WEIGHTS = {500: "JetBrainsMono-Medium.ttf", 600: "JetBrainsMono-SemiBold.ttf"}


def instance(src: str, wght: float) -> TTFont:
    font = TTFont(src)
    instantiateVariableFont(font, {"wght": wght}, inplace=True)
    # pyftmerge has no VarStore merge logic — strip leftover variation tables.
    for tag in ("fvar", "gvar", "avar", "STAT", "HVAR", "VVAR", "MVAR"):
        if tag in font:
            del font[tag]
    gdef = font.get("GDEF")
    if gdef is not None and getattr(gdef.table, "VarStore", None) is not None:
        gdef.table.VarStore = None
    return font


def advance(font: TTFont, ch: str) -> int:
    cmap = font.getBestCmap()
    gname = cmap.get(ord(ch))
    if gname is None:
        return -1
    return font["hmtx"][gname][0]


def merge_weight(wght: int, out_path: str) -> None:
    os.makedirs(TMP_DIR, exist_ok=True)
    insts = {"hanken": instance(HANKEN, wght), "noto": instance(NOTO_SANS, wght)}
    # pyftmerge crashes on one-sided tables (BASE carries a VarStore it can't
    # merge; vhea/vmtx/BASE are Noto-only) — restrict both to the shared set.
    shared = set(insts["hanken"].keys()) & set(insts["noto"].keys())
    required = {"head", "hhea", "maxp", "hmtx", "cmap", "glyf", "loca", "name", "post", "OS/2"}
    missing = required - shared
    if missing:
        sys.exit(f"FATAL: essential tables not shared by inputs: {sorted(missing)}")
    paths = []
    for base, inst in insts.items():
        for tag in list(inst.keys()):
            if tag != "GlyphOrder" and tag not in shared:
                del inst[tag]
        p = os.path.join(TMP_DIR, f"{base}-{wght}.ttf")
        inst.save(p)
        paths.append(p)
    merged = Merger().merge(paths)
    merged.save(out_path)

    # Ownership check: Latin must resolve to Hanken outlines, hanzi to Noto.
    chk = TTFont(out_path, lazy=True)
    solo_hanken = instance(HANKEN, wght)
    solo_noto = instance(NOTO_SANS, wght)
    for ch, expect_src in (("A", solo_hanken), ("z", solo_hanken), ("0", solo_hanken),
                           ("中", solo_noto), ("的", solo_noto)):
        got = advance(chk, ch)
        want = advance(expect_src, ch)
        owner = "Hanken" if expect_src is solo_hanken else "Noto"
        status = "OK" if got == want else "MISMATCH"
        print(f"  w{wght} {ch!r} -> {owner} adv={got} (want {want}) {status}")
    # Latin coverage sanity for the digits/time strings everywhere.
    missing = [ch for ch in "amber:./%-" if advance(chk, ch) < 0]
    if missing:
        sys.exit(f"FATAL: merged font missing {missing!r}")
    print(f"  wrote {os.path.relpath(out_path, ROOT)} "
          f"({os.path.getsize(out_path) / 1024 / 1024:.2f} MB)")


def build_mono() -> None:
    for wght, out_name in MONO_WEIGHTS.items():
        out_path = os.path.join(OUT, out_name)
        inst = instance(JB_MONO, wght)
        inst.save(out_path)
        print(f"  wrote {os.path.relpath(out_path, ROOT)} "
              f"({os.path.getsize(out_path) / 1024:.0f} KB)")


def build_serif_subset(charset_cps: set) -> None:
    out_path = os.path.join(OUT, "NotoSerifSC.otf")
    opts = Options()
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.recalc_bounds = True
    font = TTFont(NOTO_SERIF)
    ss = Subsetter(options=opts)
    ss.populate(unicodes=sorted(charset_cps))
    ss.subset(font)
    font.save(out_path)
    chk = TTFont(out_path, lazy=True)
    cps = set(chk.getBestCmap().keys())
    missing = sorted(set("中文阅读衬线«»“”‘’—…·") - {chr(c) for c in cps})
    print(f"  wrote {os.path.relpath(out_path, ROOT)} "
          f"({os.path.getsize(out_path) / 1024 / 1024:.2f} MB), cps={len(cps)}, "
          f"sample-missing={missing}")


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    # 清掉上次失败/中断遗留的中间产物(TMP_DIR 在打包目录内,绝不能进 HAP)
    shutil.rmtree(TMP_DIR, ignore_errors=True)
    sans_cps = set(TTFont(NOTO_SANS, lazy=True).getBestCmap().keys())
    print(f"Noto Sans SC subset charset: {len(sans_cps)} codepoints")

    try:
        print("AmberSans merged weights:")
        for wght, out_name in WEIGHTS.items():
            merge_weight(wght, os.path.join(OUT, out_name))

        print("JetBrainsMono extra weights:")
        build_mono()

        print("NotoSerifSC subset:")
        build_serif_subset(sans_cps)

        total = sum(os.path.getsize(os.path.join(OUT, f))
                    for f in os.listdir(OUT) if os.path.isfile(os.path.join(OUT, f)))
        print(f"rawfile/fonts total: {total / 1024 / 1024:.2f} MB")
    finally:
        shutil.rmtree(TMP_DIR, ignore_errors=True)


if __name__ == "__main__":
    main()
