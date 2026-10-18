# 原生 Smart UI 揭示冲突整合记录

- 日期：2026-10-18
- 隔离工作区：`/tmp/amber-review-round2-delivery-20261018`
- 整合对象：本轮修复提交 `37a9da867` 与远端 `origin/harmony-preview` 的 `104f07f8c`。
- 修改权责：仅 `NativeSmartUiCard.ets`、`NativeSmartUiNode.ets`，经主 agent 明确扩展至 `native_smart_ui_card.test.cjs`。
- 未修改原用户工作区生产文件，未 stage、commit 或 push。

## 决策与保留契约

1. 保留本地 controller 归属、去重 reconciliation、非法编辑快照回传、reset 持久化回调、nodeId 草稿回调、80 组件/任务/学习渲染路径，以及 `themeEpoch` 的 ForEach 子树重挂载。
2. 揭示采用远端 generation reveal：稳定来源 key 的进程内一次性 ledger，实际节点窗口坐标测量，约 680ms Canvas 覆盖层，减少动态效果、后台、可见性和触摸门控。本地旧 240ms opacity/scale 揭示由该明确的新行为替代，避免同一结果双重动画。
3. component 叶节点增加无额外间距的 Column 测量容器，组件内部用于 availableWidth 的 onAreaChange 完整保留。允许子节点的 component 容器不进入遮罩擦除框，防止整块容器提前擦掉所有子节点；仍通过原有 ChildNode 递归取得叶节点测量。
4. Node 的 aboutToDisappear 移除本节点测量，适配 tabs/disclosure/accordion 仍保留在 document 中但实际已卸载的子节点。
5. Card 的 aboutToDisappear 调用 finishReveal，统一清理开始计时器、pending 和 active 状态。尚未播放的不消费来源 key，允许再次可见时按现有资格请求；已经播放的来源仍由 ledger 禁止重播。
6. 全程使用仓库已有 API 与 V1 状态机制，没有新增 SDK API、依赖、权限或状态框架。

## 验证与回喂

自动合并遗留的最后一项测试仍提取旧 `onReadyChanged` opacity/scale 实现。首先在整合后执行单文件，观察到 10 pass / 1 fail，失败为旧测试 mock 不满足新 overlay 契约（`this.cancelReveal is not a function`）。该测试被替换为新契约的实际方法回归，而原有状态/controller/几何/原生控件行为断言继续保留。

新增/更新用例实际提取生产 Card/Node 方法执行，覆盖：

- 流中已经收到 end record 时不揭示，消息结束且取得资格后延后一轮开始。
- 准备阶段不消费 key，开始后消费，同 key 重挂载不重播。
- 已完成历史初次挂载不播放；新结果可以在首次挂载时已完成并具备资格。
- 减少动态效果直接消费并展示成品。
- 后台 pending 不运行，前台恢复；离页清理计时器/pending/active；活动揭示进入后台取消。
- 来源 key 改变清理旧测量，等待新节点布局。
- component 叶节点报告真实几何，component 容器不挖整块洞，隐藏/卸载子节点移除旧测量。
- 原有 80 组件、reconciliation 去重、reset 和草稿/状态路径测试继续运行。

最终执行：

```sh
env NODE_PATH=/tmp/amber-review-round2-delivery-20261018/harmony/chat/node_modules node --test \
  harmony/entry/src/test/native_smart_ui_card.test.cjs \
  harmony/entry/src/test/native_smart_ui_components.test.cjs \
  harmony/entry/src/test/ui_generation_motion.test.cjs \
  harmony/entry/src/test/miniapp_reveal_entry.test.cjs \
  harmony/entry/src/test/inline_miniapp_runtime.test.cjs
```

结果：**43/43 pass，0 fail**。`git diff --check` 通过。

测试装配首次运行暴露了 test helper 在允许 public 生命周期方法后误取 ledger 同名 consume 的问题；已改为优先提取 private 方法后再匹配生命周期方法。`NODE_PATH` 显式指向隔离工作区所链接的既有依赖，修正 ui_generation_motion 测试的模块解析；这些是测试装配修正，没有为此改动产品逻辑。

已按 `code-simplifier` 对本次整合差异完成一次有限可维护性检查：只有一套揭示状态与 watcher、统一 finish 清理入口、现有 controller 持续作为状态 owner，递归回调保持直接传递；没有再做额外重构。

## 边界

本记录仅证明目标方法和相关 Node 测试；主 agent 另行负责合并后全量测试、ArkTS lint、构建、签名和独立整合 review。此子任务没有执行设备安装与截图，不能以 Node 测试替代设备视觉验收。
