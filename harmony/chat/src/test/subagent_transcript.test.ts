// subagent_transcript.test.ts — D-132a Task 5
// Android baseline: SubAgentTranscriptReader.kt/Test + SubAgentManager.appendEvent
import assert from 'node:assert/strict';
import { test } from 'node:test';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type {
  SubAgentTranscriptPort, SubAgentTranscriptTail,
} from '../main/ets/chat/subagent_transcript.ts';
import { readSubAgentDisplayTextFromTranscript } from '../main/ets/index.ts';
import { appendSubAgentTranscriptEvent } from '../main/ets/chat/subagent_transcript.ts';

interface AppendCall {
  path: string;
  text: string;
}

interface ReadTailCall {
  path: string;
  maxBytes: number;
}

class MemoryTranscriptPort implements SubAgentTranscriptPort {
  readonly contents: Map<string, string> = new Map();
  readonly canonicalPaths: Map<string, string | null> = new Map();
  readonly regularFiles: Set<string> = new Set();
  readonly unreadableFiles: Set<string> = new Set();
  readonly appendCalls: AppendCall[] = [];
  readonly readTailCalls: ReadTailCall[] = [];

  canonicalPath(path: string): Promise<string | null> {
    if (this.canonicalPaths.has(path)) {
      const resolved: string | null | undefined = this.canonicalPaths.get(path);
      return Promise.resolve(resolved !== undefined ? resolved : null);
    }
    return Promise.resolve(path);
  }

  appendText(path: string, text: string): Promise<void> {
    const previous: string | undefined = this.contents.get(path);
    this.contents.set(path, (previous !== undefined ? previous : '') + text);
    this.appendCalls.push({ path, text });
    return Promise.resolve();
  }

  exists(path: string): Promise<boolean> {
    return Promise.resolve(this.contents.has(path) || this.regularFiles.has(path));
  }

  isRegularFile(path: string): Promise<boolean> {
    return Promise.resolve(this.regularFiles.has(path));
  }

  isPathInside(root: string, path: string): Promise<boolean> {
    return Promise.resolve(path.startsWith(`${root}/`));
  }

  readTail(path: string, maxBytes: number): Promise<SubAgentTranscriptTail> {
    this.readTailCalls.push({ path, maxBytes });
    if (this.unreadableFiles.has(path)) return Promise.reject(new Error('unreadable'));
    const content: string | undefined = this.contents.get(path);
    if (content === undefined) return Promise.reject(new Error('missing'));
    const bytes: Buffer = Buffer.from(content, 'utf8');
    const start: number = Math.max(0, bytes.length - maxBytes);
    return Promise.resolve({
      text: bytes.subarray(start).toString('utf8'),
      startsAfterFileStart: start > 0,
    });
  }
}

const addFile = (files: MemoryTranscriptPort, path: string, text: string): void => {
  files.regularFiles.add(path);
  files.contents.set(path, text);
};

const eventLine = (event: string, displayText: string): string => {
  const payload: JsonObject = {};
  payload['display_text'] = displayText;
  const line: JsonObject = {};
  line['event'] = event;
  line['created_at_ms'] = 1;
  line['payload'] = payload;
  return JSON.stringify(line);
};

test('writer appends current manager started and finished payloads with exact outer order', async () => {
  const files = new MemoryTranscriptPort();
  const startedPayload: JsonObject = {};
  startedPayload['status'] = 'running';
  startedPayload['run_id'] = 'run-1';
  startedPayload['subagent_id'] = 'reviewer';
  startedPayload['subagent_name'] = 'Reviewer';
  startedPayload['dynamic'] = false;
  startedPayload['task_objective'] = 'Review a small change.';
  startedPayload['started_at_ms'] = 1;
  startedPayload['updated_at_ms'] = 1;

  const finishedPayload: JsonObject = {};
  finishedPayload['status'] = 'completed';
  finishedPayload['run_id'] = 'run-1';
  finishedPayload['subagent_id'] = 'reviewer';
  finishedPayload['subagent_name'] = 'Reviewer';
  finishedPayload['dynamic'] = false;
  finishedPayload['task_objective'] = 'Review a small change.';
  finishedPayload['started_at_ms'] = 1;
  finishedPayload['updated_at_ms'] = 2;
  finishedPayload['result'] = '{"status":"completed","summary":"Done","confidence":"high"}';
  finishedPayload['display_text_chars'] = 4;
  finishedPayload['display_text'] = 'Done';

  await appendSubAgentTranscriptEvent(files, '/runs/run.jsonl', 'started', 10, startedPayload);
  await appendSubAgentTranscriptEvent(files, '/runs/run.jsonl', 'finished', 20, finishedPayload);

  assert.deepEqual(files.appendCalls.map((call: AppendCall): string => call.path),
    ['/runs/run.jsonl', '/runs/run.jsonl']);
  files.appendCalls.forEach((call: AppendCall): void => {
    assert.equal(call.text.endsWith('\n'), true);
    assert.equal(call.text.substring(0, call.text.length - 1).includes('\n'), false);
  });
  const events: JsonObject[] = files.appendCalls.map((call: AppendCall): JsonObject =>
    JSON.parse(call.text) as JsonObject);
  assert.deepEqual(events.map((event: JsonObject): unknown => event['event']), ['started', 'finished']);
  assert.deepEqual(events.map((event: JsonObject): unknown => event['created_at_ms']), [10, 20]);
  assert.deepEqual(events[0]['payload'], startedPayload);
  assert.deepEqual(events[1]['payload'], finishedPayload);
  assert.deepEqual(Object.keys(events[0]), ['event', 'created_at_ms', 'payload']);
  assert.deepEqual(Object.keys(events[1]), ['event', 'created_at_ms', 'payload']);
});

