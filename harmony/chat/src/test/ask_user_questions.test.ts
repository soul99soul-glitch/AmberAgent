// ask_user_questions — ask_user 问题解析/应答载荷规格测试(D-105)
//
// Android 基准:
//   ChatMessageAskUserStep.kt:87-99(questions 解析,runCatching 全损 → [])
//   :331-345(提交载荷 {answers:{id: string|[...]}} 声明序 = questions 序)
//   :74-84(answeredAnswers = answer JSON 的 answers 对象;失败 → null)
//   :246-253(已应答展示:数组 → ' · ' 连接;原始 → 字符串;缺失 → 整段 answer)
//   :348-353(提交使能:multi → 非空集;其余 → 非空白)
//   :295-296(anyAnswered:任一非空白文本 或 任一非空多选)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  parseAskUserQuestions, buildAskUserAnswerPayload,
  parseAskedAnswers, askAnswerDisplayText,
  isAskUserSubmittable, hasAnyAskAnswer,
} from '../main/ets/chat/ask_user_questions.ts';

test('parseAskUserQuestions: 完整三类型逐字解析', () => {
  const input = JSON.stringify({
    questions: [
      { id: 'q1', question: '叫什么?', selection_type: 'text' },
      { id: 'q2', question: '选一个', options: ['甲', '乙'], selection_type: 'single' },
      { id: 'q3', question: '选多个', options: ['a', 'b', 'c'], selection_type: 'multi' },
    ],
  });
  const qs = parseAskUserQuestions(input);
  assert.equal(qs.length, 3);
  assert.deepEqual(qs[0], { id: 'q1', question: '叫什么?', options: [], selectionType: 'text' });
  assert.deepEqual(qs[1], { id: 'q2', question: '选一个', options: ['甲', '乙'], selectionType: 'single' });
  assert.deepEqual(qs[2], { id: 'q3', question: '选多个', options: ['a', 'b', 'c'], selectionType: 'multi' });
});

test('parseAskUserQuestions: 缺省 → 默认(id/question 空串,options [],selection_type text)', () => {
  const qs = parseAskUserQuestions(JSON.stringify({ questions: [{}] }));
  assert.deepEqual(qs, [{ id: '', question: '', options: [], selectionType: 'text' }]);
});

test('parseAskUserQuestions: 坏 JSON/缺 questions/非数组 → [](:98 getOrElse)', () => {
  assert.deepEqual(parseAskUserQuestions('not json'), []);
  assert.deepEqual(parseAskUserQuestions('{}'), []);
  assert.deepEqual(parseAskUserQuestions('{"questions": 42}'), []);
});

test('parseAskUserQuestions: 元素非对象/options 非数组 → 全损 [](runCatching 语义)', () => {
  assert.deepEqual(parseAskUserQuestions('{"questions": [1]}'), []);
  assert.deepEqual(parseAskUserQuestions('{"questions": [{"id":"q","question":"x","options":"bad"}]}'), []);
});

test('buildAskUserAnswerPayload: multi → 数组,其余 → 字符串(缺省空串),键序 = questions 序', () => {
  const qs = parseAskUserQuestions(JSON.stringify({
    questions: [
      { id: 'q1', question: 'a', selection_type: 'multi' },
      { id: 'q2', question: 'b' },
      { id: 'q3', question: 'c', selection_type: 'single' },
    ],
  }));
  const payload = buildAskUserAnswerPayload(
    qs,
    { q2: '自由文本', q3: '乙' },
    { q1: ['x', 'y'] },
  );
  assert.equal(payload, '{"answers":{"q1":["x","y"],"q2":"自由文本","q3":"乙"}}');
});

test('buildAskUserAnswerPayload: 未答字段落默认(multi → [],其余 → "")', () => {
  const qs = parseAskUserQuestions(JSON.stringify({
    questions: [{ id: 'm', question: 'a', selection_type: 'multi' }, { id: 't', question: 'b' }],
  }));
  assert.equal(buildAskUserAnswerPayload(qs, {}, {}), '{"answers":{"m":[],"t":""}}');
});

test('parseAskedAnswers: 合法 → answers 对象;坏 JSON/非对象/answers 非对象 → null(:80)', () => {
  assert.deepEqual(
    parseAskedAnswers('{"answers":{"q1":"好的","q2":["a","b"]}}'),
    { q1: '好的', q2: ['a', 'b'] },
  );
  assert.equal(parseAskedAnswers('bad'), null);
  assert.equal(parseAskedAnswers('[]'), null);
  assert.equal(parseAskedAnswers('{"answers":"x"}'), null);
});

test('askAnswerDisplayText: 数组 join " · ";原始 → content;缺失 → 整段 answer(:246-253)', () => {
  const answered = parseAskedAnswers('{"answers":{"q1":["甲","乙"],"q2":"自由","q3":{"x":1}}}');
  assert.ok(answered !== null);
  const fallback = '{"answers":{}}';
  assert.equal(askAnswerDisplayText(answered, 'q1', fallback), '甲 · 乙');
  assert.equal(askAnswerDisplayText(answered, 'q2', fallback), '自由');
  // 非原始(对象)→ 回退整段 answer
  assert.equal(askAnswerDisplayText(answered, 'q3', fallback), fallback);
  // 键缺失 → 整段 answer
  assert.equal(askAnswerDisplayText(answered, 'q4', 'raw-answer'), 'raw-answer');
  // answeredAnswers 为 null → 整段 answer
  assert.equal(askAnswerDisplayText(null, 'q1', 'raw-answer'), 'raw-answer');
});

test('isAskUserSubmittable: multi 需非空集;其余需非空白(:348-353)', () => {
  const qs = parseAskUserQuestions(JSON.stringify({
    questions: [
      { id: 'm', question: 'a', selection_type: 'multi' },
      { id: 't', question: 'b' },
    ],
  }));
  assert.equal(isAskUserSubmittable(qs, {}, {}), false);
  assert.equal(isAskUserSubmittable(qs, { t: '  ' }, { m: ['x'] }), false);
  assert.equal(isAskUserSubmittable(qs, { t: '答' }, { m: [] }), false);
  assert.equal(isAskUserSubmittable(qs, { t: '答' }, { m: ['x'] }), true);
});

test('hasAnyAskAnswer: 任一非空白文本 或 任一非空多选(:295-296)', () => {
  assert.equal(hasAnyAskAnswer({}, {}), false);
  assert.equal(hasAnyAskAnswer({ q: '  ' }, { m: [] }), false);
  assert.equal(hasAnyAskAnswer({ q: '字' }, {}), true);
  assert.equal(hasAnyAskAnswer({}, { m: ['x'] }), true);
});
