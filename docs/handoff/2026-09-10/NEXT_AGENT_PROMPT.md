# 给下一位 AI 的接手 prompt

请接手 AmberAgent Android 追齐 iOS 的持续实施任务，从已有工作树继续，不要重做整轮调研、重置仓库或覆盖已有改动。

**工作目录**：`/Users/mi/Downloads/AI/AmberAgent/android`。

**先完整阅读交接入口**：
`/Users/mi/Downloads/AI/AmberAgent/android/docs/handoff/2026-09-10/README.md`

然后按交接里的顺序阅读：

1. `docs/plans/2026-09-09-android-ios-parity-execution.md`（当前阶段状态和实际验证）；
2. `docs/plans/2026-09-09-android-ios-parity-plan.md`（W01–W19 和扩展范围）；
3. `docs/audits/2026-09-09-android-ios-parity-research.md`（实施前双端证据，部分结论已被当前源码/台账更新）；
4. `docs/handoff/2026-09-10/phase4-protocol-agent.md`、`phase4-native-agent.md`、`phase4-ui-agent.md`；
5. 后续 Phase 5–8 的专项准备报告，路径已在交接入口中列出。

当前状态必须准确理解：

- Android 在 `main`，HEAD 为 `ab984f6df1d6b5f9bae2b007ccdcf43f1d467e62`，有大量未提交改动，开始前就有 39 项用户 WIP。本轮没有提交、推送或发布。用 `docs/audits/2026-09-09-parity-wip-baseline.json` 与交接清单识别，不能 `git reset`、全量 checkout 或撤回原有删除。
- iOS 可只读访问 `/Users/mi/Downloads/AI/AmberAgent/ios` 和 `https://github.com/soul99soul-glitch/AmberAgent-iOS`。用户明确授权覆盖 Android AGENTS.md 的兄弟仓库一般限制。对照 HEAD 是 `1023e8a08f5957157226725b43dbf3e2e7078a17`，不要修改 iOS。
- Phase 0–3 已完成并经过独立逻辑/UI review；Phase 3 最终 90 项 JVM、7 项设置、17 项真实 WebView 测试通过。不要把保留的历史失败日志/黑屏旧截图当作尚未修复，也不要将合成测试当成真实 SSO。
- Phase 4 因用户主动暂停交接而未完成。Q03/Q04/Q05 的页面改动、资源和 helper 测试已落盘，**没有经过编译/运行/独立复审**；MiniApp 协议、原生系统能力、handler 和 speech owner 均尚未落码。TTS 拟拆分但没有真正派发过，不要等旧 agent 交付。
- Phase 5–9 尚未实施。包括账号/SSH/系统入口、手机集成、健康与提醒、手表伴侣、全产品最终验收。不能把 E01/E02 静默删掉，也不能到手机部分做完就宣布全部完成。

请现在恢复实施：先核对真实文件和交接清单，编译/定点验证已落盘的 Phase 4 UI 半成品，然后按已有边界完成协议授权、真实 native owner、Runner 生命周期和 SDK 接线。现有 ZXing、Room grants/audit、Settings、Keystore、WorkManager 等能复用就复用。新能力未决授权须确认并持久化；发现阶段不能弹授权；分享面板打开不等于分享成功；未知副作用不能伪报成功或泛化自动重试。

按 Phase 4 → 5 → 6 → 7 → 8 → 9 逐阶段推进。每个阶段完成后，使用独立 subagent review：核查入口/配置→执行 owner→持久化调用链，失败/取消/重启/返回之后是否闭环，同时检查 UI 错位、对齐、边距、间距、控件大小、大字体和键盘。只修有证据的明确问题，定点复验，阶段门通过后进入下一阶段。不要过度防御、过度兜底、过度设计；不要写仅复制实现或源码 grep 的测试来冒充接线验证。

独立并行工作按文件分工，主任务统一负责整合及串行 Gradle/设备操作；无需复用前一会话的 agent ID。用户已授权明确、可回滚的实施和检查，不要反复问“是否继续”。如遇真正外部账号/设备缺失，先完成不依赖它的实现和验证，诚实记录证据边界，不编造通过结果。

环境要点：

- 全程中文，定期给简短、具体的进度。遵守当前仓库 AGENTS.md；新会话按其 Ponytail 规则激活一次。
- 构建曾遇 KSP 增量问题，使用 `--offline --console=plain -Pksp.incremental=false`；不要为环境问题大改构建配置。`RouteActivity.kt` 保持 CRLF。
- **所有设备操作只能选定合成模拟器。不能无选择运行 connectedDebugAndroidTest，不能操作连接的个人手机。** 使用 `adb -s emulator-5554`，先核对 serial/AVD，再 `install -r` 与直接 `am instrument`。
- 本次临时 HTTP 服务、adb reverse 和测试模拟器已停止/清理；模拟器关闭前 font_scale=1.0。原 AVD 是 API35 `od_mobile_test`，以 `-read-only -no-snapshot-save -no-audio -no-window` 运行。恢复方法、APK/test runner 路径和脚本见交接。
- 本轮没有新增依赖或批准发布；SSH client 和 Wear transport 的选择仍是实质待解决问题。不要用 Termux 冒充 managed SSH，或用 Synara Mac 工作台冒充手机任务 owner。

持续更新执行台账，明确区分“准备/已落码/静态检查/单元测试/模拟器/真实账号或设备”，保留关键证据。完成全部阶段后再给全产品交付总结；如有无法实测的外部边界，逐项写明。
