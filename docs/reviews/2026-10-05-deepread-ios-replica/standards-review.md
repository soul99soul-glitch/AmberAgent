# Standards 与正确性审查

2026-10-05；本轮深读 UI WIP 的只读审查。规范：`AGENTS.md`、`harmony-arkts/SKILL.md`。基线 HEAD `b2d054bd7a81c64795497098d54cf30ad049e04f`，按本轮 `/tmp/amber-deepread-ios-replica-20261005-151821` 备份排除前置 WIP。末次增量仅复验模板 description、共享 Workbench、wrapper 与 sheet。未构建、未操作设备。

## 硬规范

未发现 V1/V2 混用、可变全局 UI 状态或裸 BuilderParam 构造。根页真实纪元重挂载；newsroom/付印定时器有清理。官方本地色彩文档与 SDK `ets/component/common.d.ts` 确认 `radialGradient` 起始 API 7（`:26312`）、`BlendMode.PLUS` 起始 API 12（`:13640`），符合基线。

## 确证缺陷与修正复验

- **P2 字体缺省，已修。** `SettingDeepReadPage.ets:212` 独立版 `serif` / 宿主 `default` 与文章一致。
- **P1 模板错路由，已修。** `DeepReadArticlePage.ets:267` 使用注册页面并处理迟到失败。
- **P2 宿主已保存稿无法重试，已修。** `DeepReadComposerSheet.ets:195` 允许重新打开同一稿，测试证明不重复写库。
- **P2 Sheet 覆盖 Library 键盘恢复，已修。** `DeepReadComposerSheet.ets:77` 尊重宿主当前 mode；按 hide→disappear 顺序最终保持 OFFSET。
- **P1 编辑器 port 被深拷贝，已修。** 原 `@Prop editorPort` 导致 child 写入的 Back/leave 回调无法回传父对象，绕过未保存确认。官方 FAQ `faqs-arkui-778` 确认 @Prop 深拷贝；`DeepReadTemplateWorkbench.ets:67` 已改普通共享引用，销毁清回调，每次 sheet 新建独立 port/session。
- **P2 模板预览偏好未消费，已修。** 无 KV 时 preview 默认系统而文章默认衬线；自动预览先就绪时，迟到字号/字体也不刷新 HTML。`DeepReadTemplateWorkbench.ets:215`、`:221`、`:222` 统一缺省并调用既有门控 `loadPreview()`；缺省/显式偏好和慢偏好测试通过。

此前入口/字体/创建/键盘测试 **37/37**；最新模板 UI、存储、历史快照测试 **65/65**。当前无剩余确证阻断。

description 可选、旧数据补 `''`，HTML 与历史快照保持原值。AI 入口 push 要求页，生成后 sheet 编辑；保存或取消仅关闭 sheet，AI 要求保留。自定义编辑/预览采用稳定 sheet anchor；一次保存流程、迟到会话拒绝与宿主兼容均有行为证据。

## 判断建议与边界

无需为 smell 增加抽象。取消/恢复/旧稿保留沿用原控制方法。ArkUI 模态、主题切换与运行画面由主 agent 构建及截图验收；源码与 Node 测试不替代设备证据。
