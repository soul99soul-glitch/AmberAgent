import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeNovelMaterial, makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';
import { classifyNovelMaterials, novelMaterialRelevanceScore } from '../main/ets/novel/material_injection.ts';
import { buildNovelContext } from '../main/ets/novel/context_builder.ts';

const material = (id: string, mode: 'always' | 'smart' | 'off', title: string = id) => ({
  ...makeNovelMaterial({ kind: 'world', title, content: 'secret lantern', now: 1, injectionMode: mode }), id,
});
const project = (...materials: ReturnType<typeof material>[]) => ({ ...makeNovelProject({ name: '资料', now: 1 }), materials });

test('smart relevance uses the iOS title/alias 100, unique tags 20 and content word score', () => {
  const item = { ...material('m', 'smart', 'Captain'), aliases: ['Émile'], tags: ['harbor', 'HARbor'] };
  assert.equal(novelMaterialRelevanceScore(item, 'émile harbor secret lantern'), 122);
  assert.equal(novelMaterialRelevanceScore(item, ''), 0);
  const words = Array.from({ length: 25 }, (_, index) => `word${index}`);
  assert.equal(novelMaterialRelevanceScore({ ...material('m', 'smart', 'a'), content: words.join(' ') }, words.join(' ')), 20);
});

test('always/smart/off and per-run overrides use the effective material view', () => {
  const p = project(material('always', 'always'), material('matched', 'smart', 'harbor'),
    material('miss', 'smart'), material('off', 'off'));
  const decisions = classifyNovelMaterials(p, 'harbor');
  assert.deepEqual(decisions.filter(item => item.included).map(item => item.materialId), ['always', 'matched']);
  assert.equal(decisions.find(item => item.materialId === 'off')?.reason, 'disabled');
  const overrides = classifyNovelMaterials(p, 'harbor', {
    forceIncludeMaterialIds: ['off'], forceExcludeMaterialIds: ['always', 'matched'],
  });
  assert.deepEqual(overrides.filter(item => item.included).map(item => item.materialId), ['off']);
  assert.equal(overrides.find(item => item.materialId === 'off')?.protected, true);
  assert.throws(() => classifyNovelMaterials(p, '', { forceIncludeMaterialIds: ['deleted'], forceExcludeMaterialIds: [] }), /已过期/);
});

test('branch overrides are protected before exclude/off and hidden shared materials stay excluded', () => {
  const base = material('base', 'smart');
  const hidden = material('hidden', 'always');
  const override = { ...base, content: '分支权威', injectionMode: 'off' as const, enabled: false };
  const p = { ...project(override), baseMaterials: [base, hidden], materialOverrides: [override], hiddenMaterialIds: ['hidden'] };
  const decisions = classifyNovelMaterials(p, '', { forceIncludeMaterialIds: [], forceExcludeMaterialIds: ['base'] });
  assert.equal(decisions.length, 1);
  assert.equal(decisions[0].reason, 'branchOverride');
  assert.equal(decisions[0].included, true);
  assert.equal(decisions[0].protected, true);
  assert.match(decisions[0].text, /分支权威/);
});

test('smart score sorts descending with stable kind/title/id ties regardless of mutation time', () => {
  const weak = { ...material('weak', 'smart', 'distant'), tags: ['harbor'], updatedAt: 999 };
  const strong = { ...material('strong', 'smart', 'harbor'), updatedAt: 0 };
  const tie = { ...strong, id: 'earlier' };
  assert.deepEqual(classifyNovelMaterials(project(weak, strong, tie), 'harbor')
    .filter(item => item.included).map(item => item.materialId), ['earlier', 'strong', 'weak']);
});

test('context assembly protects always/forced sections and queries current user text and chapter context', () => {
  const p = project(material('always', 'always'), material('smart', 'smart', 'harbor'), material('off', 'off'));
  p.chapters = [makeNovelChapter({ title: '当前章节', content: '来到 harbor', now: 1 })];
  const context = buildNovelContext(p, 'write', null, null, '继续', { forceIncludeMaterialIds: ['off'], forceExcludeMaterialIds: [] });
  assert.equal(context.sections.find(item => item.key === 'material:always')?.required, true);
  assert.equal(context.sections.find(item => item.key === 'material:smart')?.required, false);
  assert.equal(context.sections.find(item => item.key === 'material:off')?.required, true);
});
