import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  makeUserMessage, makeAssistantMessage, makeSystemMessage,
  getTools, isToolExecuted, canToolResumeExecution, latestAssistantText,
  UIMessagePartTool,
} from '../main/ets/agent/message.ts';

test('makeUserMessage creates user role with single text part', () => {
  const m = makeUserMessage('hello');
  assert.equal(m.role, 'user');
  assert.equal(m.parts.length, 1);
  assert.equal(m.parts[0].kind, 'text');
  if (m.parts[0].kind === 'text') assert.equal(m.parts[0].text, 'hello');
});

test('makeAssistantMessage creates empty assistant', () => {
  const m = makeAssistantMessage();
  assert.equal(m.role, 'assistant');
  assert.deepEqual(m.parts, []);
});

test('makeSystemMessage creates system role', () => {
  const m = makeSystemMessage('system prompt');
  assert.equal(m.role, 'system');
});

test('each message has unique id', () => {
  const a = makeUserMessage('a');
  const b = makeUserMessage('b');
  assert.notEqual(a.id, b.id);
});

test('getTools extracts only tool parts', () => {
  const m = makeAssistantMessage([
    { kind: 'text', text: 'thinking...' },
    { kind: 'tool', toolCallId: 'c1', toolName: 'search', input: '{}', output: [], approvalState: 'auto' },
    { kind: 'text', text: 'more' },
    { kind: 'tool', toolCallId: 'c2', toolName: 'write', input: '{}', output: [], approvalState: 'auto' },
  ]);
  const tools = getTools(m);
  assert.equal(tools.length, 2);
  assert.equal(tools[0].toolCallId, 'c1');
  assert.equal(tools[1].toolName, 'write');
});

test('isToolExecuted false when output empty', () => {
  const t: UIMessagePartTool = {
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}', output: [], approvalState: 'auto',
  };
  assert.equal(isToolExecuted(t), false);
});

test('isToolExecuted true when output non-empty', () => {
  const t: UIMessagePartTool = {
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}',
    output: [{ kind: 'text', text: 'result' }],
    approvalState: 'auto',
  };
  assert.equal(isToolExecuted(t), true);
});

test('canToolResumeExecution: approved + not executed = true', () => {
  const t: UIMessagePartTool = {
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}', output: [], approvalState: 'approved',
  };
  assert.equal(canToolResumeExecution(t), true);
});

test('canToolResumeExecution: executed = false even if approved', () => {
  const t: UIMessagePartTool = {
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}',
    output: [{ kind: 'text', text: 'done' }],
    approvalState: 'approved',
  };
  assert.equal(canToolResumeExecution(t), false);
});

test('canToolResumeExecution: pending = false (awaiting user)', () => {
  const t: UIMessagePartTool = {
    kind: 'tool', toolCallId: 'c1', toolName: 'x', input: '{}', output: [], approvalState: 'pending',
  };
  assert.equal(canToolResumeExecution(t), false);
});

test('latestAssistantText returns concatenated text of last assistant message', () => {
  const msgs = [
    makeUserMessage('q1'),
    makeAssistantMessage([
      { kind: 'text', text: 'first ' },
      { kind: 'reasoning', reasoning: 'internal', createdAt: 0, finishedAt: null },
      { kind: 'text', text: 'answer' },
    ]),
    makeUserMessage('q2'),
    makeAssistantMessage([
      { kind: 'text', text: 'second answer' },
    ]),
  ];
  assert.equal(latestAssistantText(msgs), 'second answer');
});

test('latestAssistantText returns empty string when no assistant message', () => {
  const msgs = [makeUserMessage('q1')];
  assert.equal(latestAssistantText(msgs), '');
});

test('JSON round-trip preserves discriminated union', () => {
  const m = makeAssistantMessage([
    { kind: 'text', text: 'a' },
    { kind: 'tool', toolCallId: 'c1', toolName: 'search', input: '{"q":"x"}', output: [], approvalState: 'auto' },
    { kind: 'image', url: 'https://x.com/y.png' },
  ]);
  const round = JSON.parse(JSON.stringify(m));
  assert.equal(round.parts[0].kind, 'text');
  assert.equal(round.parts[1].kind, 'tool');
  assert.equal(round.parts[2].kind, 'image');
});
