#!/usr/bin/env bash
# HarmonyOS HAP 本地签名脚本（完全离线，无需 AGC）
#
# 用 SDK 自带的 hap-sign-tool.jar + 自建完整 CA 链，对未签名 HAP 做本地调试签名。
# 生成的签名 HAP 可直接用 hdc install 安装到开启了「开发者模式 + USB 调试」的设备。
#
# 前置：
#   1. HarmonyOS Command Line Tools 已安装（默认在 ~/Library/Huawei/commandline/）
#   2. java 在 PATH 中
#
# 用法：
#   ./scripts/sign-hap.sh                                       # 用默认路径
#   ./scripts/sign-hap.sh /path/to/unsigned.hap /output/dir    # 自定义
#   SIGN_KEY_PWD=mypwd ./scripts/sign-hap.sh                    # 自定义密码
#
# 退出码：0 成功；非 0 失败。

set -euo pipefail

# ---------- 参数 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

UNSIGNED_HAP="${1:-$PROJECT_DIR/entry/build/default/outputs/default/entry-default-unsigned.hap}"
OUTPUT_DIR="${2:-$PROJECT_DIR/entry/build/default/outputs/default/signed}"
SIGN_KEY_PWD="${SIGN_KEY_PWD:-123456}"
KEY_ALIAS="deepread-key"

# ---------- SDK 路径 ----------
CMDLINE_DIR="${HARMONY_CMDLINE_DIR:-$HOME/Library/Huawei/commandline/command-line-tools}"
SDK_DIR="$CMDLINE_DIR/sdk/default/openharmony"
LIB_DIR="$SDK_DIR/toolchains/lib"
HDC="$SDK_DIR/toolchains/hdc"
SIGN_JAR="$LIB_DIR/hap-sign-tool.jar"

# ---------- 校验 ----------
[ -f "$SIGN_JAR" ] || { echo "✗ 找不到 hap-sign-tool.jar: $SIGN_JAR"; exit 1; }
[ -f "$UNSIGNED_HAP" ] || { echo "✗ 找不到未签名 HAP: $UNSIGNED_HAP"; exit 1; }

mkdir -p "$OUTPUT_DIR"
MATERIAL_DIR="$OUTPUT_DIR/material"
mkdir -p "$MATERIAL_DIR"

echo "── HarmonyOS HAP 本地签名 ──"
echo "未签名 HAP : $UNSIGNED_HAP"
echo "输出目录   : $OUTPUT_DIR"
echo "签名工具   : $SIGN_JAR"
echo ""

# ---------- 1. CA 链 ----------
CA_KS="$MATERIAL_DIR/ca-keystore.p12"
if [ ! -f "$CA_KS" ]; then
  echo "[1/8] 生成根 CA"
  java -jar "$SIGN_JAR" generate-ca \
    -keyAlias "deepread-root-ca" -keyPwd "$SIGN_KEY_PWD" \
    -keyAlg ECC -keySize NIST-P-256 \
    -subject "C=CN,O=Amber,OU=Amber,CN=DeepRead Root CA" \
    -validity 3650 -signAlg SHA384withECDSA \
    -keystoreFile "$CA_KS" -keystorePwd "$SIGN_KEY_PWD" \
    -outFile "$MATERIAL_DIR/root-ca.cer" 2>&1 | grep -E "(success|ERROR)" || true

  echo "[2/8] 生成应用子 CA"
  java -jar "$SIGN_JAR" generate-ca \
    -keyAlias "deepread-app-ca" -keyPwd "$SIGN_KEY_PWD" \
    -keyAlg ECC -keySize NIST-P-256 \
    -issuer "C=CN,O=Amber,OU=Amber,CN=DeepRead Root CA" \
    -issuerKeyAlias "deepread-root-ca" -issuerKeyPwd "$SIGN_KEY_PWD" \
    -subject "C=CN,O=Amber,OU=Amber,CN=DeepRead App CA" \
    -validity 3650 -signAlg SHA384withECDSA \
    -keystoreFile "$CA_KS" -keystorePwd "$SIGN_KEY_PWD" \
    -outFile "$MATERIAL_DIR/sub-app-ca.cer" 2>&1 | grep -E "(success|ERROR)" || true

  echo "[3/8] 生成 Profile 子 CA"
  java -jar "$SIGN_JAR" generate-ca \
    -keyAlias "deepread-profile-ca" -keyPwd "$SIGN_KEY_PWD" \
    -keyAlg ECC -keySize NIST-P-256 \
    -issuer "C=CN,O=Amber,OU=Amber,CN=DeepRead Root CA" \
    -issuerKeyAlias "deepread-root-ca" -issuerKeyPwd "$SIGN_KEY_PWD" \
    -subject "C=CN,O=Amber,OU=Amber,CN=DeepRead Profile CA" \
    -validity 3650 -signAlg SHA384withECDSA \
    -keystoreFile "$CA_KS" -keystorePwd "$SIGN_KEY_PWD" \
    -outFile "$MATERIAL_DIR/sub-profile-ca.cer" 2>&1 | grep -E "(success|ERROR)" || true
