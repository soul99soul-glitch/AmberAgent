// three_scene — bounded declarative 3D scene data for show-widget.
//
// This module only normalizes data and draws an SVG cover. It never evaluates
// JavaScript or attempts to provide a Three.js runtime.
// The cover is used for standalone export; the local preview consumes the normalized scene.

import type { JsonObject, JsonValue } from './json.ts';

export type ThreeSceneObjectType = 'box' | 'sphere' | 'cylinder' | 'cone' | 'torus' | 'plane';

export interface ThreeSceneObject {
  id: string;
  type: ThreeSceneObjectType;
  color: string;
  position: number[];
  rotation: number[];
  scale: number[];
  size: number[];
}

export interface ThreeSceneSpec {
  objects: ThreeSceneObject[];
}

export const THREE_SCENE_RENDERER: string = 'threejs';
export const THREE_SCENE_RENDERER_ALIAS: string = 'three';
export const THREE_SCENE_MAX_OBJECTS: number = 48;
export const THREE_SCENE_MAX_ID_CHARS: number = 48;
export const THREE_SCENE_MAX_COORDINATE: number = 100;
export const THREE_SCENE_MAX_SIZE: number = 40;
export const THREE_SCENE_MAX_SCALE: number = 10;

const object = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;

const string = (value: JsonValue | undefined): string => typeof value === 'string' ? value : '';

const OBJECT_TYPES: ThreeSceneObjectType[] = ['box', 'sphere', 'cylinder', 'cone', 'torus', 'plane'];

const SIZE_LENGTHS: Record<ThreeSceneObjectType, number> = {
  box: 3,
  sphere: 1,
  cylinder: 2,
  cone: 2,
  torus: 2,
  plane: 2,
};

const isThreeSceneObjectType = (value: string): value is ThreeSceneObjectType =>
  (OBJECT_TYPES as string[]).includes(value);

const finiteVector = (
  value: JsonValue | undefined,
  length: number,
  max: number,
  positive: boolean,
): number[] | null => {
  if (!Array.isArray(value) || value.length !== length) return null;
  const out: number[] = [];
  for (const item of value) {
    if (typeof item !== 'number' || !Number.isFinite(item)) return null;
    if (positive ? item <= 0 || item > max : Math.abs(item) > max) return null;
    out.push(item);
  }
  return out;
};

const validColor = (value: string): boolean => /^#[0-9a-f]{6}$/i.test(value);

/** Normalize one complete object. Invalid or incomplete objects return null. */
export const normalizeThreeSceneObject = (value: JsonValue | undefined): ThreeSceneObject | null => {
  const raw = object(value);
  if (raw === null) return null;
  const id: string = string(raw.id).trim();
  const typeText: string = string(raw.type).trim().toLowerCase();
  const color: string = string(raw.color).trim().toLowerCase();
  if (id.length === 0 || id.length > THREE_SCENE_MAX_ID_CHARS ||
    !isThreeSceneObjectType(typeText) || !validColor(color)) return null;
  const position: number[] | null = finiteVector(raw.position, 3, THREE_SCENE_MAX_COORDINATE, false);
  const rotation: number[] | null = finiteVector(raw.rotation, 3, THREE_SCENE_MAX_COORDINATE, false);
  const scale: number[] | null = finiteVector(raw.scale, 3, THREE_SCENE_MAX_SCALE, true);
  const size: number[] | null = finiteVector(raw.size, SIZE_LENGTHS[typeText], THREE_SCENE_MAX_SIZE, true);
  if (position === null || rotation === null || scale === null || size === null) return null;
  return { id, type: typeText, color, position, rotation, scale, size };
};

/** Normalize a complete scene. Every supplied object must pass the same bounds. */
export const normalizeThreeScene = (value: JsonValue | undefined): ThreeSceneSpec | null => {
  const raw = object(value);
  if (raw === null || !Array.isArray(raw.objects) || raw.objects.length > THREE_SCENE_MAX_OBJECTS) return null;
  const objects: ThreeSceneObject[] = [];
  const ids = new Set<string>();
  for (const item of raw.objects) {
    const normalized: ThreeSceneObject | null = normalizeThreeSceneObject(item);
    if (normalized === null || ids.has(normalized.id)) return null;
    ids.add(normalized.id);
    objects.push(normalized);
  }
  return { objects };
};

const numberText = (value: number): string => Number(value.toFixed(2)).toString();

interface ProjectedObject {
  object: ThreeSceneObject;
  x: number;
  y: number;
  radius: number;
  depth: number;
}

