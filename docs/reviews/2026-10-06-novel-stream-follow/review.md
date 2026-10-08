# 小说流式跟随与完成交接修复

2026-10-06。产品为 HarmonyOS 独立小说创作 app.amber.novel。基线 HEAD b2d054bd7a81c64795497098d54cf30ad049e04f，工作区已有大量未提交修改；task-page.patch 和 task-composer-test.patch 相对于本轮开始前备份，限定本轮修改范围。未提交或推送。

## 原因与处理

- 旧页面每个流式 snapshot 都直接 scrollEdge，新增一行就瞬移；改为复用 Amber 已有 NativeTimelineFollow，在布局变化时由系统 List 动画跟随，真实拖动/惯性滚动暂停。原生动画停止回调不再被误认成用户离开底部。
- 生成结束才插入“收进章节”44vp按钮和6vp间距，且 live/saved 标题高度不同；生成中预留50vp按钮空间，并统一标题行高度18vp。
- 结束回调先清掉 live 正文、再异步读取保存消息，会造成空白和重建。现先读保存结果，普通流式和保存消息在同一个 ForEach 中按稳定 id 保留，正文与 Header/Footer 从当前快照读取，完成时不再销毁正文行。短文未暴露的长文列表锚点丢失，由36段实际测试发现；offset补偿无效，已移除。
- 只在首次打开或切换分支时触发初始定位；暂停阅读状态保留。回到底部按钮从不能正常刷新的 overlay builder 移到同级 Stack，实际可见并可恢复跟随。
- 结束事件同步重放不再恢复过期订阅；项目读取失败时保留最后正文并提供重载入口，同时释放已结束任务。工具续接沿原消息模式计算按钮预留。

## 验证

- 本轮新增生产方法/实际UI回调回归，先观察失败后修复；相关小说页面、导入、草稿、原生跟随/滚动归因检查共198项通过。使用仓库的 tsx/tsconfig 环境运行，未删断言。
- ArkTS lint：当前页面无缺陷。独立小说 assembleApp 成功，使用原有 AGC 签名，verify-app 成功。
- 模拟器：任务专用 Amber Novel Scroll QA，HarmonyOS API24，1264×1848。通过独立本地 QA OpenAI SSE 服务输出36段中文长文，避免改动用户真机 Provider 或项目。
- 原始短文完成时两段正文均向上移动43px；按钮预留修复后的短文移动0px，见 terminal-geometry.json。最终稳定行渲染的36段长文，结束前后33–36段 bounds仅有1px差异，见 final-long-geometry.json。
- 长文持续跟随由第6段推进到第17段，系统 trace记录71次原生动画滚动，见 native-scroll-events.txt；该追踪来自原生跟随修正后的中间包，最终包沿用同一驱动。不能据此宣称真机FPS或所有模型性能。
- 手动阅读前文时后续快照不改变已读段落位置；点击“回到底部”后恢复到当时的第20段，并继续生成到第36段。截图 final-paused.jpeg 来自稳定行整理前、按钮修复后的包，手势路径保持相同。
- 最终包暂停阅读的完成交接对照见 final-paused-long-*；验收结果补充于本文件末尾。

## Review 与交付边界

只读 subagent review核对订阅、模式来源、读取失败释放和新合并行的字段新鲜度，发现的问题均已处理；新合并后的行数计算也按同一消息序列同步，避免带提案/工具的末尾索引错误。普通新生成保存时领域沿用UI id，可保留列表项。快速开始仍使用专用结构化尾。

签名HAP：/tmp/amber-novel-scroll-20261006/novel-scroll-final-signed.hap
SHA-256：863a330b27d42e6f9b5ba58a6df2b5100151e434c2fae49a8ae417cdccaa0bc8

当前HDC仅连接本轮模拟器，尚未覆盖安装真机；没有完成用户真实模型与真机滚动帧率验收。构建、签名、模拟器安装启动及模拟流式UI验收分别记录，不能相互代替。

最终包暂停阅读完成验收：6段可见正文的 bounds 完全一致，移动0px。

同一个最终签名HAP再验证36段持续跟随：结束前后33–36段仅向下1px，收录按钮占用预留空间，无长文锚点跳回。见 final-package-geometry.json 与 final-package-drained/done.jpeg。
