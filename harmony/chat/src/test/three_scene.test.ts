import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeThreeScene, normalizeThreeSceneObject, renderThreeSceneCover,
} from '../main/ets/chat/three_scene.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const object = (overrides: JsonObject = {}): JsonObject => ({
  id: 'cube', type: 'box', color: '#ff6633', position: [0, 1, 0], rotation: [0, 0, 0],
  scale: [1, 1, 1], size: [2, 2, 2], ...overrides,
});

test('normalizes bounded declarative scene objects and renders a static SVG cover', () => {
  const scene = normalizeThreeScene({ objects: [object(), object({ id: 'ball', type: 'sphere', size: [1] })] });
  assert.ok(scene !== null);
  assert.deepEqual(scene.objects[1].type, 'sphere');
  const svg = renderThreeSceneCover(scene);
  assert.match(svg, /^<svg/);
  assert.match(svg, /#ff6633/);
  assert.doesNotMatch(svg, /<script|javascript:|THREE|three\.js/i);
});

test('rejects malformed, non-finite, duplicate and giant geometry before rendering', () => {
  assert.equal(normalizeThreeSceneObject(object({ position: [0, Number.NaN, 0] })), null);
  assert.equal(normalizeThreeSceneObject(object({ size: [999, 2, 2] })), null);
  assert.equal(normalizeThreeScene({ objects: [object(), object()] }), null);
  assert.equal(normalizeThreeScene({ objects: [object({ type: 'box', size: [1, 2] })] }), null);
  assert.equal(normalizeThreeScene({ objects: [object({ id: 'bad-color', color: 'red' })] }), null);
  assert.equal(normalizeThreeScene({ objects: Array.from({ length: 513 }, (_value, index) => object({ id: `o-${index}` })) }), null);
});

test('supports multi-hundred voxel scenes, box defaults, and compact voxels tuples', () => {
  const shorthand = normalizeThreeSceneObject({ id: 'v1', color: '#36f', position: [1, 2, 3] });
  assert.deepEqual(shorthand, {
    id: 'v1',
    type: 'box',
    color: '#3366ff',
    position: [1, 2, 3],
    rotation: [0, 0, 0],
    scale: [1, 1, 1],
    size: [1, 1, 1],
  });
  const dense = normalizeThreeScene({
    objects: Array.from({ length: 200 }, (_v, i) => ({ id: `b-${i}`, color: '#4caf50', position: [i % 10, Math.floor(i / 10), 0] })),
    voxels: [
      [0, 5, 0, '#f80'],
      [1, 5, 0, '#ff8800', 2],
      [2, 5, 0, '#112233', 2, 3, 4],
    ],
  });
  assert.ok(dense !== null);
  assert.equal(dense.objects.length, 203);
  assert.deepEqual(dense.objects[200].size, [1, 1, 1]);
  assert.deepEqual(dense.objects[201].size, [2, 2, 2]);
  assert.deepEqual(dense.objects[202].size, [2, 3, 4]);
});


