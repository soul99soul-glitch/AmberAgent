import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { budgetStage, shouldHideToolsForBudget } from '../main/ets/agent/budget_prompt.ts';
import { classifyError, decideRetry, delayForAttempt, DEFAULT_RETRY_SETTING, RetrySetting } from '../main/ets/agent/retry_classifier.ts';

test('budgetStage for small loops (maxSteps=4)', () => {
  assert.equal(budgetStage(0, 4), null);     // remaining 4, no warning yet
  assert.equal(budgetStage(1, 4), 'WARN');   // remaining 3
  assert.equal(budgetStage(2, 4), 'TIGHT');  // remaining 2
  assert.equal(budgetStage(3, 4), 'FINAL');  // remaining 1
  assert.equal(budgetStage(4, 4), 'FINAL');  // remaining 0
});

test('shouldHideToolsForBudget true only at FINAL and no resumable', () => {
  assert.equal(shouldHideToolsForBudget(0, 32, false), false);
  assert.equal(shouldHideToolsForBudget(31, 32, false), true);   // FINAL + no resumable
  assert.equal(shouldHideToolsForBudget(31, 32, true), false);   // FINAL but has resumable
});

// ===== Retry Classifier =====

test('classifyError: AbortError = cancelled', () => {
  const e = new Error('aborted');
  e.name = 'AbortError';
  assert.equal(classifyError(e), 'cancelled');
});

test('classifyError: 429 = rate_limit', () => {
  assert.equal(classifyError({ status: 429, message: 'Too Many Requests' }), 'rate_limit');
});

test('classifyError: 500 = server', () => {
  assert.equal(classifyError({ status: 500, message: 'Internal Server Error' }), 'server');
});

test('classifyError: 401 = auth (permanent)', () => {
  assert.equal(classifyError({ status: 401, message: 'Unauthorized' }), 'auth');
});

test('classifyError: 400 = bad_request (permanent)', () => {
  assert.equal(classifyError({ status: 400, message: 'Bad Request' }), 'bad_request');
});

test('classifyError: context_length message = context (permanent)', () => {
  assert.equal(classifyError({ message: 'context_length exceeded' }), 'context');
});

test('classifyError: quota message = quota (permanent)', () => {
  assert.equal(classifyError({ message: 'insufficient_quota' }), 'quota');
});

test('classifyError: content_policy = safety (permanent)', () => {
  assert.equal(classifyError({ message: 'blocked by content_policy' }), 'safety');
});

test('classifyError: timeout message = timeout', () => {
  assert.equal(classifyError({ message: 'Request timed out' }), 'timeout');
});

test('classifyError: network error message = network', () => {
  assert.equal(classifyError({ message: 'connection reset by peer' }), 'network');
  assert.equal(classifyError({ message: 'ECONNRESET' }), 'network');
});

test('classifyError: unknown fallback', () => {
  assert.equal(classifyError({ message: 'something weird' }), 'unknown');
});

test('decideRetry: retryable error with attempt < maxRetries', () => {
  const d = decideRetry({ status: 429, message: 'rate limit' }, 0);
  assert.equal(d.retryable, true);
  assert.equal(d.category, 'rate_limit');
  assert.ok(d.delayMs > 0);
  assert.equal(d.attempt, 1);
});

test('decideRetry: permanent error not retryable', () => {
  const d = decideRetry({ status: 401, message: 'unauthorized' }, 0);
  assert.equal(d.retryable, false);
  assert.equal(d.delayMs, 0);
});

test('decideRetry: exhausted maxRetries', () => {
  const d = decideRetry({ status: 500, message: 'server error' }, 5);
  assert.equal(d.retryable, false);
});

test('decideRetry: disabled via setting', () => {
  const d = decideRetry({ status: 500, message: 'server error' }, 0, { ...DEFAULT_RETRY_SETTING, enabled: false });
  assert.equal(d.retryable, false);
});

test('delayForAttempt: exponential growth capped at maxDelayMs', () => {
  const s: RetrySetting = { enabled: true, maxRetries: 5, initialDelayMs: 1000, maxDelayMs: 16000, jitterRatio: 0 };
  // attempt 0: 1000 * 2^0 = 1000
  // attempt 1: 1000 * 2^1 = 2000
  // attempt 4: 1000 * 2^4 = 16000 (capped)
  assert.equal(delayForAttempt(0, s), 1000);
  assert.equal(delayForAttempt(1, s), 2000);
  assert.equal(delayForAttempt(4, s), 16000);
});

test('delayForAttempt: jitter within ratio', () => {
  const s: RetrySetting = { enabled: true, maxRetries: 5, initialDelayMs: 1000, maxDelayMs: 16000, jitterRatio: 0.5 };
  for (let i = 0; i < 20; i++) {
    const d = delayForAttempt(2, s);  // base 4000, jitter ±2000
    assert.ok(d >= 2000 && d <= 6000, `delay ${d} outside jitter range`);
  }
});
