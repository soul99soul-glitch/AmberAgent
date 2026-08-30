import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import type { NovelCreation } from '../main/ets/novel/creation.ts';
import type {
  NovelRunEvent, NovelRun, NovelBatchPolishEvent, NovelBatchPolishRun,
} from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type { NovelModelRunning, NovelModelEvent, NovelModelRequest } from '../main/ets/novel/model_running.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/agent/message.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';

const NOW = 1_000_000;
const delay = (ms: number): Promise<void> => new Promise(res => setTimeout(res, ms));

const publishCanonicalTurn = async (
  request: NovelModelRequest, text: string, subscribers: Set<(event: NovelModelEvent) => void>,
): Promise<void> => {
  if (request.operation.kind !== 'turn') throw new Error('test model expected turn');
  const user: UIMessage = makeUserMessage(request.operation.userPrompt);
  const withUser: UIMessage[] = request.history.concat([user]);
  await request.checkpoint(withUser);
  subscribers.forEach(cb => { cb({ kind: 'delta', text }); });
  const messages: UIMessage[] = withUser.concat([makeAssistantMessage(text)]);
  await request.checkpoint(messages);
  subscribers.forEach(cb => {
    cb({
      kind: 'snapshot', messages, generationActive: false,
      textDeltasLive: true, transport: 'live',
    });
  });
  subscribers.forEach(cb => { cb({ kind: 'completed' }); });
};

// 收集 run 事件直到终结(started 除外),返回全部事件
const runToTerminal = (run: NovelRun): Promise<NovelRunEvent[]> => {
  return new Promise<NovelRunEvent[]>(resolve => {
    const events: NovelRunEvent[] = [];
    run.subscribe((e: NovelRunEvent): void => {
      events.push(e);
      if (e.kind === 'completed' || e.kind === 'interrupted' || e.kind === 'failed') {
        resolve(events);
      }
    });
  });
};

const batchToTerminal = (run: NovelBatchPolishRun): Promise<NovelBatchPolishEvent[]> => {
  return new Promise<NovelBatchPolishEvent[]>(resolve => {
    const events: NovelBatchPolishEvent[] = [];
    run.subscribe((event: NovelBatchPolishEvent): void => {
      events.push(event);
      if (event.kind === 'completed' || event.kind === 'cancelled') resolve(events);
    });
  });
};

// 按 systemPrompt 区分:素材建议调用返回 JSON,其余返回散文
const smartModel = (): NovelModelRunning => ({
  async validate(): Promise<void> {},
  start(req: NovelModelRequest) {
    const subs = new Set<(e: NovelModelEvent) => void>();
    setTimeout((): void => {
      const isSuggestion: boolean = req.systemPrompt.includes('资料整理助手');
      const text: string = isSuggestion
        ? '{"suggestions":[{"kind":"character","title":"主角","content":"勇敢"}]}'
        : '这是正文';
      void publishCanonicalTurn(req, text, subs);
    }, 0);
    return {
      subscribe(cb: (e: NovelModelEvent) => void): () => void {
        subs.add(cb);
        return () => { subs.delete(cb); };
      },
    };
  },
  cancel(_runId: string): void {},
});

// 可控模型:挂起直到外部 emit / cancel
const controllableModel = (): {
  model: NovelModelRunning;
  emit: (e: NovelModelEvent) => Promise<void>;
} => {
  const subs = new Set<(e: NovelModelEvent) => void>();
  let request: NovelModelRequest | null = null;
  let baseMessages: UIMessage[] = [];
  const model: NovelModelRunning = {
    async validate(): Promise<void> {},
    start(req: NovelModelRequest) {
      request = req;
      if (req.operation.kind === 'turn') {
        baseMessages = req.history.concat([makeUserMessage(req.operation.userPrompt)]);
        void req.checkpoint(baseMessages);
      }
      return {
        subscribe(cb: (e: NovelModelEvent) => void): () => void {
          subs.add(cb);
          return () => { subs.delete(cb); };
        },
      };
    },
    cancel(_runId: string): void {
      subs.forEach(cb => { cb({ kind: 'failed', message: 'cancelled' }); });
    },
  };
  return {
    model: model,
    emit: async (e: NovelModelEvent): Promise<void> => {
      let event: NovelModelEvent = e;
      if (e.kind === 'snapshot' && request !== null) {
        const messages: UIMessage[] = baseMessages.concat(e.messages);
        await request.checkpoint(messages);
        event = { ...e, messages };
      }
      subs.forEach(cb => { cb(event); });
    },
  };
};

