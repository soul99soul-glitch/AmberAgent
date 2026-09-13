// Batch 4 (R22) regression probe — executes the REAL NotificationNotifier.ets.
// Asserts: (1) notifyCompleted awaits the real platform publish;
//          (2) an old run's cancelRunning(token) cannot撤 new run's notification;
//          (3) the matching token still cancels;
//          (4) P2-3: notifyCompleted REJECTS when the platform publish fails.
// Platform publish/cancel are deferred so landing order is observable.
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const ts = require('/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/node_modules/typescript');
const SRC = '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets';

const deferred = () => {
  let resolve; let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject, settled: false };
};
const platform = { queue: [], visible: new Map() };

const wantAgentMock = {
  OperationType: { START_ABILITY: 1 },
  WantAgentFlags: { UPDATE_PRESENT_FLAG: 1 },
  getWantAgent() {
    const d = deferred();
    platform.queue.push({ kind: 'want', d });
    return d.promise;
  },
};
const notificationManagerMock = {
  ContentType: { NOTIFICATION_CONTENT_BASIC_TEXT: 0 },
  publish(request) {
    const d = deferred();
    platform.queue.push({ kind: 'publish', id: request.id, title: request.content.normal.title, d });
    return d.promise;
  },
  cancel(id) {
    const d = deferred();
    platform.queue.push({ kind: 'cancel', id, d });
    return d.promise;
  },
};

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
new vm.Script(`(function(require,module,exports){${js}\n})`).runInContext(vm.createContext({}))
  (spec => { if (spec in mocks) return mocks[spec]; throw new Error(`unexpected require: ${spec}`); }, mod, mod.exports);

const notifier = mod.exports.createNotificationNotifier();
const flush = () => new Promise(r => setImmediate(r));
const settleKind = (kind) => {
  const i = platform.queue.findIndex(p => p.kind === kind);
  if (i < 0) return false;
  const p = platform.queue.splice(i, 1)[0];
  if (p.kind === 'publish') platform.visible.set(p.id, p.title);
  if (p.kind === 'cancel') platform.visible.delete(p.id);
  p.d.settled = true;
  p.d.resolve();
  return true;
};
const pending = (kind) => platform.queue.some(p => p.kind === kind);
// Reject the queued publish (platform failure) — P2-3 path
const failPublish = () => {
  const i = platform.queue.findIndex(p => p.kind === 'publish');
  if (i < 0) return false;
  const p = platform.queue.splice(i, 1)[0];
  p.d.settled = true;
  p.d.reject(new Error('platform publish failed'));
  return true;
};

let failures = 0;
const check = (label, cond) => { console.log(`${cond ? 'PASS' : 'FAIL'}  ${label}`); if (!cond) failures++; };

// Run fn; resolve want so it proceeds to publish; report whether the returned promise
// stays pending until the real publish promise lands.
const drive = async (fn) => {
  const p = fn();
  let returned = false;
  p.then(() => { returned = true; });
  settleKind('want');
  await flush();
  const pendingBeforePublish = !returned;
  settleKind('publish');
  await flush();
  await p;
  return pendingBeforePublish;
};

const T = 'topic-1';
const id = mod.exports.notificationIdFor(T);
platform.visible.clear();
platform.queue.length = 0;

// (1) notifyCompleted must await the real publish
const awaitedPublish = await drive(() => notifier.notifyCompleted(T, '标题', true, 2));
check('notifyCompleted stays pending until platform publish settles (awaits real publish)', awaitedPublish);
check('completed notification is visible after publish', platform.visible.get(id) === '深度阅读完成');
check('no stray cancel queued by notifyCompleted', !pending('cancel'));

// (2) new run B (token 2) publishes running; old run A (token 1) finally cancel must no-op
platform.visible.clear();
const runningB = notifier.notifyRunning(T, '标题', 2);
settleKind('want'); await flush();
settleKind('publish'); await flush();
await runningB;
await notifier.cancelRunning(T, 1); // stale token
check('stale old-run token cancel must not queue a cancel', !pending('cancel'));
check('new run notification survives stale cancel', platform.visible.get(id) === '正在深度阅读');

// (3) matching token still cancels
const cancelB = notifier.cancelRunning(T, 2);
check('matching token cancel is queued', pending('cancel'));
settleKind('cancel'); await flush();
await cancelB;
check('matching token cancel removes notification', !platform.visible.has(id));

// (4) P2-3: platform publish failure must make notifyCompleted REJECT
platform.queue.length = 0;
platform.visible.clear();
const failCall = notifier.notifyCompleted(T, '标题', true, 3);
settleKind('want'); await flush();
failPublish();
let rejected = false;
try { await failCall; } catch { rejected = true; }
check('notifyCompleted rejects when platform publish fails', rejected);
check('failed completion publish does not mark notification visible', !platform.visible.has(id));

console.log(`\n${failures === 0 ? 'ALL PASS' : `${failures} FAILURE(S)`}`);
process.exit(failures === 0 ? 0 : 1);
