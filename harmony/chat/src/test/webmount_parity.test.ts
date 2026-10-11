import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';

import {
  parseWebMountGetRequest, buildWebMountGetScript, buildWebMountVisualSnapshotScript,
  buildWebMountFindRefScript, planWebMountAct, webMountActApprovalClass, webMountActHasTap, webMountActHasTapText,
  buildWebMountActStepArgs, webMountActPlainWaitMs, classifyWebMountStepOutput,
  parseWebMountCookieNames, webMountCookieExpiryValues, webMountClearTargets, webMountWwwTwin,
  webMountPngSize, webMountRedactCredentials, webMountTargetSelector, webMountLooksSensitiveName,
  webMountVisualReadUserText, WEBMOUNT_ACT_MAX_STEPS, WEBMOUNT_VISUAL_REF_BASE, WEBMOUNT_FIND_REF_BASE,
  webMountActNeedsSnapshot, checkWebMountActSnapshot, webMountActTapSelector, webMountThrownErrorCode,
  webMountVisionTargetSize, WEBMOUNT_VISION_MAX_EDGE,
} from '../main/ets/chat/webmount/parity.ts';
import type { WebMountActStep } from '../main/ets/chat/webmount/parity.ts';

// ===== 最小 fake DOM(只覆盖脚本用到的 API) =====

interface Rect { left: number; top: number; width: number; height: number; }

class FakeEl {
  tagName: string;
  attrs: Map<string, string> = new Map();
  children: FakeEl[] = [];
  parentElement: FakeEl | null = null;
  innerText: string = '';
  textContent: string = '';
  value?: string;
  checked?: boolean;
  disabled: boolean = false;
  readOnly: boolean = false;
  rect: Rect;
  style: { display: string; visibility: string; cursor: string; opacity: string } = { display: 'block', visibility: 'visible', cursor: 'auto', opacity: '1' };
  constructor(tag: string, attrs: Record<string, string> = {}, rect: Rect = { left: 0, top: 0, width: 100, height: 20 }) {
    this.tagName = tag.toUpperCase();
    for (const [k, v] of Object.entries(attrs)) this.attrs.set(k, v);
    this.rect = rect;
  }
  add(child: FakeEl): FakeEl { child.parentElement = this; this.children.push(child); return child; }
  getAttribute(name: string): string | null { return this.attrs.has(name) ? this.attrs.get(name)! : null; }
  setAttribute(name: string, value: string): void { this.attrs.set(name, String(value)); }
  removeAttribute(name: string): void { this.attrs.delete(name); }
  getBoundingClientRect(): Record<string, number> {
    const r = this.rect;
    return { left: r.left, top: r.top, width: r.width, height: r.height, right: r.left + r.width, bottom: r.top + r.height };
  }
  click(): void { /* no-op */ }
  matches(sel: string): boolean { return matchesSelector(this, sel); }
  closest(sel: string): FakeEl | null {
    let cur: FakeEl | null = this;
    while (cur !== null) { if (matchesSelector(cur, sel)) return cur; cur = cur.parentElement; }
    return null;
  }
}

const matchesCompound = (el: FakeEl, part: string): boolean => {
  const p = part.trim();
  if (p === 'body *') return el.tagName !== 'BODY' && el.tagName !== 'HTML';
  const m = /^([a-z0-9]+|\*)?((?:\[[^\]]+\])*)$/i.exec(p);
  if (m === null) throw new SyntaxError(`unsupported selector ${p}`);
  if (m[1] !== undefined && m[1] !== '*' && el.tagName !== m[1].toUpperCase()) return false;
  const attrRe = /\[([^\]=]+)(?:=("?)([^"\]]*)\2)?\]/g;
  let a: RegExpExecArray | null;
  while ((a = attrRe.exec(m[2] ?? '')) !== null) {
    const v = el.getAttribute(a[1]);
    if (v === null) return false;
    if (a[3] !== undefined && a[0].includes('=') && v !== a[3]) return false;
  }
  return true;
};

const matchesSelector = (el: FakeEl, sel: string): boolean =>
  sel.split(',').some((part: string): boolean => matchesCompound(el, part));

const walk = (root: FakeEl, out: FakeEl[]): void => {
  for (const c of root.children) { out.push(c); walk(c, out); }
};