else
  echo "[1-3/8] CA 链已存在，跳过"
fi

# ---------- 2. 应用密钥 + 证书 ----------
APP_KS="$MATERIAL_DIR/app-keystore.p12"
if [ ! -f "$APP_KS" ]; then
  echo "[4/8] 生成应用密钥对"
  java -jar "$SIGN_JAR" generate-keypair \
    -keyAlias "$KEY_ALIAS" -keyPwd "$SIGN_KEY_PWD" \
    -keyAlg ECC -keySize NIST-P-256 \
    -keystoreFile "$APP_KS" -keystorePwd "$SIGN_KEY_PWD" 2>&1 | grep -E "(success|ERROR)" || true
else
  echo "[4/8] 应用密钥已存在，跳过"
fi

if [ ! -f "$MATERIAL_DIR/app-debug-cert.cer" ]; then
  echo "[5/8] 签发应用调试证书"
  java -jar "$SIGN_JAR" generate-app-cert \
    -keyAlias "$KEY_ALIAS" -keyPwd "$SIGN_KEY_PWD" \
    -issuer "C=CN,O=Amber,OU=Amber,CN=DeepRead App CA" \
    -issuerKeyAlias "deepread-app-ca" -issuerKeyPwd "$SIGN_KEY_PWD" \
    -subject "C=CN,O=Amber,OU=Amber,CN=DeepRead Debug" \
    -validity 365 -signAlg SHA256withECDSA \
    -keystoreFile "$APP_KS" -keystorePwd "$SIGN_KEY_PWD" \
    -issuerKeystoreFile "$CA_KS" -issuerKeystorePwd "$SIGN_KEY_PWD" \
    -outForm certChain \
    -rootCaCertFile "$MATERIAL_DIR/root-ca.cer" \
    -subCaCertFile "$MATERIAL_DIR/sub-app-ca.cer" \
    -outFile "$MATERIAL_DIR/app-debug-cert.cer" 2>&1 | grep -E "(success|ERROR)" || true
else
  echo "[5/8] 应用调试证书已存在，跳过"
fi

if [ ! -f "$MATERIAL_DIR/profile-cert.cer" ]; then
  echo "[6/8] 签发 Profile 签名证书"
  java -jar "$SIGN_JAR" generate-profile-cert \
    -keyAlias "$KEY_ALIAS" -keyPwd "$SIGN_KEY_PWD" \
    -issuer "C=CN,O=Amber,OU=Amber,CN=DeepRead Profile CA" \
    -issuerKeyAlias "deepread-profile-ca" -issuerKeyPwd "$SIGN_KEY_PWD" \
    -subject "C=CN,O=Amber,OU=Amber,CN=DeepRead Profile Debug" \
    -validity 365 -signAlg SHA256withECDSA \
    -keystoreFile "$APP_KS" -keystorePwd "$SIGN_KEY_PWD" \
    -issuerKeystoreFile "$CA_KS" -issuerKeystorePwd "$SIGN_KEY_PWD" \
    -outForm certChain \
    -rootCaCertFile "$MATERIAL_DIR/root-ca.cer" \
    -subCaCertFile "$MATERIAL_DIR/sub-profile-ca.cer" \
    -outFile "$MATERIAL_DIR/profile-cert.cer" 2>&1 | grep -E "(success|ERROR)" || true
else
  echo "[6/8] Profile 签名证书已存在，跳过"
fi

# ---------- 3. Profile p7b（每次重签，因为可能改 bundle name/device-id） ----------
echo "[7/8] 签名 Profile (生成 .p7b)"