const makeCreation = (model: NovelModelRunning): NovelCreation =>
  createNovelCreation({
    repository: createFileNovelRepository(createMemoryFileStore()),
    modelRunning: model,
    nowMs: (): number => NOW,
    idleTimeoutMs: 500,
  });

test('create + open roundtrip', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('我的小说');
  const opened = await creation.open(p.id);
  assert.equal(opened.name, '我的小说');
});

test('generate write: streams, persists user+assistant, sets granularity', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('小说');
  await creation.setModelPolicy(p.id, {
    writing: { kind: 'global' }, review: null, stateSync: { kind: 'global' },
  });
  const run = creation.generate(p.id, '写第一章', 'write', 'whole_chapter');
  const events = await runToTerminal(run);
  assert.ok(events.some(e => e.kind === 'started'));
  assert.ok(events.some(e => e.kind === 'snapshot'));
  const completed = events.find(e => e.kind === 'completed');
  assert.ok(completed !== undefined);

  const reloaded = await creation.open(p.id);
  assert.equal(reloaded.messages.length, 2);
  assert.equal(reloaded.messages[0].role, 'user');
  assert.equal(reloaded.messages[1].role, 'assistant');
  assert.equal(reloaded.messages[1].content, '这是正文');
  assert.equal(reloaded.lastGenerationGranularity, 'whole_chapter');
});

test('generate write: canonical snapshots replace partial and persist the final assistant text', async () => {
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const project = await creation.create('小说');
  const terminal = runToTerminal(creation.generate(project.id, '写一段', 'write', 'continuation'));
  await delay(10);
  await ctl.emit({
    kind: 'snapshot',
    messages: [makeAssistantMessage('第一帧')],
    generationActive: true,
    textDeltasLive: true,
    transport: 'live',
  });
  await ctl.emit({
    kind: 'snapshot',
    messages: [makeAssistantMessage('第一帧继续')],
    generationActive: false,
    textDeltasLive: false,
    transport: 'live',
  });
  await ctl.emit({ kind: 'completed' });
  const events = await terminal;
  assert.equal(events.filter(event => event.kind === 'snapshot').length, 2);

  const reloaded = await creation.open(project.id);
  assert.equal(reloaded.messages[1].content, '第一帧继续');
});

test('generate discuss: granularity stays null on message', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('小说');
  await creation.setModelPolicy(p.id, {
    writing: { kind: 'global' }, review: null, stateSync: { kind: 'global' },
  });
  const run = creation.generate(p.id, '聊聊主角', 'discuss');
  await runToTerminal(run);
  const reloaded = await creation.open(p.id);
  const assistant = reloaded.messages.find(m => m.role === 'assistant');
  assert.equal(assistant?.mode, 'discuss');
  assert.equal(assistant?.granularity, null);
});

test('generate: empty input → failed', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('小说');
  const events = await runToTerminal(creation.generate(p.id, '   ', 'write'));
  assert.ok(events.some(e => e.kind === 'failed'));
});

test('generate: model validate failure → failed', async () => {
  const noModel: NovelModelRunning = {
    async validate(): Promise<void> { throw new Error('尚未配置模型'); },
    start(): { subscribe: (cb: (e: NovelModelEvent) => void) => () => void } {
      return { subscribe: (): () => void => ((): void => {}) };
    },
    cancel(): void {},
  };
  const creation = makeCreation(noModel);
  const p = await creation.create('小说');
  const events = await runToTerminal(creation.generate(p.id, '写', 'write'));
  assert.ok(events.some(e => e.kind === 'failed'));
});

