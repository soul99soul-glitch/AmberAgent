# 鸿蒙独立产品构建

独立产品复用当前 `harmony/` 的运行代码，生成可重建的本地工程，分别使用独立 bundle 和应用沙盒：

| 产品 | Bundle | 工程输出 |
|---|---|---|
| Amber 小说 | `app.amber.novel` | `.harmony-standalone/novel/` |
| Amber 深读 | `app.amber.deepread.reader` | `.harmony-standalone/deepread/` |

宿主已经占用 `app.amber.deepread`；独立深读使用新身份，避免覆盖宿主和宿主数据。

## 命令

```bash
# 只生成当前源码快照
node scripts/harmony-standalone/prepare.mjs --product all

# 生成工程、安装本地域依赖、构建未签名 HAP
node scripts/harmony-standalone/build.mjs --product novel
node scripts/harmony-standalone/build.mjs --product deepread

# 可选：构建后使用既有离线 CA 签名，只用于模拟器
node scripts/harmony-standalone/build.mjs --product all --sign

# 聚焦生成器行为验证
node --test scripts/harmony-standalone/prepare.test.mjs
```

`--product` 支持 `novel`、`deepread` 和 `all`。默认保留未签名输出；`--sign` 调用生成工程自己的 `scripts/sign-hap.sh`，该脚本读取生成工程的 `AppScope/app.json5`，签名 profile 的 bundle 随产品变化。零售真机需要为各自 bundle 单独配置有效 AGC 签名。

`ohpm` 优先使用 PATH 中已安装的版本，随后查找本机 Command Line Tools 和 DevEco Studio 的既有位置，也可用 `AMBER_OHPM` 指定。SDK 构建统一复用 `scripts/harmony-inventory/build-harmony-app.mjs`，通过 `AMBER_HARMONY_DIR` 传入生成工程绝对路径。SDK、Node、Java 和原生工具链沿用宿主构建要求。

## 源码与安全边界

生成器复制当前工作树中的 `AppScope`、`entry`、`chat`、`deepread`、`native` 和构建配置，包含尚未提交的功能代码。更新源码后需重新生成：仅更新变化的源码，依据 `.amber-product.json` 中的 `sourceFiles` 清理上次生成、此次已退役的文件，不删除整个工程。未变化文件保留时间戳，生成工程的 `build`、`oh_modules`、`.hvigor`、原生 `target/build/archives` 缓存及本地离线签名 `material` 均保留，以支持增量构建和使用同证书覆盖安装。清单以外新增的本地文件也保留。生成器只更新带自身产品标记的目录。

不会复制宿主的 `build`、`target`、`archives`、`node_modules`、`oh_modules`、`.hvigor`、`.git` 等构建缓存，不复制 symlink、证书、keystore、profile、`.env`、本地依赖认证配置、数据库和显式用户数据目录。宿主 `build-profile.json5` 中的 AGC `signingConfigs` 和产品 `signingConfig` 在写入生成工程前删除。应用使用新的 bundle，因此已有宿主账号配置、密钥、小说项目和阅读历史不会自动迁移。

保留原图标、主题资源和 pages manifest。`product_kind` 字符串决定独立入口，`app_name` 和 `EntryAbility_label` 使用独立产品名。独立工程仅声明网络、网络状态、后台运行及振动权限；小说保留 `NovelPolishWorkAbility`，深读不注册该扩展。

`entry` 当前原生构建依赖 `native/rust-napi`、`ssh`、`python` 和 `mosh`，故同步复制原生源码，但排除缓存和依赖归档。首次独立构建需要下载并重新构建这些依赖。当前 pages manifest 和源文件之间存在共享设置、平台实现与页面跳转，因此保留既有页面路由和域模块以保证编译和跳转兼容；产品可见导航由入口限定。

## 验证范围

聚焦测试通过实际临时工程验证：不同 bundle、资源及路由保留、权限和扩展裁剪、AGC 材料剔除、宿主配置不变、外部 symlink 不泄漏、新源码快照和失效文件移除、构建缓存与签名证书保留、未变化源码时间戳保留，以及对非生成目录的保护。实际 HAP 构建、签名、安装和界面截图应分别报告，生成器测试不证明这些环节成功。

### 2026-10-03 本机构建结果

- 生成器行为测试 `6/6` 通过。
- 小说独立工程完成构建、离线签名和 `verify-app` 验证；设置页修正后的增量构建耗时 `15.600 s`。日志：`/tmp/amber-standalone-novel-build.log`。
- 深读独立工程完成构建、离线签名和 `verify-app` 验证；首次全构建耗时 `2 min 11.032 s`，其中首次原生依赖构建为 `2 min 7.219 s`。日志：`/tmp/amber-standalone-deepread-build.log`。
- 两产品签名工具均验证 codesign 和 SHA-256 digest 成功；各自 profile bundle 分别为 `app.amber.novel` 和 `app.amber.deepread.reader`。
- 截图验收修正顶部对齐及深色占位文字后，最终顺序完成两产品增量构建和签名：小说 `11.184 s`、深读 `10.824 s`，日志：`/tmp/amber-standalone-final-build.log`。最终两包均已同证书覆盖安装、冷启动，详见 [交付记录](../reviews/2026-10-03-harmony-standalone/completion.md)。
- 小说真实证书在重新生成、源码修改后重建及再次签名的前后保持一致，SHA-256 为 `de6ad215552a489059e5cace8729f12e8db843a1e84802cf137630ae1658d663`。深读证书 SHA-256 为 `59f67e152e7ee54159e609801379f00a039cd72ec9b10c9eac19930d53740274`。

签名 HAP 位于各自工程的 `entry/build/default/outputs/default/signed/entry-default-signed.hap`。本节只记录本机构建与离线签名证据，设备安装、启动、界面和 AI 服务验收由独立验收记录提供。
