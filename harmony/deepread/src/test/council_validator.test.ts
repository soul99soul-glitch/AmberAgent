import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { parseTask, validateSeats, resolveSynthesisModelId, buildDefaultSeats, resolveCouncilDynamicSeats } from '../main/ets/council/validator.ts';
import type { CouncilTaskInput } from '../main/ets/council/validator.ts';
import { makeSeat, makeRuntimeSetting } from '../main/ets/council/models.ts';
import type { ModelCouncilRuntimeSetting, ModelCouncilSeat } from '../main/ets/council/models.ts';
import { makeModelConfig } from '../main/ets/domain/model_config.ts';
import type { ModelConfig } from '../main/ets/domain/model_config.ts';

const pool: ModelConfig[] = [
  makeModelConfig({ id: 'mA', label: 'A', baseUrl: 'http://a', apiKey: 'k', model: 'ma' }),
  makeModelConfig({ id: 'mB', label: 'B', baseUrl: 'http://b', apiKey: 'k', model: 'mb' }),
];

const setting = (over: Partial<ModelCouncilRuntimeSetting> = {}): ModelCouncilRuntimeSetting =>
  makeRuntimeSetting({ enabled: true, ...over });

test('parseTask: default strategy injects 3 core seats', () => {
  const input: CouncilTaskInput = { mode: '', objective: '要不要做 X?' };
  const spec = parseTask(input, setting(), pool);
  assert.equal(spec.seats.length, 3);
  const roles = spec.seats.map(s => s.role).sort();
  assert.deepEqual(roles, ['judge', 'opponent', 'supporter']);
});

test('parseTask: models assigned round-robin from pool', () => {
  const spec = parseTask({ mode: '', objective: 'q' }, setting(), pool);
  const ids = spec.seats.map(s => s.modelId);
  assert.ok(ids.every(id => id === 'mA' || id === 'mB'));
  assert.ok(ids.includes('mA') && ids.includes('mB'), 'uses both models');
});

test('parseTask: compare forces rounds=1', () => {
  const spec = parseTask({ mode: 'compare', objective: 'q', rounds: 4 }, setting(), pool);
  assert.equal(spec.mode, 'compare');
  assert.equal(spec.rounds, 1);
});

test('parseTask: debate clamps rounds to maxRounds', () => {
  const spec = parseTask({ mode: 'debate', objective: 'q', rounds: 99 }, setting({ maxRounds: 5 }), pool);
  assert.equal(spec.rounds, 5);
});

test('parseTask: empty objective throws', () => {
  assert.throws(() => parseTask({ mode: '', objective: '   ' }, setting(), pool), /objective/);
});

test('parseTask: explicit seats used as-is (no core injection)', () => {
  const seats: ModelCouncilSeat[] = [
    makeSeat({ name: '甲', role: 'custom1', modelId: 'mA' }),
    makeSeat({ name: '乙', role: 'custom2', modelId: 'mB' }),
  ];
  const spec = parseTask({ mode: '', objective: 'q', seats: seats }, setting(), pool);
  assert.equal(spec.seats.length, 2);
  assert.deepEqual(spec.seats.map(s => s.role), ['custom1', 'custom2']);
});

test('parseTask: extraLens adds lens seats, dedup by role', () => {
  const spec = parseTask({ mode: '', objective: 'q', extraLens: ['product', 'product', 'risk'] }, setting(), pool);
  const roles = spec.seats.map(s => s.role);
  assert.ok(roles.includes('product'));
  assert.ok(roles.includes('risk'));
  assert.equal(roles.filter(r => r === 'product').length, 1, 'deduped');
  assert.equal(spec.seats.length, 5); // 3 core + product + risk
});

test('parseTask: caps seats at maxSeats', () => {
  const spec = parseTask(
    { mode: '', objective: 'q', extraLens: ['product', 'marketing', 'pr', 'engineering', 'ux', 'risk'] },
    setting({ maxSeats: 4 }),
    pool,
  );
  assert.equal(spec.seats.length, 4);
});

test('validateSeats: fewer than 2 throws', () => {
  assert.throws(() => validateSeats(setting(), [makeSeat({ name: 'a', role: 'r', modelId: 'mA' })], pool), /至少/);
});

