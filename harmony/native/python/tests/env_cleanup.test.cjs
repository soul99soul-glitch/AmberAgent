const assert = require('node:assert/strict');
const { test } = require('node:test');
const { Worker } = require('node:worker_threads');
const path = require('node:path');
const addon = path.resolve(__dirname, '../build/host/amber_python_host.node');
const resourceRoot = path.resolve(__dirname, '../../../entry/src/main/resources/rawfile/python');
const source = `
const {parentPort, workerData} = require('node:worker_threads');
const native = require(workerData.addon);
(async () => {
 const promise = native.pythonExecute('worker-request', {
  source: workerData.loop ? 'while True:\\n try:\\n  while True: pass\\n except BaseException:\\n  pass' : 'import json, math\\nprint(math.sqrt(16))',
  stdin: '', timeoutMs: 60000, resourceRoot: workerData.resourceRoot
 });
 parentPort.postMessage('started');
 const result = await promise;
 parentPort.postMessage(result);
})();`;
function worker(loop) {
 return new Worker(source, {eval: true, workerData: {addon, resourceRoot, loop}});
}
function message(instance) { return new Promise((resolve, reject) => { instance.once('message', resolve); instance.once('error', reject); }); }
test('env termination actually cancels and releases running interpreter', async () => {
 for (let i = 0; i < 8; ++i) {
  const instance = worker(true); assert.equal(await message(instance), 'started');
  await new Promise(resolve => setTimeout(resolve, 100));
  const start = Date.now(); await instance.terminate(); assert.ok(Date.now() - start < 1500);
 }
});
test('reinitialized owners run after env teardown without static-module pollution', async () => {
 for (let i = 0; i < 8; ++i) {
  const instance = worker(false);
  const result = new Promise((resolve, reject) => { instance.on('message', value => {if (value !== 'started') resolve(value);}); instance.once('error', reject); });
  assert.equal((await result).stdout, '4.0\n'); await instance.terminate();
 }
});
test('concurrent NAPI environments share one serial owner and shutdown independently', async () => {
 const one = worker(true); const two = worker(false);
 assert.equal(await message(one), 'started');
 const result = new Promise((resolve, reject) => {two.on('message', value => {if (value !== 'started') resolve(value);}); two.once('error', reject);});
 await new Promise(resolve => setTimeout(resolve, 100));
 await one.terminate(); assert.equal((await result).stdout, '4.0\n'); await two.terminate();
});
