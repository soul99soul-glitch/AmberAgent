const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const vm = require('node:vm');
const ts = require('typescript');
const main = path.resolve(__dirname, '../../../entry/src/main');

test('streaming request deduplicates snapshots and skips devices before API20', () => {
  for (const sdk of [12, 26]) {
    const calls = [];
    const source = ts.transpileModule(fs.readFileSync(path.join(main, 'ets/components/StreamingFrameRate.ets'), 'utf8'),
      { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
    const exports = {};
    vm.runInNewContext(source, { exports, canIUse: () => true, require: name => {
      if (name === 'libamber_native.so') return { default: {
        setStreamingFrameRateActive: active => { calls.push(active); return 0; }
      } };
      if (name === '@kit.BasicServicesKit') return { deviceInfo: { sdkApiVersion: sdk } };
      return { hilog: { warn() {} } };
    } });
    const rate = new exports.StreamingFrameRate();
    rate.update(true); rate.update(true); rate.update(true); rate.update(false); rate.update(false);
    assert.deepEqual(calls, sdk >= 20 ? [true, false] : []);
  }
});

test('real native worker handles duplicate start, stop during wait, late callback and failures', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-native-vsync-'));
  try {
    // Compile the production worker with host replacements for the OS transport.
    // NAPI registration is excluded; its actual ABI is checked by the HAP build.
    const source = fs.readFileSync(path.join(main, 'cpp/stream_frame_rate.cpp'), 'utf8');
    const core = source.slice(source.indexOf('#include <atomic>'), source.indexOf('void Cleanup(void *)')) + '\n}\n';
    const harness = `
#include <dlfcn.h>
#include <cassert>
#include <atomic>
#include <chrono>
#include <thread>
struct OH_NativeVSync {};
struct OH_NativeVSync_ExpectedRateRange { int min, max, expected; };
using Callback = void (*)(long long, void *);
std::atomic<int> creates{0}, destroys{0}, requests{0};
std::atomic<bool> missing{false}, pauseFrames{false}, requestError{false};
Callback saved = nullptr; void *savedData = nullptr;
int SetExpected(OH_NativeVSync *, OH_NativeVSync_ExpectedRateRange *range) {
  assert(range->min == 60 && range->max == 120 && range->expected == 120); return 0;
}
void *Lookup(void *, const char *) { return missing ? nullptr : reinterpret_cast<void *>(SetExpected); }
#define dlsym Lookup
#define LOG_APP 0
#define OH_LOG_INFO(...) ((void)0)
#define OH_LOG_WARN(...) ((void)0)
OH_NativeVSync *OH_NativeVSync_Create(const char *, unsigned int) { ++creates; return new OH_NativeVSync; }
void OH_NativeVSync_Destroy(OH_NativeVSync *v) { ++destroys; delete v; }
int OH_NativeVSync_GetPeriod(OH_NativeVSync *, long long *p) { *p = 8333333; return 0; }
int OH_NativeVSync_RequestFrame(OH_NativeVSync *, Callback cb, void *data) {
  saved = cb; savedData = data; ++requests;
  if (requestError) return 50401000;
  if (!pauseFrames) { std::this_thread::sleep_for(std::chrono::milliseconds(1)); cb(0, data); }
  return 0;
}
${core}
int main() {
  auto &controller = Controller();
  missing = true; assert(controller.Start() == -1); assert(creates == 0);
  missing = false; assert(controller.Start() == 0);
  assert(controller.Start() == 0); assert(creates == 1);
  while (requests < 3) std::this_thread::yield();
  controller.Stop(); assert(destroys == 1); controller.Stop();
  // A callback copied before Destroy can still fire without touching freed data.
  saved(0, savedData);
  pauseFrames = true; int prior = requests;
  assert(controller.Start() == 0);
  while (requests == prior) std::this_thread::yield();
  auto started = std::chrono::steady_clock::now();
  controller.Stop();
  assert(std::chrono::steady_clock::now() - started < std::chrono::milliseconds(80));
  assert(destroys == 2); saved(0, savedData);
  requestError = true; assert(controller.Start() == 0);
  while (destroys < 3) std::this_thread::yield();
  controller.Stop(); assert(creates == destroys);
  requestError = false; assert(controller.Start() == 0);
  std::this_thread::sleep_for(std::chrono::milliseconds(140));
  controller.Stop(); assert(creates == destroys);
}
`;
    const file = path.join(temp, 'test.cpp'), binary = path.join(temp, 'test');
    fs.writeFileSync(file, harness);
    execFileSync('c++', ['-std=c++17', '-pthread', '-Wall', '-Wextra', file, '-o', binary]);
    execFileSync(binary, [], { timeout: 5000 });
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
});
