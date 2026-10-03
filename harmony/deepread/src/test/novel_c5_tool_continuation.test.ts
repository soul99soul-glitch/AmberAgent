import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import type { NovelRun, NovelRunEvent } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type {
  NovelModelEvent, NovelModelRequest, NovelModelRunning,
} from '../main/ets/novel/model_running.ts';
import {
  makeAssistantMessage, makeUIMessage, makeUserMessage,
} from '../main/ets/agent/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from '../main/ets/agent/message.ts';

const terminal = (run: NovelRun): Promise<NovelRunEvent> => new Promise(resolve => {
  run.subscribe((event: NovelRunEvent): void => {
    if (event.kind === 'completed' || event.kind === 'waiting_user' ||
      event.kind === 'interrupted' || event.kind === 'failed') resolve(event);
  });
});

const replaceTool = (
  messages: UIMessage[], toolCallId: string, verdict: 'approved' | 'denied' | 'answered',
): UIMessage[] => messages.map((message: UIMessage): UIMessage => ({
  ...message,
  parts: message.parts.map((part: UIMessagePart): UIMessagePart => {
    if (part.type !== 'tool' || part.toolCallId !== toolCallId) return part;
    if (verdict === 'approved') {
      return {
        ...part,
        approvalState: { type: 'approved' },
        output: [{ type: 'text', text: '{"status":"accepted"}', metadata: null }],
      };
    }
    if (verdict === 'answered') {
      return {
        ...part,
        approvalState: { type: 'answered', answer: 'answer' },
        output: [{ type: 'text', text: 'answer', metadata: null }],
      };
    }
    return {
      ...part,
      approvalState: { type: 'denied', reason: 'denied' },
      output: [{ type: 'text', text: '{"status":"denied"}', metadata: null }],
    };
  }),
}));

const toolModel = (
  chapterPath: () => string,
  executeApproved: () => Promise<void>,
): NovelModelRunning => ({
  async validate(): Promise<void> {},
  start(request: NovelModelRequest) {
    const subscribers = new Set<(event: NovelModelEvent) => void>();
    setTimeout((): void => {
      void (async (): Promise<void> => {
        if (request.operation.kind === 'turn') {
          const user: UIMessage = makeUserMessage(request.operation.userPrompt);
          const assistant: UIMessage = makeUIMessage('assistant', [{
            type: 'tool',
            toolCallId: 'write-call-1',
            toolName: 'novel_workspace_write',
            input: JSON.stringify({
              proposal_id: 'proposal-1',
              patches: [{ operation: 'write', path: chapterPath(), content: '新正文' }],
            }),
            output: [],
            approvalState: { type: 'pending' },
            metadata: null,
          }]);
          const messages: UIMessage[] = request.history.concat([user, assistant]);
          await request.checkpoint(messages);
          subscribers.forEach(callback => callback({
            kind: 'snapshot', messages, generationActive: false,
            textDeltasLive: false, transport: 'unavailable',
          }));
          subscribers.forEach(callback => callback({ kind: 'waiting_user' }));
          return;
        }
        const continuation = request.operation;
        if (continuation.kind !== 'tool_continuation') throw new Error('Expected tool continuation');
        const verdict = continuation.verdict.kind;
        let updated: UIMessage[] = replaceTool(
          request.history, continuation.toolCallId, verdict);
        if (verdict === 'approved') {
          // 真实 adapter 先 checkpoint approved/no-output，再由 dispatcher 调 tool port。
          updated = updated.map((message: UIMessage): UIMessage => ({
            ...message,
            parts: message.parts.map((part: UIMessagePart): UIMessagePart =>
              part.type === 'tool' && part.toolCallId === continuation.toolCallId
                ? { ...part, output: [] } : part),
          }));
          await request.checkpoint(updated);
          await executeApproved();
          updated = replaceTool(request.history, continuation.toolCallId, verdict);
        }
        const messages: UIMessage[] = updated.concat([makeAssistantMessage('已处理写入决定。')]);
        await request.checkpoint(messages);
        subscribers.forEach(callback => callback({
          kind: 'snapshot', messages, generationActive: false,
          textDeltasLive: false, transport: 'unavailable',
        }));
        subscribers.forEach(callback => callback({ kind: 'completed' }));
      })();
    }, 0);
    return {
      subscribe(callback: (event: NovelModelEvent) => void): () => void {
        subscribers.add(callback);
        return (): void => { subscribers.delete(callback); };
      },
    };
  },
  cancel(): void {},
});

