import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  createNovelCreation,
  type NovelPolishEffects,
} from '../main/ets/novel/creation.ts';
import {
  createFileNovelRepository,
  type NovelProjectRepository,
} from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import {
  makeNovelChapter,
  makeNovelProject,
  type NovelModelPolicy,
  type NovelModelTarget,
  type NovelProject,
} from '../main/ets/novel/models.ts';
import type {
  NovelModelEvent,
  NovelModelRequest,
  NovelModelRunning,
  NovelModelStream,
} from '../main/ets/novel/model_running.ts';
import type {
  DurablePolishJob,
  PolishContextOptions,
  PolishStage,
} from '../main/ets/novel/polish.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { defaultNovelModelDefaults } from '../main/ets/novel/standalone_defaults.ts';

const NOW: number = 1_700_000_000_000;
const WRITER: NovelModelTarget = {
  kind: 'fixed', providerId: 'writer-provider', modelId: 'writer-model',
};
const REVIEWER: NovelModelTarget = {
  kind: 'fixed', providerId: 'review-provider', modelId: 'review-model',
};
const POLICY: NovelModelPolicy = { writing: WRITER, review: REVIEWER, stateSync: null };
const CONTEXT: PolishContextOptions = {
  includePlot: true,
  includeForeshadows: true,
  includeCharacters: true,
  includeDecisions: true,
};

type ModelResponse = string | 'pending';
type ModelResponder = (request: NovelModelRequest, index: number) => ModelResponse;
type ValidateHook = (target: NovelModelTarget, projectId: string) => Promise<void>;

class FakeModel implements NovelModelRunning {
  readonly requests: NovelModelRequest[] = [];
  readonly validated: Array<{ target: NovelModelTarget; projectId: string }> = [];
  readonly cancelled: string[] = [];
  onCancel: ((runId: string) => void) | null = null;
  private readonly responder: ModelResponder;
  private readonly validateHook: ValidateHook;
  private readonly callbacks: Map<string, (event: NovelModelEvent) => void> = new Map();

  constructor(
    responder: ModelResponder = (): 'pending' => 'pending',
    validateHook: ValidateHook = async (): Promise<void> => {},
  ) {
    this.responder = responder;
    this.validateHook = validateHook;
  }

  validate(target: NovelModelTarget, projectId: string): Promise<void> {
    this.validated.push({ target, projectId });
    return this.validateHook(target, projectId);
  }

  start(request: NovelModelRequest): NovelModelStream {
    const index: number = this.requests.length;
    this.requests.push(request);
    return {
      subscribe: (callback: (event: NovelModelEvent) => void): (() => void) => {
        this.callbacks.set(request.runId, callback);
        const response: ModelResponse = this.responder(request, index);
        if (response !== 'pending') {
          queueMicrotask((): void => { this.complete(index, response); });
        }
        return (): void => { this.callbacks.delete(request.runId); };
      },
    };
  }

  cancel(runId: string): void {
    this.cancelled.push(runId);
    if (this.onCancel !== null) this.onCancel(runId);
    this.callbacks.get(runId)?.({ kind: 'failed', message: 'cancelled by test' });
  }

  complete(index: number, text: string): void {
    const request: NovelModelRequest | undefined = this.requests[index];
    if (request === undefined) throw new Error(`missing model request ${index}`);
    const callback = this.callbacks.get(request.runId);
    if (callback === undefined) throw new Error(`model request ${index} has no subscriber`);
    callback({
      kind: 'snapshot', messages: [makeAssistantMessage(text)], generationActive: false,
      textDeltasLive: false, transport: 'unavailable',
    });
    callback({ kind: 'completed' });
  }

  hasSubscriber(index: number): boolean {
    const request: NovelModelRequest | undefined = this.requests[index];
    return request !== undefined && this.callbacks.has(request.runId);
  }
}

class FakeEffects implements NovelPolishEffects {
  readonly events: string[] = [];

  schedule(job: DurablePolishJob): Promise<void> {
    this.events.push(`schedule:${job.stage}:${job.cursor}`);
    return Promise.resolve();
  }

  cancel(job: DurablePolishJob): Promise<void> {
    this.events.push(`cancel:${job.stage}:${job.cursor}`);
    return Promise.resolve();
  }