test('reads newest display text from JSONL inside the canonical run root', async () => {
  const files = new MemoryTranscriptPort();
  const path = '/runs/run.jsonl';
  addFile(files, path, [
    '{"event":"started","payload":{"status":"running"}}',
    eventLine('finished', 'Human-readable final answer'),
  ].join('\n'));

  assert.equal(
    await readSubAgentDisplayTextFromTranscript(path, '/runs', files),
    'Human-readable final answer',
  );
});

test('rejects a canonical transcript path outside the canonical run root', async () => {
  const files = new MemoryTranscriptPort();
  files.canonicalPaths.set('/runs/link.jsonl', '/outside/run.jsonl');
  addFile(files, '/outside/run.jsonl', eventLine('finished', 'leak'));

  assert.equal(
    await readSubAgentDisplayTextFromTranscript('/runs/link.jsonl', '/runs', files),
    '',
  );
  assert.equal(files.readTailCalls.length, 0);
});

test('rejects non-jsonl canonical paths including a jsonl symlink to a non-jsonl target', async () => {
  const files = new MemoryTranscriptPort();
  addFile(files, '/runs/run.txt', eventLine('finished', 'wrong extension'));
  files.canonicalPaths.set('/runs/link.jsonl', '/runs/actual.txt');
  addFile(files, '/runs/actual.txt', eventLine('finished', 'canonical wrong extension'));

  assert.equal(await readSubAgentDisplayTextFromTranscript('/runs/run.txt', '/runs', files), '');
  assert.equal(await readSubAgentDisplayTextFromTranscript('/runs/link.jsonl', '/runs', files), '');
  assert.equal(files.readTailCalls.length, 0);
});

test('rejects missing paths and paths that are not regular files', async () => {
  const files = new MemoryTranscriptPort();
  files.contents.set('/runs/directory.jsonl', eventLine('finished', 'not a file'));

  assert.equal(await readSubAgentDisplayTextFromTranscript('/runs/missing.jsonl', '/runs', files), '');
  assert.equal(await readSubAgentDisplayTextFromTranscript('/runs/directory.jsonl', '/runs', files), '');
  assert.equal(files.readTailCalls.length, 0);
});

test('returns empty text for a valid empty regular jsonl file', async () => {
  const files = new MemoryTranscriptPort();
  addFile(files, '/runs/empty.jsonl', '');

  assert.equal(await readSubAgentDisplayTextFromTranscript('/runs/empty.jsonl', '/runs', files), '');
  assert.deepEqual(files.readTailCalls, [{ path: '/runs/empty.jsonl', maxBytes: 256 * 1024 }]);
});

test('reads non-ASCII UTF-8 display text from a bounded tail', async () => {
  const files = new MemoryTranscriptPort();
  const path = '/runs/utf8.jsonl';
  addFile(files, path, 'x'.repeat(300000) + '\n' + eventLine('finished', '最终答案 — café'));

  assert.equal(await readSubAgentDisplayTextFromTranscript(path, '/runs', files), '最终答案 — café');
});

