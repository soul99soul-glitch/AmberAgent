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

test('memory store: bytes preserve exact view and roundtrip', async () => {
  const fs = createMemoryFileStore();
  const backing = new Uint8Array([9, 1, 2, 3, 9]);
  await fs.writeBytes('artifacts/a.bin', backing.subarray(1, 4));
  assert.deepEqual(Array.from((await fs.readBytes('artifacts/a.bin')) ?? []), [1, 2, 3]);
});

test('memory store: rename moves a complete directory tree', async () => {
  const fs = createMemoryFileStore();
  await fs.writeText('.staging/p/manifest.yaml', 'v1');
  await fs.writeText('.staging/p/branches/main/chapters/001-a.md', 'A');
  await fs.rename('.staging/p', 'p');
  assert.equal(await fs.readText('p/manifest.yaml'), 'v1');
  assert.equal(await fs.readText('p/branches/main/chapters/001-a.md'), 'A');
  assert.equal(await fs.exists('.staging/p'), false);
});

test('memory store: deleteTree removes descendants but not siblings', async () => {
  const fs = createMemoryFileStore();
  await fs.writeText('p/a.txt', 'A');
  await fs.writeText('p/sub/b.txt', 'B');
  await fs.writeText('peer.txt', 'P');
  await fs.deleteTree('p');
  assert.deepEqual(await fs.list('p'), []);
  assert.equal(await fs.readText('peer.txt'), 'P');
});