  notify(job: DurablePolishJob): Promise<void> {
    this.events.push(`notify:${job.stage}:${job.cursor}`);
    return Promise.resolve();
  }
}

const projectFixture = (
  id: string, chapterCount: number, modelPolicy: NovelModelPolicy = POLICY,
): NovelProject => {
  const project: NovelProject = makeNovelProject({ id, name: id, now: NOW, modelPolicy });
  return {
    ...project,
    chapters: Array.from({ length: chapterCount }, (_unused: unknown, index: number) =>
      makeNovelChapter({
        id: `chapter-${index + 1}`,
        title: `第 ${index + 1} 章`,
        content: `第 ${index + 1} 章旧正文。`,
        now: NOW,
      })),
  };
};

const makeNow = (): (() => number) => {
  let now: number = NOW + 100;
  return (): number => { now += 1; return now; };
};

const eventually = async <T>(
  read: () => Promise<T> | T,
  accept: (value: T) => boolean,
  message: string,
): Promise<T> => {
  for (let attempt: number = 0; attempt < 200; attempt++) {
    const value: T = await read();
    if (accept(value)) return value;
    await new Promise<void>((resolve: () => void): void => { setImmediate(resolve); });
  }
  throw new Error(message);
};

const waitForRequest = (model: FakeModel, count: number): Promise<number> =>
  eventually(
    (): number => model.requests.length,
    (length: number): boolean => length >= count && model.hasSubscriber(count - 1),
    `model did not receive request ${count}`,
  );

const writerResponse = (request: NovelModelRequest): string => {
  if (request.operation.kind !== 'turn') throw new Error('writer request must be a turn');
  const frozen = JSON.parse(request.operation.userPrompt.split('\n')[0]) as {
    jobId: string;
    chapter: { ordinal: number };
  };
  return JSON.stringify({
    jobId: frozen.jobId,
    content: `第 ${frozen.chapter.ordinal} 章润色正文。`,
  });
};

const reviewResponse = (request: NovelModelRequest): string => {
  if (request.operation.kind !== 'turn') throw new Error('review request must be a turn');
  const lines: string[] = request.operation.userPrompt.split('\n');
  const frozen = JSON.parse(lines[0]) as {
    jobId: string;
    chapter: { id: string; ordinal: number; sourceDigest: string };
  };
  const candidate = JSON.parse(lines.find((line: string): boolean => line.startsWith('candidate:'))
    ?.slice('candidate:'.length) ?? '{}') as { candidateId: string; candidateDigest: string };
  return JSON.stringify({
    jobId: frozen.jobId,
    chapterId: frozen.chapter.id,
    chapterOrdinal: frozen.chapter.ordinal,
    sourceDigest: frozen.chapter.sourceDigest,
    candidateId: candidate.candidateId,
    candidateDigest: candidate.candidateDigest,
    findings: [],
    blocking: false,
    rewriteRequired: false,
    rewriteInstructions: '',
  });
};

const automaticResponder = (request: NovelModelRequest): string =>
  request.systemPrompt.includes('章节润色者') ? writerResponse(request) : reviewResponse(request);

const startDirectJob = async (
  repository: NovelProjectRepository, projectId: string, chapterCount: number,
): Promise<DurablePolishJob> => {
  await repository.createProject(projectFixture(projectId, chapterCount));
  const status = await repository.workspaceStatus(projectId);
  return await repository.startPolishJob(
    projectId, status.cas, `${projectId}-job`, 1, chapterCount, CONTEXT, NOW + 1);
};

const claimRef = (job: DurablePolishJob): { token: string; epoch: number } => {
  if (job.claim === null) throw new Error('test job has no claim');
  return { token: job.claim.token, epoch: job.claim.epoch };
};

test('startPolish validates every distinct model before persistence', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const policy: NovelModelPolicy = {
    writing: WRITER,
    review: REVIEWER,
    stateSync: { kind: 'fixed', providerId: 'state-provider', modelId: 'state-model' },
  };
  await repository.createProject(projectFixture('start-validation', 1, policy));
  const effects = new FakeEffects();
  const model = new FakeModel(
    (): 'pending' => 'pending',
    async (target: NovelModelTarget): Promise<void> => {
      if (target.kind === 'fixed' && target.providerId === 'state-provider') {
        throw new Error('state model unavailable');
      }
    },
  );
  const creation = createNovelCreation({
    repository, modelRunning: model, polishEffects: effects, nowMs: makeNow(),
  });

  await assert.rejects(
    creation.startPolish('start-validation', 1, 1, CONTEXT),
    /state model unavailable/,
  );
  assert.deepEqual(model.validated.map(item => item.target), [
    policy.writing, policy.review, policy.stateSync,
  ]);
  assert.deepEqual(await repository.listPolishJobs('start-validation'), []);
  assert.deepEqual(effects.events, []);
});

