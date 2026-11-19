const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const zlib = require('node:zlib');
process.env.TSX_TSCONFIG_PATH ??= path.resolve(__dirname, '../../tsconfig.json');
require('../../node_modules/tsx/dist/cjs/index.cjs');
const artifact = require('../main/ets/chat/workspace_artifacts.ts');
const writer = require('../main/ets/chat/zip_writer.ts');
const reader = require('../main/ets/chat/zip_archive.ts');

test('the legacy artifact path exports the same ZIP writer and round-trips entries', async () => {
  assert.equal(artifact.buildZipBytes, writer.buildZipBytes);
  assert.equal(artifact.dosTimeDate, writer.dosTimeDate);
  assert.equal(artifact.crc32, writer.crc32);
  const { time, date } = artifact.dosTimeDate(new Date());
  const payload = new TextEncoder().encode('hello amber');
  const zip = await artifact.buildZipBytes(
    [{ name: 'notes/readme.md', data: payload, compression: 'stored' }],
    async bytes => new Uint8Array(zlib.deflateRawSync(bytes)),
    time,
    date,
  );
  const entries = reader.readZipEntries(zip);
  assert.deepEqual(entries.map(entry => [entry.name, entry.method]), [['notes/readme.md', 0]]);
});
