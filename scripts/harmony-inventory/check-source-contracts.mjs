#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { normalizeOpenAIStreamDataLines } from '../../harmony/spikes/source-only/streaming/openai_sse_parser_reference.mjs';

const root = path.resolve(import.meta.dirname, '../..');
const fixtures = path.join(root, 'harmony', 'tests', 'fixtures');

function readJson(rel) {
  return JSON.parse(fs.readFileSync(path.join(fixtures, rel), 'utf8'));
}

function readJsonl(rel) {
  return fs
    .readFileSync(path.join(fixtures, rel), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertDeepEqual(actual, expected, message) {
  const actualText = JSON.stringify(actual);
  const expectedText = JSON.stringify(expected);
  if (actualText !== expectedText) {
    throw new Error(`${message}\nactual:   ${actualText}\nexpected: ${expectedText}`);
  }
}

const checks = [];

checks.push(() => {
  const tsSource = fs.readFileSync(
    path.join(root, 'harmony', 'spikes', 'source-only', 'streaming', 'openai_sse_parser.ts'),
    'utf8'
  );
  const referenceSource = fs.readFileSync(
    path.join(root, 'harmony', 'spikes', 'source-only', 'streaming', 'openai_sse_parser_reference.mjs'),
    'utf8'
  );
  assert(tsSource.includes('if (lines.length === 1) return [toEvent(lines[0])]'), 'TS skeleton must return an array for single-line SSE events');
  for (const snippet of [
    "lines.join('\\n')",
    "if (looksLikeJson(joined))",
    "return { kind: 'done' }",
    "current.startsWith('data:')",
  ]) {
    assert(tsSource.includes(snippet), `TS skeleton missing parser snippet: ${snippet}`);
    assert(referenceSource.includes(snippet), `reference parser missing parser snippet: ${snippet}`);
  }
});

checks.push(() => {
  for (const row of readJsonl('streaming/openai_malformed_nested_data_prefix.jsonl')) {
    const events = normalizeOpenAIStreamDataLines(row.raw);
    assert(events[0]?.kind === 'payload', 'malformed nested data should produce payload');
    assert(events[0].json === row.expectedPayload, `expected payload mismatch for ${row.raw}`);
  }
});

checks.push(() => {
  for (const rel of ['streaming/openai_multi_line_payload.json', 'streaming/openai_multiline_data_prefixed_payload.json']) {
    const fixture = readJson(rel);
    const events = normalizeOpenAIStreamDataLines(fixture.rawData);
    const payloads = events.filter((event) => event.kind === 'payload').map((event) => event.json);
    assert(payloads.length === fixture.expectedPayloadCount, `${rel} payload count mismatch`);
    assertDeepEqual(JSON.parse(payloads[0]), fixture.expectedJson, `${rel} JSON mismatch`);
  }
});

checks.push(() => {
  const fixture = readJson('streaming/openai_independent_json_lines.json');
  const events = normalizeOpenAIStreamDataLines(fixture.rawData);
  const payloads = events.filter((event) => event.kind === 'payload').map((event) => JSON.parse(event.json));
  assertDeepEqual(payloads, fixture.expectedPayloads, 'independent JSON lines should split into separate payloads');
});

checks.push(() => {
  const rows = readJsonl('streaming/openai_chat_completion_chunks.jsonl');
  const done = rows.at(-1);
  const events = normalizeOpenAIStreamDataLines(done.raw);
  assert(events.length === 1 && events[0].kind === 'done', 'DONE row should parse as done');
  const empty = normalizeOpenAIStreamDataLines('   ');
  assert(empty.length === 1 && empty[0].kind === 'empty', 'blank data should parse as empty');
});

checks.push(() => {
  for (const row of readJsonl('streaming/openai_done_and_error.jsonl')) {
    const events = normalizeOpenAIStreamDataLines(row.raw);
    if (row.expected.done) {
      assert(events.length === 1 && events[0].kind === 'done', 'DONE fixture should parse as done');
    }
    if (row.expected.errorMessage) {
      assert(events[0]?.kind === 'payload', 'error fixture should parse as payload');
      const payload = JSON.parse(events[0].json);
      assert(payload.error?.message === row.expected.errorMessage, 'error message mismatch');
      assert(payload.error?.type === row.expected.errorType, 'error type mismatch');
    }
  }
});

checks.push(() => {
  const conversation = readJson('messages/conversation_minimal.json');
  const rows = readJson('storage/conversation_rdb_rows.json');
  assert(rows.sourceFixture === '../messages/conversation_minimal.json', 'storage source fixture link mismatch');
  assert(rows.tables.conversation[0].id === conversation.id, 'conversation id mismatch');
  assert(rows.tables.message_node.length === conversation.messageNodes.length, 'message node count mismatch');
});

checks.push(() => {
  const regex = readJson('transformers/regex_input_output.json');
  assert(regex.expected.parts.some((part) => part.type === 'text' && part.text === 'hi '), 'regex fixture should replace hello with hi');
  assert(regex.expected.parts.some((part) => part.type === 'reasoning' && part.reasoning === 'reasoning' && part.finishedAt === null), 'think fixture should expose unfinished reasoning');

  const promptExpected = readJson('transformers/prompt_injection_expected.json');
  assert(promptExpected.expectedSystemContains.includes('Base system prompt.'), 'prompt injection expected base system prompt missing');
  assert(promptExpected.expectedSystemContains.length >= 3, 'prompt injection expected should include injected content');
});

checks.push(() => {
  const events = readJsonl('agent_loop/turn_events.jsonl');
  assert(events.some((event) => event.type === 'tool_call'), 'agent loop happy path should include tool_call');
  assert(events.at(-1).type === 'done', 'agent loop happy path should end with done');

  const failed = readJsonl('agent_loop/turn_events_failed.jsonl');
  assert(failed.at(-1).type === 'error' && failed.at(-1).expectedStatus === 'failed', 'failed fixture should end in failed error');

  const cancelled = readJsonl('agent_loop/turn_events_cancelled.jsonl');
  assert(cancelled.some((event) => event.type === 'cancel_requested'), 'cancel fixture should include cancel_requested');
  assert(cancelled.at(-1).type === 'cancelled', 'cancel fixture should end with cancelled');
});

checks.push(() => {
  for (const rel of [
    'contracts/streaming_contract.md',
    'contracts/transformer_contract.md',
    'contracts/agent_loop_contract.md',
  ]) {
    const text = fs.readFileSync(path.join(fixtures, rel), 'utf8');
    for (const match of text.matchAll(/`\.\.\/([^`]+)`/g)) {
      const target = path.join(fixtures, match[1]);
      assert(fs.existsSync(target), `${rel} references missing fixture ${match[1]}`);
    }
  }
});

let passed = 0;
for (const check of checks) {
  check();
  passed += 1;
}

console.log(`Source contract checks passed (${passed} check groups).`);
