import assert from 'node:assert/strict';
import { test } from 'node:test';
import { NativeInlineRenderer } from '../../../entry/src/main/ets/components/NativeMarkdownInline.ts';
import type { NativeInlineRun, NativeInlineSurface } from '../../../entry/src/main/ets/components/NativeMarkdownInline.ts';

class FakeSurface implements NativeInlineSurface {
  text: string = '';
  calls: string[] = [];
  fades: { start: number; length: number; opacity: number }[] = [];
  append(runs: NativeInlineRun[], opacity: number): void {
    this.calls.push(`append:${runs.map((run) => run.text).join('')}:${opacity}`);
    this.text += runs.map((run) => run.text).join('');
  }
  replace(start: number, count: number, runs: NativeInlineRun[], opacity?: number): void {
    this.calls.push(`replace:${start}:${count}:${opacity}`);
    this.text = this.text.slice(0, start) + runs.map((run) => run.text).join('');
  }
  setOpacity(start: number, runs: NativeInlineRun[], opacity: number): void {
    this.calls.push('fade');
    this.fades.push({ start, length: runs.reduce((sum, run) => sum + run.text.length, 0), opacity });
  }
  publish(): void { this.calls.push(`publish:${this.text}`); }
}
const textRun = (text: string): NativeInlineRun => ({ kind: 'text', text, url: '' });

test('NativeInlineRenderer appends only new text and publishes stable surface', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('old')], false);
  surface.calls = [];
  renderer.update([textRun('old + new')], true);
  assert.deepEqual(surface.calls, ['append: + new:0', 'publish:old + new']);
  renderer.update([textRun('old + new')], true);
  assert.equal(surface.calls.length, 2, 'unchanged snapshot has no controller call');
});

test('NativeInlineRenderer replaces only incompatible tail after Markdown retyping, without replaying old text', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('old **bold')], false);
  surface.calls = [];
  renderer.update([textRun('old '), { kind: 'bold', text: 'bold', url: '' }], true);
  assert.deepEqual(surface.calls, ['replace:4:6:1', 'fade', 'publish:old bold']);
  assert.equal(renderer.frame(100), true, 'style correction fades the changed suffix instead of popping opaque');
  renderer.frame(350);
  assert.equal(surface.fades.at(-1)?.start, 4, 'stable prefix is never replayed');
  assert.ok(surface.fades.at(-1)!.opacity > 0 && surface.fades.at(-1)!.opacity < 1);
});

test('NativeInlineRenderer onFrame fades appended unit only, content unchanged and completion keeps fade running', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('old')], false);
  renderer.update([textRun('oldabcdefghijkl')], true);
  renderer.frame(100);
  renderer.update([textRun('oldabcdefghijkl')], false);
  surface.calls = [];
  assert.equal(renderer.frame(350), true);
  assert.equal(surface.text, 'oldabcdefghijkl');
  assert.deepEqual(surface.calls, ['fade', 'publish:oldabcdefghijkl']);
  assert.equal(surface.fades.at(-1)?.start, 3);
  assert.equal(surface.fades.at(-1)?.length, 12);
  assert.ok(Math.abs(surface.fades.at(-1)!.opacity - 0.5375) < 0.00001);
  assert.equal(renderer.frame(600), false);
  assert.equal(surface.fades.at(-1)?.opacity, 1);
});

test('NativeInlineRenderer append units retain their own clocks and never replay earlier ranges', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('old')], false);
  renderer.update([textRun('oldabcdefghijkl')], true);
  renderer.frame(100);
  renderer.frame(300);
  renderer.update([textRun('oldabcdefghijklmnopqrstuvwx')], true);
  renderer.frame(600);
  assert.equal(surface.fades.find((fade) => fade.start === 3 && fade.opacity === 1)?.length, 12);
  const second = surface.fades.filter((fade) => fade.start === 15).at(-1)!;
  assert.ok(second.opacity > 0 && second.opacity < 1);
  surface.fades = [];
  renderer.frame(800);
  assert.ok(surface.fades.every((fade) => fade.start === 15), 'first unit is retired');
  renderer.update([textRun('oldabcdefghijklmnopqrstuvwx history')], false);
  assert.equal(renderer.frame(900), false);
});

