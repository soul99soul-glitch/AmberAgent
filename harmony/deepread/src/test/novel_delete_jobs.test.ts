import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { createProject, saveChapter } from '../main/ets/novel/mutations.ts';
import type { NovelModelEvent, NovelModelRequest } from '../main/ets/novel/model_running.ts';

for (const kind of ['ghostwrite', 'polish'] as const) {
  test(`delete cancels only the target project's held ${kind}, waits for cleanup, and restoration keeps it terminal`, async () => {
    const files = createMemoryFileStore();
    const repository = createFileNovelRepository(files);
    const requests: NovelModelRequest[] = [];
    const cancelled: string[] = [];
    const callbacks = new Map<string, (event: NovelModelEvent) => void>();
    const started = new Map<string, () => void>();
    const model = {
      async validate() {},
      start(request: NovelModelRequest) {
        requests.push(request);
        return { subscribe(callback: (event: NovelModelEvent) => void) {
          callbacks.set(request.runId, callback);
          started.get(request.projectId)?.();
          return () => callbacks.delete(request.runId);
        } };
      },
      cancel(runId: string) {
        cancelled.push(runId);
        setTimeout(() => callbacks.get(runId)?.({ kind: 'failed', message: 'cancelled test provider' }), 0);
      },
    };
    const creation = createNovelCreation({ repository, modelRunning: model });
    const fixture = (id: string) => {
      const project = createProject(id, Date.now());
      project.id = id;
      project.branchSettings.thisChapterPlan = '续写新章';
      return kind === 'polish' ? saveChapter(project, null, '第一章', '作者正文', Date.now()).project : project;
    };
    const target = await repository.createProject(fixture(`delete-${kind}`));
    const other = await repository.createProject(fixture(`keep-${kind}`));
    const waitTarget = new Promise<void>(resolve => started.set(target.id, resolve));
    const waitOther = new Promise<void>(resolve => started.set(other.id, resolve));
    const context = { includePlot: false, includeForeshadows: false, includeCharacters: false, includeDecisions: false };
    const targetJob = kind === 'ghostwrite' ? await creation.startGhostwrite(target.id, 1)
      : await creation.startPolish(target.id, 1, 1, context);
    const otherJob = kind === 'ghostwrite' ? await creation.startGhostwrite(other.id, 1)
      : await creation.startPolish(other.id, 1, 1, context);
    await Promise.all([waitTarget, waitOther]);
    try {
      await creation.delete(target.id);
      const targetRequest = requests.find(request => request.projectId === target.id);
      assert.deepEqual(cancelled, [targetRequest?.runId]);
      assert.equal(callbacks.has(targetRequest!.runId), false, 'delete waited for provider subscription cleanup');
      assert.equal((kind === 'ghostwrite' ? await repository.loadGhostwriteJob(other.id, otherJob.jobId)
        : await repository.loadPolishJob(other.id, otherJob.jobId)).stage, 'writing');
      const privatePath = '.amber/jobs.json';
      const deletedJobs = await files.readText(`amberagent/novel-workspace/.deleted/${target.id}/${privatePath}`);
      assert.match(deletedJobs ?? '', /"stage":"cancelled"/);
      const restored = await creation.restorePreviousProject();
      assert.equal(restored?.id, target.id);
      assert.equal((kind === 'ghostwrite' ? await repository.loadGhostwriteJob(target.id, targetJob.jobId)
        : await repository.loadPolishJob(target.id, targetJob.jobId)).stage, 'cancelled');
      const requestCount = requests.length;
      await creation.open(target.id);
      await new Promise<void>(resolve => setImmediate(resolve));
      assert.equal(requests.length, requestCount, 'restoring and opening does not restart cancelled work');
    } finally {
      // Release every held fake provider even when a red assertion fails.
      for (const [runId, callback] of callbacks) {
        callback({ kind: 'failed', message: 'test cleanup' });
        callbacks.delete(runId);
      }
    }
  });
}
