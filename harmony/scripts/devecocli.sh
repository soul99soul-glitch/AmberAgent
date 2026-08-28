#!/usr/bin/env bash
# devecocli 封装 — 绕开 PATH 里 DevEco Studio 自带的 Node 18(devecocli 需 Node >= 20)。
# 用法同 devecocli: ./scripts/devecocli.sh docs search AppStorage
exec /opt/homebrew/bin/node /opt/homebrew/bin/devecocli "$@"
