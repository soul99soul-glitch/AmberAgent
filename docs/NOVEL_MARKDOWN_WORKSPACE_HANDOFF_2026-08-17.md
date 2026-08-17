# 小说 Markdown 工作区交接 · 2026-08-17

日期：2026-08-17  
基线：`feat/ios-provider-parity-claude` @ `e2a9ebb5c`  
适用范围：小说书/账本工作区五阶段、真机安装、远端推送卡点。不覆盖 Chat 流式、Gemini、主题、设定过滤的实现细节。

本文是时点快照，**不取代** [`PROJECT_STATE.md`](PROJECT_STATE.md) 或规格 [`superpowers/specs/2026-08-16-novel-markdown-workspace-design.md`](superpowers/specs/2026-08-16-novel-markdown-workspace-design.md)。当前事实以代码、`git status` 和 `PROJECT_STATE.md` 为准。

不取代：[`SVG_SAVE_BUTTON_HANDOFF_2026-08-05.md`](SVG_SAVE_BUTTON_HANDOFF_2026-08-05.md)（无关主题）。  
补充：`PROJECT_STATE.md` 里「小说工作区五阶段」一节；该节仍是日常入口。

---

## 一句话状态

五阶段最小闭环已提交（`e2a9ebb5c`），已无线装到 iPhone Air，**没推上远端**。作者 UI 没换。JSON 包仍是权威。收录/代笔后的「剧情状态同步」仍是旧 JSON 抽取，所以还会卡很久。真正的薄 VCS（工作树当书、指针当历史、写 `plot/` 替代抽取）还没接到主路径。

## 锁定决策（不要重新辩论）

规格已锁，不要再问一遍：

1. 书是文件；agent 用通用读写；host 只守正史闸门和版本指针。
2. 借 git 的模型（工作树、commit、branch、checkout），不内嵌真 git，不对正文做三路 merge。
3. **方案 A：会话留账本**，不做 `session/*.md`。
4. 磁盘目的地是 markdown 工作树；现在不迁打开中的书。
5. 导入永远新建 projectID；备份保书、丢会话/回执/旧检查点。
6. 创作 / 正文 / 设定、审批卡、身份卡留着。
7. 不要再默认加新的 `novel_*` 动词。

## 五阶段实际落地了什么

| 阶段 | 落地 | 没落地 |
|---|---|---|
| 1 导出 | 设定页「导出工作区」；`setting/world\|outline\|writing/` 分目录；`NovelWorkspaceFolderDocument` | zip 单独通道不是重点 |
| 2 导入 | 文件列表选文件夹 → 转成项目包 → 现有预览/导入；永远 `NovelProjectID()`；只收 `branches/<mainBranch>/` | 不覆盖原书 |
| 3 讨论虚拟树 | `novel_workspace_{list,read,grep,status,write}` | 磁盘仍是 JSON |
| 4 写 plot | 讨论里写 `plot/*.md` 出审批卡，确认后 `applyWorkspacePlot` 抄进快照并标 synchronized | **收录/代笔/编辑器保存仍走 `syncManualEdits` JSON 抽取** |
| 5 checkout | 每次 sharded 落盘写 `projects/{id}/checkout/`；无 layout 时 dual-read | checkout 不是 git；权威仍是 JSON |

阶段 4 的替换句在规格里写得很死：收录或改正文之后模型自己改 `plot/`，与正文同一笔 commit；不再抽 JSON，不再 delta/rebuild。**这一句没接到创作主路径。**

## 为什么还会卡在「剧情状态同步」

用户判断是对的。

- 主路径仍是 `needsSync` → `syncManualEdits` → `stateDelta` / `stateRebuild`。
- 末章改一段：`preferStateDelta: true`，仍是整章抽取。
- 中间章、编辑器整章保存、不少代笔收录：分段 `stateRebuild`。历史上 1.6 万字、3 段串行、单段可到数分钟。
- `applyWorkspacePlot` **只**在讨论 `novel_workspace_write` 写剧情文件、作者点审批卡之后走。收录气泡、代笔自动收录、正文保存不走它。
- `checkout/` 只是落盘旁路，不会让同步变短。

下一切口（若继续北星）：收录/改正文成功后改成写 `plot/`，标 `.synchronized`，把自动 `manualSync` 从这条路上拿掉。不要先迁库，不要先做真 git。

## Git 与远端

```text
branch: feat/ios-provider-parity-claude
HEAD:   e2a9ebb5c feat(ios): add novel markdown workspace export and plot write path
remote: origin/feat/ios-provider-parity-claude  本地超 14 个提交
工作区：只有未跟踪截图 .tmp-iphone-frame* / tmp-iphone-frame.jpg，不要提交
```

本主题提交只含工作区五阶段。设定过滤等在更早的 `7d95b8452`。

**push 失败**：GitHub secret scanning 拦的是更早的 `a8ded52c8`（Gemini/Antigravity），不是 `e2a9ebb5c`。

- `iosApp/iosApp/IOSAntigravityOAuthClient.swift:25` Client ID
- 同文件 `:26` Client Secret  
  代码注释写明是已公开 installed-app 值。

放行页（须用执行 push 的 GitHub 账号打开）：

