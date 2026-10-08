const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const crypto = require('node:crypto');

const entryPort = () => {
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/SyncCryptoPort.ets');
  const text = fs.readFileSync(filename, 'utf8');
  const file = ts.createSourceFile(filename, text, ts.ScriptTarget.Latest, true);
  const source = file.statements.filter(s => !ts.isImportDeclaration(s)).map(s => s.getText(file)).join('\n');
  const host = { exports: {}, Uint8Array, Math, Error,
    util: {
      // Official API 9+ contract: encodeInto('') returns undefined.
      TextEncoder: { create: () => ({ encodeInto: s => s === '' ? undefined : new Uint8Array(Buffer.from(s, 'utf8')) }) },
      Base64Helper: class { decodeSync(s) { return new Uint8Array(Buffer.from(s, 'base64')); } },
    },
    cryptoFramework: { createMd: () => {
      let bytes;
      return { updateSync: blob => { bytes = blob.data; }, digestSync: () => ({ data: crypto.createHash('sha256').update(bytes).digest() }) };
    } },
  };
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText, host);
  return host.exports.createEntrySyncCryptoPort();
};
const expected = (passphrase, salt, iterations) => {
  const pass = Buffer.from(passphrase, 'utf8');
  let block = crypto.createHash('sha256').update(Buffer.concat([salt, pass])).digest();
  for (let i = 1; i < iterations; i++) block = crypto.createHash('sha256').update(Buffer.concat([block, pass])).digest();
  return block.toString('hex');
};
for (const passphrase of ['', '验收 password']) {
  test(`actual Entry backup KDF supports ${passphrase === '' ? 'passwordless' : 'UTF-8 password'} archives`, async () => {
    const salt = Buffer.from('fixture-salt');
    const key = await entryPort().deriveKey(passphrase, salt.toString('base64'), 3);
    assert.equal(Buffer.from(key).toString('hex'), expected(passphrase, salt, 3));
  });
}
