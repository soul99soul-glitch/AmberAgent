# P7 小说代笔与裁决卡独立审查

2026-10-03。范围仅 `NovelGhostwriteCard` / `NovelChapterVerdictCard` 的动态 Props/State、按钮 gating 与本次设备卡片证据。生产只读，不构建或操作设备。

## 当前判定

**本范围 source + device 审查全部通过，报告冻结。** 独立源码回归 **7/7 通过**；设备证据确认原同卡冻结问题关闭、展开后详细审稿文字可读、任务完成后第 5 章已出现在正文目录。裁决「查看/收起」热区的精确尺寸问题已由 owner 仅补 `minWidth:44`，最终包同控件实际 AX 均为 154 × 154 px，已关闭，无剩余 pending。

## 源码审查

旧编译缓存中的 observer callbacks 直接捕获 builder 参数 `done/total/enabled/expanded/review`；父卡仍挂载时，这些 callback 保持旧值。新源码动态 job/review/plan/candidate/progress/readiness 与展开文本都在 UI 表达式中读取当前 `this.@Prop/@State`。旧点击回调也检查当前 `busy/canStart`，不会继续使用先前 enabled 快照。

审核 findings 的行 key 包含实际 message/location，必要性明确：同 index/kind/code 的下一轮审核如果改变文字，旧 key 会复用闭包捕获的旧 finding；新 key 精确随显示事实改变，同内容保持稳定，无新状态存储。

当前永久 mounted-callback 回归 5 项，独立探针 2 项，共 **7/7**。独立探针额外验证已挂载的 plan/model disabled reason 替换、旧开始回调的实时 busy 拒绝，以及实际 findings key 的同 code 新 message/location 行替换。日志 `/tmp/p7-novel-card-independent-review.log`，探针 `/tmp/p7-novel-card-independent-review.cjs`。

## 设备动态链与布局

已逐图和 AX 核对 `device/p7/`：

- `ghostwrite-frozen-update-repro.jpeg/json` 的旧卡仍显示第 5 章「正在写作」，对应原 callback 冻结缺陷。
- `ghostwrite-fixed-initial.jpeg/json` 显示同第 5 章、同冻结计划 `nv_murhpm3v_1_1aqyle9i:plan:5`，持久失败原因和重试按钮真实可达。
- `ghostwrite-same-card-reviewing-fixed.jpeg/json` 中，同冻结计划卡更新为「正在联合审稿」，AX 状态 bounds `[896,1301][1149,1350]`；展开裁决标签实时显示「收起」，此前仅「查看」的状态已改变。未靠退出重开证明更新。
- `verdict-expanded-fixed.jpeg/json` 滚到完整审核细节：重写说明、`P7_LOCAL_REVIEW` finding、位置、状态增量与情节状态均可读；重试/取消按钮在图内并列，AX 为 `[617,1492][869,1646]` 与 `[897,1492][1149,1646]`，文字、边框及两按钮间距清楚，无相互遮挡。
- `ghostwrite-same-card-completed-fixed.jpeg/json` 回到下一次启动卡并显示计划需处理，符合 `creation.ghostwriteJob` 只返回非 completed/cancelled job。`NovelWorkspacePage.pollGhostwrite` 在 active job 变 null 后读取 `latestGhostwriteReport` 并刷新 project；不能将该 UI 切换误判为持久任务丢失。
- `ghostwrite-success-chapter5.jpeg/json` 明确显示「共 5 章」，目录第 05 项 `Local draft chapter` / `51 字`；新章与原第 3/4 章共同可见。截图中的整章润色卡属于之前第 4 章任务，不是第 5 章代笔 receipt，应避免混用证据。

暗色卡边界、左右文本列、计划/候选查看按钮与裁决壳均在本次图内对齐。详情长行自然换行，宿主滚动后尾部与动作可达，未发现该范围的新错位或正文遮挡。

## 精确尺寸问题与最终复验（已关闭）

`ghostwrite-same-card-reviewing-fixed.json` 的裁决「收起」bounds `[964,2304][1091,2458]` 为 127 × 154 px；同图计划「查看」182 × 154 px。44 vp 高对应本机约 3.5 px/vp，故裁决宽约 36.3 vp。源码 `Body` 仅 `minHeight:44` 与左 padding 12，符合该实际尺寸。

owner 已仅在该裁决 Text 的 `constraintSize` 增加 `minWidth:44`，保持 padding、对齐与回调原行为。源码已独立确认。最终 root 构建/签名日志为 `/tmp/amber-p8-final-build.log`；本 reviewer 独立读取实际最终包图/AX：

- `verdict-44-final-header.jpeg/json`：第 6 章裁决「查看」bounds `[937,2402][1091,2556]`，154 × 154 px，达到 44 × 44 vp。
- `verdict-44-expanded-real.jpeg/json`：同第 6 章裁决「收起」bounds `[937,1782][1091,1936]`，154 × 154 px。浅色下长 candidate/plan 标识自然换行，finding、位置、stateDelta 与 plotState 可读，未发现文字挤压、横向溢出或相互遮挡。

本次第 6 章仅证明最终按钮尺寸与展开排版；前述第 5 章同卡状态流转和完成收录是独立历史证据，不合并章节或借第 6 章失败状态否定第 5 章已完成事实。本 reviewer 不修改生产源码。

上述设备请求采用本地 controlled model fixture，证明真实已安装 UI 与生产调用链，不能据此声称外部云模型成功。
