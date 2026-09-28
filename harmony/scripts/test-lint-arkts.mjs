import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./lint-arkts.sh', import.meta.url));
const fixture = mkdtempSync(join(tmpdir(), 'lint-arkts-test-'));
const callsFile = join(fixture, 'calls.jsonl');
const sdk = join(fixture, 'sdk');
const stub = join(sdk, 'bin', 'codelinter');
mkdirSync(dirname(stub), { recursive: true });
writeFileSync(stub, `#!${process.execPath}
const fs = require('fs');
fs.appendFileSync(process.env.LINT_STUB_CALLS, JSON.stringify(process.argv.slice(2)) + '\\n');
switch (process.env.LINT_STUB_MODE) {
  case 'red-error': console.error('\\x1b[31mEngine failed\\x1b[0m'); break;
  case 'plugin-error': console.error("Failed to load plugin '@typescript': Cannot find module '@typescript/eslint-plugin'"); break;
  case 'base-error': console.error('Some error occurred during linting. This may cause incomplete report results.'); break;
  case 'defect': console.log('-Defects: 1; Errors: 1; Warns: 0; Suggestions: 0;'); process.exit(4);
}
console.log('No defects found in your code.');
`, { mode: 0o755 });

function source(relative) {
  const path = join(fixture, relative);
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, 'export const value: number = 1;\n');
  return path;
}

const first = source('first file.ets');
const second = source('second.ts');
const directory = join(fixture, 'sources');
const third = source('sources/third.ets');
source('sources/build/ignored.ets');
source('sources/node_modules/ignored.ts');
source('sources/src/test/ignored.ts');

function run(targets, mode = 'clean', extraEnv = {}) {
  writeFileSync(callsFile, '');
  const result = spawnSync('/bin/bash', [script, ...targets], {
    encoding: 'utf8',
    env: {
      ...process.env,
      HARMONY_CMDLINE_DIR: sdk,
      LINT_STUB_CALLS: callsFile,
      LINT_STUB_MODE: mode,
      LINT_CHUNK: '80',
      LINT_EXIT_ON: 'error',
      ...extraEnv,
    },
  });
  assert.ifError(result.error);
  const calls = readFileSync(callsFile, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
  return { ...result, calls };
}

try {
  let result = run([first, second]);
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.calls[0].slice(0, 2), [first, second]);

  result = run([directory, second, first], 'clean', { LINT_CHUNK: '1' });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(result.calls.map((args) => args[0]), [third, second, first]);

  for (const mode of ['red-error', 'plugin-error', 'base-error']) {
    result = run([first], mode);
    assert.equal(result.status, 2, `${mode}: ${result.stdout} ${result.stderr}`);
    assert.match(result.stderr, /检查未完成/);
  }

  result = run([first], 'defect');
  assert.equal(result.status, 1);
  result = run([first], 'red-error', { LINT_EXIT_ON: 'none' });
  assert.equal(result.status, 2);
  assert.ok(!result.calls[0].includes('-e'));

  result = run([first], 'defect', { LINT_EXIT_ON: 'warn' });
  assert.equal(result.status, 1);
  assert.equal(result.calls[0][result.calls[0].indexOf('-e') + 1], 'error,warn');

  for (const chunk of ['0', '-1', 'bad']) {
    result = run([first], 'clean', { LINT_CHUNK: chunk });
    assert.equal(result.status, 2);
    assert.equal(result.calls.length, 0);
  }
  result = run([first, join(fixture, 'missing.ets')]);
  assert.equal(result.status, 2);
  assert.equal(result.calls.length, 0);
  const emptyDirectory = join(fixture, 'empty');
  mkdirSync(emptyDirectory);
  result = run([emptyDirectory]);
  assert.equal(result.status, 2);
  assert.equal(result.calls.length, 0);
  console.log('PASS: multi-target batching, ignores, tool-error gate, defect gate, none/warn, invalid targets/chunks');
} finally {
  rmSync(fixture, { recursive: true, force: true });
}
