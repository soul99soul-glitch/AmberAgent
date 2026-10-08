# P1 独立设备验收

2026-09-30，Pura 90，1320 × 2856，HarmonyOS 6.1.1 / API 24。官方 emulator list 核实本实例 running ↔ `127.0.0.1:5555`，其他模拟器 stopped；未操作真机。

测试包为 `/private/tmp/novel-validation-source/harmony/entry/build/default/outputs/default/entry-default-signed.hap`。包中小说源码与当时 P1 产品源码一致，但快照恢复了无关 DeepRead 中间态，因此本报告不代表最新整个工作树构建通过。

## 已实际验证

- 从生产首页继续 `NovelPhaseCheck-0930` 后，工作区保留 `Draft-P1-keep-me` 草稿；失败恢复输入后离页再入仍保留相同文本。
- 使用本机 mock OpenAI SSE 提供方 `http://127.0.0.1:18088/v1`，gpt-4o 选择，实际生成三段中文候选。属于传输、调用链和 UI 验收，不是云模型质量验收。
- 默认整章候选为新开章节，第 1 章。
- 初始 3/3 段，取消第二段后 2/3，自动稿同步去掉第二段。
- 人工编辑为 `Manual-P1-preserve` 后取消首段，显示 1/3，但人工稿保持。显式“按当前选择重置”后稿件变为仅第三段。
- 清空选择后显示 0/3，UI dump 中“收录” `enabled=false`。
- 人工稿状态点击“取消”弹出明确放弃确认，选择“继续编辑”保留稿件和选择。
- 收录编辑器键盘开启后，取消和收录按钮均在键盘上方可见。
- 全选后实际收录第 1 章成功，阅读器显示三段完整内容，55 字；上一章/下一章按钮与底部手势区有间隔。

## 已确认缺陷

1. 作者输入消息也显示“收进章节”按钮。root 已查证 domain 只允许 assistant + write，交给 host 精准修按钮条件。截图 `p1-generated.jpeg`。
2. 阅读器“编辑本章”正文输入框打开键盘后，字数、取消、保存按钮被遮住。截图 `p1-reader-keyboard.jpeg`，UI dump 中按钮不可见。交给 host 修本 sheet 布局，等待新包复验。

## 环境边界

- 本轮只有一次独立 Pura 启动，没有 reset、卸载、第二次 coldboot 或全局 HDC 重启。
- HDC 间歇延迟约 30 秒；普通 uptime 和 dump 也同样迟滞。仅此 TCP target 进行一次 remove/reconnect，未将此延迟归因于小说 UI。
- 从阅读器触发整章重新生成，首次在 TCP reconnect 后失败；重挂 reverse port 后再次失败。host mock `/models` 健康且 `hdc fport ls` 可见 Reverse task，但仍出现 transport error，同时普通 UI swipe 超过 45 秒。来源替换默认值未能实际观察，不能据此判定产品缺陷或声称这条调用已验收。
- 配置页第一次验收发现此前 URL 被输入插入为拼接字符串；通过可见 UI Ctrl+A 整替换并保存后恢复成功。此为验收输入操作错误。
- 尚未完成中文 IME 预上屏提交、全部脏关闭入口、长候选/长标题、润色/重生成来源默认替换、续写并入目标、分支草稿隔离、活动代笔展开与资料编辑器截图。

## 截图

- `p1-workspace-resumed.jpeg`：生产工作区和草稿。
- `p1-generated.jpeg`：mock 候选及作者错误收录按钮。
- `p1-collect-full.jpeg`：收录完整布局。
- `p1-collect-keyboard.jpeg`：收录键盘与底部操作。
- `p1-collect-dirty-close.jpeg`：脏关闭确认。
- `p1-reader-body.jpeg`：收录后的阅读器。
- `p1-reader-keyboard.jpeg`：阅读器键盘遮挡缺陷。