test('busy: second generate on same project fails', async () => {
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const p = await creation.create('小说');
  const run1 = creation.generate(p.id, '写', 'write');
  await delay(10); // 让 run1 进入运行态
  const events2 = await runToTerminal(creation.generate(p.id, '再写', 'write'));
  assert.ok(events2.some(e => e.kind === 'failed' && e.message.includes('正在生成')));
  creation.interrupt(run1.id); // 清理
});

test('interrupt: keeps partial and persists interrupted message', async () => {
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const p = await creation.create('小说');
  const run = creation.generate(p.id, '写一段', 'write', 'continuation');
  const eventsP = runToTerminal(run);
  await delay(10);
  await ctl.emit({ kind: 'delta', text: 'partial' });
  await delay(5);
  creation.interrupt(run.id);
  const events = await eventsP;
  const interrupted = events.find(e => e.kind === 'interrupted');
  assert.ok(interrupted !== undefined);
  assert.equal(interrupted?.kind === 'interrupted' ? interrupted.partial : '', 'partial');

  const reloaded = await creation.open(p.id);
  const assistant = reloaded.messages.find(m => m.role === 'assistant');
  assert.ok(assistant !== undefined);
  assert.equal(assistant?.interrupted, true);
  assert.equal(assistant?.content, 'partial');
});

test('collectMessage: saves chapter and produces a suggestion (async)', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('小说');
  await creation.setModelPolicy(p.id, {
    writing: { kind: 'global' }, review: null, stateSync: { kind: 'global' },
  });
  await runToTerminal(creation.generate(p.id, '写第一章', 'write', 'whole_chapter'));
  const after = await creation.open(p.id);
  const assistant = after.messages.find(m => m.role === 'assistant');
  assert.ok(assistant !== undefined);

  // collectMessage 现在解耦:章节同步落库,建议后台异步分析
  const result = await creation.collectMessage(p.id, assistant!.id, { kind: 'new_chapter', title: '第一章' });
  assert.equal(result.chapter.title, '第一章');
  assert.equal(result.chapter.content, '这是正文');
  assert.equal(result.suggestionCount, 0, 'suggestionCount is 0 (analyzed async)');

  // 章节与消息标记立即可见
  const immediately = await creation.open(p.id);
  assert.equal(immediately.chapters.length, 1);
  const msg = immediately.messages.find(m => m.id === assistant!.id);
  assert.equal(msg?.collectedChapterId, result.chapter.id);

  // 等待后台建议分析完成
  await delay(30);
  const reloaded = await creation.open(p.id);
  assert.equal(reloaded.materialSuggestions.length, 1);
  assert.equal(reloaded.materialSuggestions[0].status, 'pending');
});

test('resolveMaterialSuggestion accept: creates material', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('小说');
  await creation.setModelPolicy(p.id, {
    writing: { kind: 'global' }, review: null, stateSync: { kind: 'global' },
  });
  await runToTerminal(creation.generate(p.id, '写', 'write'));
  const after = await creation.open(p.id);
  const assistant = after.messages.find(m => m.role === 'assistant');
  await creation.collectMessage(p.id, assistant!.id, { kind: 'new_chapter', title: '第一章' });
  // 等待后台建议分析完成
  await delay(30);
  const withSug = await creation.open(p.id);
  const sug = withSug.materialSuggestions[0];

  const material = await creation.resolveMaterialSuggestion(p.id, sug.id, true);
  assert.ok(material !== null);
  assert.equal(material?.title, '主角');
  const reloaded = await creation.open(p.id);
  assert.equal(reloaded.materials.length, 1);
  assert.equal(reloaded.materialSuggestions.find(s => s.id === sug.id)?.status, 'accepted');
});