test('write tool approval resolves the C4 proposal before the same toolCallId continues', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  let path: string = '';
  let creation = createNovelCreation({
    repository,
    modelRunning: toolModel(
      (): string => path,
      async (): Promise<void> => {
        await creation.workspaceWriteResult(project.id, 'proposal-1', [{
          operation: 'write', path, content: '新正文',
        }]);
      }),
    nowMs: (): number => 1_000,
  });
  const project = await creation.create('小说');
  await creation.saveChapter(project.id, null, '第一章', '旧正文');
  const files = await repository.workspaceFiles(project.id);
  path = files.find(file => /\/chapters\/001-.+\.md$/.test(file.path))?.path ?? '';
  assert.notEqual(path, '');

  const waiting = await terminal(creation.generate(project.id, '改写第一章', 'discuss'));
  assert.equal(waiting.kind, 'waiting_user');
  const pending = await creation.open(project.id);
  const tool = pending.messages.flatMap(message => message.uiMessage.parts)
    .find(part => part.type === 'tool');
  assert.equal(tool?.type === 'tool' ? tool.toolCallId : '', 'write-call-1');

  const completed = await terminal(creation.continueTool(
    project.id, 'write-call-1', { kind: 'approved' }));
  assert.equal(completed.kind, 'completed');
  const read = await creation.workspaceRead(project.id, path);
  assert.equal(read.content, '新正文');
  const proposals = await creation.workspaceProposals(project.id);
  assert.equal(proposals[0].status, 'accepted');

  // 同一动作重试复用 deterministic command receipt，不会再次覆盖或生成第二个 proposal。
  await terminal(creation.continueTool(project.id, 'write-call-1', { kind: 'approved' }));
  assert.equal((await creation.workspaceProposals(project.id)).length, 1);
  assert.equal((await creation.workspaceRead(project.id, path)).content, '新正文');
});

test('chapter plan write follows the existing tool approval and branch path normalization chain', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  const creation = createNovelCreation({
    repository,
    modelRunning: toolModel(
      (): string => 'plan/this-chapter.md',
      async (): Promise<void> => {
        await creation.workspaceWriteResult(project.id, 'proposal-1', [{
          operation: 'write', path: 'plan/this-chapter.md', content: '新正文',
        }]);
      }),
    nowMs: (): number => 1_000,
  });
  const project = await creation.create('计划提案');
  assert.equal((await terminal(creation.generate(project.id, '调整本章目标', 'discuss'))).kind, 'waiting_user');
  assert.equal((await creation.workspaceRead(project.id, 'plan/this-chapter.md')).content, '');
  assert.equal((await terminal(creation.continueTool(
    project.id, 'write-call-1', { kind: 'approved' }))).kind, 'completed');
  const reloaded = createFileNovelRepository(store);
  assert.equal((await reloaded.loadProject(project.id)).branchSettings.thisChapterPlan, '新正文');
  const proposal = (await reloaded.workspaceProposals(project.id))[0];
  assert.equal(proposal.status, 'accepted');
  assert.equal(proposal.patches[0].path, 'branches/main/plan/this-chapter.md');
});

test('write execution boundary rejects schema-invalid patch instead of coercing it to an empty write', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({
    repository,
    modelRunning: toolModel((): string => '', async (): Promise<void> => {}),
    nowMs: (): number => 2_000,
  });
  const project = await creation.create('小说');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '不可清空');
  const files = await repository.workspaceFiles(project.id);
  const path: string = files.find(file => /\/chapters\/001-.+\.md$/.test(file.path))?.path ?? '';
  const invalidTool: UIMessagePartTool = {
    type: 'tool',
    toolCallId: 'invalid-write',
    toolName: 'novel_workspace_write',
    input: JSON.stringify({
      proposal_id: 'invalid-proposal',
      patches: [{ operation: 'replace', path, content: null }],
    }),
    output: [],
    approvalState: { type: 'pending' },
    metadata: null,
  };
  await assert.rejects(
    creation['prepareWorkspaceWriteTool'](
      project.id, invalidTool, { kind: 'approved' }),
    /operation 必须是 write 或 delete/,
  );
  assert.equal((await creation.workspaceProposals(project.id)).length, 0);
  assert.equal((await creation.workspaceRead(project.id, path)).content, chapter.content);
});

test('open reconciles a durable denied tool output when no C4 proposal was created before exit', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  let path: string = '';
  const creation = createNovelCreation({
    repository,
    modelRunning: toolModel((): string => path, async (): Promise<void> => {}),
    nowMs: (): number => 3_000,
  });
  const project = await creation.create('小说');
  await creation.saveChapter(project.id, null, '第一章', '旧正文');
  const files = await repository.workspaceFiles(project.id);
  path = files.find(file => /\/chapters\/001-.+\.md$/.test(file.path))?.path ?? '';
  assert.notEqual(path, '');

  const waiting = await terminal(creation.generate(project.id, '提议改写', 'discuss'));
  assert.equal(waiting.kind, 'waiting_user');
  await repository.updateProject(project.id, current => ({
    ...current,
    messages: current.messages.map(message => ({
      ...message,
      uiMessage: replaceTool([message.uiMessage], 'write-call-1', 'denied')[0],
    })),
  }));
  assert.equal((await creation.workspaceProposals(project.id)).length, 0);

  const reopened = createNovelCreation({
    repository,
    modelRunning: toolModel((): string => path, async (): Promise<void> => {}),
    nowMs: (): number => 3_001,
  });
  await reopened.open(project.id);
  const proposals = await reopened.workspaceProposals(project.id);
  assert.equal(proposals.length, 1);
  assert.equal(proposals[0].status, 'rejected');
  assert.equal((await reopened.workspaceRead(project.id, path)).content, '旧正文');
});
