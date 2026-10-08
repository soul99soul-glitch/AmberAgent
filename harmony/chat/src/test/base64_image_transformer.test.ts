// base64_image_transformer — 语义钉死(D-076)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  convertBase64ImagePartToLocalFile, createBase64ImageToLocalFileTransformer, decodeBase64
} from '../main/ets/chat/base64_image_transformer.ts';
import type { Base64ImageDeps } from '../main/ets/chat/base64_image_transformer.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';

const imagePart = (url: string): UIMessagePart => ({ type: 'image', url, metadata: null });

const deps = (
  png: Uint8Array | null = new Uint8Array([1, 2, 3]),
): { d: Base64ImageDeps; saved: Uint8Array[] } => {
  const saved: Uint8Array[] = [];
  return {
    saved,
    d: {
      reencodeToPng: async (b: Uint8Array): Promise<Uint8Array | null> => png,
      saveUploadImage: async (b: Uint8Array): Promise<string> => {
        saved.push(b);
        return 'file:///data/files/upload/uuid.png';
      },
    },
  };
};

test('decodeBase64: 往返 + 非法符号抛错 + 错误 padding 抛错', () => {
  assert.deepEqual([...decodeBase64('QUJD')], [0x41, 0x42, 0x43]); // 'ABC'
  assert.deepEqual([...decodeBase64('QQ==')], [0x41]); // 'A'
  assert.deepEqual([...decodeBase64('QUI=')], [0x41, 0x42]); // 'AB'
  assert.throws((): Uint8Array => decodeBase64('QUJ D')); // 空格非法
  assert.throws((): Uint8Array => decodeBase64('QUJ')); // 长度错
  assert.throws((): Uint8Array => decodeBase64('Q=JD')); // pad 位错
});

test('convert: data:image → 解码 → 重编码 → 落盘 → url 替换', async () => {
  const { d, saved } = deps();
  const msg: UIMessage = makeAssistantMessage('see');
  msg.parts.push(imagePart('data:image/jpeg;base64,QUJD'));
  const out: UIMessage = await convertBase64ImagePartToLocalFile(msg, d);
  const last: UIMessagePart = out.parts[out.parts.length - 1];
  assert.equal(last.type, 'image');
  assert.equal((last as { url: string }).url, 'file:///data/files/upload/uuid.png');
  assert.equal(saved.length, 1);
  // text part 原引用
  assert.equal(out.parts[0], msg.parts[0]);
});

test('convert: 不可解码(reencode → null)→ 保留 data url part 原引用', async () => {
  const { d, saved } = deps(null);
  const part: UIMessagePart = imagePart('data:image/png;base64,QUJD');
  const msg: UIMessage = makeUserMessage('');
  msg.parts = [part];
  const out: UIMessage = await convertBase64ImagePartToLocalFile(msg, d);
  assert.equal(out.parts[0], part);
  assert.equal(saved.length, 0);
});

test('convert: 非 data image(http/file)/ 非 image part → 原引用不动', async () => {
  const { d, saved } = deps();
  const http: UIMessagePart = imagePart('https://x/y.png');
  const file: UIMessagePart = imagePart('file:///x.png');
  const msg: UIMessage = makeUserMessage('t');
  msg.parts = [http, file];
  const out: UIMessage = await convertBase64ImagePartToLocalFile(msg, d);
  assert.equal(out.parts[0], http);
  assert.equal(out.parts[1], file);
  assert.equal(saved.length, 0);
});

test('transformer onGenerationFinish: 全消息顺序转换', async () => {
  const { d } = deps();
  const t = createBase64ImageToLocalFileTransformer(d);
  const ctx: TransformerContext = { assistant: makeAssistant() };
  const m1: UIMessage = makeUserMessage('a');
  m1.parts.push(imagePart('data:image/webp;base64,QQ=='));
  const m2: UIMessage = makeAssistantMessage('b');
  const out: UIMessage[] = await t.onGenerationFinish!(ctx, [m1, m2]);
  assert.equal((out[0].parts[1] as { url: string }).url, 'file:///data/files/upload/uuid.png');
  assert.equal(out[1].parts[0], m2.parts[0]);
});

test('非法 base64 → 错误向外传播(Kotlin Base64.decode 抛错语义)', async () => {
  const { d } = deps();
  const msg: UIMessage = makeUserMessage('');
  msg.parts = [imagePart('data:image/png;base64,!!!')];
  await assert.rejects((): Promise<UIMessage> => convertBase64ImagePartToLocalFile(msg, d));
});
