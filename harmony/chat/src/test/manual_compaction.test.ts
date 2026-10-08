import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compactConversation, createMemoryCompactStore } from '../main/ets/chat/context_engine.ts';
import { compactTimelineSummary, makeCompactPolicy } from '../main/ets/chat/context_compact.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { makeAssistantRegex } from '../main/ets/chat/assistant.ts';
import { applyUserInputRegexes } from '../main/ets/chat/user_input.ts';
import type { CompactEngineDeps } from '../main/ets/chat/context_engine.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

test('manual force compresses below the automatic threshold, persists an injectable summary, and skips a single recent turn', async () => {
  const store = createMemoryCompactStore();
  let calls = 0;
  const summary = JSON.stringify({ schema_version: 2,
    timeline_summary: '目标已经确定。历史已经分析。方案已经选择。结果需要继续验证。',
    handoff_markdown: '## Goal\n' + '保留目标和执行状态。'.repeat(20) });
  const deps: CompactEngineDeps = { store, newId: () => 'manual-summary', provider: {
    streamText: async (_messages, onChunk): Promise<void> => {
      calls++;
      onChunk({ id: 'chunk', model: 'model', usage: null, choices: [{ index: 0, finishReason: null,
        message: null, delta: makeUIMessage('assistant', [{ type: 'text', text: summary, metadata: null }]) }] });
    },
  } };
  const nodes = Array.from({ length: 8 }, (_, index) => makeMessageNode([
    makeUIMessage(index % 2 === 0 ? 'user' : 'assistant', [{ type: 'text', text: `短消息${index}`, metadata: null }]),
  ]));
  const policy = makeCompactPolicy({ keepRecentTurns: 2 });
  const conversation = makeConversation('manual', nodes);
  const done = await compactConversation(conversation, policy, 128000, 'manual_compact', '', true, deps);
  assert.equal(done.status, 'completed'); assert.equal(calls, 1);
  assert.equal(done.sourceMessageCount, 4);
  const compacts = await store.getCompacts('manual');
  assert.equal(compacts.length, 1);
  assert.equal(compacts[0].sourceEndIndex, 3);
  assert.match(compactTimelineSummary(compacts[0].summary) ?? '', /目标已经确定/);
  const short = await compactConversation(makeConversation('short', nodes.slice(0, 2)), policy,
    128000, 'manual_compact', '', true, deps);
  assert.equal(short.status, 'skipped'); assert.equal(short.error, 'not_enough_history');
  assert.equal(calls, 1); assert.equal((await store.getCompacts('short')).length, 0);
});

test('user regex preserves document/metadata and excludes visual, disabled and assistant-only rules', () => {
  const metadata = { origin: 'input' };
  const parts: UIMessagePart[] = [{ type: 'text', text: 'foo', metadata },
    { type: 'document', url: 'data:text/plain;base64,YQ==', fileName: 'a.txt', mime: 'text/plain', metadata: null }];
  const rules = [
    makeAssistantRegex({ findRegex: 'foo', replaceString: 'foo!', affectingScope: ['user'] }),
    makeAssistantRegex({ findRegex: 'foo', replaceString: 'visual', affectingScope: ['user'], visualOnly: true }),
    makeAssistantRegex({ findRegex: 'foo', replaceString: 'assistant', affectingScope: ['assistant'] }),
    makeAssistantRegex({ findRegex: 'foo', replaceString: 'disabled', affectingScope: ['user'], enabled: false }),
  ];
  const accepted = applyUserInputRegexes(parts, rules);
  assert.equal(accepted[0].type === 'text' ? accepted[0].text : '', 'foo!');
  assert.equal(accepted[0].metadata, metadata); assert.equal(accepted[1], parts[1]);
  assert.equal(parts[0].type === 'text' ? parts[0].text : '', 'foo');
});
