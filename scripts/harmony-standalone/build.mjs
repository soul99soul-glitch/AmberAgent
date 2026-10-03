#!/usr/bin/env node
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { prepareProduct, repositoryRoot, selectProducts } from './prepare.mjs';

function run(command, args, cwd, env = process.env) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(command)} failed with exit ${result.status}`);
}

function ohpmCommand() {
  if (process.env.AMBER_OHPM) return process.env.AMBER_OHPM;
  if (spawnSync('ohpm', ['-v'], { stdio: 'ignore' }).status === 0) return 'ohpm';
  const candidates = [
    path.join(os.homedir(), 'Library/Huawei/commandline/command-line-tools/bin/ohpm'),
    path.join(os.homedir(), 'command-line-tools/bin/ohpm'),
    '/Applications/DevEco-Studio.app/Contents/tools/ohpm/bin/ohpm',
    '/Applications/DevEco Studio.app/Contents/tools/ohpm/bin/ohpm',
  ];
  const found = candidates.find((candidate) => fs.existsSync(candidate));
  if (!found) throw new Error('ohpm not found; set AMBER_OHPM to the executable path.');
  return found;
}

try {
  const args = process.argv.slice(2);
  const sign = args.includes('--sign');
  const selected = selectProducts(args.filter((arg) => arg !== '--sign'));
  const ohpm = ohpmCommand();
  for (const product of selected) {
    const harmonyDir = prepareProduct(product);
    console.log(`Building ${product} in ${harmonyDir}`);
    run(ohpm, ['install', '--all'], harmonyDir);
    run(process.execPath, [path.join(repositoryRoot, 'scripts/harmony-inventory/build-harmony-app.mjs')],
      repositoryRoot, { ...process.env, AMBER_HARMONY_DIR: harmonyDir });
    if (sign) run('bash', [path.join(harmonyDir, 'scripts/sign-hap.sh')], harmonyDir);
    console.log(`${product} HAP output: ${path.join(harmonyDir, 'entry/build/default/outputs/default')}`);
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
