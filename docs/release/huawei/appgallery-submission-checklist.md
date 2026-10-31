# 华为应用市场（AppGallery）上架准备与 CONDITIONAL 事项清单（Phase 6）

> 更新日期：2026-10-31
> 分支：`synara/amber-harmonyos-release-baseline`
> 本地预检脚本：`node scripts/harmony-release-preflight.mjs`

---

## 1. 本机已就绪项（PASS）

| 编号 | 检查项 | 当前状态 | 证据路径 |
|---|---|---|---|
| H6.1 | 版本号与 API 基线 | `versionName: "0.0.2"`, `versionCode: 2`；`compatibleSdkVersion` / `targetSdkVersion` 均为 `5.0.0(12)`（HarmonyOS NEXT API 12） | `harmony/AppScope/app.json5`、`harmony/build-profile.json5` |
| H6.2 | 权限最小化与用途说明 | `module.json5` 共 13 项 `requestPermissions`，全部具备真实、非空的 `$string:reason_*` 用户可见说明，已清除仅「深读」时代的旧文案 | `harmony/entry/src/main/module.json5`、`harmony/entry/src/main/resources/base/element/string.json` |
| H6.3 | 许可证一致性与关于页披露 | 根 `LICENSE` 与 4 个 `oh-package.json5` 统一声明双许可（AGPL v3 / 商业许可）；`SettingAboutPage.ets` 动态读取包版本并披露本地凭据加密（Asset Store + AES-GCM）与外发边界 | `LICENSE`、`harmony/**/oh-package.json5`、`harmony/entry/src/main/ets/pages/SettingAboutPage.ets` |
| H6.4 | 本地 HAP 构建与签名流水线 | `node scripts/harmony-inventory/build-harmony-app.mjs` 可一键产出 `entry-default-unsigned.hap` 与本地调试签名的 `entry-default-signed.hap` | `scripts/harmony-inventory/build-harmony-app.mjs`、`harmony/scripts/sign-hap.sh` |

---

## 2. 需外部账号 / 真机 / 主体资质完成的 CONDITIONAL 清单

以下事项涉及华为开发者联盟（AGC）正式证书、真机 UDID、运营主体资质或应用市场后台操作，**不在自动化环境中越权尝试**，由用户按顺序执行：

### CONDITIONAL-1：申请华为正式发布证书与 Release Profile 并切换包名 `app.amber.agent`
- **要做什么**：
  1. 在 AppGallery Connect（AGC）创建正式 HarmonyOS NEXT 应用，包名设为 `app.amber.agent`。
  2. 生成正式发布证书（`.cer`）与正式发布 Profile（`.p7b`，声明权限需覆盖 `module.json5` 中的受限权限如 `ohos.permission.READ_CONTACTS`、`ohos.permission.WRITE_CONTACTS`、`ohos.permission.FILE_ACCESS_PERSIST`、`ohos.permission.READ_PASTEBOARD`）。
  3. 将 `harmony/AppScope/app.json5` 的 `bundleName` 从 `app.amber.deepread` 切换为 `app.amber.agent`，并在 `harmony/build-profile.json5` 的 `signingConfigs` 中填入正式发布证书与 Profile 路径。
  4. 运行 `node scripts/harmony-release-preflight.mjs --strict` 与 `hvigorw --mode module -p product=default assembleApp` 产出正式签名 `.app` 包。
- **用什么账号或设备**：已完成实名认证的**华为开发者联盟（AppGallery Connect）账号** + 本机 DevEco Studio / `keytool`。
- **预计多少分钟**：**25–40 分钟**（不含华为后台人工审核受限权限的时间）。

### CONDITIONAL-2：HarmonyOS NEXT 真机安装、授权弹窗与冷启动验收
- **要做什么**：
  1. 用 USB 或无线调试连接搭载 HarmonyOS NEXT（API 12+）的华为真机，执行 `hdc list targets` 获取设备并读取真机 UDID（`hdc shell bm get --udid`）。
  2. 若使用调试 Profile 先行真机验收，将真机 UDID 加入 AGC 调试设备列表并下载更新后的调试 `.p7b` Profile。
  3. 安装签名后的 HAP（`hdc install harmony/entry/build/default/outputs/default/entry-default-signed.hap`），验证首次启动、AI 流式对话、工具审批弹窗、系统权限动态申请弹窗、杀进程重启后的加密凭据与会话恢复。
- **用什么账号或设备**：**HarmonyOS NEXT 真机（API 12+）** + **华为开发者账号**。
- **预计多少分钟**：**20–30 分钟**。

### CONDITIONAL-3：托管公网 HTTPS 隐私政策与用户协议页面，并回填应用配置
- **要做什么**：
  1. 确定运营主体名称、联系邮箱与个人信息保护负责人联系方式。
  2. 将隐私政策（披露用户自行配置的 AI 模型/搜索/MCP/网关直连外发边界、Asset Store + AES-GCM 本地凭据加密、剪贴板/通讯录/日历/麦克风/定位/蓝牙/健身运动权限用途）部署到公网可访问的 HTTPS 地址。
  3. 在 AGC 后台及应用内合规入口配置该 HTTPS 隐私政策链接。
- **用什么账号或设备**：**域名/静态站点托管账号（如官网或 GitHub Pages/Cloudflare Pages）** + **华为开发者联盟（AGC）账号**。
- **预计多少分钟**：**30–45 分钟**。

### CONDITIONAL-4：中国大陆移动互联网应用程序（APP）ICP 备案与生成式 AI 合规材料提交
- **要做什么**：
  1. 在接入服务商或主体所在地通信管理局完成移动应用 ICP 备案，获取《APP 备案号》。
  2. 在华为应用市场后台填写 APP 备案号、上传软著/主体资质，并按华为应用市场 AI 类应用审核要求填写第三方大模型服务调用说明（本应用为 BYOK 客户端，由用户自行配置 API Key 调用第三方合规模型服务）。
  3. 上传应用图标（512×512）、五张以上 HarmonyOS NEXT 真机/模拟器截图与应用简介，提交审核。
- **用什么账号或设备**：**运营主体营业执照/法人实名认证账号** + **ICP 备案系统账号** + **华为应用市场（AGC）后台账号**。
- **预计多少分钟**：后台材料填报约 **45–60 分钟**（管局备案审核周期另计，通常 3–15 个工作日）。
