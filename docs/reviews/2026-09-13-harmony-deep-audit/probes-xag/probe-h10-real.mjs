// H-10 evidence probe — executes the REAL NotificationNotifier.ets, not a transcription.
// NotificationNotifier.ets has no ArkUI struct/decorator, so it transpiles as plain TS.
// Local TypeScript (absolute path) + vm-based CommonJS loader; @kit.*/type-only modules
// are injected mocks. wantAgent.getWantAgent / notificationManager.publish / cancel are
// all deferred, so we control and observe platform landing order.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const ts = require('/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/node_modules/typescript');
const SRC = '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets';

// ---- deferred platform ----
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const platform = {
  queue: [],                 // {kind:'want'|'publish'|'cancel', id, title, d}
  visible: new Map(),        // id -> title (what the notification shade would show)
  calls: [],
};
const wantAgentMock = {
  OperationType: { START_ABILITY: 1 },
  WantAgentFlags: { UPDATE_PRESENT_FLAG: 1 },
  getWantAgent(info) {
    const d = deferred();
    platform.queue.push({ kind: 'want', id: info.requestCode, d });
    platform.calls.push('getWantAgent');
    return d.promise;
  },
};
const notificationManagerMock = {
  ContentType: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 },
  publish(request) {
    const d = deferred();
    platform.queue.push({ kind: 'publish', id: request.id, title: request.content.normal.title, d });
    platform.calls.push(`publish:${request.id}`);
    return d.promise;
  },
  cancel(id) {
    const d = deferred();
    platform.queue.push({ kind: 'cancel', id, d });
    platform.calls.push(`cancel:${id}`);
    return d.promise;
  },
};

// ---- transpile + load the real .ets ----
const js = ts.transpileModule(readFileSync(SRC, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 },
  fileName: SRC,
}).outputText;

const mocks = {
  '@kit.NotificationKit': { notificationManager: notificationManagerMock },
  '@kit.AbilityKit': { wantAgent: wantAgentMock },
  '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {}, error() {} } },
  '@kit.BasicServicesKit': {},
  '@amber/deepread-domain': {},
};
const mod = { exports: {} };
const fakeRequire = (spec) => {
  if (spec in mocks) return mocks[spec];
  throw new Error(`unexpected require: ${spec}`);
};
new vm.Script(`(function(require,module,exports){${js}\n})`).runInContext(vm.createContext({}))
  (fakeRequire, mod, mod.exports);

console.log('transpiled require targets ok; exports =', Object.keys(mod.exports));
const notifier = mod.exports.createNotificationNotifier();

const flush = () => new Promise(r => setImmediate(r));
const settle = (kind) => { const i = platform.queue.findIndex(p => p.kind === kind); if (i < 0) return false;
  const p = platform.queue.splice(i, 1)[0]; if (p.kind === 'publish') platform.visible.set(p.id, p.title);
  if (p.kind === 'cancel') platform.visible.delete(p.id); p.d.resolve(); return true; };

// drive one call and report when its returned promise resolves relative to platform ops
const step = async (label, fn) => { platform.calls.length = 0; const p = fn(); settle('want'); await flush(); await p;
  console.log(`  [${label}] returned; queued now =`, JSON.stringify(platform.queue.map(q => `${q.kind}:${q.id}`))); };

const T = 'topic-1';
console.log('\n=== A) normal successful run (scheduler.ts:141/150/159 order) ===');
await step('notifyRunning', () => notifier.notifyRunning(T, '标题'));
await step('notifyCompleted', () => notifier.notifyCompleted(T, '标题', true)); // publish dropped
await step('cancelRunning', () => notifier.cancelRunning(T));
console.log('  -> publish + cancel both still pending when awaits returned (order NOT pinned)');
// platform settles publish-then-cancel
settle('publish'); await flush(); settle('cancel'); await flush();
console.log('  platform settles [publish -> cancel] => visible =', platform.visible.get(mod.exports.notificationIdFor(T)) ?? '(none)');
// reset and try cancel-then-publish
platform.queue.length = 0; platform.visible.clear();
await step('notifyCompleted', () => notifier.notifyCompleted(T, '标题', true));
await step('cancelRunning', () => notifier.cancelRunning(T));
settle('cancel'); await flush(); settle('publish'); await flush();
console.log('  platform settles [cancel -> publish] => visible =', platform.visible.get(mod.exports.notificationIdFor(T)) ?? '(none)');

console.log('\n=== B) REPLACE: old A finally cancel vs new B notifyRunning (same id) ===');
platform.queue.length = 0; platform.visible.clear();
const b = notifier.notifyRunning(T, 'B');
await notifier.cancelRunning(T);        // old run A finally (scheduler.ts:159)
settle('want'); await flush(); await b;
console.log('  B running publish + A cancel queued =', JSON.stringify(platform.queue.map(q => `${q.kind}:${q.id}`)));
settle('publish'); await flush(); settle('cancel'); await flush();
console.log('  platform settles [publish -> cancel] => B running visible =', platform.visible.get(mod.exports.notificationIdFor(T)) ?? '(none)');
platform.queue.length = 0; platform.visible.clear();
const b2 = notifier.notifyRunning(T, 'B');
await notifier.cancelRunning(T);
settle('want'); await flush(); await b2;
settle('cancel'); await flush(); settle('publish'); await flush();
console.log('  platform settles [cancel -> publish] => B running visible =', platform.visible.get(mod.exports.notificationIdFor(T)) ?? '(none)');

console.log('\n=== ids ===');
console.log('  notificationIdFor(topic-1) =', mod.exports.notificationIdFor(T));
