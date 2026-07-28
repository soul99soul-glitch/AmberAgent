// deepread_slash_command.test.ts — D-064 /deepread 斜杠命令解析
// Android 基准: core/ai/tools/DeepReadOpenRequest.kt:31-66(parseDeepReadSlashCommand)
//   + SendMessageOrchestrator.kt:26-41(发送路径拦截语义)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type { DeepReadOpenEvent } from '../main/ets/chat/builtin_local_tools.ts';
import { parseDeepReadSlashCommand } from '../main/ets/chat/builtin_local_tools.ts';

// 测试用 sha256:定长 hex 标记(形态断言用;entry 注入真实 SHA-256)
const fakeSha256Hex = (input: string): string =>
  `aa${input.length.toString(16).padStart(2, '0')}`.repeat(16).slice(0, 64);

const textParts = (...texts: string[]): UIMessagePart[] =>
  texts.map((t: string): UIMessagePart => ({ type: 'text', text: t, metadata: null }));

const parse = (parts: UIMessagePart[]): DeepReadOpenEvent | null =>
  parseDeepReadSlashCommand(parts, fakeSha256Hex);

// ===== 前缀门槛(:36-47) =====

test('非 deepread 前缀 → null', () => {
  assert.equal(parse(textParts('你好世界')), null);
  // 必须前缀;出现在中段不触发
  assert.equal(parse(textParts('请 /deepread 主题')), null);
});

test('/deepread 单独(无 body)→ null', () => {
  assert.equal(parse(textParts('/deepread')), null);
  assert.equal(parse(textParts('/deepread   ')), null);
});

test('/deepread 后首字符非空白 → null(:42)', () => {
  assert.equal(parse(textParts('/deepreadx 主题')), null);
  assert.equal(parse(textParts('/deepreading')), null);
});

test('前缀大小写不敏感 + tab 空白可分隔', () => {
  const e: DeepReadOpenEvent | null = parse(textParts('/DeepRead\t主题'));
  assert.ok(e !== null);
  assert.equal(e.title, '主题');
  assert.equal(e.sourceUrl, null);
  assert.equal(e.forceRegenerate, false);
});

test('[ROUTE:deepread] 标签(大小写不敏感)→ 直接取余文', () => {
  const e: DeepReadOpenEvent | null = parse(textParts('[route:DEEPREAD] 火星移民'));
  assert.ok(e !== null);
  assert.equal(e.title, '火星移民');
});

test('多 text parts 以 \\n 连接;非 text parts 忽略(:32-33)', () => {
  const parts: UIMessagePart[] = [
    { type: 'image', url: 'data:image/png;base64,xx', metadata: null },
    { type: 'text', text: '/deepread 上半', metadata: null },
    { type: 'text', text: '下半', metadata: null },
  ];
  const e: DeepReadOpenEvent | null = parse(parts);
  assert.ok(e !== null);
  assert.equal(e.title, '上半\n下半');
});

test('整文空白 → null(:35)', () => {
  assert.equal(parse(textParts('   ')), null);
  assert.equal(parse([]), null);
});

// ===== force 旗标(:49-52) =====

test('--force → forceRegenerate=true 且从标题清除', () => {
  const e: DeepReadOpenEvent | null = parse(textParts('/deepread 主题 --force'));
  assert.ok(e !== null);
  assert.equal(e.forceRegenerate, true);
  assert.equal(e.title, '主题');
});

test('旗标大小写不敏感(--FORCE)+ 中文旗标(重新生成/强制刷新)', () => {
  const e1: DeepReadOpenEvent | null = parse(textParts('/deepread 主题 --FORCE'));
  assert.ok(e1 !== null && e1.forceRegenerate && e1.title === '主题');
  const e2: DeepReadOpenEvent | null = parse(textParts('/deepread 主题 重新生成'));
  assert.ok(e2 !== null && e2.forceRegenerate && e2.title === '主题');
  const e3: DeepReadOpenEvent | null = parse(textParts('/deepread 主题 强制刷新'));
  assert.ok(e3 !== null && e3.forceRegenerate && e3.title === '主题');
});

