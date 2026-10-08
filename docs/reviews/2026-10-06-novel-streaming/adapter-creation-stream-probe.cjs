const assert = require('node:assert/strict');
const root = '/Users/arquiel/Downloads/AI/amber-harmony-preview';
const domain = require(root + '/harmony/deepread/src/main/ets/index.ts');
const { createNovelInteractiveAdapter } = require(root + '/harmony/chat/src/main/ets/chat/novel_interactive_adapter.ts');
const { makeAssistant } = require(root + '/harmony/chat/src/main/ets/chat/assistant.ts');
const { makeAssistantMessage } = require(root + '/harmony/chat/src/main/ets/chat/message.ts');

(async () => {
  for (const mode of ['discuss', 'write', 'quick_start']) {
    let dataEnded = false;
    const files = domain.createMemoryFileStore();
    const repository = domain.createFileNovelRepository(files);
    const project = await repository.createProject(domain.makeNovelProject({
      id: 'stream-' + mode, name: 'stream QA', now: 1,
    }));
    const oldSnapshots = [];
    const progress = [];
    const provider = { streamText: async (_messages, onChunk, opts) => {
      for (const text of ['one', ' two', ' three']) {
        onChunk({ id: 'chunk', model: 'fixture', usage: null, choices: [{
          index: 0, delta: makeAssistantMessage(text), message: null, finishReason: null,
        }] });
        await new Promise(resolve => setTimeout(resolve, 15));
      }
      dataEnded = true;
      opts?.onDataEnd?.();
    } };
    const adapter = createNovelInteractiveAdapter({
      resolveRuntime: async () => ({ assistant: makeAssistant({}), provider,
        runtimeSnapshot: { providerId: 'fixture', modelId: 'fixture', configurationJson: '{}' } }),
      createAbortController: () => new AbortController(),
    });
    const creation = domain.createNovelCreation({ repository, modelRunning: adapter });
    const run = mode === 'quick_start' ? await creation.quickStart(project.id, 'idea')
      : creation.generate(project.id, 'idea', mode, mode === 'write' ? 'whole_chapter' : null,
        mode === 'write' ? 'prose_whole_chapter' : 'discussion');
    const terminal = await new Promise(resolve => run.subscribe(event => {
      if (event.kind === 'snapshot') {
        const count = creation.activeRun(project.id).historyCount;
        const text = domain.latestAssistantText(event.messages.slice(count));
        if (text) {
          oldSnapshots.push(event.messages);
          progress.push({ text, beforeDataEnd: !dataEnded, live: event.textDeltasLive });
        }
      }
      if (['completed', 'failed', 'interrupted'].includes(event.kind)) resolve(event);
    }));
    assert.deepEqual(progress.filter(frame => frame.beforeDataEnd).map(frame => frame.text),
      ['one', 'one two', 'one two three']);
    assert.equal(domain.latestAssistantText(oldSnapshots[0]), 'one');
    assert.ok(progress.some(frame => frame.beforeDataEnd && frame.live));
    assert.equal(terminal.kind, mode === 'quick_start' ? 'failed' : 'completed');
    console.log(JSON.stringify({ mode, progress, terminal: terminal.kind,
      firstSnapshotStill: domain.latestAssistantText(oldSnapshots[0]),
      quickStartFixtureNote: mode === 'quick_start' ? 'Plain text intentionally fails structured quick-start parsing after valid streaming.' : undefined }));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
