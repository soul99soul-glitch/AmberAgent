# 模板与来源：排版及动效细节补修

2026-10-05。本轮只修改模板列表、兼容 Workbench route wrapper、共享 Workbench、来源页及相应行为测试。保留其他 worker 的改动。对照当前只读 iOS `Sources/UI/DeepReadTemplatesView.swift`、`DeepReadTemplateGeneratorView.swift` 与 `DeepReadDetailView.swift` 中的 `DeepReadSourcesView`；与 spec review 核对选择效果、现有几何尺寸和预览草稿消费，未改变已确认的 sheet、dirty、AI push、生成或存储契约。

## 模板选择

- iOS 内置模板图标使用 `symbolEffect(.bounce)`，check 使用 scale + opacity，默认模板 ID 变化使用 `.snappy` 和 selection 触感。Harmony 在默认 ID 写入成功且值确实变化后，执行一次 280 ms 图标 keyframe：100 ms 缓出到 1.15 倍、上移 3 vp，180 ms 弹簧复位；check 在 220 ms 弹簧 scale/opacity 过渡中出现或消失。卡片与文字不放大。
- 内置行 ID 改为稳定模板 ID；自定义行仍包含名称、描述与更新时间，保留同 ID 编辑后更新的能力，但不再因选中 ID 改变重建整行，使 check 有真实连续过渡。
- 主 agent 提供的 `DeepReadHaptics.selection()` 仅在实际成功且变化、当前可见的独立模板选择触发。重复点击、写入失败、宿主、隐藏页面和旧生命周期结果均不触发。本轮不修改马达调用实现；减弱动效不禁用 selection 触感，与 iOS 的 sensoryFeedback 使用方式一致。
- 没有定时器或无限播放。减弱动效时不启动 keyframe，check 过渡时长为 0。离页、隐藏、后台、主题及减弱动效切换清除当前 bounce 的 ID/位移/缩放；frame 与 finish 捕获 motion ID，旧回调不能覆盖新选择。

`keyframeAnimateTo` 已通过本地官方文档确认自 API 11 支持；`curves.springCurve` 自 API 9 支持，时长受动画参数控制。均在 API 12 基线上，不使用超基线动画字段。图标 keyframe 是对 iOS 选择 bounce 行为的 ArkUI 表达，不能把未经逐帧核验的系统曲线称为像素或时序完全一致。

## 来源及编辑器可见细节

- 来源标题按 iOS `.headline` 使用 17 vp / 600 权重。折叠控件保留同一 chevron，展开时旋转到 90 度，200 ms 缓出更新展开状态，正文有 160 ms opacity 过渡；减弱动效和后台直接更新终态。操作与动画闭包继续受 alive/page token 限制。
- 原文、状态、错误、浏览器 URL 校验、复制和来源订阅保持原链。新增动画不改变当前只展开一个来源的既有交互契约。
- Workbench 操作行在 disabled 状态使用 muted 图标及文字颜色，覆盖生成中、空要求、保存中和尚未加载等真实禁用条件。原 HTML 编辑器、预览、保存和取消行为不变。iOS AI Form 未声明装饰性 loop，本轮没有新增 AI 循环动效。

## 设置草稿到模板预览

与设置 worker 统一 optional route 参数 `readerFontMode: 'default' | 'serif'`、`readerFontScale: number`（70...180 的百分比）。模板列表校验参数，并向编辑 sheet 的共享组件传 `previewFontMode/previewFontScale`；AI push route 及其生成结果编辑 sheet 继续传递这两个显式预览输入。无参数或无效参数保持读取现有 KV；显式有效草稿在晚到 KV 回调之前及之后都优先，且不写入 KV、AppStorage、模板内容或文章参数。正文仍按其既有持久化设置渲染。

## 验证与边界

定向测试覆盖原模板/模型/来源行为，以及成功写入后的一次选择效果、重复/失败无效果、减弱动效/宿主/隐藏/后台直达终态、旧动画帧/finish 及旧持久化回调隔离；来源 disclosure 实际状态与旧 callback；显式两种字体草稿胜过晚到 KV、无效或无参数沿用既有偏好，以及草稿参数经过 typed route 到 AI 页面。

```sh
node --test harmony/entry/src/test/deepread_template_ui.test.cjs \
  harmony/deepread/src/test/deepread_template.test.cjs \
  harmony/deepread/src/test/deepread_model_selection.test.cjs \
  harmony/deepread/src/test/deepread_template_library.test.cjs \
  harmony/deepread/src/test/deepread_sources_ui.test.cjs
```

最终定向结果 **86/86 通过**；四个修改的 ArkTS 文件 scoped lint **0 defects**，最后模板列表的弹簧曲线变更再检查亦 **0 defects**；owned `git diff --check` 通过。跨 worker 的两个设置模型 VM fixture 因新增产品身份依赖由设置 worker 补齐，原断言保留。未独立执行 build、签名、安装、设备操作或图标修改。源与行为测试不能代替动画录像、实际 UI bounds、像素或物理触感验收；这些由主 agent 集成验证。