test('chapter and material CRUD via creation', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('小说');
  const chapter = await creation.saveChapter(p.id, null, '第一章', '正文');
  assert.equal(chapter.title, '第一章');
  const material = await creation.upsertMaterial(p.id, null, 'world', '世界观', '内容', true);
  assert.equal(material.kind, 'world');

  const reloaded = await creation.open(p.id);
  assert.equal(reloaded.chapters.length, 1);
  assert.equal(reloaded.materials.length, 1);

  await creation.deleteChapter(p.id, chapter.id);
  await creation.deleteMaterial(p.id, material.id);
  const emptied = await creation.open(p.id);
  assert.equal(emptied.chapters.length, 0);
  assert.equal(emptied.materials.length, 0);
});

test('rename and setModelPolicy', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('原');
  await creation.rename(p.id, '新名');
  await creation.setModelPolicy(p.id, {
    writing: { kind: 'fixed', providerId: 'providerX', modelId: 'modelX' },
    review: null,
    stateSync: null,
  });
  const reloaded = await creation.open(p.id);
  assert.equal(reloaded.name, '新名');
  assert.equal(reloaded.modelId, 'modelX');
});

test('delete project removes it', async () => {
  const creation = makeCreation(smartModel());
  const p = await creation.create('小说');
  await creation.delete(p.id);
  await assert.rejects(creation.open(p.id));
});

// ===== 回归测试(针对 review 修复) =====

test('regression: interrupt before any token still persists user message', async () => {
  // 模型挂起不吐字,用户立即停止 → partial 为空,但 user 消息应保留
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const p = await creation.create('小说');
  const run = creation.generate(p.id, '我输入了一段提示词', 'write', 'continuation');
  const eventsP = runToTerminal(run);
  await delay(10);
  // 模型还没 emit 任何 delta,直接中断
  creation.interrupt(run.id);
  const events = await eventsP;
  assert.ok(events.some(e => e.kind === 'interrupted'));

  const reloaded = await creation.open(p.id);
  const userMsg = reloaded.messages.find(m => m.role === 'user');
  assert.ok(userMsg !== undefined, 'user 消息必须保留');
  assert.equal(userMsg!.content, '我输入了一段提示词');
  // partial 为空 → 不写 interrupted 助手占位消息
  const assistants = reloaded.messages.filter(m => m.role === 'assistant');
  assert.equal(assistants.length, 0, '无 partial 不应写助手消息');
});

test('regression: interrupt with partial persists both user and interrupted assistant', async () => {
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const p = await creation.create('小说');
  const run = creation.generate(p.id, '写一段', 'write', 'continuation');
  const eventsP = runToTerminal(run);
  await delay(10);
  await ctl.emit({ kind: 'delta', text: '部分正文' });
  await delay(5);
  creation.interrupt(run.id);
  await eventsP;

  const reloaded = await creation.open(p.id);
  assert.equal(reloaded.messages.length, 2);
  assert.equal(reloaded.messages[0].role, 'user');
  assert.equal(reloaded.messages[1].role, 'assistant');
  assert.equal(reloaded.messages[1].interrupted, true);
  assert.equal(reloaded.messages[1].content, '部分正文');
});

test('regression: delete during generation waits for run to terminate', async () => {
  // 生成中删除:delete 应等待 run 终止,避免与 updateProject 并发写竞争
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const p = await creation.create('小说');
  const run = creation.generate(p.id, '写', 'write');
  await delay(10); // 进入运行态
  // delete 时还有活跃 run
  assert.equal(creation['projectRuns'].has(p.id), true); // 内部状态:有活跃 run
  const deleteP = creation.delete(p.id);
  // 让 run 因 interrupt 终止
  creation.interrupt(run.id);
  await deleteP; // 应 resolve(不卡死)
  await assert.rejects(creation.open(p.id), '项目已删除');
});