const project = (item: ThreeSceneObject): ProjectedObject => {
  const x3: number = item.position[0];
  const y3: number = item.position[1];
  const z3: number = item.position[2];
  const extent: number = Math.max(...item.size) * Math.max(...item.scale);
  return {
    object: item,
    x: 340 + (x3 - z3) * 3.4,
    y: 202 - y3 * 3.4 + (x3 + z3) * 0.85,
    radius: Math.max(8, Math.min(72, extent * 2.4)),
    depth: x3 + z3,
  };
};

const shape = (item: ProjectedObject): string => {
  const color: string = item.object.color;
  const x: string = numberText(item.x);
  const y: string = numberText(item.y);
  const r: string = numberText(item.radius);
  switch (item.object.type) {
    case 'sphere':
      return `<circle cx="${x}" cy="${y}" r="${r}" fill="${color}" fill-opacity=".86" stroke="#ffffff" stroke-opacity=".55"/>`;
    case 'torus':
      return `<ellipse cx="${x}" cy="${y}" rx="${r}" ry="${numberText(item.radius * .42)}" fill="none" stroke="${color}" stroke-width="${numberText(Math.max(6, item.radius * .22))}" stroke-opacity=".9"/>`;
    case 'plane':
      return `<rect x="${numberText(item.x - item.radius)}" y="${numberText(item.y - item.radius * .42)}" width="${numberText(item.radius * 2)}" height="${numberText(item.radius * .84)}" rx="6" fill="${color}" fill-opacity=".38" stroke="${color}" stroke-opacity=".9"/>`;
    case 'cone':
      return `<path d="M${x} ${numberText(item.y - item.radius)} L${numberText(item.x + item.radius)} ${numberText(item.y + item.radius * .55)} L${numberText(item.x - item.radius)} ${numberText(item.y + item.radius * .55)} Z" fill="${color}" fill-opacity=".82" stroke="#ffffff" stroke-opacity=".55"/>`;
    case 'cylinder':
      return `<rect x="${numberText(item.x - item.radius)}" y="${numberText(item.y - item.radius * .58)}" width="${numberText(item.radius * 2)}" height="${numberText(item.radius * 1.16)}" rx="${numberText(item.radius * .28)}" fill="${color}" fill-opacity=".82" stroke="#ffffff" stroke-opacity=".55"/>`;
    case 'box':
      return `<path d="M${numberText(item.x)} ${numberText(item.y - item.radius)} L${numberText(item.x + item.radius)} ${numberText(item.y - item.radius * .48)} L${numberText(item.x)} ${numberText(item.y + item.radius * .04)} L${numberText(item.x - item.radius)} ${numberText(item.y - item.radius * .48)} Z" fill="${color}" fill-opacity=".84" stroke="#ffffff" stroke-opacity=".55"/>`;
  }
};

/** Static SVG cover for export when the interactive local renderer is unavailable. */
export const renderThreeSceneCover = (scene: ThreeSceneSpec, dark: boolean = false): string => {
  const projected: ProjectedObject[] = scene.objects.map(project)
    .sort((a: ProjectedObject, b: ProjectedObject): number => a.depth - b.depth);
  const background: string = dark ? '#25222D' : '#F4F7FF';
  const grid: string = dark ? '#69708A' : '#AAB8D8';
  const ink: string = dark ? '#E8ECFF' : '#27324D';
  let body: string = '';
  for (let i: number = -4; i <= 4; i++) {
    const offset: number = i * 34;
    body += `<path d="M340 206 L${numberText(340 + offset)} 292" stroke="${grid}" stroke-opacity=".34"/>`;
    body += `<path d="M340 206 L${numberText(340 - offset)} 292" stroke="${grid}" stroke-opacity=".34"/>`;
  }
  body += '<path d="M70 292H610" stroke="' + grid + '" stroke-opacity=".48"/>';
  for (const item of projected) body += shape(item);
  const labels: string = projected.length === 0
    ? '<text x="340" y="190" text-anchor="middle" font-size="18" fill="' + ink + '">正在生成 3D 场景…</text>'
    : `<text x="24" y="34" font-size="14" fill="${ink}">${projected.length} 个对象</text>`;
  return `<svg width="100%" viewBox="0 0 680 320" xmlns="http://www.w3.org/2000/svg">` +
    `<rect width="680" height="320" rx="16" fill="${background}"/>` + body + labels + '</svg>';
};