const makeContext = (body: FakeEl, extra: Record<string, unknown> = {}): vm.Context => {
  const html = new FakeEl('html');
  html.add(body);
  const all = (): FakeEl[] => { const out: FakeEl[] = [body]; walk(body, out); return out; };
  const document = {
    title: 'Fake Page',
    documentElement: { clientWidth: 400, clientHeight: 600 },
    body: body,
    querySelectorAll: (sel: string): FakeEl[] => all().filter((el: FakeEl): boolean => matchesSelector(el, sel)),
    querySelector: (sel: string): FakeEl | null => all().find((el: FakeEl): boolean => matchesSelector(el, sel)) ?? null,
  };
  const window = {
    __wmDoc: { revision: 7 }, innerWidth: 400, innerHeight: 600, scrollX: 0, scrollY: 0, devicePixelRatio: 2,
    getComputedStyle: (el: FakeEl) => el.style,
    ...extra,
  };
  return vm.createContext({ document, window, location: { href: 'https://example.com/a?q=1' } });
};

const run = (script: string, ctx: vm.Context): Record<string, unknown> =>
  JSON.parse(JSON.stringify(vm.runInContext(script, ctx))) as Record<string, unknown>;

// ===== wm_get =====

test('parseWebMountGetRequest mirrors iOS denials and target precedence', () => {
  assert.equal(parseWebMountGetRequest({ target: 'wm-1', kind: 'html' }).errorCode, 'html_disabled');
  assert.equal(parseWebMountGetRequest({ selector: '#api_token', kind: 'value' }).errorCode, 'sensitive_target');
  assert.equal(parseWebMountGetRequest({ target: 'wm-1', kind: 'attr', attr_name: 'data-csrf' }).errorCode, 'sensitive_attribute');
  assert.equal(parseWebMountGetRequest({ target: 'wm-1', kind: 'attr', attr_name: 'value' }).errorCode, 'use_kind_value');
  assert.equal(parseWebMountGetRequest({ target: 'wm-1', kind: 'attr' }).errorCode, 'invalid_arguments');
  assert.equal(parseWebMountGetRequest({}).errorCode, 'invalid_arguments');
  assert.equal(parseWebMountGetRequest({ target: 'wm-1', selector: '#other' }).errorCode, 'conflicting_target');
  const agree = parseWebMountGetRequest({ target: 'wm-1', selector: '[data-wm-ref="wm-1"]' });
  assert.equal(agree.ok, true);
  const ok = parseWebMountGetRequest({ target: 'wm-12', max_chars: 999999 });
  assert.equal(ok.ok, true);
  assert.equal(ok.selector, '[data-wm-ref="wm-12"]');
  assert.equal(ok.kind, 'text');
  assert.equal(ok.maxChars, 100000);
  assert.equal(parseWebMountGetRequest({ selector: 'h1', kind: 'TEXT' }).selector, 'h1');
  assert.equal(webMountTargetSelector('.btn'), '.btn');
  assert.equal(webMountLooksSensitiveName('password'), true);
  assert.equal(webMountLooksSensitiveName('search'), false);
});

test('wm_get script reads text/value/attr with redaction and rect', () => {
  const body = new FakeEl('body');
  const link = body.add(new FakeEl('a', { 'href': 'https://example.com/x?token=abc#h', 'data-wm-ref': 'wm-0', 'class': 'nav' },
    { left: 5, top: 10, width: 50, height: 12 }));
  link.innerText = 'Docs';
  const pwd = body.add(new FakeEl('input', { 'type': 'password', 'name': 'pw', 'data-wm-ref': 'wm-1' }));
  pwd.value = 'hunter2';
  const q = body.add(new FakeEl('input', { 'type': 'text', 'name': 'q', 'data-wm-ref': 'wm-2' }));
  q.value = 'hello world';
  const ctx = makeContext(body);

  const text = run(buildWebMountGetScript('[data-wm-ref="wm-0"]', 'text', '', 100), ctx);
  assert.equal(text['found'], true);
  assert.equal(text['text'], 'Docs');
  assert.deepEqual(text['rect'], { x: 5, y: 10, width: 50, height: 12 });
  assert.equal((text['attributes'] as Record<string, string>)['href'], 'https://example.com/x');
  assert.equal(text['revision'], 7);

  const secret = run(buildWebMountGetScript('[data-wm-ref="wm-1"]', 'value', '', 100), ctx);
  assert.equal(secret['value'], null);
  assert.equal(secret['value_redacted'], true);

  const val = run(buildWebMountGetScript('[data-wm-ref="wm-2"]', 'value', '', 5), ctx);
  assert.equal(val['value'], 'hello');
  assert.equal(val['truncated'], true);

  const attr = run(buildWebMountGetScript('[data-wm-ref="wm-0"]', 'attr', 'class', 100), ctx);
  assert.equal(attr['attr_value'], 'nav');

  const missing = run(buildWebMountGetScript('[data-wm-ref="wm-9"]', 'text', '', 100), ctx);
  assert.equal(missing['found'], false);
  assert.equal(missing['error'], 'target_not_found');

  // 注入安全:选择器中的引号/换行只作为字符串字面量
  const hostile = buildWebMountGetScript('"]);alert(1);//\u2028', 'text', '', 10);
  assert.doesNotThrow(() => new vm.Script(hostile));
  assert.ok(!hostile.includes('\u2028'));
});