# 从 app.json5 读 bundle name
BUNDLE_NAME=$(python3 -c "
import json, re
with open('$PROJECT_DIR/AppScope/app.json5') as f:
    txt = f.read()
# 去掉注释
txt = re.sub(r'//.*', '', txt)
txt = re.sub(r'/\*.*?\*/', '', txt, flags=re.DOTALL)
# 尾随逗号
txt = re.sub(r',\s*([}\]])', r'\1', txt)
d = json.loads(txt)
print(d['app']['bundleName'])
" 2>/dev/null || echo "app.amber.deepread")
echo "    bundle-name: $BUNDLE_NAME"

# 读设备 UDID 列表（如果有连接设备）
DEVICE_IDS="[]"
if [ -x "$HDC" ]; then
  UDID_LIST=$("$HDC" list targets 2>/dev/null | grep -v "^\[Empty\]" | grep -v "^$" || true)
  if [ -n "$UDID_LIST" ]; then
    # 把每个序列号转成 UDID 并构造 JSON 数组
    DEVICE_IDS=$(python3 << PYEOF || echo "[]"
import subprocess, json, re
out = """$UDID_LIST"""
udids = []
for line in out.strip().splitlines():
    sn = line.split()[0] if line.split() else ""
    if not sn: continue
    # 设备实际支持的 UDID 命令: bm get --udid(部分系统 bm dump --udid 无输出)
    udid = ""
    for cmd in (["bm", "get", "--udid"], ["bm", "dump", "--udid"]):
        try:
            r = subprocess.run(["$HDC", "-t", sn, "shell"] + cmd, capture_output=True, text=True, timeout=5)
            m = re.search(r'[0-9A-Fa-f]{64}', r.stdout)
            if m:
                udid = m.group(0).lower()
                break
        except Exception:
            pass
    if udid:
        udids.append(udid)
print(json.dumps(udids))
PYEOF
)
  fi
fi
echo "    device-ids : $DEVICE_IDS"

python3 << PYEOF
import json
cert_path = "$MATERIAL_DIR/app-debug-cert.cer"
with open(cert_path) as f:
    cert_pem = f.read()
profile = {
    "version-name": "2.0.0",
    "version-code": 2,
    "uuid": "fe686e1b-3770-4824-a938-961b140a7c98",
    "validity": {"not-before": 1719500000, "not-after": 1799000000},
    "type": "debug",
    "bundle-info": {
        "developer-id": "OpenHarmony",
        "development-certificate": cert_pem,
        "bundle-name": "$BUNDLE_NAME",
        "apl": "normal",
        "app-feature": "hos_normal_app"
    },
    "acls": {"allowed-acls": [""]},
    "permissions": {"restricted-permissions": [""]},
    "debug-info": {"device-ids": $DEVICE_IDS, "device-id-type": "udid"},
    "issuer": "pki_internal"
}
with open("$MATERIAL_DIR/profile.json", "w") as f:
    json.dump(profile, f, indent=2)
PYEOF

java -jar "$SIGN_JAR" sign-profile \
  -mode localSign \
  -keyAlias "$KEY_ALIAS" -keyPwd "$SIGN_KEY_PWD" \
  -profileCertFile "$MATERIAL_DIR/profile-cert.cer" \
  -inFile "$MATERIAL_DIR/profile.json" \
  -signAlg SHA256withECDSA \
  -keystoreFile "$APP_KS" -keystorePwd "$SIGN_KEY_PWD" \
  -outFile "$MATERIAL_DIR/profile.p7b" 2>&1 | grep -E "(success|ERROR)" || true

# ---------- 4. 签名 HAP ----------
echo "[8/8] 签名 HAP"
SIGNED_HAP="$OUTPUT_DIR/entry-default-signed.hap"
java -jar "$SIGN_JAR" sign-app \
  -mode localSign \
  -keyAlias "$KEY_ALIAS" -keyPwd "$SIGN_KEY_PWD" \
  -appCertFile "$MATERIAL_DIR/app-debug-cert.cer" \
  -profileFile "$MATERIAL_DIR/profile.p7b" \
  -inFile "$UNSIGNED_HAP" \
  -signAlg SHA256withECDSA \
  -keystoreFile "$APP_KS" -keystorePwd "$SIGN_KEY_PWD" \
  -outFile "$SIGNED_HAP" \
  -compatibleVersion 12 \
  -signCode 1 -profileSigned 1 2>&1 | grep -E "(success|ERROR|Sign Hap)" || true

# ---------- 验证 ----------
echo ""
echo "── 验证 ──"
java -jar "$SIGN_JAR" verify-app \
  -inFile "$SIGNED_HAP" \
  -outCertChain "$MATERIAL_DIR/verify-cert.cer" \
  -outProfile "$MATERIAL_DIR/verify-profile.p7b" 2>&1 | grep -E "(verify|Verify|ERROR)" | tail -5

echo ""
echo "✓ 签名完成: $SIGNED_HAP"
echo ""
echo "── 安装到设备 ──"
echo "  hdc install \"$SIGNED_HAP\""
