import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderWidgetSpec } from '../main/ets/chat/generative_widget_specs.ts';
import { widgetDocument } from '../main/ets/chat/generative_widget.ts';

const labelFonts = (svg: string): number[] => [...svg.matchAll(/<text[^>]*y="265"[^>]*font-size="(\d+)"/g)].map((m) => Number(m[1]));
const legendFonts = (svg: string): number[] => [...svg.matchAll(/<text[^>]*y="295"[^>]*font-size="(\d+)"/g)].map((m) => Number(m[1]));

test('Sparse chart labels and short legend use readable bounded 24-unit type', () => {
  const svg = renderWidgetSpec('vchart', { type: 'bar', data: [{ values: [{ x: '第一组', y: 12 }, { x: '第二组', y: 8 }] }], xField: 'x', yField: 'y' });
  assert.ok(svg);
  assert.deepEqual(labelFonts(svg), [24, 24]);
  assert.deepEqual(legendFonts(svg), [24]);
});

test('Dense chart preserves original x label font without increasing overlap', () => {
  const labels = Array.from({ length: 24 }, (_v, i) => `${i}`);
  const svg = renderWidgetSpec('chart', { type: 'line', labels, series: [{ name: 'long series legend that cannot grow', data: labels.map((_v, i) => i) }] });
  assert.ok(svg);
  assert.deepEqual(labelFonts(svg), labels.map(() => 11));
  assert.deepEqual(legendFonts(svg), [13]);
});

test('Wider but long sparse labels use available slot and preserve original SVG escaping', () => {
  const labels = ['<abc&defgh-long', '<abc&defgh-long', '<abc&defgh-long'];
  const svg = renderWidgetSpec('chart', { type: 'bar', labels, series: [{ name: '<series&>', data: [1, 2, 3] }] });
  assert.ok(svg);
  assert.deepEqual(labelFonts(svg), [16, 16, 16]);
  assert.match(svg, /&lt;abc&amp;defgh/);
  assert.match(svg, /&lt;series&amp;&gt;/);
  assert.doesNotMatch(svg, /<abc|<series/);
});

test('Dark generated chart label styles are scoped and preserve light chart and arbitrary SVG colors', () => {
  const svg = renderWidgetSpec('chart', { type: 'bar', labels: ['A'],
    series: [{ name: 'Blue', data: [1] }, { name: 'Green', data: [2] }, { name: 'Orange', data: [3] }, { name: 'Purple', data: [4] }] });
  assert.ok(svg);
  assert.match(svg, /class="amber-chart-axis-label"/);
  assert.match(svg, /class="amber-chart-legend-label"/);
  const dark = widgetDocument(svg, true);
  assert.match(dark, /\.amber-chart-axis-label\{fill:#D3D0C9\}/);
  for (const [original, readable] of [['#2563eb', '#93B4FF'], ['#16a34a', '#86DCA0'], ['#ea580c', '#FDBA74'], ['#9333ea', '#D8B4FE']]) {
    assert.ok(dark.includes(`.amber-chart-legend-label[fill="${original}"]{fill:${readable}}`));
    assert.ok(svg.includes(`fill="${original}"`), 'series geometry keeps its original color');
  }
  const light = widgetDocument(svg, false);
  assert.doesNotMatch(light, /\.amber-chart-(?:axis|legend)-label/);
  const custom = '<svg><text fill="#123456">custom</text><rect fill="#2563eb"/></svg>';
  assert.ok(widgetDocument(custom, true).includes(custom));
  assert.doesNotMatch(dark, /(?:svg\s+text|svg\s*\*)\s*\{/);
});
