import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { joinPath, createMemoryFileStore } from '../main/ets/platform/files.ts';

test('joinPath joins with single slash', () => {
  assert.equal(joinPath('a', 'b'), 'a/b');
  assert.equal(joinPath('a', 'b', 'c'), 'a/b/c');
});

test('joinPath collapses duplicate slashes', () => {
  assert.equal(joinPath('a/', '/b'), 'a/b');
  assert.equal(joinPath('a//', '//b'), 'a/b');
});

test('joinPath trims trailing slash but keeps root', () => {
  assert.equal(joinPath('a', 'b/'), 'a/b');
  assert.equal(joinPath('/'), '/');
});

test('memory store: write then read roundtrip', async () => {
  const fs = createMemoryFileStore();
  await fs.writeText('dir/a.txt', 'hello');
  assert.equal(await fs.readText('dir/a.txt'), 'hello');
});

test('memory store: read missing returns null', async () => {
  const fs = createMemoryFileStore();
  assert.equal(await fs.readText('nope.txt'), null);
});

test('memory store: write overwrites', async () => {
  const fs = createMemoryFileStore();
  await fs.writeText('a.txt', 'one');
  await fs.writeText('a.txt', 'two');
  assert.equal(await fs.readText('a.txt'), 'two');
});

test('memory store: append concatenates and creates', async () => {
  const fs = createMemoryFileStore();
  await fs.appendText('log.jsonl', '{"e":1}\n');
  await fs.appendText('log.jsonl', '{"e":2}\n');
  assert.equal(await fs.readText('log.jsonl'), '{"e":1}\n{"e":2}\n');
});

test('memory store: exists reflects state', async () => {
  const fs = createMemoryFileStore();
  assert.equal(await fs.exists('x'), false);
  await fs.writeText('x', '1');
  assert.equal(await fs.exists('x'), true);
  await fs.delete('x');
  assert.equal(await fs.exists('x'), false);
});

test('memory store: delete missing is ok', async () => {
  const fs = createMemoryFileStore();
  await fs.delete('never-existed'); // no throw
});

test('memory store: list returns immediate names, deduped', async () => {
  const fs = createMemoryFileStore();
  await fs.writeText('runs/a.jsonl', '1');
  await fs.writeText('runs/b.jsonl', '2');
  await fs.writeText('runs/sub/c.jsonl', '3');
  await fs.writeText('other/d.txt', '4');
  const names = await fs.list('runs');
  assert.deepEqual(names.sort(), ['a.jsonl', 'b.jsonl', 'sub']);
});

test('memory store: list missing dir returns empty', async () => {
  const fs = createMemoryFileStore();
  assert.deepEqual(await fs.list('nothing'), []);
});