1. https://github.com/soul99soul-glitch/AmberAgent/security/secret-scanning/unblock-secret/3I1POFPRx149l721rXQeMSbIrn6
2. https://github.com/soul99soul-glitch/AmberAgent/security/secret-scanning/unblock-secret/3I1POJWtSRIf6ggwqueaRmAE3WD

本机 `~/.gitconfig` 把所有 push URL 重写成 `blocked://`。用户明确要求推送时，用 `GIT_CONFIG_GLOBAL=/dev/null` + `gh auth git-credential` 即可碰到 GitHub；不要改用户全局 gitconfig。

不要在未授权时 `commit` / `push` / `stash` / `reset` / rebase。

## 真机

- 设备：iPhone Air，Core Device `94918570-0680-5B93-8E38-7E6B355D4426`（无线 `localNetwork`）
- xcodebuild destination：`00008150-000A594E0AF8401C`
- 派生数据：`iosApp/build/DeviceBuild`
- 包：`app.amber.ios` 1.0/1
- 2026-08-17 约 09:05 覆盖安装，容器 `B495C6A3-7694-4AB9-9613-391AFC6DEEE3`，当时 pid 59757
- 小说数据：`Library/Application Support/AmberAgent/NovelCreation`（**禁止原地改打开中的书的格式**）

未在真机点验：设定页导出工作区、文件夹导入、`workspace_write` 审批卡、写 plot 后是否真的不再抽 JSON。

装机命令（主包，不是 experimental-gpl）：

```bash
cd iosApp
xcodebuild -project AmberAgent.xcodeproj -scheme iosApp \
  -destination "platform=iOS,id=00008150-000A594E0AF8401C" \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath build/DeviceBuild build
xcrun devicectl device install app --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  build/DeviceBuild/Build/Products/Debug-iphoneos/iosApp.app
xcrun devicectl device process launch --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  --terminate-existing app.amber.ios
```

Synara `device_*` 只能开模拟器。真机用 `devicectl`。不要再 boot 第二个模拟器。

## 关键文件

新增：

- `iosApp/iosApp/NovelCreation/NovelWorkspaceBackup.swift`
- `iosApp/iosApp/NovelCreation/NovelWorkspaceImporter.swift`
- `iosApp/iosApp/NovelCreation/NovelWorkspaceFolderDocument.swift`
- `iosApp/iosApp/NovelCreation/IOSNovelWorkspaceTools.swift`
- `iosApp/iosAppTests/NovelWorkspaceBackupTests.swift`

改过的接线：

- `ai-core/.../Tool.kt`：工作区工具声明
- `NovelLiveModelAdapter.swift`：`novel_workspace_write` 进 AskUser
- `IOSNovelProjectToolExecutor.swift`：工具名分发
- `NovelProjectLifecycle.swift`：`exportWorkspace`、`applyWorkspacePlot`
- `NovelProjectRepository.swift`：checkout 旁路写/读；导入后 remap 到请求的 projectID
- `NovelProjectSettingsDetailView.swift`：导出按钮
- `NovelProjectListView.swift`：文件夹导入 + security scope
- `NovelSessionBubble.swift`：`NovelWorkspacePlotCard`
- `NovelSessionViewModel.swift` / `NovelCreationViewModel.swift`：plot 确认、apply
- `NovelGenerationReducer.swift`：`workspacePlot` prompt 校验

`applyWorkspacePlot` 行为：新建 snapshot + `manualSync` checkpoint + `syncManualEdits` operation；`current.md` 用 `splitHighlights` 避免「近期已写」加倍。不要改回「原地改当前 snapshot」——`saveManualEdit` 之后 working ≠ head，原地改会撞 `validateTransition`。

导入：先 `startAccessingSecurityScopedResource`，再读目录。永远新 ID，不要复用源 projectID。

## 验证

绿（本轮定点）：

- `NovelWorkspaceBackupTests` 4/4
- `NovelLiveModelAdapterTests`（含新工具声明）

红、与本轮无关：

- `IOSNovelProjectToolExecutorTests` 设定建议 4 例 = 既有过滤基线

本机无 Java，未跑 `:ai-core` 声明测试。导出/导入/审批卡无真机手测。

## 刻意不要做

- 改 `NovelProjectShardedStorage` 或原地迁用户正在看的书
- 把会话做成 markdown
- 内嵌真 git / 正文 `<<<<<<<` / iCloud remote
- 自动合并两条剧情线
- 把设定页改成文件浏览器
- 为迁库重做信息架构
- 在主路径改成写 `plot/` 之前删掉全部 `novel_*`
- 用几何补偿或第二套 sync 状态机掩盖抽取慢

## 下一会话怎么开工

1. 读 `AGENTS.md` → `docs/PROJECT_STATE.md` → 本规格。不要把本文当实时状态。
2. `git status --short --branch` 核对是否仍停在 `e2a9ebb5c`、是否仍超远端 14。
3. 若用户要推：先放行上面两个 secret URL，再 push；不要为 OAuth 公开客户端改写那 14 个提交，除非用户明确要改历史。
4. 若用户要消掉「剧情状态同步」长等待：动收录/改正文主路径，接 `applyWorkspacePlot` 或等价写 `plot/`，不要先做 checkout 美化。
5. 成功标准先写成一句可证伪的话，再改最小相关代码。
