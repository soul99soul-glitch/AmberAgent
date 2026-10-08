import test from 'node:test';
import assert from 'node:assert/strict';
import { jevUnverifiedChanges, jevCompletionNeedsVerification, jevCompletionBindingMatches, jevVerificationComposerAction, JEV_VERIFICATION_PROMPT } from '../main/ets/chat/jev_completion.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
const tool = (name: string, input: object, output: object): UIMessagePartTool => ({
  type: 'tool', toolCallId: 'tool', toolName: name, input: JSON.stringify(input),
  output: [{ type: 'text', text: JSON.stringify(output), metadata: null }], approvalState: { type: 'approved' }, metadata: null,
});
const write = (path = 'code/app.ts') => tool('file_write', { path, content: 'x' }, { path });
const check = (command = 'npm test', status = 'completed', running = false, exit_code = 0) =>
  tool('terminal_execute', { command }, { status, running, exit_code });
const turn = (...parts: UIMessagePartTool[]) => [makeUIMessage('user', [{ type: 'text', text: '修复这个问题', metadata: null }], { id: 'user' }),
  makeUIMessage('assistant', [...parts, { type: 'text', text: '已完成。', metadata: null }], { id: 'final' })];

test('only actual non-document changes after the last user need a completion check', () => {
  assert.equal(jevUnverifiedChanges(turn(write()))?.finalMessageId, 'final');
  assert.equal(jevUnverifiedChanges(turn(write('notes.md'))), null);
  assert.equal(jevUnverifiedChanges(turn(tool('file_edit', { old_text: 'same', new_text: 'same' }, { path: 'x.ts', replace_count: 1 }))), null);
  assert.equal(jevUnverifiedChanges(turn(tool('file_edit', { old_text: 'old', new_text: 'new' }, { path: 'x.ts', replace_count: 0 }))), null);
  assert.ok(jevUnverifiedChanges(turn(tool('file_edit', { old_text: 'old', new_text: 'new' }, { path: 'x.ts', replace_count: 1 }))));
  assert.equal(jevUnverifiedChanges(turn(tool('file_write', {}, { path: 'x.ts', status: 'failed', message: 'failure' }))), null);
  assert.equal(jevUnverifiedChanges([...turn(write()), makeUIMessage('user', [{ type: 'text', text: '新问题', metadata: null }])]), null);
});

test('only a completed successful real check after the last change clears the notice facts', () => {
  assert.equal(jevUnverifiedChanges(turn(write(), check())), null);
  assert.ok(jevUnverifiedChanges(turn(check(), write())));
  assert.ok(jevUnverifiedChanges(turn(write(), check('npm test', 'running', true))));
  assert.ok(jevUnverifiedChanges(turn(write(), check('npm test', 'completed', false, 1))));
  assert.ok(jevUnverifiedChanges(turn(write(), tool('terminal_job_start', { command: 'npm test' }, { status: 'completed', running: false, exit_code: 0 }))));
  assert.ok(jevUnverifiedChanges(turn(write(), check('echo "test passed"'))));
  assert.ok(jevUnverifiedChanges(turn(write(), check('printf \'test\''))));
  assert.equal(jevUnverifiedChanges(turn(write(), check('echo "test"; npx vitest run'))), null);
  assert.ok(jevUnverifiedChanges(turn(write(), check('true || npm test'))));
  assert.ok(jevUnverifiedChanges(turn(write(), check('npm test || true'))));
  assert.ok(jevUnverifiedChanges(turn(write(), check('npm test; true'))));
  assert.equal(jevUnverifiedChanges(turn(write(), check('npm test && echo done'))), null);
  assert.equal(jevUnverifiedChanges(turn(write(), check('node scripts/harmony-inventory/build-harmony-app.mjs'))), null);
});

test('claims threshold and final message/content binding prevent obsolete notices', () => {
  const facts = jevUnverifiedChanges(turn(write()))!;
  const evaluation = { answers: { claims_done: { kind: 'noul' as const, probability: 0.8 }, claims_verified: { kind: 'noul' as const, probability: 0.1 } }, usage: null, model: 'm' };
  assert.equal(jevCompletionNeedsVerification(evaluation), true);
  assert.equal(jevCompletionNeedsVerification({ ...evaluation, answers: { ...evaluation.answers, claims_done: { kind: 'noul', probability: 0.79 } } }), false);
  assert.equal(jevCompletionBindingMatches(facts, turn(write())), true);
  const changed = turn(write()); changed[1].parts.push({ type: 'text', text: ' 后续修改', metadata: null });
  assert.equal(jevCompletionBindingMatches(facts, changed), false);
});

test('verification composer sends only from an empty attachment-free non-edit composer', () => {
  assert.deepEqual(jevVerificationComposerAction('', false, false), { text: JEV_VERIFICATION_PROMPT, send: true });
  assert.deepEqual(jevVerificationComposerAction('draft', false, false), { text: `draft\n\n${JEV_VERIFICATION_PROMPT}`, send: false });
  assert.equal(jevVerificationComposerAction('', true, false).send, false);
  assert.equal(jevVerificationComposerAction('', false, true).send, false);
});

test('completion sends final reply with task consent and omits optional file metadata independently', async () => {
  const { makeJevSettings } = await import('../main/ets/chat/jev_models.ts');
  const { buildJevCompletionBatch } = await import('../main/ets/chat/jev_completion.ts');
  const facts = jevUnverifiedChanges(turn(write('private/file.ts')))!;
  const taskOnly = makeJevSettings({ completionCheck: { mode: 'active', allowTaskText: true } });
  const batch = buildJevCompletionBatch(taskOnly, facts, 'user task')!;
  assert.deepEqual(batch.state, { task: 'user task', finalReply: '已完成。' });
  assert.equal(JSON.stringify(batch).includes('private/file.ts'), false);
  assert.equal(buildJevCompletionBatch(makeJevSettings({ completionCheck: { mode: 'active', allowToolMetadata: true } }), facts, 'secret task'), null);
});
