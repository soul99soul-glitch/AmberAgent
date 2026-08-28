#!/usr/bin/env bash
# HarmonyOS ArkTS 静态检查(CodeLinter)回喂脚本
#
# 用 Command Line Tools 自带的 codelinter 对 harmony/ 做 .ets/.ts 静态检查,
# 作为 hvigorw 编译回喂的前置快筛——能提前抓住 any / 动态特性 / 性能反模式等编译期不报的问题。
#
# 已知坑:codelinter 单次扫描约 150+ 文件会挂起(进度条停在 ~50% 不再推进),
# 故目录模式按 80 文件/批自动分批调用,结果顺序输出。
# 速度参考:单文件 ~30s;entry/ 全量 ~8-10 分钟。改代码时优先传具体文件/子目录。
#
# 前置:
#   1. HarmonyOS Command Line Tools 已安装(默认在 ~/Library/Huawei/commandline/)
#
# 用法:
#   ./scripts/lint-arkts.sh                       # 全仓扫描,发现 error 级问题退出非 0
#   LINT_EXIT_ON=warn ./scripts/lint-arkts.sh     # warn 级也计入退出码
#   LINT_EXIT_ON=none ./scripts/lint-arkts.sh     # 只出报告不设门槛
#   ./scripts/lint-arkts.sh path/to/file.ets      # 只查指定文件/目录(推荐日常用)
#
# 退出码: 0 通过;1 存在指定级别以上的问题;2 工具/环境缺失。

set -euo pipefail

# ---------- 参数 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

TARGET="${1:-$PROJECT_DIR}"
EXIT_ON="${LINT_EXIT_ON:-error}"
CHUNK_SIZE="${LINT_CHUNK:-80}"

# ---------- 工具定位 ----------
CMDLINE_DIR="${HARMONY_CMDLINE_DIR:-$HOME/Library/Huawei/commandline/command-line-tools}"
CODELINTER="$CMDLINE_DIR/bin/codelinter"

if [[ ! -x "$CODELINTER" ]]; then
  echo "未找到 codelinter: $CODELINTER (可用 HARMONY_CMDLINE_DIR 覆盖定位)" >&2
  exit 2
fi
if [[ ! -f "$PROJECT_DIR/code-linter.json5" ]]; then
  echo "缺少配置: $PROJECT_DIR/code-linter.json5" >&2
  exit 2
fi

EXTRA_ARGS=()
if [[ "$EXIT_ON" != "none" ]]; then
  EXTRA_ARGS+=(-e "$EXIT_ON")
fi

run_codelinter() {
  # bash 3.2(macOS 自带)下空数组在 set -u 中展开会报 unbound,用 + 展开惯用法
  "$CODELINTER" "$@" -c "$PROJECT_DIR/code-linter.json5" -f default ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
}

FAILED=0

if [[ -f "$TARGET" ]]; then
  # 单文件:直接跑,最快
  run_codelinter "$TARGET" || FAILED=1
else
  # 目录:收集目标文件(镜像 code-linter.json5 的 ignore 规则),按批调用
  # (macOS /bin/bash 是 3.2,没有 mapfile,用 while-read)
  FILES=()
  while IFS= read -r f; do
    FILES+=("$f")
  done < <(find "$TARGET" \
    \( -name '*.ets' -o -name '*.ts' \) \
    -not -path '*/build/*' \
    -not -path '*/oh_modules/*' \
    -not -path '*/node_modules/*' \
    -not -path '*/src/ohosTest/*' \
    -not -path '*/src/test/*' | sort)
  TOTAL=${#FILES[@]}
  if [[ "$TOTAL" -eq 0 ]]; then
    echo "目标下没有 .ets/.ts 文件: $TARGET" >&2
    exit 2
  fi
  BATCHES=$(( (TOTAL + CHUNK_SIZE - 1) / CHUNK_SIZE ))
  echo "共 $TOTAL 个文件,分 $BATCHES 批(每批最多 $CHUNK_SIZE)"
  for ((i = 0; i < TOTAL; i += CHUNK_SIZE)); do
    BATCH=("${FILES[@]:i:CHUNK_SIZE}")
    echo "--- 批 $((i / CHUNK_SIZE + 1))/$BATCHES (${#BATCH[@]} 文件) ---"
    run_codelinter "${BATCH[@]}" || FAILED=1
  done
fi

exit "$FAILED"
