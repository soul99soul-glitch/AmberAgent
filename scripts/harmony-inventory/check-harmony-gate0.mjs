#!/usr/bin/env node

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync, execSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '../..');
const output = path.join(root, 'docs', 'harmony-gate0-report.md');

function commandVersion(command, args = ['--version']) {
  const location = resolveCommand(command);
  if (!location) return { status: 'missing', detail: 'not found in PATH' };
  return executableVersion(location, args);
}

function resolveCommand(command) {
  try {
    const location = execFileSync('sh', ['-lc', `command -v ${command}`], { encoding: 'utf8' }).trim();
    if (location) return location;
  } catch {
    // continue with zsh, because DevEco docs commonly ask users to edit zsh startup files on macOS.
  }
  try {
    const location = execFileSync('zsh', ['-ic', `command -v ${command}`], { encoding: 'utf8' }).trim();
    return location || null;
  } catch {
    return null;
  }
}

function executableVersion(executable, args = ['--version']) {
  let version = '';
  try {
    version = execFileSync(executable, args, { encoding: 'utf8', timeout: 5000 }).trim().split('\n')[0];
  } catch (error) {
    version = `found, version check failed: ${error.message}`;
  }
  return { status: 'found', detail: `${executable}${version ? ` (${version})` : ''}` };
}

function existsAny(paths) {
  for (const candidate of paths) {
    if (fs.existsSync(candidate)) return { status: 'found', detail: candidate };
  }
  return { status: 'missing', detail: paths.join(' ; ') };
}

function envVar(name) {
  const value = process.env[name];
  return value ? { status: 'found', detail: value } : { status: 'missing', detail: 'not set' };
}

function expandHome(candidate) {
  if (candidate.startsWith('~/')) return path.join(os.homedir(), candidate.slice(2));
  return candidate;
}

function findExistingExecutable(candidates, args = ['--version']) {
  for (const candidate of candidates.map(expandHome)) {
    if (fs.existsSync(candidate)) return executableVersion(candidate, args);
  }
  return { status: 'missing', detail: candidates.join(' ; ') };
}

function collectAppCandidates() {
  return [
    '/Applications/DevEco-Studio.app',
    '/Applications/DevEco Studio.app',
    path.join(os.homedir(), 'Applications/DevEco-Studio.app'),
    path.join(os.homedir(), 'Applications/DevEco Studio.app'),
  ];
}

function collectToolCandidates(toolName) {
  const apps = collectAppCandidates().filter((candidate) => fs.existsSync(candidate));
  const sdkRoots = [
    ...apps.flatMap((app) => [
      path.join(app, 'Contents', 'sdk'),
      path.join(app, 'Contents', 'tools'),
    ]),
    '~/Library/Huawei/Sdk',
    '~/HarmonyOS/Sdk',
    '~/command-line-tools',
    '~/Library/Huawei/commandline/command-line-tools',
  ].map(expandHome);

  const direct = [];
  for (const app of apps) {
    direct.push(path.join(app, 'Contents', 'tools', 'ohpm', 'bin', 'ohpm'));
    direct.push(path.join(app, 'Contents', 'tools', 'hvigor', 'bin', 'hvigor'));
    direct.push(path.join(app, 'Contents', 'tools', 'hvigor', 'bin', 'hvigorw'));
    direct.push(path.join(app, 'Contents', 'tools', 'node', 'bin', 'node'));
    direct.push(path.join(app, 'Contents', 'tools', 'nodejs', 'bin', 'node'));
    direct.push(path.join(app, 'Contents', 'jbr', 'Contents', 'Home', 'bin', 'java'));
  }
  direct.push(path.join(os.homedir(), 'command-line-tools', 'bin', toolName));
  direct.push(path.join(os.homedir(), 'Library', 'Huawei', 'commandline', 'command-line-tools', 'bin', toolName));

  const recursive = [];
  for (const base of sdkRoots) {
    collectByName(base, toolName, recursive, 5);
  }

  return [...new Set([...direct, ...recursive])];
}

function collectByName(dir, fileName, results, maxDepth, depth = 0) {
  if (depth > maxDepth || !fs.existsSync(dir)) return;
  let entries = [];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }
  for (const entry of entries) {
    const absolute = path.join(dir, entry.name);
    if (entry.isFile() && entry.name === fileName) results.push(absolute);
    if (entry.isDirectory()) collectByName(absolute, fileName, results, maxDepth, depth + 1);
  }
}

function toolVersion(command, args = ['--version']) {
  const pathResult = commandVersion(command, args);
  if (pathResult.status === 'found') return pathResult;
  return findExistingExecutable(collectToolCandidates(command), args);
}

function firstFound(...results) {
  const found = results.find((result) => result.status === 'found');
  if (found) return found;
  return {
    status: 'missing',
    detail: results.map((result) => result.detail).filter(Boolean).join(' ; '),
  };
}

function hdcTargets() {
  const hdc = resolveCommand('hdc') || collectToolCandidates('hdc').find((candidate) => fs.existsSync(candidate));
  if (!hdc) return { status: 'missing', detail: 'hdc unavailable or no targets: hdc executable not found' };
  try {
    const output = execFileSync(hdc, ['list', 'targets'], { encoding: 'utf8', timeout: 5000 }).trim();
    if (!output) return { status: 'missing', detail: 'hdc returned no targets' };
    return { status: 'found', detail: output.replace(/\n/g, '; ') };
  } catch (error) {
    return { status: 'missing', detail: `hdc unavailable or no targets: ${error.message}` };
  }
}

