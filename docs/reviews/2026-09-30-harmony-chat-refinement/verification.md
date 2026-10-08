# Harmony Chat 体验修正验证

2026-09-30，在现有回顾功能未提交改动上做局部修正。三个 phase 均完成独立 subagent 代码和 UI review；未提交或推送 Git。

## 完成行为

- 顶部回顾胶囊按内容宽度居中；长标题限制最大宽度，服务商/模型保留省略与箭头。
- 模型面板从顶部滑入/收回；再次点击同一服务商/模型入口可关闭。上下文占用量固定在面板顶部、思考等级固定底部；等级分段条本体为 28 vp。
- 用户气泡使用连续曲率路径；左上、右上、左下角 26 vp，右下角 6 vp。
- 思考头部和内容为同一壳；收起显示整数秒数与本次请求档位，展开显示正文。图标为简洁三圆点。历史未知档位保持未知。
- 待审批工具即使此前被折叠，追加审批时也保持展开；已闭合 think-tag 的思考结束时间不会随后续正文增长。
- 添加按钮收起时为严格 44×44 vp 圆形。联网开关保存到现有搜索偏好，图标、状态文字与 toast 明确下一次发送生效。
- 保留纵向回弹；Chat 顶部背景不随滚动改变，消息列表隐藏滚动条。Session 横向侧滑限定在既有 88 vp 操作区；删除仅左侧圆角，置顶仅右侧圆角。
- 网络 chunk 到思考、正文和 Markdown 的显示链路实时更新；保留现有显示节奏与解析机制。

## Review 与必要逻辑验证

- Phase 1：顶部布局、同入口开关、转场、气泡及添加入口 review 通过；最终顶部拖动、退出帧与侧滑限位图 review 通过。
- Phase 2：思考来源、手动折叠、自动收起、计时清理、历史档位和审批闭环 review 通过；最终三点图标和展开壳 UI review 通过。
- Phase 3：响应类型/状态、DONE、错误、取消、dataEnd 与 Markdown 更新链路 review 通过。
- 档位元数据行为测试 1 条、think-tag 首次闭合时间测试 1 条、HTTP 头状态/MIME 测试 4 条，均通过。没有新增 UI 实现镜像断言或运行全仓测试。
- 本轮改动文件定向 ArkTS lint 均为 0 error；既有大模块仍有 warning。最后三点图标和气泡文件检查无 defects。
- 最终构建和 AGC 调试签名成功：`/tmp/amber-chat-delivery-build.log`，`BUILD SUCCESSFUL in 31 s 999 ms`。工具链既有 symbol zip 链接 warning 不影响已签名 HAP。
- `git diff --check` 通过。

## 实际运行证据

独立 QA 模拟器 `127.0.0.1:5555`，本地 provider 每 4 秒发送一块，整流 40 秒，无真实服务费用。

最初查到当前运行时的 `headersReceive` 只有普通响应头，不带 HTTP 状态行；`dataReceive` 确实每 4 秒到达。修复只对明确 `text/event-stream` 立即消费，HTTP status 保持未知；JSON/plain 或明确错误状态仍等待真实 code，未伪造 200。临时诊断日志已移除。

- 成功流 request `e3928feb`：17:02:05 开始，17:02:45 DONE；[8 秒思考中间态](thinking-live-light.jpeg) 已显示内容；[DONE 前的 Markdown](markdown-live-light.jpeg) 已有标题、粗体、列表、代码块且仍有停止按钮。
- 深色流 request `1a137104`：17:03:55 开始，17:04:13 人工停止；[18 秒 Markdown 中间态](markdown-live-dark.jpeg) 已有格式化标题，思考固定为 12 秒；[停止后](cancelled-dark.jpeg) 保留部分内容并恢复输入。
- HTTP 503 JSON 响应：沿既有重试策略结束，[最终错误](http-error.jpeg) 显示在独立红色错误栏，未成为助手正文。
- [最终浅色界面](final-light.jpeg)、[最终展开思考](final-thinking-expanded.jpeg)、[顶部回弹中](top-bounce.jpeg)、[完整模型面板](model-menu.jpeg)、[模型面板退出帧](menu-exit.jpeg)、[删除限位](swipe-delete.jpeg)、[置顶限位](swipe-pin.jpeg) 均已目测并经 subagent 复核。

截图中的橙色 JSON 样建议 chip 来自本地 provider 对辅助 suggestions 请求统一返回回顾 JSON，与取消及 HTTP 错误正文无关。深色验证覆盖消息区；人工写入 QA 主题偏好下，系统状态栏图标仍为黑色，本轮没有将此证据扩展为整屏系统主题验收。

## 真机交付与清理

- 真机 `6HK0226627000384`：覆盖安装最终调试签名 HAP 成功，`aa start` 成功；[实际首页](device-home.jpeg) 与[现有 Chat](device-chat.jpeg) 已截图目测。
- HAP：`harmony/entry/build/default/outputs/default/entry-default-signed.hap`。
- SHA-256：`038ca703ea38c33355c821a09f230a9542b417f6ef257fbeee955b7a8bb1ad5c`。
- 真机保留原有数据，未写入 QA fixture，未主动发起真实服务商生成请求；真实服务商的网络与动态性能验收不包含在本轮本地 SSE 证据中。
- 模拟器仅删除已知 `recap-qa-conversation` 及其节点，原会话数量恢复为 0；原设置备份与恢复结果 SHA-256 均为 `3286dbf5eacc9482499913c6586c75afaede557fe577cda341b54f0f87747c79`。
- 已移除 18881 反向转发并停止本轮 QA provider；原有小说数据未改动。