test('NativeInlineRenderer clamps fade to iOS 0.5s for 1/12 chars and scales 36/600 chars', () => {
  for (const count of [1, 12, 36, 600]) {
    const surface = new FakeSurface();
    const renderer = new NativeInlineRenderer(surface);
    renderer.update([textRun('old')], false);
    renderer.update([textRun('old' + 'x'.repeat(count))], true);
    renderer.frame(100);
    const duration = Math.min(500, Math.max(1000 / 30, 6000 / count));
    assert.equal(renderer.frame(100 + duration - 0.01), true);
    assert.equal(renderer.frame(100 + duration + 0.01), false);
    assert.equal(surface.fades.at(-1)?.opacity, 1);
  }
});

test('NativeInlineRenderer retype keeps only surviving prefix fades and never splits emoji', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('old')], false);
  renderer.update([textRun('old😀**bold')], true);
  renderer.frame(100);
  renderer.update([textRun('old😀'), { kind: 'bold', text: 'bold', url: '' }], true);
  surface.fades = [];
  renderer.frame(350);
  assert.deepEqual(surface.fades.map((fade) => [fade.start, fade.length]), [[3, 2], [5, 4]]);
  surface.calls = [];
  renderer.update([textRun('old😁'), { kind: 'bold', text: 'bold', url: '' }], false);
  assert.equal(surface.calls[0], 'replace:3:6:1');
});

test('style-only correction keeps existing opacity and does not replay already visible text', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('old')], false);
  renderer.update([textRun('oldword')], true);
  renderer.frame(100); renderer.frame(350);
  const opacity = surface.fades.at(-1)!.opacity;
  surface.fades = [];
  renderer.update([textRun('old'), { kind: 'bold', text: 'word', url: '' }], true);
  assert.deepEqual(surface.fades, [{ start: 3, length: 4, opacity }]);
  assert.equal(renderer.frame(600), false, 'the original fade clock finishes without restarting');
  surface.fades = [];
  renderer.update([textRun('oldword')], true);
  assert.equal(renderer.frame(700), false);
  assert.equal(surface.fades.length, 0, 'fully visible text only changes style');
});


test('fade frames publish rendering only, never rebind native text', () => {
  const publications: boolean[] = [];
  const surface = new FakeSurface();
  surface.publish = (contentChanged?: boolean): void => { publications.push(contentChanged!); };
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('已显示')], false);
  renderer.update([textRun('已显示新增文字')], true);
  renderer.frame(100);
  renderer.frame(350);
  renderer.frame(600);
  assert.deepEqual(publications, [true, true, false, false]);
});

test('drawing ranges are detached snapshots and track retyping and final fade retirement', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('原有')], false);
  renderer.update([textRun('原有😀**粗体')], true);
  renderer.frame(100);
  renderer.frame(350);
  const first = renderer.activeFadeRanges();
  const originalOpacity = first[0].opacity;
  first[0].opacity = 1;
  assert.equal(renderer.activeFadeRanges()[0].opacity, originalOpacity);
  renderer.update([textRun('原有😀'), { kind: 'bold', text: '粗体', url: '' }], true);
  assert.deepEqual(renderer.activeFadeRanges().map(({ start, length }) => [start, length]), [[2, 2], [4, 2]]);
  renderer.frame(850);
  assert.deepEqual(renderer.activeFadeRanges(), []);
});


test('a newly mounted streaming paragraph fades in for 500ms even when its first snapshot is large', () => {
  const surface = new FakeSurface();
  const renderer = new NativeInlineRenderer(surface);
  renderer.update([textRun('新段落'.repeat(40))], true);
  renderer.frame(100);
  renderer.frame(200);
  assert.equal(renderer.hasAnimation(), true, 'new 120-char block must not turn fully opaque in 50ms');
  assert.ok(renderer.activeFadeRanges()[0].opacity < 0.5);
  renderer.update([textRun('新段落'.repeat(40))], false);
  assert.equal(renderer.frame(599), true, 'completion keeps its original entry fade');
  assert.equal(renderer.frame(600), false);
});
