import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  makeUserMessage, makeAssistantMessage, makeSystemMessage, makeUIMessage,
  getTools, isToolExecuted, canToolResumeExecution, latestAssistantText,
} from '../main/ets/agent/message.ts';
import type {
  UIMessagePart, UIMessagePartText, UIMessagePartTool,
} from '../main/ets/agent/message.ts';

const textPart = (text: string): UIMessagePartText => ({ type: 'text', text, metadata: null });

const toolPart = (
  toolCallId: string,
  toolName: string = 'x',
  approvalState: UIMessagePartTool['approvalState'] = { type: 'auto' },
  output: UIMessagePart[] = [],
): UIMessagePartTool => ({
  type: 'tool', toolCallId, toolName, input: '{}', output, approvalState, metadata: null,
});

test('makeUserMessage creates user role with single text part', () => {
  const m = makeUserMessage('hello');
  assert.equal(m.role, 'user');
  assert.equal(m.parts.length, 1);
  assert.equal(m.parts[0].type, 'text');
  if (m.parts[0].type === 'text') assert.equal(m.parts[0].text, 'hello');
});

test('makeAssistantMessage creates empty assistant', () => {
  const m = makeAssistantMessage('');
  assert.equal(m.role, 'assistant');
  assert.deepEqual(m.parts, [textPart('')]);
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
  const m = makeUIMessage('assistant', [
    textPart('thinking...'),
    toolPart('c1', 'search'),
    textPart('more'),
    toolPart('c2', 'write'),
  ]);
  const tools = getTools(m);
  assert.equal(tools.length, 2);
  assert.equal(tools[0].toolCallId, 'c1');
  assert.equal(tools[1].toolName, 'write');
});

test('isToolExecuted false when output empty', () => {
  const t = toolPart('c1');
  assert.equal(isToolExecuted(t), false);
});

test('isToolExecuted true when output non-empty', () => {
  const t = toolPart('c1', 'x', { type: 'auto' }, [textPart('result')]);
  assert.equal(isToolExecuted(t), true);
});

test('canToolResumeExecution: approved + not executed = true', () => {
  const t = toolPart('c1', 'x', { type: 'approved' });
  assert.equal(canToolResumeExecution(t), true);
});

test('canToolResumeExecution: executed = false even if approved', () => {
  const t = toolPart('c1', 'x', { type: 'approved' }, [textPart('done')]);
  assert.equal(canToolResumeExecution(t), false);
});

test('canToolResumeExecution: pending = false (awaiting user)', () => {
  const t = toolPart('c1', 'x', { type: 'pending' });
  assert.equal(canToolResumeExecution(t), false);
});

test('latestAssistantText returns concatenated text of last assistant message', () => {
  const msgs = [
    makeUserMessage('q1'),
    makeUIMessage('assistant', [
      textPart('first '),
      {
        type: 'reasoning', reasoning: 'internal',
        createdAt: '1970-01-01T00:00:00.000Z', finishedAt: null, metadata: null,
      },
      textPart('answer'),
    ]),
    makeUserMessage('q2'),
    makeUIMessage('assistant', [textPart('second answer')]),
  ];
  assert.equal(latestAssistantText(msgs), 'second answer');
});

test('latestAssistantText returns empty string when no assistant message', () => {
  const msgs = [makeUserMessage('q1')];
  assert.equal(latestAssistantText(msgs), '');
});

test('JSON round-trip preserves discriminated union', () => {
  const m = makeUIMessage('assistant', [
    textPart('a'),
    { ...toolPart('c1', 'search'), input: '{"q":"x"}' },
    { type: 'image', url: 'https://x.com/y.png', metadata: null },
  ]);
  const round = JSON.parse(JSON.stringify(m));
  assert.equal(round.parts[0].type, 'text');
  assert.equal(round.parts[1].type, 'tool');
  assert.equal(round.parts[2].type, 'image');
});
