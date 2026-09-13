# Batch 0 — R01 Crypto/ArkTS 编译阻断修复

状态：代码完成，静态验证通过；**未自行构建**（主代理统一 hvigor）。真实 crypto 往返待设备。

## 修改文件（仅此三个，未触碰其它业务/签名/真实 token）

1. `harmony/entry/src/main/ets/platform_impl/SyncCryptoPort.ets`
2. `harmony/entry/src/main/ets/platform_impl/WebMountOAuthSupport.ets`
3. `harmony/entry/src/main/ets/pages/SettingExperimentalWebMountPage.ets`

## 修复 ID

R01（P1 当前工作树无法编译）。覆盖 build.log 全部 11 条诊断中落在这三个文件的 11 条
（ERROR:12 的另一条在别处，非本组范围）。

## 权威依据（API 12 SDK 声明 + 官方文档，非记忆）

SDK：`/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/api/@ohos.security.cryptoFramework.d.ts`

| 调用 | SDK 精确声明 | 行 | since |
|---|---|---|---|
| 对称密钥生成 | `function createSymKeyGenerator(algName: string): SymKeyGenerator` | 2112 | 12 |
| 导入密钥 | `convertKeySync(key: DataBlob): SymKey`（SymKeyGenerator） | 2006 | 12 |
| 随机数 | `generateRandomSync(len: number): DataBlob`（Random） | 1332 | 12 |
| 密文更新 | `updateSync(data: DataBlob): DataBlob`（Cipher） | 3734 | 12 |
| 收尾 | `doFinalSync(data: DataBlob \| null): DataBlob` | 4061 | 12 |
| 初始化 | `initSync(opMode: CryptoMode, key: Key, params: ParamsSpec \| null): void` | 3551 | 12 |
| GCM 参数 | `interface GcmParamsSpec extends ParamsSpec { iv: DataBlob; aad: DataBlob; authTag: DataBlob; algName: string }` | 328 | 12 |
| 模式枚举 | `CryptoMode.ENCRYPT_MODE = 0` / `DECRYPT_MODE = 1` | 603/625 | 12 |

原代码的三处误用与 SDK 不符：`createSymKeySync(...)`（不存在，应为 createSymKeyGenerator+convertKeySync）、
`cipher.initSync(options)` 单参（应为 3 参 (opMode,key,params)）、`cryptoFramework.CipherSpec`（非导出，
且不能做 init 参数）、`Random.getRandomSync`（不存在，应为 generateRandomSync）。

**GCM 关键合同（官方文档 crypto-aes-sym-encrypt-decrypt-gcm.md）**：
`update(data)` 返回密文；`doFinal(null)` 返回 authTag；解密时把 authTag 填进 `GcmParamsSpec.authTag`，
`doFinal` 校验失败抛异常。GCM 算法库目前仅支持 16 字节 authTag。
来源：`https://raw.githubusercontent.com/openharmony/docs/master/zh-cn/application-dev/security/CryptoArchitectureKit/crypto-aes-sym-encrypt-decrypt-gcm.md`
（本地 `devecocli docs` 缺失路径 MODULE_NOT_FOUND，按合同改用官方文档 + SDK d.ts。）

## 最小机制

### SyncCryptoPort.ets
- `importAes256Key`：`createSymKeyGenerator('AES256').convertKeySync({data:key})`。
- `encryptGcm`：`initSync(ENCRYPT_MODE, key, GcmParamsSpec{iv,aad:empty,authTag:empty})`
  → `updateSync(plaintext)` 取密文 → `params.authTag = doFinalSync(null)` 取 16B tag
  → 返回 `ciphertext || tag`。**保持 SyncCryptoPort 合同 `encrypt` 返回 ciphertext||tag(16B) 不变**。
- `decryptGcm`：`ciphertext.length < 16 → null`；截末尾 16B 作 `authTag` 传入 `GcmParamsSpec`，
  其余为密文 → `updateSync` + `doFinalSync(null)` 校验 tag；认证失败（抛异常）→ `null`。
- 错误语义：认证失败返回 `null`（对齐合同「auth fail → null」）；`initSync`/密钥/IV 参数错误不吞，
  在 `decrypt` 中直接向上抛（初始化错误明确可诊断）；`encrypt` 包一层带 message 的 `Error`。
- `randomBytes`：`rnd.generateRandomSync(n)`。
- KDF **完全未改**：仍是 SHA256-ITERATED（salt||pass 迭代，max 100k），manifest `SHA256-ITERATED` 兼容不变。
- 所有对象字面量均对应 SDK 显式 interface（DataBlob / GcmParamsSpec），消除 arkts-no-untyped-obj-literals。

### WebMountOAuthSupport.ets
- `randomBase64Url`：`getRandomSync` → `generateRandomSync`，显式 `DataBlob` 类型。行为不变。

### SettingExperimentalWebMountPage.ets
- 新增模块级显式 `interface FeishuCodePaste { code: string; state: string | null }`；
  `parseFeishuCodePaste` 返回类型由内联对象字面量类型改为 `FeishuCodePaste`，两个 return 改为显式类型局部变量。
- **行为逐字保持**：裸 code → `{code:trimmed,state:null}`；URL 分支解析逻辑与取值不变。

## 测试命令与结果

| 命令 | 结果 |
|---|---|
| `harmony/scripts/lint-arkts.sh <三个文件>` | exit 0，`No defects found`（仍有既有 "Some error occurred during linting...incomplete report" 提示，与 R01 记录一致，不作为强证据） |
| `cd harmony/chat && npx tsx --test src/test/sync_snapshot.test.ts src/test/webmount_oauth.test.ts` | pass 10 / fail 0（domain 层，验证 encrypt/decrypt 返回语义与 PKCE 合同未被破坏） |
| SDK d.ts 签名核对 + 官方 GCM 文档核对 | 见上表；所有替换后的 API 均 @since ≤12，无高版本 API，无需 canIUse |

未新增正式测试（按合同：优先现有测试，不造 assert-only/源码字符串测试）。未跑 hvigor（主代理统一构建）。
真实 GCM/PKCE 往返与装机验证**待设备**，本批不声称 UI/运行期已验证。

## 共享接线需求 / 未解决点

- **无新接线需求**：三个文件导出符号（`createEntrySyncCryptoPort`、`createWebMountCryptoPort`、
  `makeFeishuPkce`、`exchangeFeishuCode`、`createKvWebMountTokenStore`、`feishuTokenStatusText`）
  与页面 import 全部未变，签名兼容。
- **待主代理构建验证**：`CompileArkTS` 需从 ERROR:12 → 0。本组三个文件理应清零全部 11 条相关诊断。
- **真实 crypto 待设备**：GCM 往返（含篡改/错密码返回 null）、PKCE SHA-256 base64url、随机数
  CSPRNG 未在设备实跑；本批仅静态 + domain mock 测试。
- 未改签名配置、未读取真实 token、未触碰其它业务文件。