test('wm_get refuses token-carrying elements, hidden text and secret attributes', () => {
  const body = new FakeEl('body');
  const head = new FakeEl('head');
  const meta = head.add(new FakeEl('meta', { 'name': 'csrf-token', 'content': 'abc123', 'id': 'm' }));
  void meta;
  body.add(head);
  const script = body.add(new FakeEl('script', { 'id': 'cfg', 'type': 'application/json' }));
  script.innerText = '{"apiKey":"sk-xyz"}';
  body.add(new FakeEl('link', { 'id': 'l', 'href': 'https://x.com/a.css' }));
  body.add(new FakeEl('style', { 'id': 'st' }));
  body.add(new FakeEl('noscript', { 'id': 'ns' }));
  body.add(new FakeEl('template', { 'id': 'tp' }));
  const hidden = body.add(new FakeEl('input', { 'type': 'hidden', 'name': 'authenticity', 'id': 'h' }));
  hidden.value = 'tok';
  const invisible = body.add(new FakeEl('div', { 'id': 'inv' }, { left: 0, top: 0, width: 0, height: 0 }));
  invisible.innerText = 'hidden secret';
  invisible.textContent = 'hidden secret';
  const dn = body.add(new FakeEl('div', { 'id': 'dn' }));
  dn.style.display = 'none';
  dn.textContent = 'display none text';
  const transparent = body.add(new FakeEl('div', { 'id': 'op' }));
  transparent.style.opacity = '0';
  transparent.innerText = 'ghost';
  const empty = body.add(new FakeEl('div', { 'id': 'emp' }));
  empty.textContent = 'only in textContent';
  const pwd = body.add(new FakeEl('input', { 'type': 'password', 'id': 'pw', 'title': 'Your password' }));
  void pwd;
  const tokenField = body.add(new FakeEl('input', { 'type': 'text', 'name': 'api_token', 'id': 'tk', 'title': 'visible-title' }));
  void tokenField;
  const ctx = makeContext(body);

  for (const id of ['m', 'cfg', 'l', 'st', 'ns', 'tp', 'h']) {
    for (const kind of ['text', 'attr', 'value'] as const) {
      const out = run(buildWebMountGetScript(`[id="${id}"]`, kind, kind === 'attr' ? 'content' : '', 100), ctx);
      assert.equal(out['denied'], true, `${id}/${kind}`);
      assert.equal(out['error'], 'restricted_element', `${id}/${kind}`);
      assert.equal(out['text'], undefined);
      assert.equal(out['attr_value'], undefined);
      assert.equal(out['value'], undefined);
    }
  }
  for (const id of ['inv', 'dn', 'op']) {
    const out = run(buildWebMountGetScript(`[id="${id}"]`, 'text', '', 100), ctx);
    assert.equal(out['denied'], true, id);
    assert.equal(out['error'], 'not_visible', id);
    assert.equal(out['text'], undefined);
  }
  // 可见元素也不回退 textContent(textContent 含 display:none 后代文本)
  const noFallback = run(buildWebMountGetScript('[id="emp"]', 'text', '', 100), ctx);
  assert.equal(noFallback['text'], '');
  // attr 也受页内敏感字段检查约束
  const pwAttr = run(buildWebMountGetScript('[id="pw"]', 'attr', 'title', 100), ctx);
  assert.equal(pwAttr['attr_value'], null);
  assert.equal(pwAttr['attr_redacted'], true);
  const tkAttr = run(buildWebMountGetScript('[id="tk"]', 'attr', 'title', 100), ctx);
  assert.equal(tkAttr['attr_value'], null);
  assert.equal(tkAttr['attr_redacted'], true);
  const okAttr = run(buildWebMountGetScript('[id="emp"]', 'attr', 'id', 100), ctx);
  assert.equal(okAttr['attr_value'], 'emp');
  assert.equal(okAttr['attr_redacted'], false);
});