test('startPolish persists queued work then notifies and schedules it', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('start-effects', 1));
  const model = new FakeModel();
  const effects = new FakeEffects();
  const creation = createNovelCreation({
    repository, modelRunning: model, polishEffects: effects, nowMs: makeNow(),
  });

  const started: DurablePolishJob = await creation.startPolish('start-effects', 1, 1, CONTEXT);
  assert.equal((await repository.loadPolishJob('start-effects', started.jobId)).jobId, started.jobId);
  assert.deepEqual(effects.events, ['notify:queued:0', 'schedule:queued:0']);
  assert.deepEqual(model.validated.slice(0, 2).map(item => item.target), [WRITER, REVIEWER]);

  await waitForRequest(model, 1);
  await creation.cancelPolish('start-effects', started.jobId);
});

test('NovelCreation checkpoints writer output before review then atomically collects the approved chapter', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('durable-roundtrip', 1));
  const model = new FakeModel();
  const creation = createNovelCreation({ repository, modelRunning: model, nowMs: makeNow() });

  const started: DurablePolishJob = await creation.startPolish('durable-roundtrip', 1, 1, CONTEXT);
  await waitForRequest(model, 1);
  assert.equal(model.requests[0].toolProfile, 'read_only');
  assert.deepEqual(model.requests[0].modelTarget, WRITER);
  model.complete(0, writerResponse(model.requests[0]));

  await waitForRequest(model, 2);
  const reviewing: DurablePolishJob = await repository.loadPolishJob(
    'durable-roundtrip', started.jobId);
  assert.equal(reviewing.stage, 'reviewing');
  assert.equal(reviewing.candidate?.content, '第 1 章润色正文。');
  assert.equal(model.requests[1].toolProfile, 'read_only');
  assert.deepEqual(model.requests[1].modelTarget, REVIEWER);
  model.complete(1, reviewResponse(model.requests[1]));

  const completed: DurablePolishJob = await eventually(
    (): Promise<DurablePolishJob> => repository.loadPolishJob('durable-roundtrip', started.jobId),
    (job: DurablePolishJob): boolean => job.stage === 'completed',
    'polish job did not complete',
  );
  const project: NovelProject = await repository.loadProject('durable-roundtrip');
  assert.equal(completed.progress.length, 1);
  assert.equal(project.chapters[0].content, '第 1 章润色正文。');
  assert.equal(project.chapterVersions.length, 1);
  assert.equal(project.chapterVersions[0].kind, 'polish');
  assert.equal(project.chapterVersions[0].content, '第 1 章旧正文。');
  assert.equal(model.requests.length, 2);
});

test('pausePolish persists paused state before cancelling the live model request', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('pause-order', 1));
  const model = new FakeModel();
  const creation = createNovelCreation({ repository, modelRunning: model, nowMs: makeNow() });
  const started: DurablePolishJob = await creation.startPolish('pause-order', 1, 1, CONTEXT);
  await waitForRequest(model, 1);

  let stageAtCancel: Promise<PolishStage> | null = null;
  model.onCancel = (): void => {
    stageAtCancel = repository.loadPolishJob('pause-order', started.jobId)
      .then((job: DurablePolishJob): PolishStage => job.stage);
  };
  const paused: DurablePolishJob = await creation.pausePolish('pause-order', started.jobId);

  assert.equal(paused.stage, 'paused');
  assert.equal((await repository.loadPolishJob('pause-order', started.jobId)).stage, 'paused');
  assert.deepEqual(model.cancelled, [model.requests[0].runId]);
  assert.notEqual(stageAtCancel, null);
  assert.equal(await stageAtCancel, 'paused');
});