test('reads only the final 256 KiB and discards a partial first line', async () => {
  const files = new MemoryTranscriptPort();
  const path = '/runs/large.jsonl';
  const discarded: string = eventLine('finished', 'must not parse') + 'x'.repeat(300000);
  addFile(files, path, discarded + '\n' + eventLine('finished', 'Tail answer'));

  assert.equal(await readSubAgentDisplayTextFromTranscript(path, '/runs', files), 'Tail answer');
  assert.deepEqual(files.readTailCalls, [{ path, maxBytes: 256 * 1024 }]);

  const noNewlinePath = '/runs/no-newline.jsonl';
  addFile(files, noNewlinePath, 'x'.repeat(300000));
  assert.equal(await readSubAgentDisplayTextFromTranscript(noNewlinePath, '/runs', files), '');
});

test('skips corrupt JSON and invalid payload/display_text shapes', async () => {
  const files = new MemoryTranscriptPort();
  const path = '/runs/corrupt.jsonl';
  addFile(files, path, [
    eventLine('finished', 'Recovered answer'),
    'not-json',
    '[]',
    '{"payload":"not-an-object"}',
    '{"payload":{"display_text":{"nested":true}}}',
    '{"payload":{"display_text":null}}',
  ].join('\n'));

  assert.equal(
    await readSubAgentDisplayTextFromTranscript(path, '/runs', files),
    'Recovered answer',
  );
});

test('uses Android JsonPrimitive content and newest nonblank semantics', async () => {
  const files = new MemoryTranscriptPort();
  const path = '/runs/primitives.jsonl';
  addFile(files, path, [
    '{"payload":{"display_text":"  kept verbatim  "}}',
    '{"payload":{"display_text":""}}',
    '{"payload":{"display_text":"  \t"}}',
    '{"payload":{"display_text":false}}',
  ].join('\n'));

  assert.equal(await readSubAgentDisplayTextFromTranscript(path, '/runs', files), 'false');

  files.contents.set(path, '{"payload":{"display_text":1e3}}');
  assert.equal(await readSubAgentDisplayTextFromTranscript(path, '/runs', files), '1e3');

  files.contents.set(path, [
    '{"payload":{"display_text":"  kept verbatim  "}}',
    '{"payload":{"display_text":""}}',
    '{"payload":{"display_text":"  \\t"}}',
  ].join('\n'));

  assert.equal(
    await readSubAgentDisplayTextFromTranscript(path, '/runs', files),
    '  kept verbatim  ',
  );
});

test('preserves Kotlin nonblank semantics for display text', async () => {
  const files = new MemoryTranscriptPort();
  const path = '/runs/kotlin-blank.jsonl';
  addFile(files, path, '{"payload":{"display_text":"\\ufeff"}}');
  assert.equal(await readSubAgentDisplayTextFromTranscript(path, '/runs', files), '\ufeff');

  files.contents.set(path, [
    '{"payload":{"display_text":"kept"}}',
    '{"payload":{"display_text":"\\u001c"}}',
  ].join('\n'));
  assert.equal(await readSubAgentDisplayTextFromTranscript(path, '/runs', files), 'kept');

  files.contents.set(path, [
    '{"payload":{"display_text":"kept"}}',
    '{"payload":{"display_text":"\\u00a0"}}',
    '{"payload":{"display_text":"\\u2007"}}',
    '{"payload":{"display_text":"\\u202f"}}',
  ].join('\n'));
  assert.equal(await readSubAgentDisplayTextFromTranscript(path, '/runs', files), 'kept');
});

test('payload.display_text is sufficient even without event metadata', async () => {
  const files = new MemoryTranscriptPort();
  const path = '/runs/payload-only.jsonl';
  addFile(files, path, '{"payload":{"display_text":"Payload only"}}');

  assert.equal(
    await readSubAgentDisplayTextFromTranscript(path, '/runs', files),
    'Payload only',
  );
});

test('blank, invalid, and unreadable paths return empty text without throwing', async () => {
  const files = new MemoryTranscriptPort();
  assert.equal(await readSubAgentDisplayTextFromTranscript(' \t', '/runs', files), '');
  assert.equal(files.readTailCalls.length, 0);

  files.canonicalPaths.set('/invalid-root', null);
  assert.equal(
    await readSubAgentDisplayTextFromTranscript('/runs/run.jsonl', '/invalid-root', files),
    '',
  );

  files.canonicalPaths.set('/invalid.jsonl', null);
  assert.equal(
    await readSubAgentDisplayTextFromTranscript('/invalid.jsonl', '/runs', files),
    '',
  );

  const unreadable = '/runs/unreadable.jsonl';
  files.regularFiles.add(unreadable);
  files.unreadableFiles.add(unreadable);
  assert.equal(
    await readSubAgentDisplayTextFromTranscript(unreadable, '/runs', files),
    '',
  );
});
