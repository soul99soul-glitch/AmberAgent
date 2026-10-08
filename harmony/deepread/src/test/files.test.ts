import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';

test('memory store: list returns immediate names, deduped', async () => {
  const fs = createMemoryFileStore();
  await fs.writeText('runs/a.jsonl', '1');
  await fs.writeText('runs/b.jsonl', '2');
  await fs.writeText('runs/sub/c.jsonl', '3');
  await fs.writeText('other/d.txt', '4');
  const names = await fs.list('runs');
  assert.deepEqual(names.sort(), ['a.jsonl', 'b.jsonl', 'sub']);
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
