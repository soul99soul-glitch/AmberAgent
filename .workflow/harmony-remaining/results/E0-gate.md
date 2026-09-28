# E0 gate — D3–D8 独立补审与修复

2026-09-28。三份独立报告：E0-review-D3-D4、E0-review-D5-D6、E0-review-D7-D8。结果 PASS。A/B及已提供的设备截图范围C通过，备份两字段显隐交互最终独立复审通过，关闭阶段。

## 修复范围

- 余额请求切换/旧响应、provider导入真实验证及非法模型拒绝、CardGroup builder崩溃、分类备份真实 wire/scope/部分恢复反馈。
- Jev候选ID与batch绑定、Council动态席位容量与取消/生成门、子代理终态呈现及模型行52vp；飞书六件工具permission与signal到真实request端。
- 本章plan/this-chapter.md审批→CAS写回/receipt闭环、plain与Markdown复制语义；Mermaid ready/revision/实例ID、长图显示及真实分片保存。
- ChatPage九张sheet稳定独立宿主，导出动作真实可达；长对话图片按屏幕像素分页，所有像素连续覆盖，尾页平衡，避免无内容最后一页。PhotoAccessHelper/PixelMap及时release。
- 实际暗图审查发现眼睛/子代理placeholder/Mermaid箭头不可读，局部颜色修复。备份眼睛首修的Builder按值冻结由实际dummy输入点击复现，改成内部直接读写State，不新增组件或兜底。
- 修复测试fixture与真实lint闸门：tool_loop重复参数按真实loopguard区分，observable用fakeclock；lint合法recommended规则，分引擎/退出码/内部失败真实判定。

## 中央验证

- chat全域2160/2160（/tmp/harmony-remaining-E0-chat-tests.log），后续导出分页改动同命令再次全域2165/2165（PTY session77621）；deepread792/792（/tmp/harmony-remaining-E0-deepread-tests.log）。两域tsc PASS；新增实际export/helper/host同组18/18，native消费者另10/10记录E1，不重复求和。
- 33改动文件同规则精确lint，LINT_CHUNK=4全部9批exit0，0error/1910warnings，/tmp/harmony-remaining-E0-lint-small.log；大批SDKarkPerfCheck真实深递归卡住，保留sample并只终止该进程后分批，未删除规则或谎报第一次通过。其后精确小改动再窄lint0error，既有性能warn保留。
- 最终Release签名构建 /tmp/harmony-remaining-E0-final-build.log SUCCESS 6.527s，已有AGC配置签出HAP；模拟器/真机install-r均成功。E1融合HAP同时含原生计数；分阶段提交仅拆已审代码，不在工作树回滚E1。
- Provider导入深浅截图与picker取消真实可达无崩溃；子代理deep/light模型/placeholder、Council主页面deep/light布局通过；Council空deepread模型库门真实显示，不把它当settings.enabled=false验收。
- 真实Chat长按→动作→导出→图片→系统确认保存；100段对话多页首/中/末像素连续，尾页含90–100/END与圆角。Mermaid小图1页/长图4页真实保存，0–25与连接完整；最终暗色PNG右边原生bar消失。原始截图/tmp/e0-chat-balanced-{first,page9,tail}.jpeg，/tmp/e0-mermaid-{first,page2,page3,tail}.jpeg，/tmp/e0-mermaid-final-png-tail.jpeg；最终theme亮暗footer单独已审。

## 验收层边界

- helper/调用链审查不是每个UI状态实机验收。导入选中文件/实际备份restore、活动条全部终态、reasoning两态图片/剪贴板两态、多MarkdownText组合、取消缓存实测、横屏/分屏仍未逐态拍摄；不得扩称。
- 像素分页保持完整连续覆盖，可在字形内分页，未做语义分页。1910既有warning不扩大成顺手重构。
- 小说专用提案diff/章节裁决UI仍E13，所有缓做子系统仍按E1–E14推进，历史D裁定不等于产品完整。

最终非空dummy显隐闭环六图已独立复审，备份亮暗及WebDAV亮色明文/遮蔽对应、图标同步，report最终C PASS。最终E0截图均为模拟器127.0.0.1:5555（API24）；签名HAP同时安装并启动真机HOP-AL00（API26），不将模拟器截图称作真机视觉证据。