for (const action of ['resume', 'retry'] as const) {
  test(`${action}Polish notifies and schedules the durable job`, async () => {
    const projectId: string = `${action}-effects`;
    const repository = createFileNovelRepository(createMemoryFileStore());
    let job: DurablePolishJob = await startDirectJob(repository, projectId, 1);
    job = await repository.claimPolishJob(projectId, job.jobId, 'setup-owner', NOW + 2, 60_000);
    const claim = claimRef(job);
    job = await repository.checkpointPolishStage(projectId, job.jobId, claim, 'writing', NOW + 3);
    if (action === 'resume') {
      job = await repository.pausePolishJob(projectId, job.jobId, claim, NOW + 4);
    } else {
      job = await repository.failPolishJob(projectId, job.jobId, claim, 'provider failed', NOW + 4);
    }
    const effects = new FakeEffects();
    const creation = createNovelCreation({
      repository, modelRunning: new FakeModel(), polishEffects: effects, nowMs: makeNow(),
    });

    const activated: DurablePolishJob = action === 'resume'
      ? await creation.resumePolish(projectId, job.jobId)
      : await creation.retryPolish(projectId, job.jobId);
    const stage = action === 'resume' ? 'writing' : 'queued';
    assert.equal(activated.stage, stage);
    assert.deepEqual(effects.events, [`notify:${stage}:0`, `schedule:${stage}:0`]);
  });
}

test('cancelPolish notifies and cancels scheduled work without launching a model', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const job: DurablePolishJob = await startDirectJob(repository, 'cancel-effects', 1);
  const model = new FakeModel();
  const effects = new FakeEffects();
  const creation = createNovelCreation({
    repository, modelRunning: model, polishEffects: effects, nowMs: makeNow(),
  });

  const cancelled: DurablePolishJob = await creation.cancelPolish('cancel-effects', job.jobId);
  assert.equal(cancelled.stage, 'cancelled');
  assert.deepEqual(effects.events, ['notify:cancelled:0', 'cancel:cancelled:0']);
  assert.equal(model.requests.length, 0);
});

test('drivePolishInBackground commits at most one chapter per call and reschedules waiting work', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const job: DurablePolishJob = await startDirectJob(repository, 'background', 2);
  const model = new FakeModel(automaticResponder);
  const effects = new FakeEffects();
  const creation = createNovelCreation({
    repository, modelRunning: model, polishEffects: effects, nowMs: makeNow(),
  });

  const first: DurablePolishJob = await creation.drivePolishInBackground(
    'background', job.branchId, job.jobId);
  assert.equal(first.stage, 'waiting_system');
  assert.equal(first.cursor, 1);
  assert.equal(first.progress.length, 1);
  assert.equal(model.requests.length, 2);
  assert.deepEqual(effects.events, ['notify:waiting_system:1', 'schedule:waiting_system:1']);
  const halfway: NovelProject = await repository.loadProject('background');
  assert.equal(halfway.chapters[0].content, '第 1 章润色正文。');
  assert.equal(halfway.chapters[1].content, '第 2 章旧正文。');

  const second: DurablePolishJob = await creation.drivePolishInBackground(
    'background', job.branchId, job.jobId);
  assert.equal(second.stage, 'completed');
  assert.equal(second.cursor, 2);
  assert.equal(model.requests.length, 4);
  assert.deepEqual(effects.events.slice(2), ['notify:completed:2', 'cancel:completed:2']);
});

test('cold open recovers an active polish job without any page-owned driver', async () => {
  const store = createMemoryFileStore();
  const seedRepository = createFileNovelRepository(store);
  const job: DurablePolishJob = await startDirectJob(seedRepository, 'cold-active', 1);

  const coldRepository = createFileNovelRepository(store);
  const model = new FakeModel(automaticResponder);
  const effects = new FakeEffects();
  const coldCreation = createNovelCreation({
    repository: coldRepository, modelRunning: model, polishEffects: effects, nowMs: makeNow(),
  });
  const opened: NovelProject = await coldCreation.open('cold-active');
  assert.equal(opened.id, 'cold-active');

  const recovered: DurablePolishJob = await eventually(
    (): Promise<DurablePolishJob> => coldRepository.loadPolishJob('cold-active', job.jobId),
    (current: DurablePolishJob): boolean => current.stage === 'completed',
    'cold-open polish recovery did not complete',
  );
  await eventually(
    (): number => effects.events.length,
    (length: number): boolean => length >= 2,
    'cold-open completion effects were not delivered',
  );
  assert.equal(recovered.progress.length, 1);
  assert.equal((await coldRepository.loadProject('cold-active')).chapters[0].content,
    '第 1 章润色正文。');
  assert.deepEqual(effects.events, ['notify:completed:1', 'cancel:completed:1']);
});

