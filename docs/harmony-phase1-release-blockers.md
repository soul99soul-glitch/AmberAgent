# 鸿蒙 Phase 1 发布前清单（2026-10-31）

本文只记录**当前代码与仓库状态**，区分「代码已闭环」「仍需外部动作」「本机无法验证」三类。
外部备案、正式签名、证书、商店审核不属于代码范围，不得用代码改动代替。

## 1. 本机环境基线（2026-10-31 实测）

| 项 | 结果 | 说明 |
|---|---|---|
| 单元/集成测试 | 4814 / 4815 通过 | 唯一失败项 `recipes → existing CPython addon` 需要 `harmony/native/python/build/host/amber_python_host.node`（本机未构建宿主插件，`harmony/**/build/` 被 gitignore） |
| ArkTS lint（改动文件） | 0 error | `harmony/scripts/lint-arkts.sh <changed files>` |
| `hvigorw assembleApp` | **失败（环境）** | `entry:default@BuildNativeWithNinja`：`harmony/native/mosh` 与 rust 依赖的 `build-deps.sh` 需要访问 `github.com` 拉取第三方源码，本机 `curl: (35) SSL_ERROR_SYSCALL`；ArkTS 编译阶段在原生任务之后，因此本机无法完成整包编译 |
| 模拟器 | 可用 | `Emulator -start "Pura 90" -noWindow` 后 `hdc list targets` 得到 `127.0.0.1:5555`；但**没有可安装的 HAP**（上一条），故本轮无设备截图证据 |
| `ohpm install` | 通过 | 含 `license: SEE LICENSE IN LICENSE` 的三份 oh-package |

> 结论：本轮 UI 结论来自静态审查 + 现有 UI 行为测试，**不是**模拟器目测结论；不得据此声称已完成设备验收。

## 2. 代码侧已闭环（本轮完成）

- 普通模型 API Key 静息加密：`providers` / `model_configs` / `search_services` 的 `apiKey`、`privateKey`、`password`，以及 `ai_api_key`、`<source>_api_key` 标量键，均不再以明文落 Preferences。见 `DECISION_LOG.md` D-133。
- 独立 review 补修：`models[].customHeaders[].value` 与 `models[].providerOverwrite.apiKey` 也已纳入加密；主密钥不可解时走可见恢复路径（清空密文字段、保留非密配置、provider 设置页提示重填），不再永久报错。
- 删除 `Storage.getSecret/setSecret/deleteSecret`（会把密钥明文写进 Preferences 的死路径）。
- `ai_api_key` 保留（不再删除）：它由标量加密覆盖，且 Deep Read AI 配置卡与 `OpenAiCompatibleAiClient` 仍在读它。
- 许可证声明一致性：`harmony/{chat,deepread,entry}/oh-package.json5` 与 `libamber_native` 由 `Apache-2.0` 改为 `SEE LICENSE IN LICENSE`（与仓库根 `LICENSE` 的分段双许可一致）；关于页新增「许可协议」「项目主页」两行并可直接打开仓库 LICENSE。

## 3. 仍需外部动作（不得由代码假装完成）

1. **备案**：APP 备案主体与域名，需运营者在华为云/工信部完成。
2. **正式签名**：当前 `harmony/build-profile.json5` 是 DevEco debug 材料（D-005）；上架需 AGC 正式证书与 profile，且 `bundleName` 与正式包名一致。
3. **正式包名**：`harmony/AppScope/app.json5` 仍为 `app.amber.deepread`，而 ADR 记录的目标正式包名是 `app.amber.agent`。改名会与当前 AGC debug profile 的包名绑定冲突（`install` 报 profile 不匹配），因此必须在拿到正式 profile 后一次性改，**不应在无正式签名时单独改**。
4. **隐私政策**：仓库内没有任何隐私政策文本、运营者主体与公开 HTTPS 地址，`SettingAboutPage` 也已移除原先无法核实的「不收集任何用户数据」绝对表述。上架前必须补：运营者名称与联系方式、公开 HTTPS 链接、第三方模型/检索服务的数据出境说明。关于页的隐私行待该链接就绪后再加。
5. **`pac.json5` 隐私清单**：`ProcessProjectPrivacyProfile` 任务存在但工程内没有 `pac.json5`，AGC 生成隐私标签需要它；内容依赖运营者主体与数据用途，不能凭空生成。
6. **商店素材**：截图与描述需在正式签名包上现拍。
7. **`build-profile.json5` 入库的 debug 签名配置**：D-005 已知项，发布分支必须替换。

## 3b. 已知同类未覆盖项（明确不在本轮范围内）

> 2026-10-31 Phase 4 更新：`mcp_servers.headers` 已纳入静息加密（D-136），设置备份已改为不含密钥明文。下面三条按当前状态修正。

- ~~`mcp_servers` 的 `headers` 仍是明文~~ → **已修（D-136）**：`commonOptions.headers[].second` 走 `secret_fields_blob` 的 `headers` 槽位，名称与顺序留明文。仍未覆盖的是 **URL 内嵌凭据**（`https://user:pw@host/mcp`）与 `assistants[].customHeaders[].value`。
- ~~设置备份导出载荷含明文 key~~ → **已修（D-136）**：`buildSyncSettingsBlob` 在打包前过 `stripSecretFields`。与 Android（`redactForExport` 掩码 + refs）/ iOS（`IOSCredentialRedactor`）的取值不同：鸿蒙不回填密钥，恢复后需用户重填（两端 refs 方案要求共享 SecretStore 寻址，鸿蒙尚无该协议）。
- 四个 oh-package 用 `license: SEE LICENSE IN LICENSE` 指向仓库根 `LICENSE`（包内无副本；这些包不对外发布）。

## 4. 本机未验证项（需在有网络/有正式签名的机器上补）

- 整包 `assembleApp` 与设备安装。
- 加密路径的设备侧实测：Asset Store 主密钥创建、密文文件写入、升级迁移（把旧版明文 `providers` 覆盖升级到本版后打开设置应看到凭据仍在、Preferences 内无明文）。
- 智能 UI 卡片在真实字体缩放（设置-显示-字体大小）与深浅色下的目测结论。

## 5. 智能 UI 卡片现状（更正口径）

代码内 `SMART_UI_COMPONENT_KINDS` 为 **80 种**组件、8 个家族（`harmony/chat/src/main/ets/chat/smart_ui/components.ts`），
不是 26 种。渲染入口 `entry/src/main/ets/components/NativeSmartUiComponent.ets`（家族级）+ `NativeSmartUiInputs/Data/Study/Values.ets`。
本轮据此做静态 UI 审查并修复：

- 有序列表序号栏、`colorChoice` 色块、单选/复选标记使用固定 vp，大字号（Font Scale）下会被裁 → 改为随 `fontSize` 缩放（默认 16 时像素值不变）。
- `badge/chip/tag` 长文本会换行撑破胶囊 → 单行 + 省略号。
- 学习卡的「上一题/下一题/查看结果/重新练习/显示答案」用固定 44vp 高 + 随字号放大的文字 → 改为最小高度 44，大字号下不再纵向裁切文字（触控区不变）。
