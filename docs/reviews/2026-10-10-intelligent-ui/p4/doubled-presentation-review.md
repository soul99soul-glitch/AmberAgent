# 样式预设 13 → 26

- column 6 种：plain、group、outlined、elevated、callout、section。
- text 11 种：body、heading、subheading、caption、metric、code、badge、hero、lead、eyebrow、quote。
- choice 4 种：list、chips、tiles、segmented。button 4 种：primary、secondary、ghost、pill。input 新增 underline，省略保留原填充样式。
- 与现有 stack/row/grid、五种语义色独立组合；未新增依赖或可执行脚本。
- 已接通严格解析、模型指令、原生渲染和导出。新增一项覆盖全部 26 种 preset 的解析→收集→导出回归检查。
- 23 项域层/模型提示检查、6 项宿主/fixture 检查通过；ArkTS lint 无 defects，最终构建成功。
- subagent review 两项发现已修：长 chips 标签收缩换行；outlined 非中性色语义生效。复审通过。
- 样式 gallery 含全部 13 种新样式及长标签，模拟器截图确认实际差异；另调整 tiles 高度对齐、segmented 上下留白。
- 零售真机已覆盖安装，保留数据。未跑全量回归、所有排列、深色主题矩阵或性能基准。

最终检查：下划线输入框写入 umbrella，分段选择技术细节，点击胶囊按钮后草稿为“请用 技术细节 解释 根证书。补充：umbrella”，断言通过，未发送。真机截图见 doubled-presentation-phone.jpeg、doubled-presentation-phone-top.jpeg。

改动文件：smart_ui/protocol.ts、generative_widget_prompt.ts、NativeSmartUiNode.ets、ChatVisualFixture.ets、smart_ui_protocol.test.ts。本轮沿用已有 variant/layout/tone 快照复制逻辑，无需重复修改导出。