// ===== wm_visual_snapshot =====

test('visual snapshot restamps interactive refs like observe and adds visual candidates', () => {
  const body = new FakeEl('body');
  const stale = body.add(new FakeEl('div', { 'data-wm-ref': 'wm-0' }));
  stale.innerText = 'stale';
  const b1 = body.add(new FakeEl('button', {}, { left: 0, top: 0, width: 80, height: 30 }));
  b1.innerText = 'Search';
  const hidden = body.add(new FakeEl('button'));
  hidden.style.display = 'none';
  const below = body.add(new FakeEl('a', { href: '/next' }, { left: 0, top: 2000, width: 80, height: 20 }));
  below.innerText = 'Next';
  const card = body.add(new FakeEl('a', { href: '/card' }, { left: 0, top: 100, width: 200, height: 150 }));
  const img = card.add(new FakeEl('img', { src: 'https://cdn.example.com/i.png?sig=1', alt: 'Cover' },
    { left: 0, top: 100, width: 200, height: 120 }));
  const para = body.add(new FakeEl('p', {}, { left: 0, top: 300, width: 300, height: 40 }));
  para.innerText = 'Some paragraph   text';
  const ctx = makeContext(body);
  const out = run(buildWebMountVisualSnapshotScript(), ctx);
  const interactive = out['interactive_elements'] as Array<Record<string, unknown>>;
  // b1=wm-0, below=wm-1(视口外不输出但占号), card=wm-2
  assert.deepEqual(interactive.map((e) => e['ref']), ['wm-0', 'wm-2']);
  assert.equal(stale.getAttribute('data-wm-ref'), null);
  assert.equal(below.getAttribute('data-wm-ref'), 'wm-1');
  const candidates = out['visual_candidates'] as Array<Record<string, unknown>>;
  assert.equal(candidates.length, 2);
  assert.equal(candidates[0]['ref'], `wm-${WEBMOUNT_VISUAL_REF_BASE}`);
  assert.equal(candidates[0]['interactive_target_ref'], 'wm-2');
  assert.equal(candidates[0]['src'], 'https://cdn.example.com/i.png');
  assert.equal(candidates[0]['single_click_supported'], false);
  assert.equal(candidates[1]['nearby_text'], 'Some paragraph text');
  assert.equal(img.getAttribute('data-wm-ref'), `wm-${WEBMOUNT_VISUAL_REF_BASE}`);
  assert.equal((out['viewport'] as Record<string, number>)['device_pixel_ratio'], 2);
});

// ===== wm_act find =====

test('find ref script prefers exact interactive text and stamps the next free ref', () => {
  const body = new FakeEl('body');
  body.add(new FakeEl('button', { 'data-wm-ref': 'wm-4' })).innerText = 'More options';
  const exact = body.add(new FakeEl('button'));
  exact.innerText = 'More';
  const ctx = makeContext(body);
  const out = run(buildWebMountFindRefScript('', 'more', ''), ctx);
  assert.equal(out['found'], true);
  assert.equal(out['count'], 2);
  assert.equal(out['ref'], `wm-${WEBMOUNT_FIND_REF_BASE}`);
  assert.equal(exact.getAttribute('data-wm-ref'), `wm-${WEBMOUNT_FIND_REF_BASE}`);

  const reuse = run(buildWebMountFindRefScript('', 'More options', ''), ctx);
  assert.equal(reuse['ref'], 'wm-4');

  const span = body.add(new FakeEl('div', { 'role': 'button', 'onclick': 'x' }));
  const inner = span.add(new FakeEl('span'));
  inner.innerText = 'Deep label';
  span.innerText = 'Deep label';
  const viaAncestor = run(buildWebMountFindRefScript('', 'deep label', 'button'), ctx);
  assert.equal(viaAncestor['found'], true);
  assert.equal(viaAncestor['tag'], 'div');

  const none = run(buildWebMountFindRefScript('', 'absent', ''), ctx);
  assert.equal(none['found'], false);
  const bad = run(buildWebMountFindRefScript('a[', '', ''), ctx);
  assert.equal(bad['error'], 'invalid_selector');
});

