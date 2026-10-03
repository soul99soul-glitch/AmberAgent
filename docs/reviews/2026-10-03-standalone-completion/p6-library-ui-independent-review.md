# P6 阅读库 UI 独立审查

审查范围：`DeepReadHistoryPage.ets`、其调用的真实 `observeDeepReadLibrary`／query helpers、`deepread_history_ui.test.cjs` 与旧 UI-07 返回阅读库回归。本 reviewer 未修改阅读库生产源码，也没有构建或操作设备。

## P6-LUI01：只有运行中记录时，手动刷新失败没有可见提示

**原 owner 已精准修复，独立生产反例复验关闭。** 原问题为阅读库已有刚启动、尚未形成 cache 的 run 时，真实聚合 helper 生成一条 cached=false 的可见文章行。手动刷新 listHistory(0) 失败会写入 loadError，但原 build 顶部错误提示仅在 `entries.length > 0` 时显示；此时 entries 为空而 visibleItems 有运行中行，空态错误分支也不会执行，所以失败整页不可见。

独立实际生产方法复现 `/tmp/amber-p6-library-refresh-error-review.cjs`，日志 `/tmp/amber-p6-library-refresh-error-review.log`：真实 Page lifecycle／reload 方法消费真实聚合及 query helper，再执行从生产 build 提取的错误条件。结果 `loadError=DB read rejected`、`cachedEntries=0`、`visibleRows=1`、`actualWarningBranch=false`。本复现不仿写状态机或查询逻辑。

最终修改仅将非空列表提示条件按当前 `visibleItems().length > 0` 判断，未新增状态或兜底流程。独立更新原生产反例读取最终 build guard 后，`cachedEntries=0`、`visibleRows=1`、同一 DB read error 仍保留，`actualWarningBranch=true`；日志 `/tmp/amber-p6-library-refresh-error-reviewed.log`。原 owner 新增 active-only 实际方法回归后，UI 8/8 通过，本 reviewer 已独立重跑并核对新增 case。

## 已核闭环与 UI 源码合同

- 首次显示先订阅 repository，随后异步接 scheduler 聚合 observer；模型／runtime 不可用时仅写 runtimeError，已有库和 repository observer 继续可用。
- 新 admitted run 没有 cache 时，真实 query 返回 cached=false 的运行中行。保存首稿并完成后，原 topicId 转为完成／cached=true，不伪造 cache entry。
- hide／disappear 释放 repository 和 run observers，递增 observation／load tokens；迟到 scheduler 初始化、队列 callback、旧 listHistory read 均不能改已隐藏或重开的页面。show 重新接线，query／filter 保留。
- 同页 observer 新值会使旧手动 snapshot token 过期；正文搜索及状态筛选调用实际领域查询，未截断历史数量。
- rowKey 包含 topicId、updatedAt、title、date、status、expired、cached，同 ID 标题、状态及缓存归属改变会更新行内容。创建和重读都携真实目标路由。
- 搜索输入、刷新、筛选及创建热区至少 44 vp；列表行有上下各 14 vp 留白及标题／日期，长标题允许两行省略。筛选横向 Scroll TopStart；输入聚焦采用 RESIZE，离页恢复先前键盘避让模式。没有新增 timer 或轮询。

源码尺寸声明只能验证布局合同，不能替代新包设备上的文字溢出、键盘和点击区域实测。该 gate 由 root 统一负责。

## 独立验证

- `harmony/entry/src/test/deepread_history_ui.test.cjs`：最终 8/8 通过，日志 `/tmp/amber-p6-library-ui-independent-final-tests.log`。
- `harmony/entry/src/test/ui_send_provider_lifecycle.test.cjs` 仅 UI-07：1/1 通过，日志 `/tmp/amber-p6-library-ui07-independent-tests.log`。
- 领域 library／history 原回归：7/7 通过，日志 `/tmp/amber-p6-library-domain-independent-tests.log`。以上仓库测试共 16/16；独立生产 guard 反例另外 1/1。

P6-LUI01 已关闭，当前审查范围无剩余已确认源码逻辑或调用链 blocker。单 History page lint 由原 owner 报告 NoDefects；设备视觉、真实后台任务和通知回到阅读库 gate 由 root 另行记录。只读 review 未扩展修改其他页面。
