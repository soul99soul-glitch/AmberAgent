import type {
  ContinueCandidate, AgentCronTask, AgentCronManager, MiniAppRepository, FilesRepository
} from '@amber/chat-domain';
import { mergeContinueCandidates } from '@amber/chat-domain';
import type {
  NovelCreation, DeepReadScheduler, DeepReadRepository, ModelCouncilManager, ModelCouncilRun
} from '@amber/deepread-domain';
import { loadDeepReadContinueCandidates } from '../DeepReadContinueSupport.ets';
import { relativeTimeMs } from './HomePresentation.ets';

export interface HomeContinuePorts {
  getNovelCreation: () => NovelCreation;
  getAgentCronManager: () => Promise<AgentCronManager>;
  getDeepReadScheduler: () => Promise<DeepReadScheduler>;
  getRepository: () => DeepReadRepository;
  getMiniAppRepositorySync: () => MiniAppRepository;
  getCouncilManager: () => ModelCouncilManager | null;
  getFilesRepository: () => FilesRepository;
}
let cachedContinue: ContinueCandidate | null = null;
let cachedContinueReady: boolean = false;

@Observed
export class HomeContinueSession {
  candidate: ContinueCandidate | null = null;
  private pageAlive: boolean = false;
  private continueSeq: number = 0;
  private readonly ports: HomeContinuePorts;
  constructor(ports: HomeContinuePorts) { this.ports = ports; }

  activate(seedFromCache: boolean): boolean {
    this.pageAlive = true;
    if (seedFromCache) this.candidate = cachedContinue;
    return seedFromCache && cachedContinueReady;
  }
  dispose(): void { this.pageAlive = false; }

  refresh(): void {
    const seq: number = ++this.continueSeq;
    const novelList: Promise<ContinueCandidate[]> = this.ports.getNovelCreation().listProjects()
      .then((projects): ContinueCandidate[] => projects.slice(0, 3).map((p): ContinueCandidate => ({
        sourceKind: 'novel',
        sourceId: p.id,
        route: { kind: 'novel', projectId: p.id },
        title: p.name.length > 0 ? p.name : '未命名小说',
        summary: `${p.chapters.length} 章 · ${p.messages.length} 条讨论`,
        lastUpdatedAt: p.updatedAt,
        status: p.chapters.length > 0 ? 'draft' : 'waiting_user',
        priority: 0,
        isRunning: false,
      })))
      .catch((_err: Error): ContinueCandidate[] => []);
    const cronList: Promise<ContinueCandidate[]> = this.ports.getAgentCronManager()
      .then((m): Promise<AgentCronTask[]> => m.listTasksSnapshot())
      .then((tasks: AgentCronTask[]): ContinueCandidate[] => {
        const out: ContinueCandidate[] = [];
        for (const t of tasks) {
          if (!t.enabled) continue;
          if (t.lastStatus !== 'Failed' && t.lastStatus !== 'Running') continue;
          if (out.length >= 3) break;
          out.push({
            sourceKind: 'cron',
            sourceId: t.id,
            route: { kind: 'cron', taskId: t.id },
            title: t.title,
            summary: t.lastStatus === 'Failed' ? '上次失败，可重试' : '运行中',
            lastUpdatedAt: t.updatedAtMs,
            status: t.lastStatus === 'Failed' ? 'failed_resumable' : 'running',
            priority: 0,
            isRunning: t.lastStatus === 'Running',
          });
        }
        return out;
      })
      .catch((_err: Error): ContinueCandidate[] => []);
    const deepList: Promise<ContinueCandidate[]> = this.ports.getDeepReadScheduler()
      .then((scheduler: DeepReadScheduler): Promise<ContinueCandidate[]> =>
        loadDeepReadContinueCandidates(this.ports.getRepository(), scheduler))
      .catch((_err: Error): ContinueCandidate[] => []);
    const miniList: Promise<ContinueCandidate[]> = this.ports.getMiniAppRepositorySync().listAll()
      .then((apps): ContinueCandidate[] => apps.slice(0, 2).map((app): ContinueCandidate => ({
        sourceKind: 'miniapp',
        sourceId: app.id,
        route: { kind: 'miniapp', appId: app.id },
        title: app.title,
        summary: '已生成，尚未打开',
        lastUpdatedAt: app.updatedAt,
        status: 'draft',
        priority: 0,
        isRunning: false,
      })))
      .catch((_err: Error): ContinueCandidate[] => []);
    // 议会:运行中的 run(内存态;无持久任务系统,进程存活期内可继续)
    const councilList: Promise<ContinueCandidate[]> = Promise.resolve().then((): ContinueCandidate[] => {
      const mgr = this.ports.getCouncilManager();
      if (mgr === null) return [];
      return mgr.listActive().map((run: ModelCouncilRun): ContinueCandidate => ({
        sourceKind: 'council',
        sourceId: run.runId,
        route: { kind: 'council', conversationId: '', runId: run.runId },
        title: run.task.objective.length > 0 ? run.task.objective : '模型议会',
        summary: '讨论进行中',
        lastUpdatedAt: run.updatedAtMs,
        status: 'running',
        priority: 0,
        isRunning: true,
      }));
    }).catch((_err: Error): ContinueCandidate[] => []);
    // 生图:最近一条 chat_images 产物 → 直达来源会话(产物即任务实体;
    //   鸿蒙生图无独立任务注册表,诚实以产物为源)
    const imageList: Promise<ContinueCandidate[]> = this.ports.getFilesRepository().listByFolder('chat_images')
      .then((rows): ContinueCandidate[] => {
        if (rows.length === 0) return [];
        const parts: string[] = rows[0].relativePath.split('/');
        if (parts.length < 3) return [];
        return [{
          sourceKind: 'image',
          sourceId: rows[0].relativePath,
          route: { kind: 'chat', conversationId: parts[1] },
          title: '生成的图片',
          summary: '已生成，可查看或继续修改',
          lastUpdatedAt: rows[0].createdAt,
          status: 'draft',
          priority: 0,
          isRunning: false,
        }];
      })
      .catch((_err: Error): ContinueCandidate[] => []);
    Promise.all([novelList, cronList, deepList, miniList, councilList, imageList]).then((lists): void => {
      if (seq !== this.continueSeq || !this.pageAlive) return;
      const merged: ContinueCandidate[] = mergeContinueCandidates(lists, 6);
      this.candidate = merged.length > 0 ? merged[0] : null;
      cachedContinue = this.candidate;
      cachedContinueReady = true;
    }).catch((): void => {
      if (seq !== this.continueSeq || !this.pageAlive) return;
      this.candidate = null;
    });
  }
}

