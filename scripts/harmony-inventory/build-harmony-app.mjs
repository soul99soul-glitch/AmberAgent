#!/usr/bin/env node

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '../..');
const harmonyDir = path.join(root, 'harmony');

function existing(...candidates) {
  return candidates.find((candidate) => fs.existsSync(candidate));
}

function normalizeDevEcoSdkHome(value) {
  if (!value) return value;
  const base = path.basename(value);
  if (base === 'default' && fs.existsSync(path.join(path.dirname(value), 'default'))) {
    return path.dirname(value);
  }
  return value;
}

const commandLineToolsRoot = existing(
  path.join(os.homedir(), 'Library', 'Huawei', 'commandline', 'command-line-tools'),
  path.join(os.homedir(), 'command-line-tools'),
);

const sdkRoot = normalizeDevEcoSdkHome(
  process.env.DEVECO_SDK_HOME ||
  (commandLineToolsRoot ? path.join(commandLineToolsRoot, 'sdk') : ''),
);
const sdkDefault = existing(
  process.env.OHOS_SDK_HOME || '',
  sdkRoot ? path.join(sdkRoot, 'default') : '',
);
const ohosBaseSdkHome = process.env.OHOS_BASE_SDK_HOME ||
  (sdkDefault ? path.join(sdkDefault, 'openharmony') : '');

if (!sdkRoot || !fs.existsSync(sdkRoot)) {
  console.error('Missing DEVECO_SDK_HOME. Expected the SDK parent directory, e.g. ~/Library/Huawei/commandline/command-line-tools/sdk');
  process.exit(2);
}
if (!ohosBaseSdkHome || !fs.existsSync(ohosBaseSdkHome)) {
  console.error('Missing OHOS_BASE_SDK_HOME. Expected the OpenHarmony SDK directory, e.g. <sdk>/default/openharmony');
  process.exit(2);
}

const env = {
  ...process.env,
  DEVECO_SDK_HOME: sdkRoot,
  OHOS_BASE_SDK_HOME: ohosBaseSdkHome,
};

const sdkNinja = path.join(ohosBaseSdkHome, 'native/build-tools/cmake/bin/ninja');
if (process.platform === 'darwin' && process.arch === 'arm64' && !env.AMBER_NATIVE_NINJA) {
  const check = spawnSync(sdkNinja, ['--version']);
  if (check.error?.errno === -86) {
    const armNinja = '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/build-tools/cmake/bin/ninja';
    const armCheck = spawnSync(armNinja, ['--version']);
    if (armCheck.status !== 0) throw new Error('The SDK Ninja cannot run; set AMBER_NATIVE_NINJA to a compatible Ninja.');
    env.AMBER_NATIVE_NINJA = armNinja;
  }
}

const args = process.argv.slice(2);
const taskArgs = args.length > 0 ? args : ['assembleApp', '--no-daemon'];
console.log(`DEVECO_SDK_HOME=${env.DEVECO_SDK_HOME}`);
console.log(`OHOS_BASE_SDK_HOME=${env.OHOS_BASE_SDK_HOME}`);
console.log(`Running: hvigorw ${taskArgs.join(' ')}`);

const result = spawnSync('hvigorw', taskArgs, {
  cwd: harmonyDir,
  env,
  stdio: 'inherit',
});

if (result.error) {
  console.error(result.error.message);
  process.exit(1);
}
process.exit(result.status ?? 1);