test('regression: collectMessage does not block on suggestion analysis', async () => {
  // 建一个慢建议模型:分析要 100ms。collectMessage 应立即返回(suggestionCount=0)
  const slowSuggestionModel: NovelModelRunning = {
    async validate(): Promise<void> {},
    start(req: NovelModelRequest) {
      const subs = new Set<(e: NovelModelEvent) => void>();
      const isSuggestion: boolean = req.systemPrompt.includes('资料整理助手');
      if (isSuggestion) {
        setTimeout((): void => {
          void publishCanonicalTurn(req, '{"suggestions":[]}', subs);
        }, 100);
      } else {
        setTimeout((): void => {
          void publishCanonicalTurn(req, '正文', subs);
        }, 0);
      }
      return { subscribe: (cb): (() => void) => { subs.add(cb); return () => { subs.delete(cb); }; } };
    },
    cancel(): void {},
  };
  const creation = makeCreation(slowSuggestionModel);
  const p = await creation.create('小说');
  await creation.setModelPolicy(p.id, {
    writing: { kind: 'global' }, review: null, stateSync: { kind: 'global' },
  });
  await runToTerminal(creation.generate(p.id, '写', 'write'));
  const after = await creation.open(p.id);
  const assistant = after.messages.find(m => m.role === 'assistant')!;

  const t0 = Date.now();
  const result = await creation.collectMessage(p.id, assistant.id, { kind: 'new_chapter', title: '第一章' });
  const elapsed = Date.now() - t0;
  assert.ok(elapsed < 80, `collectMessage 应立即返回,实际 ${elapsed}ms(不应等 100ms 的建议分析)`);
  assert.equal(result.suggestionCount, 0);
  assert.equal(result.chapter.title, '第一章');
  // 章节已落库
  const reloaded = await creation.open(p.id);
  assert.equal(reloaded.chapters.length, 1);
});

test('legacy fenced setting proposals stay transcript-only and do not bypass workspace tools', async () => {
  const proposalModel: NovelModelRunning = {
    async validate(): Promise<void> {},
    start(request: NovelModelRequest): { subscribe: (cb: (event: NovelModelEvent) => void) => () => void } {
      const subs = new Set<(event: NovelModelEvent) => void>();
      setTimeout((): void => {
        const text = '建议保留。\n```setting_proposals\n'
          + '{"proposals":[{"kind":"world","title":"灵脉","content":"贯穿大陆"}]}\n```';
        void publishCanonicalTurn(request, text, subs);
      }, 0);
      return { subscribe: (cb): (() => void) => { subs.add(cb); return () => { subs.delete(cb); }; } };
    },
    cancel(): void {},
  };
  const creation = makeCreation(proposalModel);
  const project = await creation.create('小说');
  await runToTerminal(creation.generate(project.id, '聊设定', 'discuss'));

  const persisted = await creation.open(project.id);
  assert.equal(persisted.messages.length, 2);
  assert.equal(persisted.settingProposals.length, 0);
  assert.ok(persisted.messages[1].content.includes('```setting_proposals'));
});

test('batch polish runs chapters serially and returns assistant message ids', async () => {
  let active = 0;
  let maxActive = 0;
  const starts: string[] = [];
  const serialModel: NovelModelRunning = {
    async validate(): Promise<void> {},
    start(request: NovelModelRequest) {
      starts.push(request.operation.kind === 'turn' ? request.operation.userPrompt : 'continuation');
      active += 1;
      maxActive = Math.max(maxActive, active);
      const subs = new Set<(event: NovelModelEvent) => void>();
      setTimeout((): void => {
        const text: string = `润色${starts.length}`;
        active -= 1;
        void publishCanonicalTurn(request, text, subs);
      }, 5);
      return { subscribe: (cb): (() => void) => { subs.add(cb); return () => { subs.delete(cb); }; } };
    },
    cancel(): void {},
  };
  const creation = makeCreation(serialModel);
  const project = await creation.create('小说');
  const first = await creation.saveChapter(project.id, null, '第一章', '旧一');
  const second = await creation.saveChapter(project.id, null, '第二章', '旧二');

  const events = await batchToTerminal(creation.startBatchPolish(project.id, [first.id, second.id]));
  const completed = events.find(event => event.kind === 'completed');
  assert.ok(completed?.kind === 'completed');
  assert.equal(maxActive, 1);
  assert.equal(starts.length, 2);
  assert.ok(starts[0].includes('第一章'));
  assert.ok(starts[1].includes('第二章'));
  assert.deepEqual(completed.results.map(result => result.status), ['succeeded', 'succeeded']);
  assert.ok(completed.results.every(result => result.messageId !== null));
});

