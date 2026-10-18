# 远端合并冲突独立有限复核

结论：通过。对本次冲突解决及直接适配做二次核对后，没有确认需要修复的可达回归。

范围：隔离工作树 `/tmp/amber-review-round2-delivery-20261018` 中的 `NativeSmartUiCard.ets`、`NativeSmartUiNode.ets`、`native_smart_ui_card.test.cjs`。分别对照 index stage 2 的本地版本、stage 3 的远端版本与当前文件；只跟读直接调用链上的 WidgetMessageContent、typed component、overlay 及组件目录导出，没有审查整个远端提交。

## 已核对的合并结果

- 本地 typed component 渲染分支和 `NativeSmartUiComponent` 桥接保留；递归节点继续传递 controller、values、invalidStateIds、draftEnabled 和回调。`componentKindAllowsChildren` 已通过 domain index 导出，新增测量逻辑没有产生缺失导入。
- Card 保留本地 `applyReconciliation()` 的等值快照稳定处理、重复编辑去重、无效编辑向页面快照报告的路径及 reset 状态更新；没有退回远端较旧的直接赋值实现。
- 草稿事件继续使用稳定 nodeId：Node → Card → WidgetMessageContent → `onSmartUiDraft(nodeId, sourceKey)`。reset 继续走 Card controller 与页面状态回调，没有混入远端较旧的 prompt 字符串事件语义。
- 本地主题纪元 `ForEach([themeEpoch])` 子树重挂载保留；没有被远端 no-op opacity 消费替代。
- 远端 completion eligibility、source key ledger、延迟测量、前后台/可见性/reduceMotion、触摸取消和 overlay input transparency 均保留。Card 离开时现在通过 `finishReveal()` 同时清除起播定时器、pending 与 active，保留已经实际播放过的 ledger 消费状态。
- source 变化先清测量、取消旧 reveal 且不消费新 source，随后等待新 source 的面积回调；没有将旧 source 的几何或 pending timer 复用于新卡片。
- 新 typed component 叶节点会上报窗口坐标，允许子节点的 component 容器不作为整块 reveal hole；子节点消失会清除测量。该处理避免容器矩形提前擦除全部后代遮罩，也保留子控件逐项 reveal。

## 验证

独立运行 `node --test harmony/entry/src/test/native_smart_ui_card.test.cjs`，15 项全部通过，退出码 0。新增方法回放实际覆盖 completion eligibility、起播延迟、一次性消费、历史内容/reduceMotion、前后台、离开清理、source 切换和 component 测量/销毁清理；本地原有状态边界、稳定 ID 回调、weather projection 检查仍保留。

本复核没有发现需列入问题清单的真问题。以上是源代码合并与 Node 方法回放证据，不替代 ArkTS 编译、模拟器可见性/触摸/主题的实际运行结果。

本次未修改产品代码，未暂存、提交或推送；只新增本复核记录。

## 追加：ui_generation_motion 测试装配修复

复核通过。当前未暂存差异仅将 `harmony/entry/src/test/ui_generation_motion.test.cjs` 的 `require('typescript')` 改为 `require('../../../chat/node_modules/typescript')`，与相邻 `native_smart_ui_card.test.cjs`、`file_store_io.test.cjs` 使用的路径一致。

独立以该测试文件为 CommonJS 解析起点、清除 `NODE_PATH` 后执行 `createRequire().resolve()`：裸模块名 `typescript` 返回 `MODULE_NOT_FOUND`，相对路径能解析到 chat 模块现有 TypeScript 包。标准 `npm run test:harmony` 调用的 `scripts/test-harmony.mjs` 也没有设置 `NODE_PATH`。因此该一行修改修复的是可复现的测试依赖解析问题，有必要保留。

没有修改生产源码、测试目标、transpile 参数或断言，产品行为不变。主 agent 另已报告无 `NODE_PATH` 的该文件 4/4 通过，以及显式 `NODE_PATH` 的完整回归 4882/4882 通过；本次追加复核独立验证的是导入差异和模块解析事实，未重复运行套件。
