const assert = require('node:assert/strict');
const {test} = require('node:test');
const path = require('node:path');
const native = require('../build/host/amber_python_host.node');
const resourceRoot = path.resolve(__dirname, '../../../entry/src/main/resources/rawfile/python');
test('C trace remains installed after a caught interruption; no AST rewrite needed', async () => {
 const result = native.pythonExecute('handler-persistence', {resourceRoot, stdin:'', timeoutMs:5000,
  source: 'print("entered-handler-test", flush=True)\ntry:\n while True: pass\nexcept BaseException:\n print("cancel-was-swallowed")\nprint("after-handler")'});
 setTimeout(() => native.pythonCancel('handler-persistence'), 700);
 const stopped = await result;
 assert.equal(stopped.status, 'cancelled'); assert.equal(stopped.stdout, 'entered-handler-test\n');
});