test('find refs use their own range and never collide with observe/visual numbering', () => {
  const body = new FakeEl('body');
  body.add(new FakeEl('button', { 'data-wm-ref': 'wm-3' })).innerText = 'A';
  body.add(new FakeEl('img', { 'data-wm-ref': `wm-${WEBMOUNT_VISUAL_REF_BASE + 5}` }));
  const b = body.add(new FakeEl('button'));
  b.innerText = 'Target';
  const c = body.add(new FakeEl('button'));
  c.innerText = 'Other';
  const ctx = makeContext(body);
  assert.equal(run(buildWebMountFindRefScript('', 'target', ''), ctx)['ref'], `wm-${WEBMOUNT_FIND_REF_BASE}`);
  assert.equal(run(buildWebMountFindRefScript('', 'other', ''), ctx)['ref'], `wm-${WEBMOUNT_FIND_REF_BASE + 1}`);
  // 重复 find 复用已有 ref
  assert.equal(run(buildWebMountFindRefScript('', 'target', ''), ctx)['ref'], `wm-${WEBMOUNT_FIND_REF_BASE}`);
  assert.ok(WEBMOUNT_FIND_REF_BASE > WEBMOUNT_VISUAL_REF_BASE + 200);
});

// ===== wm_act plan / args / classify =====

test('planWebMountAct validates steps and approval class', () => {
  assert.equal(planWebMountAct({}).errorCode, 'steps_required');
  assert.equal(planWebMountAct({ steps: [] }).errorCode, 'steps_required');
  const many = Array.from({ length: WEBMOUNT_ACT_MAX_STEPS + 1 }, () => ({ action: 'wait' }));
  assert.equal(planWebMountAct({ steps: many }).errorCode, 'too_many_steps');
  assert.equal(planWebMountAct({ steps: [{ action: 'open' }] }).errorCode, 'unsupported_step_action');
  assert.equal(planWebMountAct({ steps: ['click'] }).errorCode, 'invalid_step');
  const plan = planWebMountAct({ steps: [{ action: 'FIND', text: 'More' }, { action: 'click' }] });
  assert.equal(plan.ok, true);
  assert.deepEqual(plan.steps.map((s) => s.toolName), ['wm_find', 'wm_click']);

  assert.equal(webMountActApprovalClass({ steps: [{ action: 'find', text: 'x' }, { action: 'scroll' }] }), 'read_only');
  assert.equal(webMountActApprovalClass({ steps: [{ action: 'type', text: 'x' }] }), 'sensitive');
  assert.equal(webMountActApprovalClass({ steps: [{ action: 'type' }, { action: 'click' }] }), 'mandatory');
  assert.equal(webMountActApprovalClass({ steps: 'bad' }), 'mandatory');
  assert.equal(webMountActApprovalClass(null), 'mandatory');
  assert.equal(webMountActHasTap({ steps: [{ action: 'tap', x: 1, y: 2 }] }), true);
  assert.equal(webMountActHasTap({ steps: [{ action: 'click' }] }), false);
  assert.equal(webMountActHasTapText('{"steps":[{"action":"tap","target":"wm-1"}]}'), true);
  assert.equal(webMountActHasTapText('not json'), false);
  assert.equal(webMountActHasTapText(''), false);
});

const step = (fields: Record<string, unknown>): WebMountActStep => {
  const plan = planWebMountAct({ steps: [fields] } as never);
  assert.equal(plan.ok, true);
  return plan.steps[0];
};

