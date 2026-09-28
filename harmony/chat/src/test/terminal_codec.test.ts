import { test } from 'node:test';
import assert from 'node:assert/strict';
import { encodeSSHCredential, decodeSSHCredential } from '../main/ets/chat/terminal/credential_codec.ts';
import { terminalUTF8Encode, TerminalUTF8Decoder, terminalTail } from '../main/ets/chat/terminal/utf8.ts';
const binding = { host: 'server', port: 22, username: 'user', authMethod: 'privateKey' as const };

test('credential codec preserves >1024-byte key, password whitespace and passphrase; validates binding', () => {
  const credential = { secret: ' \n' + 'PRIVATE KEY'.repeat(1000) + '\n ', passphrase: ' keep spaces ' };
  const encoded = encodeSSHCredential(binding, credential);
  assert.deepEqual(decodeSSHCredential(encoded, binding), credential);
  for (const change of [{ host: 'other' }, { port: 23 }, { username: 'other' }, { authMethod: 'password' as const }]) {
    assert.throws(() => decodeSSHCredential(encoded, { ...binding, ...change }), /credential_invalid/);
  }
});

test('credential codec rejects malformed JSON shape/types/extras and enforces UTF8 64KiB', () => {
  for (const raw of [null, [], 1, 'x', { ...binding, secret: 1, passphrase: null },
    { ...binding, secret: 's', passphrase: 1 }, { ...binding, secret: 's' },
    { ...binding, secret: 's', passphrase: null, extra: true }]) {
    assert.throws(() => decodeSSHCredential(JSON.stringify(raw), binding));
  }
  assert.throws(() => encodeSSHCredential(binding, { secret: '中'.repeat(21846), passphrase: null }), /credential_too_large/);
  assert.equal(decodeSSHCredential(encodeSSHCredential(binding, { secret: 'a'.repeat(65536), passphrase: null }), binding).secret.length, 65536);
});

test('UTF8 stream preserves every split for Chinese/emoji and terminal tail avoids orphan surrogate', () => {
  const text = 'abc中😀𠮷end';
  const bytes = terminalUTF8Encode(text);
  assert.deepEqual(bytes, new TextEncoder().encode(text));
  for (let split = 0; split <= bytes.length; split++) {
    const decoder = new TerminalUTF8Decoder();
    assert.equal(decoder.decode(bytes.slice(0, split)) + decoder.decode(bytes.slice(split), true), text);
  }
  assert.equal(terminalTail('a😀b', 2), 'b');
  const decoder = new TerminalUTF8Decoder();
  assert.equal(decoder.decode(new Uint8Array([0xe4])), '');
  assert.equal(decoder.decode(new Uint8Array(0), true), '\ufffd');
});
