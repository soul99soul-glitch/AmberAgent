const assert = require('node:assert/strict');
const { test } = require('node:test');
const path = require('node:path');
const native = require('../build/host/amber_python_host.node');
const root = path.resolve(__dirname, '../../../entry/src/main/resources/rawfile/python');
let sequence = 0;
const options = (source, stdin = '', timeoutMs = 15000) => ({ source, stdin, timeoutMs, resourceRoot: root });
const execute = (source, stdin = '', timeoutMs = 15000) => native.pythonExecute('test-' + ++sequence, options(source, stdin, timeoutMs));

test('real CPython version and deterministic Unicode/data modules', async () => {
  assert.equal(native.pythonVersion(), '3.14.7');
  const result = await execute('import json, math\nprint(json.dumps({"value": math.sqrt(81), "文字": input()}, ensure_ascii=False))', '你好\n');
  assert.equal(result.status, 'completed'); assert.equal(result.exitCode, 0);
  assert.deepEqual(JSON.parse(result.stdout), { value: 9, '文字': '你好' });
});
test('stdin variable and input share bounded text without filesystem', async () => {
  const result = await execute('print(stdin)\nprint(input())', 'second\n');
  assert.equal(result.stdout, 'second\n\nsecond\n');
});
test('fresh globals and module state, stdio stays app owned', async () => {
  assert.equal((await execute('import json\njson.loads = lambda value: 99\nmarker = 123\nprint(json.loads("1"))')).stdout, '99\n');
  const result = await execute('import json\nprint(json.loads("1"))\nprint("marker" in dir())');
  assert.equal(result.stdout, '1\nFalse\n');
});
test('exceptions retain user-facing error text and next execution succeeds', async () => {
  const result = await execute('raise ValueError("fixture failure")');
  assert.equal(result.status, 'failed'); assert.equal(result.exitCode, 1);
  assert.equal(result.errorCode, 'python_exception'); assert.match(result.stderr, /ValueError: fixture failure/);
  assert.equal((await execute('print(7)')).stdout, '7\n');
});
test('syntax errors return bounded execution result', async () => {
  const result = await execute('def :'); assert.equal(result.errorCode, 'python_exception'); assert.match(result.stderr, /SyntaxError/);
});
for (const source of ['open("/tmp/unavailable")', 'import os', 'import pathlib', 'import socket', 'import subprocess', 'import ctypes', 'import pprint\npprint.sys.exit(0)', 'print((1).__class__)']) {
  test('restricted access: ' + source.split('\n')[0], async () => {
    const result = await execute(source); assert.equal(result.status, 'failed'); assert.equal(result.errorCode, 'python_exception');
    assert.match(result.stderr, /unavailable/);
  });
}
test('allowed module family is genuinely compiled/imported', async () => {
  const result = await execute('import array, base64, bisect, calendar, collections, datetime, decimal, enum, fractions, functools, heapq, itertools, json, math, pprint, re, statistics, string, textwrap, unicodedata\nprint(statistics.mean([1, 2, 3]), decimal.Decimal("1.2") + decimal.Decimal("2.3"))');
  assert.equal(result.status, 'completed', result.stderr); assert.equal(result.stdout, '2 3.5\n');
});
test('combined UTF8 stdout/stderr limit interrupts execution, never partial codepoint', async () => {
  const result = await execute('print("你" * 50000)');
  assert.equal(result.status, 'failed'); assert.equal(result.errorCode, 'output_limit');
  assert.ok(Buffer.byteLength(result.stdout) + Buffer.byteLength(result.stderr) <= 131072);
  assert.equal(result.stdout.includes('\ufffd'), false); assert.ok(result.stdout.length > 40000);
  assert.equal((await execute('print("after-limit")')).stdout, 'after-limit\n');
});
test('pure bytecode loop really times out and successor runs', async () => {
  const start = Date.now(); const result = await execute('print("entered-loop", flush=True)\nwhile True: pass', '', 1000);
  assert.equal(result.status, 'timed_out'); assert.equal(result.exitCode, null); assert.equal(result.stdout, 'entered-loop\n'); assert.ok(Date.now() - start < 2000);
  assert.equal((await execute('print("after-timeout")')).stdout, 'after-timeout\n');
});
for (const source of [
  'while True:\n try:\n  while True: pass\n except BaseException:\n  pass',
  'try:\n while True: pass\nexcept BaseException:\n while True: pass',
  'while True:\n try:\n  while True: pass\n except:\n  pass',
]) {
  test('catching interruption cannot keep bytecode running', async () => {
    const id = 'cancel-' + ++sequence; const start = Date.now();
    const pending = native.pythonExecute(id, options('print("entered-loop", flush=True)\n' + source));
    setTimeout(() => native.pythonCancel(id), 700);
    const result = await pending;
    assert.equal(result.status, 'cancelled'); assert.equal(result.exitCode, null); assert.equal(result.stdout, 'entered-loop\n'); assert.ok(Date.now() - start < 2000);
    assert.equal((await execute('print("after-cancel")')).stdout, 'after-cancel\n');
  });
}
test('queued cancellation does not run its body; owner remains serial', async () => {
  const first = 'serial-first-' + ++sequence; const second = 'serial-second-' + ++sequence;
  const one = native.pythonExecute(first, options('while True: pass'));
  const two = native.pythonExecute(second, options('print("must-not-run")'));
  native.pythonCancel(second); native.pythonCancel(first);
  assert.equal((await one).status, 'cancelled');
  const stopped = await two; assert.equal(stopped.status, 'cancelled'); assert.equal(stopped.stdout, '');
});
test('same request id reusable after real settlement and unknown cancel harmless', async () => {
  native.pythonCancel('not-active');
  for (let i = 0; i < 3; ++i) assert.equal((await native.pythonExecute('reusable', options('print(1)'))).stdout, '1\n');
});
test('duplicate active id and UTF8 argument limits reject before execution', async () => {
  const pending = native.pythonExecute('duplicate', options('while True: pass'));
  await assert.rejects(native.pythonExecute('duplicate', options('pass')), { code: 'duplicate_request' });
  native.pythonCancel('duplicate'); assert.equal((await pending).status, 'cancelled');
  await assert.rejects(execute('你'.repeat(90000)), { code: 'invalid_arguments' });
  await assert.rejects(execute('pass', '你'.repeat(23000)), { code: 'invalid_arguments' });
  await assert.rejects(execute('pass', '', 0), { code: 'invalid_arguments' });
});
