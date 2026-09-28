# E9 C — Google secure store and RS256 signer

2026-09-28. Implemented after root E8 source checkpoint `189eec7bd`. Ownership is only new `GoogleSecureStore.ets`, `GoogleRS256Signer.ets` and this report; shared helpers, SSH storage, auth/UI/DI and other owners' edits were preserved. Harmony skill and frozen E9 contract were read. No build/device/account request or real account secret was used.

## Frozen entry interface

`GoogleOAuthMode`, `GoogleOAuthBinding`, `GoogleOAuthTokens` have one source in domain `chat/google_auth.ts`; Entry imports them through `@amber/chat-domain`. C locally exports `GoogleSecretKind = 'service_account_private_key' | 'oauth_client_secret'`.

```ts
stageGoogleSecret(providerId: string, kind: GoogleSecretKind, secret: string): Promise<string>
loadGoogleSecret(providerId: string, kind: GoogleSecretKind, reference: string): Promise<string | null>
deleteGoogleSecret(providerId: string, kind: GoogleSecretKind, reference: string): Promise<void>
readGoogleOAuthTokens(binding: GoogleOAuthBinding): Promise<GoogleOAuthTokens | null>
writeGoogleOAuthTokens(binding: GoogleOAuthBinding, tokens: GoogleOAuthTokens): Promise<void>
removeGoogleOAuthTokens(binding: GoogleOAuthBinding): Promise<void>
removeGoogleOAuthTokensForProvider(providerId: string): Promise<void>
signGoogleRS256(privateKeyPem: string, signingInput: Uint8Array): Promise<Uint8Array>
```

Secret stage always creates a CSPRNG UUID reference. The encrypted record binds that reference, provider ID and secret kind. Original text is preserved without trim, with a 64KiB UTF8 limit. Missing file returns null; invalid binding, corrupt envelope, wrong/missing key, GCM authentication failure and other I/O errors reject. Delete decrypts and checks the same binding before deleting. UI/D owns stage → provider metadata commit → reclaim superseded owned reference; commit failure only reclaims the new staged reference and keeps the prior provider/secret.

OAuth namespaces are provider+mode; their encrypted binding includes providerId/mode/clientId/redirectUri/clientSecretRef. Read mismatch returns null and ordinary remove mismatch is a no-op. Read/remove capture a binding copy before queueing, and write serializes its payload before its first await. `expiresAt` is milliseconds; projectId/tierId null means onboarding incomplete. B owns generation and write-tail ordering. Provider deletion calls the dedicated two-mode removal only after source deletion and B's cancellation/barrier; it checks stored provider+mode ownership and also removes its orphan namespace Asset if the file is missing. Any real failure rejects; partial cleanup is not reported as complete.

## Storage and API evidence

Files are `${abilityContext.filesDir}/amberagent/google-auth/secret-<uuid>.json` and `tokens-<providerId>-<mode>.json`; no secrets enter provider Preferences. Asset alias is `amber-google-<namespace>`. Asset SECRET contains only a 32-byte random AES key, with `DEVICE_UNLOCKED`, `SYNC_TYPE.NEVER`, and create conflict rejection. Ciphertext uses actual `createEntrySyncCryptoPort()` AES256-GCM, a fresh 12-byte random IV and `writeTextFileAtomic()` under namespace mutex. Existing token file/key are retained on unsuccessful atomic replacement; newly added keys are reclaimed on unsuccessful staging. Record/envelope sizes are bounded, file reads reject symlinks/non-files, and only File I/O missing errno 13900002 becomes absence. Byte buffers held by this store are cleared after use; immutable JS strings cannot be explicitly wiped.

This turn searched/read local official Asset docs at `API参考/安全/Asset_Store_Kit_关键资产存储服务/ArkTS_API/ohos_security_asset_关键资产存储服务_/js-apis-asset` (cache `/tmp/e9-asset-official.txt`) and checked actual SDK Asset/Crypto/util/File I/O signatures. RSA official references were read from `/tmp/e9-crypto-official.txt`, `/tmp/e9-rsa-guide-official.txt`, `/tmp/e9-rsa-spec-official.txt`; exact full document IDs are retained in `E9-google-auth-design.md`. APIs used here are API12-compatible: Asset async methods API11, cryptographically secure UUID API9, nullable asymmetric convertKey API10, Sign/init/sign and private-key clearMem API9. No higher-version PEM conversion API is used.

Signer accepts unencrypted `PRIVATE KEY` PKCS8 PEM, validates/canonical-decodes Base64, converts DER via `createAsyKeyGenerator('RSA2048').convertKey(null, DataBlob)`, and uses a new `createSign('RSA2048|PKCS1|SHA256')` → init → sign per call. PKCS1 RSA PRIVATE KEY/encrypted PEM labels are rejected. Converted private-key memory and DER are cleared afterward. The signer returns signature bytes only; JWT construction/network/cache belong to A/B.

## Necessary checks and limits

- Two-owned-file lint: exit0, no defects (0 error/0 warn), `/tmp/e9-secure-lint.txt`. Final Store-only buffer/provider-removal revision: exit0, no defects, `/tmp/e9-secure-store-final-lint.txt`.
- Narrow strict TypeScript check against actual local SDK and real domain DTO/SyncCryptoPort: exit0, `/tmp/e9-secure-types.log`, config `/tmp/e9-secure-types/tsconfig.json`. Only AppStorage and the existing atomic helper boundary have narrow declarations; no fake token/domain DTOs.
- One temporary RSA group, 1/1 pass: `/tmp/e9-rsa-check.cjs` and `/tmp/e9-rsa-check.log`. It loads actual Entry signer and domain JWT-claims source, generates a temporary real Node RSA2048 PKCS8 key, verifies exact one-hour Google claims, uses fresh signer instances, checks owned DER cleanup, verifies original JWT bytes with independent `/usr/bin/openssl`, rejects tampered bytes with OpenSSL/Node and rejects a PKCS1 PEM label. Temporary files are removed and no private key/token is printed.

The RSA check uses a Node CryptoFramework API adapter. It proves the actual source's protocol/PKCS1 signing sequence with real independent crypto; it does **not** prove API12 device CryptoFramework acceptance. No fake Asset/filesystem fixture is claimed as platform secure-storage evidence. Actual Asset access controls, ciphertext at rest/tamper, device RSA conversion/signing, callback/account/provider acceptance remain root/external gates. No repeated suite, device or build was run by C.

Final source: Store 284 lines, SHA256 `62b4e752e4d6b452c05a37ef5d3ebd46c012c295effda3701626e09c248b1645`; signer 38 lines, SHA256 `420d4a55394728e30b9e7dce9693ccb8699867f00c9c34eaa97c0504394d67bf`. The signer hash matches the unique RSA group's loaded source. Root handles ArkTS compilation and integration.
