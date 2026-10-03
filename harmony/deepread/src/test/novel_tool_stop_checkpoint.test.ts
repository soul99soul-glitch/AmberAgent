import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelMessage } from '../main/ets/novel/models.ts';
import { makeAssistantMessage, makeUIMessage } from '../main/ets/agent/message.ts';
import type { NovelModelEvent, NovelModelRequest } from '../main/ets/novel/model_running.ts';
import type { NovelRunEvent } from '../main/ets/novel/creation.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';

for (const action of ['successful-stop', 'failed-stop-and-switch', 'failed-delete'] as const) {
  test(`AskUser continuation ${action} preserves the final checkpoint result`, async () => {
    const repository = createFileNovelRepository(createMemoryFileStore());
    let failSave = false;
    const baseCommit = repository.commitProject.bind(repository);
    repository.commitProject = async (...args) => {
      if (failSave) throw new Error('tool continuation checkpoint write failed');
      return baseCommit(...args);
    };
    let callback: (event: NovelModelEvent) => void = () => {};
    let request: NovelModelRequest | null = null;
    let messages: UIMessage[] = [];
    let startedResolve: () => void = () => {};
    const started = new Promise<void>(resolve => { startedResolve = resolve; });
    const creation = createNovelCreation({ repository, modelRunning: {
      async validate() {},
      start(value) {
        request = value;
        return { subscribe(listener) {
          callback = listener;
          messages = value.history.concat([makeAssistantMessage('未落盘的续接片段')]);
          listener({ kind: 'snapshot', messages, generationActive: true, textDeltasLive: true, transport: 'live' });
          startedResolve();
          return () => {};
        } };
      },
      cancel() {
        void request?.checkpoint(messages).then(() => callback({ kind: 'failed', message: 'cancelled by user' }),
          error => callback({ kind: 'failed', message: String(error) }));
      },
    } });
    const project = await creation.create('工具续接停止');
    const main = (await repository.workspaceStatus(project.id)).activeBranchId;
    await creation.createBranch(project.id, '另一条线');
    const beforeStop = (await repository.workspaceStatus(project.id)).activeBranchId;
    const pending = makeUIMessage('assistant', [{ type: 'tool', toolCallId: 'ask', toolName: 'ask_user',
      input: '{}', output: [], approvalState: { type: 'pending' }, metadata: null }]);
    await repository.updateProject(project.id, current => ({ ...current, messages: [makeNovelMessage({
      role: 'assistant', mode: 'discuss', uiMessage: pending, createdAt: 1,
    })] }));
    const events: NovelRunEvent[] = [];
    creation.continueTool(project.id, 'ask', { kind: 'answered', answer: '答案' }).subscribe(event => events.push(event));
    await started;
    failSave = action !== 'successful-stop';
    const mutation = async () => {
      if (action === 'failed-delete') await creation.delete(project.id);
      else { await creation.stopActiveRun(project.id); await creation.switchBranch(project.id, main); }
    };
    if (action === 'successful-stop') {
      await mutation();
      assert.equal((await repository.workspaceStatus(project.id)).activeBranchId, main);
      await creation.switchBranch(project.id, beforeStop);
      assert.ok((await repository.loadProject(project.id)).messages.some(message =>
        message.uiMessage.parts.some(part => part.type === 'text' && part.text === '未落盘的续接片段')));
    } else {
      await assert.rejects(mutation(), /停止后内容未保存.*tool continuation checkpoint write failed/);
      assert.equal((await repository.workspaceStatus(project.id)).activeBranchId, beforeStop);
      const failure = events.find(event => event.kind === 'failed');
      assert.equal(failure?.kind, 'failed');
      if (failure?.kind === 'failed') assert.ok(failure.unsavedMessages?.some(message =>
        message.parts.some(part => part.type === 'text' && part.text === '未落盘的续接片段')));
    }
  });
}
