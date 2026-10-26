const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const messages = require('../../../chat/src/main/ets/chat/message.ts');
const conversations = require('../../../chat/src/main/ets/chat/conversation.ts');
const { nowIso } = require('../../../chat/src/main/ets/chat/ids.ts');
const { parseMarkdown } = require('../../../chat/src/main/ets/chat/markdown_blocks.ts');

const settle = () => new Promise(resolve => setImmediate(resolve));

function fixture(debug = true) {
  const replays = [], saved = [], logs = [];
  const dependencies = {
    '@amber/chat-domain': { ...messages, ...conversations, nowIso },
    '@kit.AbilityKit': {},
    '@kit.PerformanceAnalysisKit': { hilog: {
      info: (...args) => logs.push(args), error: (...args) => logs.push(args),
    } },
    BuildProfile: { default: { DEBUG: debug } },
    '../di/AppContainer.ets': {
      getConversationRunService: () => ({
        replayDebugVisual: (initial, frames, finalConversation, stepMs) => {
          replays.push({ initial, frames, finalConversation, stepMs });
          return { done: Promise.resolve() };
        },
      }),
      getChatRepository: () => ({ save: async conversation => { saved.push(conversation); } }),
    },
    './ConversationRunService.ets': { DEBUG_VISUAL_CONVERSATION_PREFIX: '__amber_debug_visual__-' },
    './MiniAppPlatform.ets': { getMiniAppPlatform: () => { throw new Error('Performance replay must not create a MiniApp'); } },
    './ChatVisualWeatherFixture.ets': {},
    './ChatSmartUiComponentFixture.ets': {},
  };
  const filename = path.resolve(__dirname, '../main/ets/platform_impl/ChatVisualFixture.ets');
  const output = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', output)(exports, key => {
    if (!(key in dependencies)) throw new Error('Unexpected visual replay dependency: ' + key);
    return dependencies[key];
  });
  return { ...exports, replays, saved, logs };
}

async function replay(kind, persist = false) {
  const owner = fixture();
  owner.runChatVisualFixture({ parameters: {
    amberChatVisualFixture: kind, amberChatVisualRunId: 'Performance1', amberChatVisualPersist: String(persist),
  } });
  await settle();
  assert.equal(owner.replays.length, 1, `${kind} must reach the real replay entry`);
  assert.equal(owner.saved.length, persist ? 1 : 0, 'only the explicit persistence flag may save fixture history');
  return { owner, ...owner.replays[0] };
}

test('long reasoning replay preserves full source and finishes reasoning before its answer body', async () => {
  const { initial, frames, finalConversation, stepMs } = await replay('perf-reasoning');
  assert.equal(initial.messageNodes.length, 1);
  assert.ok(frames.length >= 12 && frames.length <= 64);
  assert.ok(stepMs >= 500, 'the existing replay cadence must leave a usable screenshot window');
  const final = conversations.currentMessages(finalConversation).at(-1);
  const reasoning = final.parts.find(part => part.type === 'reasoning');
  assert.ok(reasoning.reasoning.length > 6000, 'source must exceed both the old and current display windows');
  assert.ok(reasoning.reasoning.includes('\n\n'), 'raw line and paragraph boundaries are part of the sample');
  assert.ok(reasoning.finishedAt);
  assert.ok(final.finishedAt);
  assert.ok(final.parts.some(part => part.type === 'text' && part.text.length > 0));
  let prior = '';
  for (const frame of frames) {
    assert.equal(frame.id, final.id);
    assert.equal(frame.finishedAt, null);
    const thought = frame.parts.find(part => part.type === 'reasoning');
    assert.ok(thought.reasoning.startsWith(prior), 'reasoning must be append-only through the replay');
    assert.ok(reasoning.reasoning.startsWith(thought.reasoning), 'no frame may contain a display-trimmed source');
    prior = thought.reasoning;
  }
  assert.ok(frames.some(frame => frame.parts.some(part => part.type === 'reasoning' && part.finishedAt === null)));
  assert.ok(frames.some(frame => frame.parts.some(part => part.type === 'reasoning' && part.finishedAt !== null)
    && frame.parts.some(part => part.type === 'text' && part.text.length > 0)));
  assert.equal(prior, reasoning.reasoning);
});

test('history replay has stable distinct rows and appends its live answer after the retained history', async () => {
  const { initial, frames, finalConversation } = await replay('perf-history');
  assert.equal(initial.messageNodes.length, 120);
  assert.equal(new Set(initial.messageNodes.map(node => node.id)).size, 120);
  assert.equal(new Set(conversations.currentMessages(initial).map(message => message.id)).size, 120);
  assert.equal(finalConversation.messageNodes.length, 121);
  for (let index = 0; index < initial.messageNodes.length; index++) {
    assert.strictEqual(finalConversation.messageNodes[index], initial.messageNodes[index]);
  }
  assert.ok(messages.toText(conversations.currentMessages(initial)[0]).includes('001'));
  assert.ok(messages.toText(conversations.currentMessages(initial).at(-1)).includes('120'));
  const final = conversations.currentMessages(finalConversation).at(-1);
  assert.equal(messages.toText(frames.at(-1)), messages.toText(final));
  assert.ok(frames.every(frame => frame.id === final.id && frame.finishedAt === null));
});

test('rich replay grows one source containing long prose, code, a table, math and Mermaid without network assets', async () => {
  const { frames, finalConversation } = await replay('perf-rich');
  const final = conversations.currentMessages(finalConversation).at(-1);
  const content = messages.toText(final);
  assert.ok(content.length > 6000);
  assert.equal(messages.toText(frames.at(-1)), content);
  let previous = '';
  for (const frame of frames) {
    const text = messages.toText(frame);
    assert.ok(text.startsWith(previous));
    assert.ok(content.startsWith(text));
    previous = text;
  }
  const blocks = parseMarkdown(content);
  assert.ok(blocks.some(block => block.kind === 'paragraph'
    && block.inlines.reduce((sum, inline) => sum + (inline.text?.length || 0), 0) > 1500));
  assert.ok(blocks.some(block => block.kind === 'code_block' && block.lang === 'typescript'));
  assert.ok(blocks.some(block => block.kind === 'table' && block.rows.length >= 8));
  assert.ok(blocks.some(block => block.kind === 'paragraph'
    && block.inlines.some(inline => inline.type === 'math' && inline.display)));
  assert.ok(blocks.some(block => block.kind === 'code_block' && block.lang === 'mermaid'));
  assert.equal(/https?:\/\//.test(content), false);
});

test('performance replay saves only when explicitly requested and is unavailable outside Debug', async () => {
  await replay('perf-history', true);
  const owner = fixture(false);
  owner.runChatVisualFixture({ parameters: {
    amberChatVisualFixture: 'perf-history', amberChatVisualRunId: 'ReleaseCase', amberChatVisualPersist: 'true',
  } });
  await settle();
  assert.equal(owner.replays.length, 0);
  assert.equal(owner.saved.length, 0);
});
