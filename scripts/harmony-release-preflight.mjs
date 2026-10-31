#!/usr/bin/env node
// harmony-release-preflight.mjs — 鸿蒙 Phase 6 发布前预检脚本
//
// 用法：
//   node scripts/harmony-release-preflight.mjs            # 本地基线预检（外部条件项标为 CONDITIONAL，退出码 0）
//   node scripts/harmony-release-preflight.mjs --strict   # 正式提审模式（CONDITIONAL 未就绪则退出码 1）
//   node scripts/harmony-release-preflight.mjs --json     # 输出结构化 JSON

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_ROOT = path.resolve(__dirname, '..');

const readJson5 = (filePath) =>
  vm.runInNewContext(`(${fs.readFileSync(filePath, 'utf8')})`, Object.create(null));

export const runHarmonyReleasePreflight = (repoRoot = DEFAULT_ROOT) => {
  const checks = [];
  const addCheck = (id, category, status, summary, detail) => {
    checks.push({ id, category, status, summary, detail });
  };

  // 1. AppScope/app.json5 版本号与包名检查
  const appJsonPath = path.join(repoRoot, 'harmony/AppScope/app.json5');
  const appJson = readJson5(appJsonPath).app ?? {};
  const versionCode = appJson.versionCode;
  const versionName = appJson.versionName;
  const bundleName = appJson.bundleName;

  if (typeof versionCode === 'number' && Number.isInteger(versionCode) && versionCode >= 1
    && typeof versionName === 'string' && /^\d+\.\d+\.\d+$/.test(versionName)) {
    addCheck(
      'H6-VERSION',
      'local',
      'PASS',
      `应用版本号合法：${versionName} (${versionCode})`,
      `harmony/AppScope/app.json5 -> versionName="${versionName}", versionCode=${versionCode}`,
    );
  } else {
    addCheck(
      'H6-VERSION',
      'local',
      'FAIL',
      'harmony/AppScope/app.json5 版本号缺失或格式非法',
      `versionName=${String(versionName)}, versionCode=${String(versionCode)}`,
    );
  }

  if (bundleName === 'app.amber.agent') {
    addCheck(
      'H6-BUNDLE-NAME',
      'release',
      'PASS',
      '正式上架包名已切换为 app.amber.agent',
      'harmony/AppScope/app.json5 -> bundleName="app.amber.agent"',
    );
  } else {
    addCheck(
      'H6-BUNDLE-NAME',
      'release',
      'CONDITIONAL',
      `当前包名为本地调试包名 ${String(bundleName)}（正式上架需配合 AGC 发布 Profile 切换为 app.amber.agent）`,
      '未拿到 app.amber.agent 正式发布 Profile 前直接改包名会导致本地调试签名报 profile 匹配失败',
    );
  }

  // 2. build-profile.json5 API 12 基线与签名配置检查
  const buildProfilePath = path.join(repoRoot, 'harmony/build-profile.json5');
  const buildProfile = readJson5(buildProfilePath);
  const product = (buildProfile?.app?.products ?? []).find((p) => p.name === 'default') ?? {};
  const signingConfigs = buildProfile?.app?.signingConfigs ?? [];
  const defaultSigning = signingConfigs.find((s) => s.name === 'default')?.material ?? {};

  if (product.compatibleSdkVersion === '5.0.0(12)'
    && product.targetSdkVersion === '5.0.0(12)'
    && product.runtimeOS === 'HarmonyOS') {
    addCheck(
      'H6-SDK-BASELINE',
      'local',
      'PASS',
      'HarmonyOS NEXT API 12 (5.0.0(12)) 基线配置完整',
      'harmony/build-profile.json5 -> compatibleSdkVersion/targetSdkVersion="5.0.0(12)"',
    );
  } else {
    addCheck(
      'H6-SDK-BASELINE',
      'local',
      'FAIL',
      'harmony/build-profile.json5 SDK 基线配置异常',
      JSON.stringify(product),
    );
  }

  const isDebugAutoSign = defaultSigning.keyAlias === 'debugKey'
    || (typeof defaultSigning.profile === 'string' && defaultSigning.profile.includes('.ohos/config/default_'));
  if (!isDebugAutoSign && typeof defaultSigning.profile === 'string' && defaultSigning.profile.length > 0) {
    addCheck(
      'H6-RELEASE-SIGNING',
      'release',
      'PASS',
      '已配置非默认调试目录的发布签名材料',
      `profile=${defaultSigning.profile}`,
    );
  } else {
    addCheck(
      'H6-RELEASE-SIGNING',
      'release',
      'CONDITIONAL',
      '当前使用本机 DevEco 自动生成调试签名（正式提审需替换为 AGC 生产发布证书与 Profile）',
      `harmony/build-profile.json5 -> material.profile="${String(defaultSigning.profile ?? '')}"`,
    );
  }

  // 3. module.json5 权限声明与 string.json 原因文案完整性
  const moduleJsonPath = path.join(repoRoot, 'harmony/entry/src/main/module.json5');
  const stringJsonPath = path.join(repoRoot, 'harmony/entry/src/main/resources/base/element/string.json');
  const moduleJson = readJson5(moduleJsonPath).module ?? {};
  const stringEntries = readJson5(stringJsonPath).string ?? [];
  const stringMap = new Map(stringEntries.map((item) => [item.name, item.value]));

  const permissions = moduleJson.requestPermissions ?? [];
  const missingReasons = [];
  const legacyDeepReadOnlyReasons = [];
  for (const perm of permissions) {
    const reasonRef = typeof perm.reason === 'string' ? perm.reason : '';
    if (!reasonRef.startsWith('$string:')) {
      missingReasons.push(`${perm.name}: missing $string: reason`);
      continue;
    }
    const key = reasonRef.slice('$string:'.length);
    const val = stringMap.get(key);
    if (typeof val !== 'string' || val.trim().length === 0) {
      missingReasons.push(`${perm.name}: unresolved ${reasonRef}`);
    } else if (val.includes('决定是否启动深读') || val.includes('抓取深度阅读所需的来源')) {
      legacyDeepReadOnlyReasons.push(`${perm.name} (${key}): "${val}"`);
    }
  }

  if (missingReasons.length === 0 && legacyDeepReadOnlyReasons.length === 0) {
    addCheck(
      'H6-PERMISSION-REASONS',
      'local',
      'PASS',
      `全部 ${permissions.length} 项权限声明均具备真实、非空的用户可见用途说明`,
      'harmony/entry/src/main/module.json5 + string.json',
    );
  } else {
    addCheck(
      'H6-PERMISSION-REASONS',
      'local',
      'FAIL',
      '权限用途文案缺失或仍残留仅深读时代的旧描述',
      [...missingReasons, ...legacyDeepReadOnlyReasons].join('; '),
    );
  }

  // 4. 许可证声明一致性（根 LICENSE + 4 个 oh-package.json5 + SettingAboutPage.ets）
  const licensePath = path.join(repoRoot, 'LICENSE');
  const ohPackages = [
    'harmony/oh-package.json5',
    'harmony/entry/oh-package.json5',
    'harmony/chat/oh-package.json5',
    'harmony/deepread/oh-package.json5',
  ];
  const licenseMismatches = [];
  if (!fs.existsSync(licensePath)) {
    licenseMismatches.push('Missing root LICENSE');
  }
  for (const rel of ohPackages) {
    const pkg = readJson5(path.join(repoRoot, rel));
    if (pkg.license !== 'SEE LICENSE IN LICENSE') {
      licenseMismatches.push(`${rel}: license="${String(pkg.license)}"`);
    }
  }
  const aboutPagePath = path.join(repoRoot, 'harmony/entry/src/main/ets/pages/SettingAboutPage.ets');
  const aboutText = fs.readFileSync(aboutPagePath, 'utf8');
  if (!aboutText.includes('getBundleInfoForSelfSync')) {
    licenseMismatches.push('SettingAboutPage.ets 未使用 getBundleInfoForSelfSync 动态读取真实版本');
  }
  if (aboutText.includes('不收集任何用户数据')) {
    licenseMismatches.push('SettingAboutPage.ets 仍含不实的“不收集任何用户数据”绝对化声明');
  }
  if (!aboutText.includes('AGPL v3')) {
    licenseMismatches.push('SettingAboutPage.ets 未声明 AGPL v3 与商业许可双许可口径');
  }

  if (licenseMismatches.length === 0) {
    addCheck(
      'H6-LEGAL-ABOUT',
      'local',
      'PASS',
      '双许可声明、动态版本号读取与关于页数据安全说明一致',
      'LICENSE + 4×oh-package.json5 + SettingAboutPage.ets',
    );
  } else {
    addCheck(
      'H6-LEGAL-ABOUT',
      'local',
      'FAIL',
      '许可证声明或关于页合规项不一致',
      licenseMismatches.join('; '),
    );
  }

  // 5. 外部合规项（隐私政策 HTTPS URL / pac.json5 / ICP 备案）
  const pacPath = path.join(repoRoot, 'harmony/AppScope/resources/base/profile/pac.json5');
  if (fs.existsSync(pacPath)) {
    addCheck(
      'H6-PRIVACY-FILING',
      'release',
      'PASS',
      '已存在 pac.json5 隐私声明配置',
      pacPath,
    );
  } else {
    addCheck(
      'H6-PRIVACY-FILING',
      'release',
      'CONDITIONAL',
      '待填入运营主体、公网 HTTPS 隐私政策 URL、应用联网 ICP 备案与生成式 AI 合规材料',
      '详见 docs/release/huawei/appgallery-submission-checklist.md 与 Hub Library CONDITIONAL 清单',
    );
  }

  const passCount = checks.filter((c) => c.status === 'PASS').length;
  const conditionalCount = checks.filter((c) => c.status === 'CONDITIONAL').length;
  const failCount = checks.filter((c) => c.status === 'FAIL').length;

  return {
    ok: failCount === 0,
    strictOk: failCount === 0 && conditionalCount === 0,
    summary: { passCount, conditionalCount, failCount, total: checks.length },
    checks,
  };
};

if (import.meta.url === `file://${process.argv[1]}`) {
  const args = new Set(process.argv.slice(2));
  const strict = args.has('--strict');
  const asJson = args.has('--json');
  const result = runHarmonyReleasePreflight(DEFAULT_ROOT);

  if (asJson) {
    console.log(JSON.stringify(result, null, 2));
  } else {
    console.log('=== Amber HarmonyOS Phase 6 发布预检 ===');
    for (const c of result.checks) {
      console.log(`[${c.status}] ${c.id}: ${c.summary}`);
      if (c.detail) console.log(`       ${c.detail}`);
    }
    console.log(
      `\n汇总: PASS=${result.summary.passCount}, CONDITIONAL=${result.summary.conditionalCount}, FAIL=${result.summary.failCount}`,
    );
  }

  const exitOk = strict ? result.strictOk : result.ok;
  process.exit(exitOk ? 0 : 1);
}
