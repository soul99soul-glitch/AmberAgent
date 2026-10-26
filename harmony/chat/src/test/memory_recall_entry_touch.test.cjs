const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const now = Date.UTC(2026, 9, 18, 4);
const sourceFile = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/ConversationRunDependencies.ets');

const loadRecall = (domain, initialRecords, { profile = null, probabilities = null, maxPromptChars = 6000,
  recalledMemoryIds = [] } = {}) => {
  let records = initialRecords;
  const touches = [];
  let reinforcementCalls = 0;
  const repository = {
    getActiveRecords: async () => records,
    touchMemories: async ids => {
      touches.push(Array.from(ids));
      records = domain.touchMemoryRecords(records, ids, now);
    },
    reinforceMemories: async () => { reinforcementCalls++; },
  };
  const imports = {
    '@amber/chat-domain': domain,
    '@kit.PerformanceAnalysisKit': { hilog: {} },
  };
  const source = fs.readFileSync(sourceFile, 'utf8') + '\nexport { memorySystemBlocks };\n';
  const js = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports = {};
  class Clock extends Date { static now() { return now; } }
  vm.runInNewContext(js, { exports, require: name => imports[name] ?? {}, Date: Clock,
    Map, Set, JSON, String, Number, Math, Error, Promise }, { filename: sourceFile });
  const conversation = domain.makeConversation('entry-recall', [
    domain.toMessageNode(domain.makeUserMessage('请解释当前项目')),
  ]);
  const ctx = {
    seed: { agentRuntime: { enableCoreMemory: true, enableShortTermMemory: true, enableLongTermMemory: true,
      memoryRecall: { maxItems: 12, maxPromptChars, debug: false } } },
    activeConversation: () => conversation,
    recalledMemoryIds: [...recalledMemoryIds],
  };
  return { run: () => exports.memorySystemBlocks(ctx, {
    memoryRepository: repository, memoryLibrary: { getAllRecords: async () => records },
    memoryTopics: { snapshotProfile: async () => profile },
    rerankContext: async () => probabilities === null ? null : { shadow: false, probabilities },
  }), ctx, touches,
    records: () => records, reinforcementCalls: () => reinforcementCalls };
};

const memory = (domain, id, content, options = {}) => domain.makeMemoryRecord({
  id, content, scope: 'long_term', kind: 'user', createdAt: now - 86400000,
  updatedAt: now - 86400000, ...options,
});

test('Entry recall touches the current injected record without reinforcing or retouching prior turns', async () => {
  const domain = await import('../main/ets/index.ts');
  const recall = loadRecall(domain, [memory(domain, 1, '用户偏好简洁中文回复')], { recalledMemoryIds: [9] });
  const blocks = await recall.run();
  assert.match(blocks.join('\n'), /用户偏好简洁中文回复/);
  assert.deepEqual(recall.ctx.recalledMemoryIds, [9, 1]);
  assert.deepEqual(recall.touches, [[1]]);
  assert.equal(recall.records()[0].lastUsedAt, now);
  assert.equal(recall.records()[0].reinforcementCount, 0);
  assert.equal(recall.reinforcementCalls(), 0);
});

test('Entry recall touches injected profile sources and verbatim records, excluding JeV rejected sources', async () => {
  const domain = await import('../main/ets/index.ts');
  const records = [memory(domain, 1, '用户偏好简洁中文回复'), memory(domain, 2, '用户偏好精确解释'),
    memory(domain, 3, '用户喜欢讨论技术'), memory(domain, 4, '当前项目需要保留数据', { scope: 'core', pinned: true })];
  const profile = domain.buildMemoryProfile([
    { text: '用户偏好简洁中文回复', memoryIds: [1] }, { text: '用户偏好精确解释', memoryIds: [2] },
  ], records.slice(0, 3), now);
  assert.ok(profile);
  const recall = loadRecall(domain, records, { profile, probabilities: { 1: 1, 2: 1, 3: 0, 4: 1 } });
  const blocks = await recall.run();
  assert.match(blocks[0], /<user-profile>/);
  assert.match(blocks[0], /memory_id=1/);
  assert.match(blocks[0], /memory_id=2/);
  assert.match(blocks[1], /\[memory:4\]/);
  assert.doesNotMatch(blocks.join('\n'), /用户喜欢讨论技术/);
  assert.deepEqual(recall.ctx.recalledMemoryIds, [1, 2, 4]);
  assert.deepEqual(recall.touches, [[1, 2, 4]]);
  assert.deepEqual(recall.records().map(row => row.lastUsedAt), [now, now, null, now]);
  assert.equal(recall.reinforcementCalls(), 0);
});

test('Entry recall does not touch records rejected by the final rendered prompt budget or all rejected by JeV', async () => {
  const domain = await import('../main/ets/index.ts');
  const records = [memory(domain, 1, '当前项目约束', { scope: 'core', pinned: true }),
    memory(domain, 2, '用户要求' + '精确解释'.repeat(20))];
  const recall = loadRecall(domain, records, { maxPromptChars: 300 });
  const blocks = await recall.run();
  assert.match(blocks.join('\n'), /\[memory:1\]/);
  assert.doesNotMatch(blocks.join('\n'), /\[memory:2\]/);
  assert.deepEqual(recall.ctx.recalledMemoryIds, [1]);
  assert.deepEqual(recall.touches, [[1]]);
  assert.deepEqual(recall.records().map(row => row.lastUsedAt), [now, null]);

  const rejected = loadRecall(domain, records, { probabilities: { 1: 0, 2: 0 } });
  assert.equal((await rejected.run()).length, 0);
  assert.deepEqual(rejected.ctx.recalledMemoryIds, []);
  assert.deepEqual(rejected.touches, []);
});