export const continueIcon = (item: ContinueCandidate): string => {
    if (item.sourceKind === 'novel') return 'ph_notebook';
    if (item.sourceKind === 'cron') return 'ph_clock';
    if (item.sourceKind === 'deepread') return 'ph_bookOpen';
    if (item.sourceKind === 'council') return 'ph_users';
    if (item.sourceKind === 'image') return 'ph_imageSquare';
    return 'ph_squaresFour';
  };
export const continueTitle = (item: ContinueCandidate): string => {
    if (item.sourceKind === 'novel') return `《${item.title}》`;
    if (item.sourceKind === 'miniapp') return '小应用';
    return item.title;
  };
export const continueMeta = (item: ContinueCandidate): string => {
    if (item.sourceKind === 'novel') return `小说创作 · ${relativeTimeMs(item.lastUpdatedAt)}`;
    if (item.sourceKind === 'cron') return `定时任务 · ${item.summary}`;
    if (item.sourceKind === 'deepread') return `深度阅读 · ${item.summary}`;
    if (item.sourceKind === 'council') return `模型议会 · ${item.summary}`;
    if (item.sourceKind === 'image') return `图片生成 · ${item.summary}`;
    return `「${item.title}」 · ${item.summary}`;
  };
export const continueCta = (item: ContinueCandidate): string => {
    if (item.sourceKind === 'novel') return item.isRunning ? '查看' : '继续';
    if (item.sourceKind === 'cron') return item.isRunning ? '查看' : '继续';
    if (item.sourceKind === 'deepread') return item.isRunning ? '查看' : '继续';
    if (item.sourceKind === 'council') return '查看';
    if (item.sourceKind === 'image') return '查看';
    return '打开';
  };