test('旗标清除后为空 → null(:53)', () => {
  assert.equal(parse(textParts('/deepread --force')), null);
  assert.equal(parse(textParts('/deepread --force --regen')), null);
});

test('quirk 钉住:--regenerate 先被 --regen 折叠残留 "erate"(:50-52 fold 顺序)', () => {
  // Android DEEP_READ_FORCE_FLAGS fold 顺序:--force → --regen → --regenerate → …
  // '--regenerate' 中的 '--regen' 前缀先被清除 → 标题残留 'erate'
  const e: DeepReadOpenEvent | null = parse(textParts('/deepread 主题 --regenerate'));
  assert.ok(e !== null);
  assert.equal(e.forceRegenerate, true);
  assert.equal(e.title, '主题 erate');
});

// ===== URL 提取(:54-58) =====

test('纯 URL → sourceUrl 提取,标题由 URL slug 派生', () => {
  const e: DeepReadOpenEvent | null =
    parse(textParts('/deepread https://www.foo.com/bar-baz.html'));
  assert.ok(e !== null);
  assert.equal(e.sourceUrl, 'https://www.foo.com/bar-baz.html');
  assert.equal(e.title, 'bar baz');
});

test('标题 + URL → 标题为去除 URL 后余文', () => {
  const e: DeepReadOpenEvent | null =
    parse(textParts('/deepread 我的标题 https://foo.com/a'));
  assert.ok(e !== null);
  assert.equal(e.title, '我的标题');
  assert.equal(e.sourceUrl, 'https://foo.com/a');
});

test('URL 去除后余文为空 → 标题回退 URL 派生(:57-58 takeIf 落空)', () => {
  const e: DeepReadOpenEvent | null = parse(textParts('/deepread https://foo.com/'));
  assert.ok(e !== null);
  assert.equal(e.sourceUrl, 'https://foo.com/');
  assert.equal(e.title, 'foo.com');
});

test('URL + force:旗标在 URL 外 → force + url 提取', () => {
  const e: DeepReadOpenEvent | null =
    parse(textParts('/deepread https://foo.com/a --force'));
  assert.ok(e !== null);
  assert.equal(e.forceRegenerate, true);
  assert.equal(e.sourceUrl, 'https://foo.com/a');
});

test('quirk 钉住:旗标子串命中 URL 查询串也会被折叠(:49 any + :50 fold)', () => {
  // force 检测在 lowercased body 上,'--force' 命中查询串 → force=true;
  // 折叠把 URL 里的 '--force' 一并清除,随后 URL 正则提取折叠后文本
  const e: DeepReadOpenEvent | null =
    parse(textParts('/deepread https://foo.com/?a=--force'));
  assert.ok(e !== null);
  assert.equal(e.forceRegenerate, true);
  assert.equal(e.sourceUrl, 'https://foo.com/?a=');
});

test('非法 URL(http://? 无 host)→ runCatching 吞错 → null(:59-65)', () => {
  assert.equal(parse(textParts('/deepread http://?')), null);
});

test('非 http(s) 协议文本不被 URL 正则提取 → 整文作标题', () => {
  const e: DeepReadOpenEvent | null = parse(textParts('/deepread ftp://foo.com/x'));
  assert.ok(e !== null);
  assert.equal(e.sourceUrl, null);
  assert.equal(e.title, 'ftp://foo.com/x');
});

// ===== topicId 键(:94-104 经 createDeepReadOpenEvent) =====

test('topicId:key = sourceUrl trim 小写 → chat_deep_read_ + sha256 前 24', () => {
  const e: DeepReadOpenEvent | null =
    parse(textParts('/deepread https://Foo.com/A'));
  assert.ok(e !== null);
  const expectedKey: string = 'https://foo.com/a';
  assert.equal(e.topicId, `chat_deep_read_${fakeSha256Hex(expectedKey).slice(0, 24)}`);
});

test('topicId:无 URL 时 key = title trim 小写', () => {
  const e: DeepReadOpenEvent | null = parse(textParts('/deepread 火星 移民'));
  assert.ok(e !== null);
  assert.equal(e.topicId, `chat_deep_read_${fakeSha256Hex('火星 移民').slice(0, 24)}`);
});
