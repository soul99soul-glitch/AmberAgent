# P7 最终触控尺寸修复

Root 的实际设备截图/AX 已确认两处触控尺寸不足 44vp：`device/p7/compose-long-title-keyboard` 中 Compose 主题输入框默认 40vp（实际 140px）；`device/p7/regenerate-longtitle-light` 中重新生成确认弹窗两动作约 41.7vp（实际 146px）。本次仅修复这些确认事实，不调整周边排版、文案、领域或调用行为。

- `pages/DeepReadComposePage.ets`：主题 TextInput 增加 `.height(44)`。
- `components/DeepReadConfirmationDialog.ets`：取消与重新生成动作各增加 `.constraintSize({ minHeight: 44 })`，保留原 padding、同行布局、分隔线、确认与取消回调。

现有 `deepread_compose_capture_ui.test.cjs` 和 `deepread_reader.test.cjs` focused 回归 **31/31**；前者覆盖创建/模板与陈旧生命周期，后者覆盖读页重新生成、续跑和弹层返回等现有路径。仓库没有单独 ConfirmationDialog focused 测试，此低影响尺寸改动未新增源码镜像测试。日志：`/tmp/amber-p7-final-touch-tests.log`。

两文件 scoped ArkTS lint **0 errors / 2 warnings**，均为既有性能建议（Compose 文件读取循环的状态读取、Dialog 小组件建议）。日志：`/tmp/amber-p7-final-touch-lint.log`。

源码冻结，已通知 root。未构建、签名、安装或操作设备；新尺寸的实际 AX/截图验收由 root 统一最后构建后复查。
