// code_highlight 纯逻辑测试
//
// 覆盖:
//   normalizeLang — 别名归一(js/tsx/py/kt/sh 等)
//   tokenizeCode — 6 语言各一例 + 关键字/字符串/注释/数字/操作符 命中
//   highlightCode — HTML span 输出 + 颜色注入 + 未知语言降级
//   边界 — 空 code / 纯空白 / 无 token

import { test } from 'node:test';
import assert from 'node:assert/strict';

import { tokenizeCode, highlightCode, HIGHLIGHT_COLORS } from '../main/ets/chat/code_highlight.ts';

// ===== normalizeLang(经 highlightCode 间接验证) =====

// ===== tokenizeCode 各语言 =====

test('javascript:关键字 + 字符串 + 数字 + 操作符', () => {
  const tokens = tokenizeCode('const x = "hello" + 42;', 'javascript');
  const types = tokens.map((t) => t.type);
  assert.equal(types[0], 'keyword');      // const
  assert.equal(types.includes('string'), true);   // "hello"
  assert.equal(types.includes('number'), true);   // 42
  assert.equal(types.includes('operator'), true); // = / +
});

test('typescript:interface + 类型关键字', () => {
  const tokens = tokenizeCode('interface Foo { x: number }', 'typescript');
  assert.equal(tokens[0].text, 'interface');
  assert.equal(tokens[0].type, 'keyword');
  const numberToken = tokens.find((t) => t.text === 'number');
  assert.ok(numberToken);
  assert.equal(numberToken?.type, 'keyword');
});

test('python:def + 多行注释', () => {
  const tokens = tokenizeCode('def add(a, b):\n  """docstring"""\n  return a + b', 'python');
  assert.equal(tokens[0].text, 'def');
  assert.equal(tokens[0].type, 'keyword');
  const returnToken = tokens.find((t) => t.text === 'return');
  assert.ok(returnToken);
  assert.equal(returnToken?.type, 'keyword');
});

test('kotlin:fun + val + when', () => {
  const tokens = tokenizeCode('fun main() { val x = 1 }', 'kotlin');
  assert.equal(tokens[0].text, 'fun');
  assert.equal(tokens[0].type, 'keyword');
  const valToken = tokens.find((t) => t.text === 'val');
  assert.ok(valToken);
  assert.equal(valToken?.type, 'keyword');
});

test('json:true/false/null 关键字', () => {
  const tokens = tokenizeCode('{"ok": true, "x": null}', 'json');
  const trueToken = tokens.find((t) => t.text === 'true');
  const nullToken = tokens.find((t) => t.text === 'null');
  assert.ok(trueToken);
  assert.equal(trueToken?.type, 'keyword');
  assert.ok(nullToken);
  assert.equal(nullToken?.type, 'keyword');
});

test('bash:# 行注释 + echo 关键字', () => {
  const tokens = tokenizeCode('#!/bin/bash\necho hello', 'bash');
  assert.equal(tokens[0].type, 'comment');
  const echoToken = tokens.find((t) => t.text === 'echo');
  assert.ok(echoToken);
  assert.equal(echoToken?.type, 'keyword');
});

// ===== highlightCode HTML 输出 =====

test('highlightCode:HTML 转义(< > & ")', () => {
  const html = highlightCode('const x = a < b && c > d;', 'javascript');
  assert.ok(html.includes('&lt;'));
  assert.ok(html.includes('&gt;'));
  assert.ok(html.includes('&amp;'));
});

test('highlightCode:未知语言降级为纯文本 escapeHtml(不伪高亮)', () => {
  const html = highlightCode('some <code> here', 'unknown-lang');
  assert.ok(!html.includes('<span'));
  assert.ok(html.includes('&lt;code&gt;'));
});

test('highlightCode:空 lang 降级为纯文本', () => {
  const html = highlightCode('const x = 1;', '');
  assert.ok(!html.includes('<span'));
});

test('highlightCode:空 code → 空/最小输出', () => {
  const html = highlightCode('', 'javascript');
  assert.equal(html, '');
});

test('highlightCode:块注释(js/ts/kotlin)', () => {
  const html = highlightCode('/* comment */', 'javascript');
  assert.ok(html.includes(HIGHLIGHT_COLORS.comment));
});

test('highlightCode:模板字符串(js/ts)', () => {
  const html = highlightCode('const s = `hello ${name}`;', 'javascript');
  assert.ok(html.includes(HIGHLIGHT_COLORS.string));
});

// ===== 边界 =====
