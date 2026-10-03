const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

function children(source) {
  const regex = /\b(Column|Row)\(\)\s*\{/g;
  let output = '', cursor = 0, match;
  while ((match = regex.exec(source))) {
    const open = source.indexOf('{', match.index);
    let end = open + 1, depth = 1;
    for (; depth && end < source.length; end++) {
      if (source[end] === '{') depth++;
      if (source[end] === '}') depth--;
    }
    output += source.slice(cursor, match.index) + match[1] + '().children(() => {' + children(source.slice(open + 1, end - 1)) + '})';
    cursor = end; regex.lastIndex = end;
  }
  return output + source.slice(cursor);
}
function methods(source, names) {
  return names.map(name => {
    const match = new RegExp('^  (?:private )?' + name + '\\(', 'm').exec(source);
    assert.ok(match, name);
    let end = source.indexOf('{', match.index) + 1, depth = 1;
    for (; depth && end < source.length; end++) {
      if (source[end] === '{') depth++;
      if (source[end] === '}') depth--;
    }
    return source.slice(match.index, end);
  }).join('\n');
}
// ArkUI retains creation/update callbacks. Delay each production UI expression so
// updates execute the same lexical expression after @Prop/@State replacement,
// rather than calling the builder again (which would hide by-value captures).
function deferredUi(context) {
  const visit = node => {
    node = ts.visitEachChild(node, visit, context);
    if (ts.isCallExpression(node) && node.arguments.length &&
      ((ts.isIdentifier(node.expression) && ['Text', 'NovelApprovalShell', 'NovelChapterVerdictCard', 'ForEach'].includes(node.expression.text)) ||
       (ts.isPropertyAccessExpression(node.expression) && ['width', 'opacity', 'fontColor', 'accessibilityText'].includes(node.expression.name.text)))) {
      const first = node.arguments[0];
      const getter = ts.factory.createArrowFunction(undefined, undefined, [], undefined, ts.factory.createToken(ts.SyntaxKind.EqualsGreaterThanToken), first);
      return ts.factory.updateCallExpression(node, node.expression, node.typeArguments, [getter, ...node.arguments.slice(1)]);
    }
    return node;
  };
  return root => ts.visitNode(root, visit);
}
function fixture(filename, names, values) {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/components', filename), 'utf8');
  const transformed = ts.transpileModule('class Card {' + children(methods(source, names)) + '} return Card;', {
    compilerOptions: { target: ts.ScriptTarget.ES2022 }, transformers: { before: [deferredUi] },
  }).outputText;
  const nodes = [];
  const node = (kind, value) => {
    const record = { kind, value, attrs: {} }; nodes.push(record);
    const proxy = new Proxy({}, { get: (_, key) => (...args) => { if (key === 'children') args[0](); else record.attrs[key] = args[0]; return proxy; } });
    return proxy;
  };
  const effect = { combine() { return this; }, animation() { return this; } };
  const env = { Text: value => node('Text', value), Column: () => node('Column'), Row: () => node('Row'), Blank() {},
    NovelApprovalShell: value => { const result = node('Shell', value); value().content(); return result; },
    NovelChapterVerdictCard: value => node('Verdict', value),
    ForEach: (items, render, key) => {
      const identities = new Set();
      const record = { kind: 'ForEach', rerender() {
        items().forEach((item, index) => {
          const identity = key(item, index);
          if (!identities.has(identity)) { identities.add(identity); render(item, index); }
        });
      } };
      nodes.push(record); record.rerender();
    },
    FontWeight: { Medium: 'medium', Bold: 'bold' }, HorizontalAlign: { Start: 'start', End: 'end' },
    VerticalAlign: { Center: 'center' }, TextAlign: { Center: 'center' }, TextOverflow: { Ellipsis: 'ellipsis' },
    FlexAlign: { End: 'end' }, TransitionEffect: { OPACITY: effect, translate: () => effect }, Curve: { EaseOut: 'easeout' },
    MOTION_OVERLAY: 200, FONT_SANS_MEDIUM: 'sans', ACCENT: 'accent', ACCENT_INK: 'accentink', ACCENT_LIGHT: 'light', ERROR: 'error',
    INK: 'ink', INK2: 'ink2', INK3: 'ink3', INK4: 'ink4', LINE: 'line', LINE2: 'line2', RAISED: 'raised', SURFACE: 'surface', SURFACE2: 'surface2' };
  const Card = new Function(...Object.keys(env), transformed)(...Object.values(env));
  const card = new Card(); Object.assign(card, values, { getUIContext: () => ({ animateTo: (_, update) => update() }) });
  return { card, nodes, texts: () => nodes.filter(node => node.kind === 'Text').map(node => node.value()) };
}
const ghostNames = ['jobBody', 'header', 'progressBar', 'progressPercent', 'stageLabel', 'stageDescription', 'stageColor',
  'frozenPlanSection', 'candidateSection', 'detailToggle', 'actionRow', 'smallButton', 'shortDigest', 'frozenPlanSummary', 'startGate', 'gateReady', 'gateReason', 'primaryButton', 'canStart'];
const job = { jobId: 'same-job', stage: 'paused', progress: [], targetChapterCount: 2, currentChapterOrdinal: 1, failure: null,
  frozenPlan: { planId: 'plan', digest: 'digest', content: '旧冻结计划' }, candidate: { title: '旧标题', content: '旧候选正文', chapterOrdinal: 1, attempt: 0 }, review: null };

test('same mounted ghostwrite callbacks show new progress candidate plan and child review after same-ID Prop replacement', () => {
  const f = fixture('NovelGhostwriteCard.ets', ghostNames, { job, busy: false, candidateExpanded: true, planExpanded: true });
  f.card.jobBody(job);
  f.card.job = { ...job, progress: [{}], currentChapterOrdinal: 2, candidate: { ...job.candidate, title: '新标题', content: '新候选正文', chapterOrdinal: 2 },
    frozenPlan: { ...job.frozenPlan, content: '新冻结计划' }, review: { id: 'new-review' } };
  assert.ok(f.texts().includes('1/2'));
  assert.ok(f.texts().includes('新候选正文'));
  assert.ok(f.texts().includes('新冻结计划'));
  assert.equal(f.nodes.find(node => node.kind === 'Verdict').value().review.id, 'new-review');
  const width = f.nodes.filter(node => node.kind === 'Row' && node.attrs.width).map(node => node.attrs.width());
  assert.ok(width.includes('50%'));
});

test('mounted detail labels and old action callback consume current State and busy Prop', () => {
  let resumed = 0;
  const f = fixture('NovelGhostwriteCard.ets', ghostNames, { job, busy: false, candidateExpanded: false, planExpanded: false, onResume() { resumed++; }, onCancel() {} });
  f.card.jobBody(job);
  const toggle = f.nodes.find(node => node.kind === 'Text' && node.attrs.accessibilityText && node.attrs.accessibilityText() === '查看冻结计划');
  toggle.attrs.onClick();
  assert.equal(toggle.value(), '收起');
  const resume = f.nodes.find(node => node.kind === 'Text' && node.value() === '继续');
  f.card.busy = true;
  assert.equal(resume.attrs.opacity(), 0.5);
  resume.attrs.onClick(); assert.equal(resumed, 0, 'captured callback must not dispatch after becoming busy');
  f.card.busy = false; resume.attrs.onClick(); assert.equal(resumed, 1);
});

const review = { blocking: false, rewriteRequired: true, candidateId: 'candidate', candidateDigest: 'd', planId: 'p', planDigest: 'pd',
  rewriteInstructions: '旧重写说明', findings: [], nextPlan: null, stateDelta: { chapterHighlight: '', plotState: '' } };
test('mounted verdict shell and expanded details consume replaced review candidate and plan', () => {
  const names = ['reviewCard', 'Body', 'reviewDetails', 'verdictLabel', 'verdictIcon', 'verdictTone', 'summaryLine', 'shortDigest', 'findingLabel'];
  const f = fixture('NovelChapterVerdictCard.ets', names, { review, candidate: job.candidate, frozenPlan: job.frozenPlan, expanded: true });
  f.card.reviewCard(review, job.candidate, job.frozenPlan);
  f.card.review = { ...review, rewriteRequired: false, rewriteInstructions: '新重写说明', candidateId: 'next-candidate' };
  f.card.candidate = { ...job.candidate, chapterOrdinal: 2 };
  f.card.frozenPlan = { ...job.frozenPlan, planId: 'next-plan' };
  const shell = f.nodes.find(node => node.kind === 'Shell').value();
  assert.equal(shell.statusLabel, '审稿通过');
  assert.match(shell.title, /第 2 章/); assert.match(shell.subtitle, /next-plan/);
  assert.ok(f.texts().includes('新重写说明'));
  assert.ok(f.texts().some(text => text.includes('next-candidate')));
});


test('existing mounted start readiness labels and start action reflect live plan model and busy props', () => {
  let started = 0;
  const f = fixture('NovelGhostwriteCard.ets', ghostNames, { job: null, busy: false, planReady: false, modelReady: true, planDisabledReason: '请先保存计划', modelDisabledReason: '' });
  f.card.startGate('本章计划');
  f.card.primaryButton('开始代笔', () => { started++; });
  const button = f.nodes.find(node => node.kind === 'Text' && node.value() === '开始代笔');
  button.attrs.onClick(); assert.equal(started, 0);
  f.card.planReady = true;
  assert.ok(f.texts().includes('已就绪'));
  assert.equal(button.attrs.opacity(), 1);
  button.attrs.onClick(); assert.equal(started, 1);
  f.card.modelReady = false;
  assert.equal(button.attrs.opacity(), 0.5);
  button.attrs.onClick(); assert.equal(started, 1);
});


test('same-code review findings replace keyed row text when new review changes message or location', () => {
  const names = ['reviewDetails', 'shortDigest', 'findingLabel'];
  const first = { ...review, findings: [{ kind: 'hard_continuity', code: 'C01', message: '旧问题', location: '第一段' }] };
  const f = fixture('NovelChapterVerdictCard.ets', names, { review: first });
  f.card.reviewDetails(first);
  const list = f.nodes.find(node => node.kind === 'ForEach');
  f.card.review = { ...first, findings: [{ ...first.findings[0], message: '新问题', location: '第三段' }] };
  list.rerender();
  assert.ok(f.texts().includes('新问题'));
  assert.ok(f.texts().includes('位置:第三段'));
});