test('cold open leaves waiting_system durable and only reschedules it', async () => {
  const store = createMemoryFileStore();
  const seedRepository = createFileNovelRepository(store);
  let job: DurablePolishJob = await startDirectJob(seedRepository, 'cold-waiting', 1);
  job = await seedRepository.claimPolishJob(
    'cold-waiting', job.jobId, 'system-owner', NOW + 2, 60_000);
  const claim = claimRef(job);
  job = await seedRepository.checkpointPolishStage(
    'cold-waiting', job.jobId, claim, 'writing', NOW + 3);
  job = await seedRepository.yieldPolishJob('cold-waiting', job.jobId, claim, NOW + 4);
  assert.equal(job.stage, 'waiting_system');

  const coldRepository = createFileNovelRepository(store);
  const model = new FakeModel(automaticResponder);
  const effects = new FakeEffects();
  const coldCreation = createNovelCreation({
    repository: coldRepository, modelRunning: model, polishEffects: effects, nowMs: makeNow(),
  });
  await coldCreation.open('cold-waiting');
  await eventually(
    (): number => effects.events.length,
    (length: number): boolean => length === 2,
    'waiting-system recovery was not rescheduled',
  );

  assert.equal((await coldRepository.loadPolishJob('cold-waiting', job.jobId)).stage, 'waiting_system');
  assert.deepEqual(effects.events, ['notify:waiting_system:0', 'schedule:waiting_system:0']);
  assert.equal(model.requests.length, 0);
});

test('opening from another context leaves a live background polish owner and its model result intact', async () => {
  const files = createMemoryFileStore();
  const repository = createFileNovelRepository(files);
  const job = await startDirectJob(repository, 'background-open', 1);
  const model = new FakeModel();
  const background = createNovelCreation({ repository, modelRunning: model, nowMs: makeNow() });
  const foreground = createNovelCreation({
    repository: createFileNovelRepository(files), modelRunning: model, nowMs: makeNow(),
  });
  const driving = background.drivePolishInBackground(job.projectId, job.branchId, job.jobId);
  await waitForRequest(model, 1);
  const originalClaim = (await repository.loadPolishJob(job.projectId, job.jobId)).claim;

  await foreground.open(job.projectId);
  for (let attempt = 0; attempt < 10; attempt++) await new Promise<void>(resolve => setImmediate(resolve));
  assert.equal(model.requests.length, 1);
  assert.deepEqual((await repository.loadPolishJob(job.projectId, job.jobId)).claim, originalClaim);
  assert.deepEqual(model.cancelled, []);

  model.complete(0, writerResponse(model.requests[0]));
  await waitForRequest(model, 2);
  model.complete(1, reviewResponse(model.requests[1]));
  assert.equal((await driving).stage, 'completed');
  assert.equal((await repository.loadProject(job.projectId)).chapters[0].content, '第 1 章润色正文。');
});

test('background contention returns immediately and its later stop cannot yield the foreground owner', async () => {
  const files = createMemoryFileStore();
  const repository = createFileNovelRepository(files);
  await repository.createProject(projectFixture('foreground-background', 1));
  const model = new FakeModel();
  const foreground = createNovelCreation({ repository, modelRunning: model, nowMs: makeNow() });
  const background = createNovelCreation({
    repository: createFileNovelRepository(files), modelRunning: model, nowMs: makeNow(),
  });
  const job = await foreground.startPolish('foreground-background', 1, 1, CONTEXT);
  await waitForRequest(model, 1);
  const originalClaim = (await repository.loadPolishJob(job.projectId, job.jobId)).claim;
  let returned = false;
  const duplicate = background.drivePolishInBackground(job.projectId, job.branchId, job.jobId)
    .then(result => { returned = true; return result; });
  await eventually(() => returned, value => value, 'background must not wait for the active model');
  assert.equal((await duplicate).stage, 'writing');
  await background.yieldPolishForSystem(job.projectId, job.jobId);
  assert.deepEqual((await repository.loadPolishJob(job.projectId, job.jobId)).claim, originalClaim);
  assert.equal(model.requests.length, 1);
  assert.deepEqual(model.cancelled, []);
  await foreground.cancelPolish(job.projectId, job.jobId);
});

