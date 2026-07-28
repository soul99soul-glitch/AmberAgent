// injection_serialize — 提示词注入序列化线格式规格测试(D-100)
//
// Android 基准:
//   core/model/.../Assistant.kt:144-233(InjectionPosition SerialName 蛇形;
//     PromptInjection.ModeInjection/RegexInjection 声明序 + 默认值;Lorebook)
//   ExtensionPrefs.kt:96-112(writeTo JsonInstant.encodeToString 全量;
//     readFrom decodeJsonOrNull ?: emptyList)
//   core/agent-utils/.../Json.kt:12-19(JsonInstant:ignoreUnknownKeys/
//     encodeDefaults=true/isLenient/allowTrailingComma;explicitNulls 默认 —
//     模型无可空字段)
//   存储形态:'mode_injections' = List<ModeInjection>(具体类型,无 type 判别);
//     'lorebooks' = List<Lorebook>(entries = List<RegexInjection> 具体类型)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  decodeLorebooks, decodeModeInjections,
  encodeLorebooks, encodeModeInjections,
  lorebookFromJson, lorebookToJson,
  modeInjectionFromJson, modeInjectionToJson,
  regexInjectionFromJson, regexInjectionToJson,
} from '../main/ets/chat/injection_serialize.ts';
import {
  makeLorebook, makeModeInjection, makeRegexInjection,
} from '../main/ets/chat/prompt_injection_transformer.ts';

const FIXED_ID = 'b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74';

test('golden: ModeInjection 线格式(键序=声明序,encodeDefaults 全量)', () => {
  const mi = makeModeInjection({ id: FIXED_ID, name: 'Learning Mode', content: 'c' });
  assert.equal(
    JSON.stringify(modeInjectionToJson(mi)),
    '{"id":"b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74","name":"Learning Mode","enabled":true,' +
    '"priority":0,"position":"after_system_prompt","content":"c","injectDepth":4,"role":"user"}',
  );
});

test('golden: RegexInjection 线格式(13 字段声明序)', () => {
  const ri = makeRegexInjection({
    id: FIXED_ID, name: 'e', keywords: ['k1', 'k2'], useRegex: true,
  });
  assert.equal(
    JSON.stringify(regexInjectionToJson(ri)),
    '{"id":"b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74","name":"e","enabled":true,"priority":0,' +
    '"position":"after_system_prompt","content":"","injectDepth":4,"role":"user",' +
    '"keywords":["k1","k2"],"useRegex":true,"caseSensitive":false,"scanDepth":4,' +
    '"constantActive":false}',
  );
});

test('golden: Lorebook 线格式(entries 嵌套 RegexInjection)', () => {
  const lb = makeLorebook({
    id: FIXED_ID, name: 'book', description: 'd',
    entries: [makeRegexInjection({ id: 'e1', constantActive: true })],
  });
  assert.equal(
    JSON.stringify(lorebookToJson(lb)),
    '{"id":"b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74","name":"book","description":"d",' +
    '"enabled":true,"entries":[{"id":"e1","name":"","enabled":true,"priority":0,' +
    '"position":"after_system_prompt","content":"","injectDepth":4,"role":"user",' +
    '"keywords":[],"useRegex":false,"caseSensitive":false,"scanDepth":4,' +
    '"constantActive":true}]}',
  );
});

test('list 编解码往返;encode 形态 = JSON 数组', () => {
  const mis = [makeModeInjection({ id: 'a' }), makeModeInjection({ id: 'b', enabled: false })];
  const raw = encodeModeInjections(mis);
  assert.ok(raw.startsWith('[{"id":"a"'));
  assert.deepEqual(decodeModeInjections(raw), mis);
  const lbs = [makeLorebook({ id: 'l', entries: [makeRegexInjection({ id: 'e' })] })];
  assert.deepEqual(decodeLorebooks(encodeLorebooks(lbs)), lbs);
});

test('decode 容错:缺字段 → 默认;未知键忽略(ignoreUnknownKeys)', () => {
  // Android 旧版/手写 JSON:仅部分字段
  const raw = '[{"id":"x","name":"n","position":"at_depth","unknownField":1}]';
  const out = decodeModeInjections(raw);
  assert.ok(out !== null);
  assert.deepEqual(out, [makeModeInjection({ id: 'x', name: 'n', position: 'at_depth' })]);
});

test('decode 失败 → null(decodeJsonOrNull):坏 JSON/非数组/类型错/枚举非法', () => {
  assert.equal(decodeModeInjections('{bad'), null);
  assert.equal(decodeModeInjections('{"a":1}'), null);
  assert.equal(decodeLorebooks('null'), null);
  // kotlinx:枚举非法/字段类型错 → 异常 → decodeJsonOrNull → null
  assert.equal(decodeModeInjections('[{"id":"e","position":"INVALID"}]'), null);
  assert.equal(decodeLorebooks('[{"id":"l","entries":[{"id":"e","keywords":"not-array"}]}]'), null);
});

test('id 缺失 → 生成(kotlin 默认 Uuid.random());其余字段默认', () => {
  const lbs = decodeLorebooks('[{"name":"no-id"}]');
  assert.ok(lbs !== null);
  assert.equal(lbs.length, 1);
  assert.equal(lbs[0].name, 'no-id');
  assert.ok(lbs[0].id.length > 0); // 生成 id,非空
  assert.equal(lbs[0].enabled, true);
});