function sourceCommit() {
  try {
    return execSync('git rev-parse --short HEAD', { cwd: root, encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

const hvigorResult = firstFound(toolVersion('hvigor', ['-v']), toolVersion('hvigorw', ['-v']));
const ohpmResult = toolVersion('ohpm', ['-v']);
const hdcResult = toolVersion('hdc', ['-v']);
const commandLineToolsResult = existsAny([
  path.join(os.homedir(), 'command-line-tools'),
  path.join(os.homedir(), 'Library', 'Huawei', 'commandline', 'command-line-tools'),
]);
const devEcoAppResult = existsAny(collectAppCandidates());

const checks = [
  {
    id: 'G0-T01',
    item: 'DevEco Studio app',
    result: devEcoAppResult,
  },
  { id: 'G0-T02', item: 'Command Line Tools directory', result: commandLineToolsResult },
  { id: 'G0-T03', item: 'OHOS_SDK_HOME', result: envVar('OHOS_SDK_HOME') },
  { id: 'G0-T04', item: 'HARMONYOS_HOME', result: envVar('HARMONYOS_HOME') },
  { id: 'G0-T05', item: 'DEVECO_SDK_HOME', result: envVar('DEVECO_SDK_HOME') },
  { id: 'G0-T06', item: 'hvigor/hvigorw', result: hvigorResult },
  { id: 'G0-T07', item: 'ohpm', result: ohpmResult },
  { id: 'G0-T08', item: 'hdc', result: hdcResult },
  { id: 'G0-T09', item: 'hdc targets', result: hdcTargets() },
  { id: 'G0-T10', item: 'node', result: toolVersion('node', ['--version']) },
  { id: 'G0-T11', item: 'cmake', result: toolVersion('cmake', ['--version']) },
  { id: 'G0-T12', item: 'rustc', result: commandVersion('rustc', ['--version']) },
  { id: 'G0-T13', item: 'cargo', result: commandVersion('cargo', ['--version']) },
];

const blockingMissing = checks.filter((check) =>
  ['G0-T06', 'G0-T07', 'G0-T08', 'G0-T09'].includes(check.id) && check.result.status !== 'found'
);

const toolSourceReady = devEcoAppResult.status === 'found' || commandLineToolsResult.status === 'found';
if (!toolSourceReady) {
  blockingMissing.unshift({
    id: 'G0-TOOLS',
    item: 'DevEco app or Command Line Tools source',
    result: { status: 'missing', detail: 'Install DevEco Studio or Huawei Command Line Tools' },
  });
}

const gateStatus = blockingMissing.length === 0 ? 'ready' : 'blocked';

const lines = [
  '# HarmonyOS Gate 0 Toolchain Readiness Report',
  '',
  `> Generated: ${new Date().toISOString()}`,
  `> Source commit: \`${sourceCommit()}\``,
  `> Gate status: **${gateStatus}**`,
  '',
  'Gate 0 must be ready before any HarmonyOS spike result is marked `pass`, `limited`, or `fail` in `docs/harmony-capability-matrix.md`.',
  '',
  'Toolchain probe IDs use `G0-Txx` to avoid colliding with conceptual Gate 0 IDs in the capability matrix. Matrix mapping: `G0-01` ← `G0-T01` or `G0-T02`; `G0-02` ← SDK/tool availability around `G0-T03..G0-T08`; `G0-03` requires signing setup outside this script; `G0-04` ← `G0-T08..G0-T09`; `G0-05` ← `G0-T11..G0-T13` plus HarmonyOS native SDK.',
  '',
  '## Checks',
  '',
  '| ID | Item | Status | Detail |',
  '|---|---|---|---|',
  ...checks.map((check) => `| ${check.id} | ${check.item} | ${check.result.status} | ${check.result.detail.replace(/\|/g, '\\|')} |`),
  '',
  '## Blocking Missing Items',
  '',
  ...(blockingMissing.length
    ? blockingMissing.map((check) => `- ${check.id} ${check.item}: ${check.result.detail}`)
    : ['- None detected by this script.']),
  '',
  '## Notes',
  '',
  '- This script checks local toolchain availability only; it does not prove API-level feature support.',
  '- Missing CMake/Rust is not a Chat blocker by itself; native/NAPI work is an optimization track while ArkTS fallbacks exist.',
  '- DevEco Studio GUI is useful for project creation, SDK management, signing, and debugging, but Gate 0 can also be unlocked by Command Line Tools plus SDK plus hdc target.',
  '- Missing SDK env vars are informational when tools can discover SDKs internally, but missing hvigor/ohpm/hdc/device target blocks verified device spikes.',
  '- DevEco-generated project files should still be produced by DevEco Studio or verified templates, not hand-written by AI.',
  '- If Gate 0 is blocked, continue only source-only prep: fixtures, inventories, contracts, and documentation.',
  '- This script probes PATH, zsh interactive PATH, common DevEco app bundles, common SDK locations, and common Command Line Tools locations.',
  '',
];

fs.writeFileSync(output, lines.join('\n'));
console.log(`Gate 0 status: ${gateStatus}`);
console.log(`Wrote ${path.relative(root, output)}`);
process.exitCode = gateStatus === 'ready' ? 0 : 2;