test('cold recovery takes over an unexpired durable lease when no process holds the driver lock', async () => {
  const files = createMemoryFileStore();
  const repository = createFileNovelRepository(files);
  const job = await startDirectJob(repository, 'dead-owner', 1);
  const claimed = await repository.claimPolishJob(job.projectId, job.jobId, 'terminated-process', NOW + 2, 12 * 60 * 60 * 1000);
  await repository.checkpointPolishStage(job.projectId, job.jobId, claimRef(claimed), 'writing', NOW + 3);
  const model = new FakeModel(automaticResponder);
  const cold = createNovelCreation({
    repository: createFileNovelRepository(files), modelRunning: model, nowMs: makeNow(),
  });
  await cold.open(job.projectId);
  const completed = await eventually(() => repository.loadPolishJob(job.projectId, job.jobId),
    value => value.stage === 'completed', 'a dead owner must not delay recovery until the 12-hour lease expires');
  assert.equal(completed.claimEpoch, claimed.claimEpoch + 1);
  assert.equal(completed.progress.length, 1);
  assert.equal(model.requests.length, 2);
});

test('system stop yields only the model owned by this background callback and releases its driver lock', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const job = await startDirectJob(repository, 'background-stop', 1);
  const model = new FakeModel();
  const creation = createNovelCreation({ repository, modelRunning: model, nowMs: makeNow() });
  const driving = creation.drivePolishInBackground(job.projectId, job.branchId, job.jobId);
  await waitForRequest(model, 1);
  const stopped = await creation.yieldPolishForSystem(job.projectId, job.jobId);
  assert.equal(stopped.stage, 'waiting_system');
  assert.deepEqual(model.cancelled, [model.requests[0].runId]);
  assert.equal((await driving).stage, 'waiting_system');
  assert.equal(await repository.tryWithPolishDriverLock(job.projectId, job.jobId, async () => 'released'), 'released');
});

test('explicit foreground resume takes over after an invalidated background owner releases its driver lock', async () => {
  const files = createMemoryFileStore();
  const repository = createFileNovelRepository(files);
  const job = await startDirectJob(repository, 'background-pause-resume', 1);
  const model = new FakeModel();
  const background = createNovelCreation({ repository, modelRunning: model, nowMs: makeNow() });
  const foreground = createNovelCreation({
    repository: createFileNovelRepository(files), modelRunning: model, nowMs: makeNow(),
  });
  const driving = background.drivePolishInBackground(job.projectId, job.branchId, job.jobId)
    .catch((): null => null);
  await waitForRequest(model, 1);
  await foreground.pausePolish(job.projectId, job.jobId);
  await foreground.resumePolish(job.projectId, job.jobId);
  for (let attempt = 0; attempt < 10; attempt++) await new Promise<void>(resolve => setImmediate(resolve));
  assert.equal(model.requests.length, 1, 'the replacement must wait for the old driver to release its lock');

  model.complete(0, writerResponse(model.requests[0]));
  await driving;
  await waitForRequest(model, 2);
  model.complete(1, writerResponse(model.requests[1]));
  await waitForRequest(model, 3);
  model.complete(2, reviewResponse(model.requests[2]));
  const completed = await eventually(() => repository.loadPolishJob(job.projectId, job.jobId),
    value => value.stage === 'completed', 'explicit resume must keep a replacement driver after the stale owner exits');
  assert.equal(completed.progress.length, 1);
  assert.equal((await repository.loadProject(job.projectId)).chapters[0].content, '第 1 章润色正文。');
});

