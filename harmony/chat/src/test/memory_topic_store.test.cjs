// Actual Entry store/coordinator/atomic I/O; controlled SDK host and provider only.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const fsp = fs.promises;
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const turn = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; };

test('actual topic source/model/document chain is durable, idempotent, guarded and cancellation-safe', { timeout: 5000 }, async () => {
  const domain = await import('../main/ets/index.ts');
  const directory = await fsp.mkdtemp(path.join(os.tmpdir(), 'e7-memory-topic-'));
  const entry = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
  const sourcePath = path.join(directory, 'memory/memory_records.json');
  const docs = path.join(directory, 'memory/documents');
  const values = new Map([['abilityContext', { filesDir: directory }], ['appBackgrounded', false], ['chatGenerating', false]]);
  const storage = { get: key => values.get(key), setOrCreate: (key, value) => values.set(key, value) };
  const kv = domain.createMemoryKeyValueStore();
  const worker = { enabled: false, extractionEnabled: false, dreamModelEnabled: true, topicWriteEnabled: true,
    runOnlyOnIdle: true, runOnlyOnCharging: true, dreamMaxDailyRuns: 1,
    daydreamModelId: 'daydream-choice', daydreamFollowCompressModel: false, daydreamReasoningLevel: 'high' };
  const setWorker = () => kv.put('agent_runtime', JSON.stringify({ memoryWorker: worker }));
  await setWorker();
  const rows = [1, 2].map(id => domain.makeMemoryRecord({ id, content: '已存中文事实' + id,
    scope: 'long_term', kind: 'note', assistantId: '__long_term__', createdAt: 1000, updatedAt: 1000,
    sourceConversationId: 'source-conversation', sourceMessageIds: ['source-' + id] }));
  // Old persisted JSON has no E7 fields.
  rows.forEach(row => { delete row.topicTitle; delete row.memberIds; });
  await fsp.mkdir(path.dirname(sourcePath), { recursive: true }); await fsp.writeFile(sourcePath, JSON.stringify(rows));
  let documentFailure = false, sourceReadFailure = false, writeBarrier = null;
  const handles = new Map(); let nextHandle = 1;
  const fileIo = {
    OpenMode: { READ_WRITE: 1, CREATE: 2, TRUNC: 4 },
    access: async file => { if (sourceReadFailure && file === sourcePath) throw new Error('controlled source read failure');
      try { await fsp.access(file); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } },
    readText: file => fsp.readFile(file, 'utf8'), mkdir: (file, recursive) => fsp.mkdir(file, { recursive }),
    listFile: file => fsp.readdir(file), unlink: file => fsp.unlink(file),
    stat: async file => { const stat = await fsp.stat(file); return { size: stat.size, mtime: stat.mtimeMs / 1000, isDirectory: () => stat.isDirectory() }; },
    open: async file => {
      if (documentFailure && file.startsWith(docs + '/')) throw new Error('controlled derived write failure');
      if (writeBarrier && file === sourcePath + '.tmp') { const barrier = writeBarrier; writeBarrier = null; barrier.entered.resolve(); await barrier.release.promise; }
      const handle = await fsp.open(file, 'w'); const fd = nextHandle++; handles.set(fd, handle); return { fd };
    },
    write: async (fd, buffer) => (await handles.get(fd).write(Buffer.from(buffer))).bytesWritten,
    close: async file => { await handles.get(file.fd).close(); handles.delete(file.fd); },
    renameSync: (from, to) => fs.renameSync(from, to), unlinkSync: file => fs.unlinkSync(file),
  };
  const batteryInfo = { chargingStatus: 1, BatteryChargeState: { ENABLE: 1, FULL: 3 } };
  const choice = { provider: domain.makeProviderSettingOpenAI(), model: domain.makeProviderModel({
    modelId: 'actual-daydream-model', customHeaders: [{ name: 'X-Model', value: 'configured' }],
    customBodies: [{ key: 'configured_body', value: 'true' }] }) };
  const calls = [], candidateChains = []; let model = async () => response();
  const response = () => ({ choices: [{ message: domain.makeAssistantMessage(JSON.stringify({ topics: [
    { title: '工程', summary: '两条已存事实的主题', memberIds: [1, 2] }], profile: [], duplicates: [] })) }] });
  const appContainer = { getChatKvStore: () => kv, TASK_COMPRESS_MODEL_KEY: 'compress_model',
    readTaskModelReference: async key => {
      assert.equal(key, 'compress_model'); return domain.taskModelReferenceFromId('compress-choice');
    },
    currentChatModelReference: async () => domain.taskModelReferenceFromId('chat-choice'),
    resolveTaskModelReferenceChain: async references => {
      candidateChains.push(references.map(domain.taskModelReferenceId)); return choice;
    },
    prepareProviderApi: async (provider, headers) => ({ generateText: async (messages, params, options) => {
      calls.push({ provider, headers, messages, params, options }); return model(); } }) };
  const imports = { '@amber/chat-domain': domain, '@kit.CoreFileKit': { fileIo },
    '@kit.PerformanceAnalysisKit': { hilog: { warn: () => {} } },
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) } } },
    '@kit.LocalizationKit': { i18n: { System: { getSystemLanguage: () => 'zh' } } },
    '@kit.BasicServicesKit': { batteryInfo }, '../di/AppContainer.ets': appContainer,
    './NotificationNotifier.ets': { publishBasicNotification: () => {}, cancelNotification: () => {} } };
  const cache = new Map();
  const load = name => {
    if (imports[name]) return imports[name];
    if (cache.has(name)) return cache.get(name);
    const exports = {}; cache.set(name, exports);
    const filename = path.join(entry, name);
    const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
    vm.runInNewContext(code, { exports, require: load, AppStorage: storage,
      Error, Promise, Map, Set, Math, Number, JSON, String, Date, Uint8Array, ArrayBuffer, setTimeout, clearTimeout }, { filename });
    return exports;
  };
  try {
    const store = load('./MemoryStore.ets'), ops = store.getMemoryTopicStoreOps(), library = store.getMemoryLibraryOps();
    const baseline = await ops.snapshotForTopics(); assert.equal(baseline[0].topicTitle, null); assert.deepEqual(baseline[0].memberIds, []);
    const coordinator = load('./MemoryTopicOps.ets').getMemoryTopicCoordinator();
    const first = await coordinator.run(true); assert.equal(first.status, 'completed'); assert.equal(first.sourceSaved, true); assert.equal(first.topicCount, 1);
    const topic = (await ops.snapshotForTopics()).find(row => row.kind === 'topic');
    const document = (await ops.listDocuments()).find(row => row.topicId === topic.id);
    assert.match(await ops.readDocument(document.relativePath), /已存中文事实1/);
    const modified = (await fsp.stat(path.join(docs, document.relativePath))).mtimeMs;
    const repeated = await coordinator.run(true); assert.equal(repeated.sourceSaved, false);
    assert.equal((await ops.snapshotForTopics()).find(row => row.kind === 'topic').id, topic.id);
    assert.equal((await fsp.stat(path.join(docs, document.relativePath))).mtimeMs, modified);
    assert.deepEqual(candidateChains[0], ['daydream-choice', 'compress-choice', 'chat-choice']);
    assert.equal(calls[0].headers['X-Model'], 'configured'); assert.equal(calls[0].params.reasoningLevel, 'high');
    assert.deepEqual(calls[0].params.customBody, choice.model.customBodies); assert.equal(calls[0].options.signal.aborted, false);
    assert.equal(values.get('chatGenerating'), false);

    documentFailure = true;
    await assert.rejects(library.updateContent(1, '编辑后的真实事实'), error => error instanceof store.MemoryDocumentRefreshError && error.sourceSaved);
    assert.equal(JSON.parse(await fsp.readFile(sourcePath, 'utf8')).find(row => row.id === 1).content, '编辑后的真实事实');
    assert.equal((await ops.snapshotForTopics()).find(row => row.id === topic.id).archived, true);
    await store.getMemoryReadRepository().touchMemories([1]); // Auxiliary recall is not coupled to failed document I/O.
    const preserved = await fsp.readFile(path.join(docs, document.relativePath), 'utf8');
    documentFailure = false; sourceReadFailure = true;
    await assert.rejects(ops.listDocuments(), /读取失败/);
    assert.equal(await fsp.readFile(path.join(docs, document.relativePath), 'utf8'), preserved);
    sourceReadFailure = false; await ops.syncDocuments();
    assert.equal(fs.existsSync(path.join(docs, document.relativePath)), false);
    assert.match(await ops.readDocument('index.md'), /编辑后的真实事实/);
    await assert.rejects(ops.readDocument('../memory_records.json'), /只能读取/);
    await ops.deleteTopic(topic.id);

    const beforeAuto = calls.length;
    batteryInfo.chargingStatus = 0; assert.equal((await coordinator.run(false)).status, 'skipped');
    batteryInfo.chargingStatus = 1; values.set('chatGenerating', true); assert.equal((await coordinator.run(false)).status, 'skipped');
    values.set('chatGenerating', false); assert.equal(calls.length, beforeAuto);
    const automatic = await coordinator.run(false); assert.equal(automatic.status, 'completed');
    assert.equal((await coordinator.run(false)).status, 'skipped'); assert.equal(calls.length, beforeAuto + 1);
    const autoTopic = (await ops.snapshotForTopics()).find(row => row.kind === 'topic'); await ops.deleteTopic(autoTopic.id);

    const modelEntered = deferred(), late = deferred(), queued = deferred();
    model = async () => { modelEntered.resolve(); return late.promise; };
    const actualOpsFactory = store.getMemoryTopicStoreOps;
    store.getMemoryTopicStoreOps = () => { const actual = actualOpsFactory(); return { ...actual,
      applyOptimization: (...args) => { const promise = actual.applyOptimization(...args); queued.resolve(); return promise; } }; };
    const active = coordinator.run(true); await modelEntered.promise;
    const barrier = { entered: deferred(), release: deferred() }; writeBarrier = barrier;
    const touch = store.getMemoryReadRepository().touchMemories([1]); await barrier.entered.promise;
    late.resolve(response()); await queued.promise; coordinator.cancel(); barrier.release.resolve(); await touch;
    assert.equal((await active).status, 'cancelled'); assert.equal((await ops.snapshotForTopics()).some(row => row.kind === 'topic'), false);

    worker.timeoutMs = 20; await setWorker(); const timedModel = deferred(); model = () => timedModel.promise;
    assert.equal((await coordinator.run(true)).status, 'timed_out');
    timedModel.resolve(response()); await turn(); assert.equal((await ops.snapshotForTopics()).some(row => row.kind === 'topic'), false);
    model = async () => response(); delete worker.timeoutMs; await setWorker();
    coordinator.background(); assert.equal((await coordinator.run(true)).status, 'cancelled');
    coordinator.foreground();
    const foregroundDone = deferred();
    const unsubscribe = coordinator.subscribe(state => { if (!state.running) foregroundDone.resolve(); });
    await foregroundDone.promise; unsubscribe();
    assert.equal((await coordinator.run(true)).status, 'completed'); assert.equal(values.get('chatGenerating'), false);

    // Queue/call time can precede expiry, while the actual commit is already after it.
    const oldNow = Date.now() - 1000;
    const expired = [1, 2].map(id => domain.makeMemoryRecord({ id, content: '刚到期源' + id,
      scope: 'short_term', kind: 'note', assistantId: '__short_term__', createdAt: oldNow - 1000,
      updatedAt: oldNow - 1000, expiresAt: oldNow + 500 }));
    await fsp.writeFile(sourcePath, JSON.stringify(expired));
    const declined = await ops.applyTopics(expired, [{ title: '旧分组', summary: '旧时间摘要', memberIds: [1, 2] }], oldNow);
    assert.equal(declined.staleCount, 1); assert.equal(declined.changed, false);
    assert.equal(fs.existsSync(path.join(docs, 'index.md')), false, 'Expired library must not retain an old-clock populated index');

    const persisted = JSON.stringify(expired.concat([topic]));
    await fsp.writeFile(sourcePath, persisted);
    const current = await ops.snapshotForTopics();
    assert.equal(await fsp.readFile(sourcePath, 'utf8'), persisted, 'Snapshot is a readonly availability view');
    assert.equal(current.filter(row => row.kind !== 'topic').length, 2);
    const expiredView = current.find(row => row.id === topic.id);
    assert.equal(expiredView.archived, true); assert.deepEqual(expiredView.memberIds, []);

    // The real coordinator persists all P1 outputs from a single provider response.
    const preferences = [10, 11, 12].map(id => domain.makeMemoryRecord({ id,
      content: '用户喜欢清楚简洁的解释' + id, scope: 'long_term', kind: 'user',
      createdAt: 1000, updatedAt: 1000 }));
    const duplicates = [20, 21].map(id => domain.makeMemoryRecord({ id,
      content: id === 20 ? '用户喜欢每日散步锻炼身体' : '用户喜欢每日散步锻炼身体。',
      scope: 'long_term', kind: 'note', createdAt: id * 1000, updatedAt: id * 1000 }));
    await fsp.writeFile(sourcePath, JSON.stringify(preferences.concat(duplicates)));
    model = async () => ({ choices: [{ message: domain.makeAssistantMessage(JSON.stringify({
      topics: [{ title: '表达偏好', summary: '偏好清楚简洁的解释', memberIds: [10, 11] }],
      profile: [{ text: '偏好清楚简洁的解释', memoryIds: [10, 11, 12] }], duplicates: [[20, 21]],
    })) }] });
    const beforeCombined = calls.length;
    const combined = await coordinator.run(true);
    assert.equal(combined.status, 'completed', combined.error);
    assert.equal(calls.length, beforeCombined + 1);
    assert.equal(combined.profileUpdated, true); assert.equal(combined.mergedCount, 1);
    const optimized = await ops.snapshotForTopics();
    assert.equal(optimized.find(row => row.id === 20).archived, true);
    assert.ok(optimized.find(row => row.id === 21).supersedesIds.includes(20));
    const profile = await ops.snapshotProfile();
    assert.equal(profile.items[0].text, '偏好清楚简洁的解释');
    assert.equal(JSON.parse(await fsp.readFile(path.join(directory, 'memory/profile.json'), 'utf8')).items[0].text,
      profile.items[0].text);
    assert.match(await ops.readDocument('index.md'), /用户画像/);
    assert.match(await ops.readDocument('index.md'), /偏好清楚简洁的解释/);

    // Real source queue: extraction history, profile invalidation, citation idempotence and restore.
    const extraction = store.getMemoryExtractionStoreOps();
    const beforeDeniedCommit = await fsp.readFile(sourcePath, 'utf8');
    await assert.rejects(extraction.applyActions([], Date.now(), async () => {
      throw new Error('Source changed before commit');
    }), /Source changed before commit/);
    assert.equal(await fsp.readFile(sourcePath, 'utf8'), beforeDeniedCommit);
    const appliedActions = await extraction.applyActions([{ action: 'invalidate', content: '',
      evidence: '我不再喜欢这种解释', sourceMessageId: 'user-update', sourceConversationId: 'test-chat',
      scope: 'long_term', kind: 'user', confidence: 1, expiresAt: null,
      targetId: 10, targetUpdatedAt: 1000 }], Date.now());
    assert.equal(appliedActions.invalidated.length, 1);
    assert.equal(await ops.snapshotProfile(), null);
    assert.equal(fs.existsSync(path.join(directory, 'memory/profile.json')), false);
    assert.doesNotMatch(await ops.readDocument('index.md'), /## 用户画像/);
    const readRepository = store.getMemoryReadRepository();
    await readRepository.reinforceMemories([11], 'assistant-1');
    await readRepository.reinforceMemories([11], 'assistant-1');
    await readRepository.touchMemories([11]);
    assert.equal((await library.getAllRecords()).find(row => row.id === 11).reinforcementCount, 1);
    await readRepository.reinforceMemories([11], 'assistant-2');
    assert.equal((await library.getAllRecords()).find(row => row.id === 11).reinforcementCount, 2);
    await library.restoreMemory(10);
    const restored = (await library.getAllRecords()).find(row => row.id === 10);
    assert.equal(restored.archived, false); assert.equal(restored.invalidatedAt, null);
    assert.equal((await library.getRecentEvents(1))[0].type, 'memory_restored');
    await library.restoreMemory(20);
    assert.equal(domain.nearDuplicateCandidates(await library.getAllRecords(), Date.now())
      .some(pair => [pair.a.id, pair.b.id].includes(20) && [pair.a.id, pair.b.id].includes(21)), false,
      'Restored semantic duplicate must not be archived again');
    const beforeSourceAddition = await library.getAllRecords();
    await library.addMemory({ content: '新的用户表达偏好需要下次重新整理', scope: 'long_term', kind: 'user' });
    const lateProfile = await ops.applyOptimization(beforeSourceAddition, [],
      {profile: [{text: '偏好清楚简洁的解释', memoryIds: [10,11,12]}], duplicates: []}, Date.now());
    assert.equal(lateProfile.profileUpdated, false, 'New sources not seen by the model cannot be marked evaluated');

  } finally { await fsp.rm(directory, { recursive: true, force: true }); }
});