test('buildWebMountActStepArgs translates iOS step fields to Harmony tool args', () => {
  const ctx = { lastRef: 'wm-5', documentId: 'doc-1', revision: 3 };
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'click' }), ctx),
    { target: 'wm-5', document_id: 'doc-1', revision: 3 });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'click', selector: '#go' }), ctx),
    { target: '#go', document_id: 'doc-1', revision: 3 });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'tap' }), ctx),
    { session_id: 'main', document_id: 'doc-1', revision: 3, ref: 'wm-5' });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'tap', x: 10, y: 20 }), ctx),
    { session_id: 'main', document_id: 'doc-1', revision: 3, x: 10, y: 20 });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'type', target: 'wm-2', value: 'abc' }), ctx),
    { selector: '[data-wm-ref="wm-2"]', text: 'abc', document_id: 'doc-1', revision: 3 });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'keys', key: 'Enter' }), ctx),
    { keys: '{Enter}', selector: '[data-wm-ref="wm-5"]' });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'keys', keys: 'ab{Tab}' }), { ...ctx, lastRef: null }),
    { keys: 'ab{Tab}' });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'scroll', by_y: 300, direction: 'up' }), ctx),
    { dx: 0, dy: -300, document_id: 'doc-1', revision: 3 });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'select', target: '#s', option: 'v2' }), ctx),
    { selector: '#s', value: 'v2', document_id: 'doc-1', revision: 3 });
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'wait', url_contains: '/done', text: 'Saved', timeout_ms: 99999 }), ctx),
    { url_includes: '/done', text_includes: 'Saved', timeout_ms: 60000 });
  // 无文档绑定时不注入 document_id
  assert.deepEqual(buildWebMountActStepArgs(step({ action: 'click', target: 'wm-1' }), { lastRef: null, documentId: '', revision: -1 }),
    { target: 'wm-1' });
  assert.equal(webMountActPlainWaitMs(step({ action: 'wait', wait_ms: 50000 })), 10000);
  assert.equal(webMountActPlainWaitMs(step({ action: 'wait' })), 500);
  assert.equal(webMountActPlainWaitMs(step({ action: 'wait', selector: '#x' })), null);
  assert.equal(webMountActPlainWaitMs(step({ action: 'click' })), null);
});

test('wm_act requires a snapshot binding for page-changing steps', () => {
  const plan = (steps: unknown[]) => planWebMountAct({ steps } as never);
  for (const action of ['click', 'tap', 'type', 'keys', 'select']) {
    const p = plan([{ action: 'find', text: 'x' }, { action }]);
    assert.equal(webMountActNeedsSnapshot(p), true, action);
    const missing = checkWebMountActSnapshot({ steps: [] }, p);
    assert.equal(missing.ok, false);
    assert.equal(missing.errorCode, 'snapshot_required');
    assert.equal(checkWebMountActSnapshot({ document_id: 'd1' }, p).errorCode, 'snapshot_required');
    assert.equal(checkWebMountActSnapshot({ document_id: 'd1', revision: -1 }, p).errorCode, 'snapshot_required');
    assert.equal(checkWebMountActSnapshot({ document_id: 'd1', revision: 1.5 }, p).errorCode, 'snapshot_required');
    assert.equal(checkWebMountActSnapshot({ revision: 3 }, p).errorCode, 'snapshot_required');
    const good = checkWebMountActSnapshot({ document_id: 'd1', revision: 0 }, p);
    assert.deepEqual(good, { ok: true, errorCode: '', reason: '', documentId: 'd1', revision: 0 });
  }
  const readOnly = plan([{ action: 'find', text: 'x' }, { action: 'scroll' }, { action: 'wait' }]);
  assert.equal(webMountActNeedsSnapshot(readOnly), false);
  assert.equal(checkWebMountActSnapshot({}, readOnly).ok, true);
  assert.equal(checkWebMountActSnapshot({ document_id: 'd', revision: 'x' }, readOnly).errorCode, 'invalid_arguments');
  assert.equal(webMountActNeedsSnapshot(planWebMountAct({})), false);
});

test('tap selector resolution and thrown error mapping', () => {
  assert.equal(webMountActTapSelector(step({ action: 'tap', selector: '#go' }), null), '#go');
  assert.equal(webMountActTapSelector(step({ action: 'tap', target: 'button.primary' }), 'wm-1'), 'button.primary');
  assert.equal(webMountActTapSelector(step({ action: 'tap', target: 'wm-4' }), null), null);
  assert.equal(webMountActTapSelector(step({ action: 'tap' }), 'wm-20000'), null);
  assert.equal(webMountActTapSelector(step({ action: 'tap', x: 1, y: 2 }), null), null);
  assert.equal(webMountActTapSelector(step({ action: 'tap', x: 1, y: 2, selector: '.a' }), null), '.a');
  assert.equal(webMountActTapSelector(step({ action: 'click', selector: '#go' }), null), null);
  assert.equal(webMountActTapSelector(step({ action: 'tap' }), null), null);
  assert.equal(webMountThrownErrorCode('WebMount document changed'), 'document_changed');
  assert.equal(webMountThrownErrorCode('document_changed: page changed before tap'), 'document_changed');
  assert.equal(webMountThrownErrorCode('WebMount cancelled'), 'cancelled');
  assert.equal(webMountThrownErrorCode('target_required: no ref'), 'target_required');
  assert.equal(webMountThrownErrorCode('Something odd'), 'step_error');
});