test('batch polish cancellation interrupts current chapter and does not start the next', async () => {
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const project = await creation.create('小说');
  const first = await creation.saveChapter(project.id, null, '第一章', '旧一');
  const second = await creation.saveChapter(project.id, null, '第二章', '旧二');
  const batch = creation.startBatchPolish(project.id, [first.id, second.id]);
  const terminal = batchToTerminal(batch);
  await delay(10);
  batch.cancel();
  const events = await terminal;
  const cancelled = events.find(event => event.kind === 'cancelled');
  assert.ok(cancelled?.kind === 'cancelled');
  assert.equal(cancelled.results.length, 1);
  assert.equal(cancelled.results[0].chapterId, first.id);
  assert.equal(cancelled.results[0].status, 'cancelled');
  assert.ok(!events.some(event => event.kind === 'progress' && event.chapterId === second.id));
});

test('repeated batch polish call for one project returns the active run', async () => {
  const ctl = controllableModel();
  const creation = makeCreation(ctl.model);
  const project = await creation.create('小说');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '旧正文');
  const first = creation.startBatchPolish(project.id, [chapter.id]);
  const repeated = creation.startBatchPolish(project.id, [chapter.id]);
  assert.equal(repeated.id, first.id);
  const terminal = batchToTerminal(first);
  await delay(10);
  first.cancel();
  await terminal;
});

test('adoptPolishMessage saves polish version, replaces chapter and marks message collected', async () => {
  const creation = makeCreation(smartModel());
  const project = await creation.create('小说');
  const chapter = await creation.saveChapter(project.id, null, '第一章', '旧正文');
  const events = await batchToTerminal(creation.startBatchPolish(project.id, [chapter.id]));
  const completed = events.find(event => event.kind === 'completed');
  assert.ok(completed?.kind === 'completed');
  const messageId = completed.results[0].messageId;
  assert.ok(messageId !== null);

  const adopted = await creation.adoptPolishMessage(project.id, chapter.id, messageId);
  assert.equal(adopted.content, '这是正文');
  const reloaded = await creation.open(project.id);
  assert.equal(reloaded.chapterVersions.length, 1);
  assert.equal(reloaded.chapterVersions[0].kind, 'polish');
  assert.equal(reloaded.chapterVersions[0].content, '旧正文');
  assert.equal(reloaded.messages.find(message => message.id === messageId)?.collectedChapterId, chapter.id);
});

test('adoptPolishMessage rejects a polish result generated for another chapter', async () => {
  const creation = makeCreation(smartModel());
  const project = await creation.create('小说');
  const first = await creation.saveChapter(project.id, null, '第一章', '旧一');
  const second = await creation.saveChapter(project.id, null, '第二章', '旧二');
  const events = await batchToTerminal(creation.startBatchPolish(project.id, [first.id]));
  const completed = events.find(event => event.kind === 'completed');
  assert.ok(completed?.kind === 'completed');
  const messageId = completed.results[0].messageId;
  assert.ok(messageId !== null);

  await assert.rejects(creation.adoptPolishMessage(project.id, second.id, messageId));
  const reloaded = await creation.open(project.id);
  assert.equal(reloaded.chapters.find(chapter => chapter.id === second.id)?.content, '旧二');
  assert.equal(reloaded.chapterVersions.length, 0);
});