for (const boundary of ['initial load', 'driver lock'] as const) {
  test(`system stop during background ${boundary} yields before sending any model request`, async () => {
    const repository = createFileNovelRepository(createMemoryFileStore());
    const job = await startDirectJob(repository, `background-start-stop-${boundary.replace(' ', '-')}`, 1);
    let entered!: () => void;
    const started = new Promise<void>(resolve => { entered = resolve; });
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    let initialLoad = true;
    const delayed = new Proxy(repository, {
      get(target, key) {
        if (boundary === 'initial load' && key === 'loadPolishJob') {
          return async (projectId: string, jobId: string): Promise<DurablePolishJob> => {
            const loaded = await target.loadPolishJob(projectId, jobId);
            if (initialLoad) {
              initialLoad = false;
              entered();
              await held;
            }
            return loaded;
          };
        }
        if (boundary === 'driver lock' && key === 'tryWithPolishDriverLock') {
          return (projectId: string, jobId: string, op: () => Promise<DurablePolishJob>) =>
            target.tryWithPolishDriverLock(projectId, jobId, async () => {
              entered();
              await held;
              return await op();
            });
        }
        return Reflect.get(target, key);
      },
    });
    const model = new FakeModel(automaticResponder);
    const creation = createNovelCreation({ repository: delayed, modelRunning: model, nowMs: makeNow() });
    const driving = creation.drivePolishInBackground(job.projectId, job.branchId, job.jobId);
    await started;
    await creation.yieldPolishForSystem(job.projectId, job.jobId);
    release();
    assert.equal((await driving).stage, 'waiting_system');
    assert.equal(model.requests.length, 0, 'a stopped callback must not launch a writer after its startup IO finishes');
  });
}

for (const boundary of ['claim', 'model selection', 'writer validation', 'reviewer validation'] as const) {
  test(`system stop during background ${boundary} prevents any later model request`, async () => {
    const repository = createFileNovelRepository(createMemoryFileStore());
    const job = await startDirectJob(repository, `background-stop-${boundary.replace(' ', '-')}`, 1);
    let entered!: () => void;
    const started = new Promise<void>(resolve => { entered = resolve; });
    let release!: () => void;
    const held = new Promise<void>(resolve => { release = resolve; });
    let claimed = false;
    let validations = 0;
    let selections = 0;
    const delayed = new Proxy(repository, {
      get(target, key) {
        if (boundary === 'claim' && key === 'claimPolishJob') {
          return async (...args: Parameters<NovelProjectRepository['claimPolishJob']>) => {
            if (!claimed) {
              claimed = true;
              entered();
              await held;
            }
            return await target.claimPolishJob(...args);
          };
        }
        return Reflect.get(target, key);
      },
    });
    const model = new FakeModel(automaticResponder, async () => {
      validations += 1;
      if ((boundary === 'writer validation' && validations === 1) ||
        (boundary === 'reviewer validation' && validations === 2)) {
        entered();
        await held;
      }
    });
    const creation = createNovelCreation({
      repository: delayed, modelRunning: model, nowMs: makeNow(),
      loadModelDefaults: async () => {
        selections += 1;
        if (boundary === 'model selection' && selections === 1) {
          entered();
          await held;
        }
        return defaultNovelModelDefaults();
      },
    });
    const driving = creation.drivePolishInBackground(job.projectId, job.branchId, job.jobId);
    await started;
    const requestsBeforeStop = model.requests.length;
    await creation.yieldPolishForSystem(job.projectId, job.jobId);
    release();
    assert.equal((await driving).stage, 'waiting_system');
    assert.equal(model.requests.length, requestsBeforeStop,
      'a completed system stop must prevent the pending writer/reviewer from starting');
    assert.equal(await repository.tryWithPolishDriverLock(job.projectId, job.jobId, async () => 'released'), 'released');
  });
}


test('startSelectedPolish runs a sparse selection without writing the skipped chapter', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject(projectFixture('sparse-driver', 3));
  const model = new FakeModel(automaticResponder);
  const creation = createNovelCreation({ repository, modelRunning: model, nowMs: makeNow() });
  const started = await creation.startSelectedPolish('sparse-driver', [3, 1], CONTEXT);
  const completed = await eventually(
    () => repository.loadPolishJob('sparse-driver', started.jobId),
    job => job.stage === 'completed', 'sparse polish job did not complete',
  );
  assert.deepEqual(completed.targets.map(target => target.ordinal), [1, 3]);
  assert.deepEqual(completed.progress.map(progress => progress.chapterOrdinal), [1, 3]);
  const project = await repository.loadProject('sparse-driver');
  assert.deepEqual(project.chapters.map(chapter => chapter.content), [
    '第 1 章润色正文。', '第 2 章旧正文。', '第 3 章润色正文。',
  ]);
  assert.equal(model.requests.length, 4);
});