test('validateSeats: over maxSeats throws', () => {
  const seats = [
    makeSeat({ name: 'a', role: 'r1', modelId: 'mA' }),
    makeSeat({ name: 'b', role: 'r2', modelId: 'mA' }),
    makeSeat({ name: 'c', role: 'r3', modelId: 'mA' }),
  ];
  assert.throws(() => validateSeats(setting({ maxSeats: 2 }), seats, pool), /上限/);
});

test('validateSeats: bad temperature throws', () => {
  const seats = [
    makeSeat({ name: 'a', role: 'r1', modelId: 'mA', temperature: 3 }),
    makeSeat({ name: 'b', role: 'r2', modelId: 'mB' }),
  ];
  assert.throws(() => validateSeats(setting(), seats, pool), /temperature/);
});

test('validateSeats: unknown model throws', () => {
  const seats = [
    makeSeat({ name: 'a', role: 'r1', modelId: 'nope' }),
    makeSeat({ name: 'b', role: 'r2', modelId: 'mB' }),
  ];
  assert.throws(() => validateSeats(setting(), seats, pool), /模型不存在/);
});

test('resolveSynthesisModelId: prefers configured when in pool', () => {
  assert.equal(resolveSynthesisModelId(setting({ synthesisModelId: 'mB' }), pool), 'mB');
});

test('resolveSynthesisModelId: falls back to first when configured missing', () => {
  assert.equal(resolveSynthesisModelId(setting({ synthesisModelId: 'ghost' }), pool), 'mA');
});

test('resolveSynthesisModelId: empty pool → empty string', () => {
  assert.equal(resolveSynthesisModelId(setting(), []), '');
});

test('buildDefaultSeats: includes user non-core defaultSeats', () => {
  const s = setting({ defaultSeats: [makeSeat({ name: '自定义', role: 'custom', modelId: 'mA' })] });
  const seats = buildDefaultSeats(s, [], pool);
  assert.ok(seats.some(x => x.role === 'custom'));
  assert.ok(seats.some(x => x.role === 'supporter'));
});

test('parseTask: setting.enabled=false → fail closed(任何调用路径拦截)', () => {
  assert.throws(() => parseTask(
    { mode: 'compare', objective: 'q' },
    makeRuntimeSetting({ enabled: false }), pool), /模型议会/);
});

test('dynamic seat selection exposes only seats admitted by the same runner validator', () => {
  const dynamic = [makeSeat({ name: '成本', role: 'cost' }), makeSeat({ name: '伦理', role: 'ethics' })];
  const s = setting({ maxSeats: 4 });
  const selected = resolveCouncilDynamicSeats(s, [], pool, dynamic);
  assert.deepEqual(selected.seats.map(seat => seat.seatId), [dynamic[0].seatId]);
  assert.equal(selected.remainingCapacity, 0);
  const spec = parseTask({ mode: 'compare', objective: 'q', extraSeats: selected.seats }, s, pool);
  assert.ok(spec.seats.some(seat => seat.seatId === selected.seats[0].seatId));
  assert.equal(spec.seats.length, 4);
  const full = resolveCouncilDynamicSeats(s, ['product'], pool, dynamic);
  assert.deepEqual(full.seats, []);
  assert.equal(full.remainingCapacity, 0);
});

test('dynamic seat selection preserves core seats and deduplicates default, lens and dynamic roles', () => {
  const user = makeSeat({ name: '已有成本席', role: 'cost' });
  const s = setting({ defaultSeats: [user], maxSeats: 8 });
  const dynamic = [
    makeSeat({ name: '伪核心', role: 'judge' }), makeSeat({ name: '重复成本', role: 'cost' }),
    makeSeat({ name: '重复产品', role: 'product' }), makeSeat({ name: '伦理', role: 'ethics' }),
    makeSeat({ name: '重复伦理', role: 'ethics' }),
  ];
  const selected = resolveCouncilDynamicSeats(s, ['product'], pool, dynamic);
  assert.deepEqual(selected.seats.map(seat => seat.seatId), [dynamic[3].seatId]);
  assert.equal(selected.remainingCapacity, 2);
  const effective = buildDefaultSeats(s, ['product'], pool, selected.seats);
  assert.deepEqual(effective.slice(0, 3).map(seat => seat.role), ['supporter', 'opponent', 'judge']);
});