test('vision screenshot downscale keeps long edge within limit', () => {
  assert.deepEqual(webMountVisionTargetSize(1080, 2400), { width: 706, height: WEBMOUNT_VISION_MAX_EDGE, scale: WEBMOUNT_VISION_MAX_EDGE / 2400 });
  assert.deepEqual(webMountVisionTargetSize(3000, 1000), { width: 1568, height: 523, scale: 1568 / 3000 });
  assert.deepEqual(webMountVisionTargetSize(800, 600), { width: 800, height: 600, scale: 1 });
  assert.deepEqual(webMountVisionTargetSize(1568, 1568), { width: 1568, height: 1568, scale: 1 });
  assert.equal(webMountVisionTargetSize(0, 100).scale, 1);
  assert.equal(webMountVisionTargetSize(NaN, 100).scale, 1);
});

test('classifyWebMountStepOutput understands Harmony tool output shapes', () => {
  assert.deepEqual(classifyWebMountStepOutput('{"ok":true,"value":{"clicked":true,"url":"u"}}'),
    { ok: true, errorCode: '', reason: '', value: { clicked: true, url: 'u' } });
  const notFound = classifyWebMountStepOutput('{"ok":true,"value":{"clicked":false,"error":"element not found"}}');
  assert.equal(notFound.ok, false);
  assert.equal(notFound.errorCode, 'element_not_found');
  assert.equal(classifyWebMountStepOutput('{"ok":true,"value":{"error":"document_changed"}}').errorCode, 'document_changed');
  assert.equal(classifyWebMountStepOutput('{"ok":false,"error":"document_changed: the page changed"}').errorCode, 'document_changed');
  assert.equal(classifyWebMountStepOutput('{"ok":false,"error":"SyntaxError: bad selector"}').errorCode, 'step_failed');
  assert.equal(classifyWebMountStepOutput('{"matched":false,"waited_ms":10}').errorCode, 'wait_timeout');
  assert.equal(classifyWebMountStepOutput('{"matched":true,"waited_ms":10}').ok, true);
  assert.equal(classifyWebMountStepOutput('{"dispatched":false,"error":"no_target"}').errorCode, 'no_target');
  assert.equal(classifyWebMountStepOutput('{"ok":true,"value":{"typed":false}}').errorCode, 'not_typed');
  assert.equal(classifyWebMountStepOutput('{"ok":true,"value":{"scrolled":true,"x":0,"y":10}}').ok, true);
  assert.equal(classifyWebMountStepOutput('nope').errorCode, 'unparseable_output');
});

// ===== wm_clear_session =====

test('clear session helpers compute cookie names, expiry variants and targets', () => {
  assert.deepEqual(parseWebMountCookieNames('a=1; b=2;  a=3; __Host-sid=x; '), ['a', 'b', '__Host-sid']);
  assert.equal(webMountWwwTwin('www.example.com'), 'example.com');
  assert.equal(webMountWwwTwin('example.com'), 'www.example.com');
  assert.equal(webMountWwwTwin('www.com'), null);
  assert.equal(webMountWwwTwin('localhost'), null);
  assert.equal(webMountWwwTwin('10.0.0.1'), null);
  const variants = webMountCookieExpiryValues('sid', 'www.example.com', true);
  assert.ok(variants[0].startsWith('sid=; Path=/; Max-Age=0;'));
  assert.ok(variants[0].endsWith('; Secure'));
  assert.deepEqual(variants.slice(1), [`${variants[0]}; Domain=www.example.com`, `${variants[0]}; Domain=.www.example.com`]);
  // 绝不写父域 Domain
  assert.ok(!variants.some((v) => v.endsWith('Domain=example.com') || v.endsWith('Domain=.example.com')));
  assert.ok(!webMountCookieExpiryValues('sid', 'mail.google.com', true).some((v) => /Domain=\.?google\.com$/.test(v)));
  assert.deepEqual(webMountCookieExpiryValues('__Host-x', 'a.example.com', true).length, 1);
  assert.deepEqual(webMountCookieExpiryValues('a', '10.0.0.1', false).length, 1);
  const targets = webMountClearTargets('passport.bilibili.com', [
    'https://www.bilibili.com/video/1?x=1', 'https://evil.com/', 'not a url', 'https://space.bilibili.com/',
    'https://m.passport.bilibili.com/login?x=1', 'https://passport.bilibili.com/qr',
  ]);
  // 父域 bilibili.com、兄弟 www/space 都不纳入;仅 host、www 孪生与其子域
  assert.deepEqual(targets.hosts,
    ['passport.bilibili.com', 'www.passport.bilibili.com', 'm.passport.bilibili.com']);
  assert.ok(targets.origins.includes('http://m.passport.bilibili.com'));
  assert.ok(targets.urls.includes('https://passport.bilibili.com/qr'));
  assert.ok(targets.urls.includes('https://passport.bilibili.com/login'));
  assert.ok(!targets.origins.some((o) => o.includes('evil.com') || o.endsWith('//bilibili.com') || o.includes('space.')));
  assert.deepEqual(webMountClearTargets('', []), { hosts: [], urls: [], origins: [] });
});

