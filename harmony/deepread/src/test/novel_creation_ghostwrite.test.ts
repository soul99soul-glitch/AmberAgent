import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type {
  NovelModelEvent, NovelModelRequest, NovelModelRunning, NovelModelStream,
} from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';

const waitFor = async (predicate: () => Promise<boolean>): Promise<void> => {
  for (let i: number = 0; i < 100; i++) {
    if (await predicate()) return;
    await new Promise<void>((resolve: () => void): void => { setTimeout(resolve, 5); });
  }
  throw new Error('ghostwrite did not reach terminal state');
};

const hangingModel = (): {
  model: NovelModelRunning; requests: NovelModelRequest[]; cancelled: string[];
} => {
  const requests: NovelModelRequest[] = [];
  const cancelled: string[] = [];
  const subscribers: Map<string, (event: NovelModelEvent) => void> = new Map();
  return {
    requests,
    cancelled,
    model: {
      validate(): Promise<void> { return Promise.resolve(); },
      start(request: NovelModelRequest): NovelModelStream {
        requests.push(request);
        return {
          subscribe(callback: (event: NovelModelEvent) => void): () => void {
            subscribers.set(request.runId, callback);
            return (): void => { subscribers.delete(request.runId); };
          },
        };
      },
      cancel(runId: string): void {
        cancelled.push(runId);
        subscribers.get(runId)?.({ kind: 'failed', message: 'cancelled by test' });
      },
    },
  };
};

test('NovelCreation 代笔门面完成写作→审稿→原子收录并清空末章计划', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const requests: NovelModelRequest[] = [];
  const model: NovelModelRunning = {
    validate(): Promise<void> { return Promise.resolve(); },
    start(request: NovelModelRequest): NovelModelStream {
      requests.push(request);
      return {
        subscribe(callback: (event: NovelModelEvent) => void): () => void {
          const timer = setTimeout((): void => {
            let response: string;
            if (request.systemPrompt.includes('小说正文写作者')) {
              response = JSON.stringify({ title: '第一章 风起', content: '山雨欲来。' });
            } else {
              const input = JSON.parse(
                request.operation.kind === 'turn' ? request.operation.userPrompt : '{}') as {
                candidateId: string; candidateDigest: string; planId: string; planDigest: string;
              };
              response = JSON.stringify({
                candidateId: input.candidateId,
                candidateDigest: input.candidateDigest,
                planId: input.planId,
                planDigest: input.planDigest,
                findings: [],
                blocking: false,
                rewriteRequired: false,
                rewriteInstructions: '',
                nextPlan: null,
                stateDelta: { plotState: '主角踏上旅途', chapterHighlight: '风雨前启程' },
              });
            }
            callback({
              kind: 'snapshot', messages: [makeAssistantMessage(response)], generationActive: false,
              textDeltasLive: false, transport: 'unavailable',
            });
            callback({ kind: 'completed' });
          }, 0);
          return (): void => { clearTimeout(timer); };
        },
      };
    },
    cancel(): void {},
  };
  let now: number = 1_700_000_000_000;
  const creation = createNovelCreation({
    repository,
    modelRunning: model,
    nowMs: (): number => { now += 1; return now; },
  });
  const project = await creation.create('代笔闭环');
  await creation.setBranchSettings(project.id, {
    ...project.branchSettings,
    thisChapterPlan: '第一章：主角在山雨将至时离开故乡。',
  });

  const started = await creation.startGhostwrite(project.id, 1);
  assert.equal(started.targetChapterCount, 1);
  await waitFor(async (): Promise<boolean> => {
    const jobs = await repository.listGhostwriteJobs(project.id);
    return jobs.some(job => job.jobId === started.jobId && job.stage === 'completed');
  });

  const completed = (await repository.listGhostwriteJobs(project.id))
    .find(job => job.jobId === started.jobId);
  assert.equal(completed?.stage, 'completed');
  assert.equal(completed?.progress.length, 1);
  assert.equal(await creation.ghostwriteJob(project.id), null);
  const reopened = await creation.open(project.id);
  assert.equal(reopened.chapters.length, 1);
  assert.equal(reopened.chapters[0].title, '第一章 风起');
  assert.equal(reopened.chapters[0].content, '山雨欲来。');
  assert.equal(reopened.branchSettings.thisChapterPlan, '');
  const plotFile = (await repository.workspaceFiles(project.id))
    .find(file => file.path.endsWith('/plan/plot.md'));
  assert.equal(new TextDecoder().decode(plotFile?.bytes).includes('主角踏上旅途'), false,
    '模型审稿摘要不能覆盖作者维护的剧情资料');
  assert.equal(reopened.chapterPlots.length, 1);
  assert.equal(reopened.chapterPlots[0].chapterId, reopened.chapters[0].id);
  assert.ok(reopened.chapterPlots[0].text.includes('山雨欲来。'));
  assert.equal(reopened.chapterPlots[0].stale, false);
  assert.equal(requests.length, 2);
  assert.equal(requests.every(request => request.toolProfile === 'read_only'), true);
});

for (const action of ['pause', 'cancel'] as const) {
  test(`NovelCreation ${action} 先持久化终态并中断当前 Chat run`, async () => {
    const repository = createFileNovelRepository(createMemoryFileStore());
    const hanging = hangingModel();
    let now: number = 1_700_000_100_000;
    const creation = createNovelCreation({
      repository,
      modelRunning: hanging.model,
      nowMs: (): number => { now += 1; return now; },
    });
    const project = await creation.create(`代笔${action}`);
    await creation.setBranchSettings(project.id, {
      ...project.branchSettings,
      thisChapterPlan: '生成一章并等待取消。',
    });
    const started = await creation.startGhostwrite(project.id, 1);
    await waitFor(async (): Promise<boolean> => hanging.requests.length === 1);

    const terminal = action === 'pause'
      ? await creation.pauseGhostwrite(project.id, started.jobId)
      : await creation.cancelGhostwrite(project.id, started.jobId);
    assert.equal(terminal.stage, action === 'pause' ? 'paused' : 'cancelled');
    assert.deepEqual(hanging.cancelled, [hanging.requests[0].runId]);
    await waitFor(async (): Promise<boolean> => {
      const durable = await repository.loadGhostwriteJob(project.id, started.jobId);
      return durable.stage === terminal.stage;
    });
    assert.equal((await repository.loadProject(project.id)).chapters.length, 0);
  });
}
