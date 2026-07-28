import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { Semaphore, withPermit } from '../main/ets/council/semaphore.ts';

const delay = (ms: number): Promise<void> => new Promise(res => setTimeout(res, ms));

test('semaphore: allows up to permits concurrently', async () => {
  const sem = new Semaphore(2);
  let active = 0;
  let maxActive = 0;
  const job = async (): Promise<void> => {
    await sem.acquire();
    active += 1;
    if (active > maxActive) maxActive = active;
    await delay(20);
    active -= 1;
    sem.release();
  };
  await Promise.all([job(), job(), job(), job(), job()]);
  assert.equal(maxActive, 2);
});

test('semaphore: release hands off to a waiter', async () => {
  const sem = new Semaphore(1);
  const order: string[] = [];
  await sem.acquire();
  const p = sem.acquire().then(() => { order.push('second'); });
  await delay(5);
  order.push('first');
  sem.release();
  await p;
  assert.deepEqual(order, ['first', 'second']);
});

test('semaphore: available reflects permits', () => {
  const sem = new Semaphore(3);
  assert.equal(sem.available(), 3);
});

test('withPermit: releases even on error', async () => {
  const sem = new Semaphore(1);
  await assert.rejects(withPermit(sem, async () => { throw new Error('boom'); }));
  assert.equal(sem.available(), 1, 'permit returned');
});

test('withPermit: returns value', async () => {
  const sem = new Semaphore(1);
  const v = await withPermit(sem, async () => 42);
  assert.equal(v, 42);
});