test('clear targets never cross public-suffix or parent/sibling boundaries', () => {
  // foo.co.uk 不得波及 bbc.co.uk / co.uk
  const uk = webMountClearTargets('foo.co.uk', ['https://bbc.co.uk/news', 'https://www.bbc.co.uk/', 'https://shop.foo.co.uk/cart']);
  assert.deepEqual(uk.hosts, ['foo.co.uk', 'www.foo.co.uk', 'shop.foo.co.uk']);
  assert.ok(!uk.origins.some((o) => o.includes('bbc') || o.endsWith('//co.uk')));
  // alice.github.io 不得波及 bob.github.io / github.io
  const gh = webMountClearTargets('alice.github.io', ['https://bob.github.io/', 'https://github.io/', 'https://alice.github.io/repo']);
  assert.deepEqual(gh.hosts, ['alice.github.io', 'www.alice.github.io']);
  assert.ok(!gh.origins.some((o) => o.includes('bob.') || o.endsWith('//github.io')));
  // mail.google.com 不得波及 google.com / accounts.google.com / www.google.com
  const mail = webMountClearTargets('mail.google.com', [
    'https://accounts.google.com/signin', 'https://www.google.com/', 'https://google.com/', 'https://mail.google.com/mail/u/0',
  ]);
  assert.deepEqual(mail.hosts, ['mail.google.com', 'www.mail.google.com']);
  assert.ok(!mail.origins.some((o) => /\/\/(www\.|accounts\.)?google\.com$/.test(o)));
  // www 站点:裸域孪生纳入,兄弟子域不纳入
  const www = webMountClearTargets('www.example.com', ['https://example.com/a', 'https://m.example.com/', 'https://x.www.example.com/']);
  assert.deepEqual(www.hosts, ['www.example.com', 'example.com', 'x.www.example.com']);
  // 后缀相同但非子域(notexample.com)不纳入
  assert.deepEqual(webMountClearTargets('example.com', ['https://notexample.com/']).hosts, ['example.com', 'www.example.com']);
  // IP 站点:仅自身
  assert.deepEqual(webMountClearTargets('10.0.0.1', ['http://10.0.0.1/x', 'http://1.10.0.0.1/']).hosts, ['10.0.0.1']);
});

// ===== misc =====

test('png size, credential redaction and visual read prompt', () => {
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
    0, 0, 0x04, 0x38, 0, 0, 0x09, 0x60]);
  assert.deepEqual(webMountPngSize(png), { width: 1080, height: 2400 });
  assert.equal(webMountPngSize(new Uint8Array(10)), null);
  const red = webMountRedactCredentials('Authorization: Bearer abcdefghijklmnop and sk-1234567890abcdefXYZ');
  assert.equal(red.redacted, true);
  assert.ok(!red.text.includes('abcdefghijklmnop'));
  assert.equal(webMountRedactCredentials('plain text').redacted, false);
  assert.ok(webMountVisualReadUserText('Is it there?', 'https://x.com/a?token=1#f', 'T').includes('https://x.com/a\n'));
});

test('all generated page scripts are syntactically valid', () => {
  for (const script of [
    buildWebMountGetScript('[id="a"]', 'text', '', 10),
    buildWebMountGetScript('[id="a"]', 'attr', 'title', 10),
    buildWebMountVisualSnapshotScript(),
    buildWebMountFindRefScript('', 'x', 'link'),
  ]) {
    assert.doesNotThrow(() => new vm.Script(`(function(){ try { const v = ${script}; return JSON.stringify(v); } catch (e) { return String(e); } })()`));
  }
});
