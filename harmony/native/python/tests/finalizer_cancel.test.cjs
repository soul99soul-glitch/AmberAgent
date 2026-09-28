const assert = require('node:assert/strict');
const {test} = require('node:test');
const path = require('node:path');
const native = require('../build/host/amber_python_host.node');
const resourceRoot = path.resolve(__dirname, '../../../entry/src/main/resources/rawfile/python');
const options = (source, timeoutMs = 15000) => ({source,stdin:'',timeoutMs,resourceRoot});
test('warm finalizer cleanup remains cancellable and successor executes', async () => {
 assert.equal((await native.pythonExecute('warm-finalizer',options('pass'))).status,'completed');
 const pending = native.pythonExecute('finalizer', options('class Hold:\n def __del__(self):\n  while True: pass\nobj=Hold()\nprint("body-complete",flush=True)',5000));
 setTimeout(() => native.pythonCancel('finalizer'),1000);
 const result = await pending;
 assert.equal(result.status,'cancelled'); assert.equal(result.exitCode,null); assert.match(result.stdout,/body-complete/);
 assert.equal((await native.pythonExecute('after-finalizer',options('print(7)'))).stdout,'7\n');
});
test('dynamic type finalizer cleanup times out without a function-name ban', async () => {
 const result = await native.pythonExecute('dynamic-finalizer', options('def spin(self):\n while True: pass\nHold=type("Hold",(),{"__del__":spin})\nobj=Hold()\nprint("dynamic-body-complete",flush=True)',1500));
 assert.equal(result.status,'timed_out'); assert.equal(result.exitCode,null); assert.match(result.stdout,/dynamic-body-complete/);
 assert.equal((await native.pythonExecute('after-dynamic-finalizer',options('print(8)'))).stdout,'8\n');
});
