# 独立应用复用 iOS 图标

2026-10-05，根据用户要求，将鸿蒙小说创作和深度阅读两个产品的标准图标分别替换为对应 iOS 产品的原始 PNG。直接复制文件字节，没有重绘、缩放、改色或重新编码。

## 产品与真实源资源

| 产品 | iOS AppIcon 资源 | 鸿蒙打包输入 | 尺寸 | 文件大小 |
| --- | --- | --- | --- | --- |
| 小说创作 | `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/NovelApp/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png` | `/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/products/novel/app_icon.png` | 1024 × 1024 | 1,109,182 字节 |
| 深度阅读 | `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png` | `/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/products/deepread/app_icon.png` | 1024 × 1024 | 6,534 字节 |

已经逐张查看原始图像：小说创作为朱红色「文」印章，深度阅读为朱红色书本符号。两个产品使用各自的 iOS 图标。

两份源文件与目标文件的 SHA-256 分别完全一致：

- 小说创作：`f30b1498c1e3abf465971582f50ac1744e0db9d5d8056f7a11e1576a4ca04e71`
- 深度阅读：`25d0b56afbfdf96d300a871aafb410430ac8efcd53b1d7e7a234fdf008df0b7e`

## 实际 identity 与引用链

- Novel iOS：`NovelApp/AmberNovel.xcodeproj/project.pbxproj` 的 Debug / Release 设置均为 `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`，`PRODUCT_BUNDLE_IDENTIFIER = app.amber.novel`；Resources 构建阶段引用 `Assets.xcassets`。`NovelApp/project.yml` 的同名设置一致。`Resources/Info.plist` 没有覆盖为另一图标。AppIcon 的 `Contents.json` 将无 appearance 条件的标准图标明确引用为 `icon-1024.png`。
- DeepRead iOS：`DeepReadApp/AmberDeepRead.xcodeproj/project.pbxproj` 的 Debug / Release 设置均为 `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`，`PRODUCT_BUNDLE_IDENTIFIER = app.amber.deepread`；Resources 构建阶段引用 `Assets.xcassets`。`DeepReadApp/project.yml` 使用生成的 Info.plist，图标设置一致。AppIcon 的 `Contents.json` 只引用标准 `AppIcon.png`。
- 鸿蒙：现有 `scripts/harmony-standalone/prepare.mjs` 根据产品读取 `harmony/products/<product>/app_icon.png`，复制到独立生成项目的 `AppScope/resources/base/media/<product>_app_icon.png` 和 `entry/src/main/resources/base/media/<product>_app_icon.png`。AppScope app icon、EntryAbility icon 和 startWindowIcon 均引用 `$media:<product>_app_icon`。独立 bundle 分别为 `app.amber.novel` 和 `app.amber.deepread.reader`。

小说 iOS catalog 另含 `icon-1024-dark.png` 与 `icon-1024-tinted.png`，它们是 iOS appearance 变体。鸿蒙此轮复用无 appearance 条件的标准图标，不增加自动暗色／染色切换逻辑。

## 保留与验证边界

替换前，两份鸿蒙旧 `app_icon.png` 均为工作树内未跟踪资源，已先备份到任务独占目录 `/tmp/amber-ios-icons-backup-20261005-ekp4evhd/` 的 `novel/app_icon.png` 和 `deepread/app_icon.png`；目录内 `manifest.json` 保存源、目标及新旧哈希。旧图 SHA-256：Novel `8bc1e4d9deb03153b1046d382cfdd357b36060623e37891503ed40d4720eefab`，DeepRead `f4261b26728fe07711fb083eef4945108b50639fe7b4681340289f59a160bfec`。

保留两个产品旧 `app_icon.master.png` 及已有历史图标报告。`harmony/products/README.md` 已更新当前打包来源，避免误把旧生成原图当作当前图标。没有修改 iOS 仓库、prepare 脚本、宿主应用图标或其他工作树改动。

本子任务验证了 PNG 签名、1024 × 1024 尺寸、源目标逐字节一致、SHA-256 一致，以及现有打包引用链。资源替换本身不新增实现镜像测试；独立 HAP 构建、签名、安装和桌面图标验收由主任务另行执行，不能从文件复制结果推断已经安装。

## 主任务安装验收

两款应用均已在 API 24 Pura 90 模拟器 `127.0.0.1:5555` 覆盖安装、冷启动，并实际查看桌面图标：[桌面截图](motion/device/06-ios-icons-desktop.jpeg)。小说为红色「文」章，深读为红色书本，宿主图标保持原样。小说原有项目和章节仍可见：[冷启动截图](motion/device/05-novel-icon-cold-start.jpeg)。

深读由本轮最终源码构建并官方签名。打包输入仍与 iOS 的 1024 × 1024 PNG 逐字节一致；SDK 资源编译器在 HAP 中生成 512 × 512 PNG，故 HAP 内 PNG 的哈希不同。这是构建处理，并非另画图标，已解包查看并核对桌面效果。

小说为保护当前已验收运行代码，使用原已签名 HAP 的载荷，仅替换 `resources/base/media/app_icon.png` 和 `resources/phone/media/app_icon.png` 两处图标，再由官方工具签名。109 项载荷逐项核对，其他文件字节、ABC/native 代码和 4 KB 对齐均保持；不能将该图标交付称为本轮小说功能源码构建。证据：[载荷比较](motion/novel-icon-only-payload-check.json)、[源快照比较](motion/novel-icon-only-source-check.json)、[签名日志](motion/novel-icon-final-sign.log)、[覆盖安装日志](motion/novel-icon-final-install.log)。生成工程的 phone override 也已同步，避免旧图覆盖 base 资源。

签名与安装证据支持当前模拟器交付，尚未做零售真机的正式 AGC 证书安装验证。最新 HAP 路径、源图哈希与包图哈希记录在 [final-artifacts.json](motion/final-artifacts.json)。
