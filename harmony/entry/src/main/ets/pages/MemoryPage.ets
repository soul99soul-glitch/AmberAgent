// MemoryPage — 记忆管理页(D-088 首片:dream 审核+候选;D-089 二片:worker 开关组+
//   记忆库三桶 CRUD + 事件日志)
// Android 锚点:
//   SettingAgentMemoryPage.kt:993-1080(DreamReviewSection)/:884-906,912-991(候选区)
//   SettingAgentMemoryPage.kt:432-526(MemoryWorkerSubpage 开关组:三 Switch +
//     supporting 文案逐字 + canRunDream 门 + '立即运行不可用' 提示)
//   SettingAgentMemoryPage.kt:570-687(Library:三桶 RecordsSection + 维护工具)
//   SettingAgentMemoryPage.kt:1183-1316(MemoryRecordsSection/MemoryItem:
//     #id + content maxLines 5 + 编辑/删除;空文案 zh strings 逐字)
//   SettingAgentMemoryPage.kt:1105-1135(MemoryEventsSection:take(6) + 空文案 +
//     message 空白回退 'memory=X candidate=Y')
//   SettingAgentMemoryPage.kt:127-149(编辑对话框:标题/label/保存/取消;
//     id==0 → addMemory 否则 updateMemory)/:254-269(删除确认:confirm_delete)
//   SettingAgentMemoryVM.kt:68-74(updateAgentRuntime)/:77-93(add/update/delete)
// 裁剪(P1 登记):Overview 导航/Recall 开关页/Compaction 页/AgentSoul 卡/
//   记忆汇总卡('Amber 对你的了解')/导入导出(目录选择器未落地)/toolbar
//   '立即运行一次'(依附 workScheduler 调度环)/记忆库?'信息'图标视觉(以文字 '?' 代)。
import { promptAction, router } from '@kit.ArkUI';
import type {
  AssistantMemory, MemoryCandidate, MemoryDreamPlanOpResult, MemoryDreamApplyOpResult,
  MemoryEvent, MemoryIgnoreLowConfidenceResult, MemoryMergeSuggestion,
  MemorySupersedeSuggestion, PersistedMemoryDreamPlan,
} from '@amber/chat-domain';
import {
  acceptMemoryCandidate, applyMemoryDreamPlanOp, dismissMemoryDreamPlanOp,
  ignoreLowConfidenceMemoryCandidates, ignoreMemoryCandidate, isAnyDreamEnabled,
  memoryDreamApplyFailureMessage, memoryDreamPlanFailureMessage,
  memoryDreamReviewSummaryText, memoryDreamPlanHasChanges,
  memoryLowConfidenceIgnoredMessage, planMemoryDreamOp,
  LOW_CONFIDENCE_CANDIDATE_THRESHOLD,
  MEMORY_BUCKET_GLOBAL, MEMORY_BUCKET_LONG_TERM, MEMORY_BUCKET_SHORT_TERM,
  MEMORY_DREAM_APPLIED_MESSAGE, MEMORY_DREAM_APPLY_EMPTY_MESSAGE,
  MEMORY_DREAM_PLAN_EMPTY_MESSAGE, MEMORY_DREAM_PLAN_REPLACED_MESSAGE,
  MEMORY_DREAM_PLAN_SAVED_MESSAGE, MEMORY_NO_LOW_CONFIDENCE_MESSAGE,
} from '@amber/chat-domain';
import type { MemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import { getMemoryDreamPlanStore, getMemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import {
  loadAgentRuntimeSnapshot, updateAgentSoulMarkdown, updateContextCompactionToggles,
  updateMemoryWorkerToggles, updateRecallToggles,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import {
  getMemoryCandidateOpsDeps, loadMemoryDreamApplyOpDeps,
  loadMemoryDreamPlanOpDeps, loadPendingMemoryCandidates,
  logMemoryDreamOpFailure,
} from '../platform_impl/MemoryDreamOps.ets';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';

// 候选卡 meta 行(Page :947-953 逐字:标签 + confidence %.2f + 高置信后缀)
const candidateMetaText = (c: MemoryCandidate): string => {
  let text: string = `[${c.scope}/${c.kind}] confidence ${c.confidence.toFixed(2)}`;
  if (c.confidence >= LOW_CONFIDENCE_CANDIDATE_THRESHOLD) {
    text += ' · 建议人工审核';
  }
  return text;
};

// merge 行(Page :1053-1056 逐字)
const mergeLineText = (s: MemoryMergeSuggestion): string =>
  `合并 #${s.targetMemoryId} <- ${s.duplicateMemoryIds.join(',')}`;

// supersede 行(Page :1058-1063 逐字,reason 非空白拼 ' · reason')
const supersedeLineText = (s: MemorySupersedeSuggestion): string => {
  const base: string = `替换 ${s.oldMemoryIds.join(',')} → ${s.newContent}`;
  return s.reason.trim().length > 0 ? `${base} · ${s.reason}` : base;
};

// 事件行回退文案(Page :1120-1124:message 空白 → 原始 id 行,逐字)
const eventMessageText = (e: MemoryEvent): string =>
  e.message.trim().length > 0
    ? e.message
    : `memory=${e.memoryId !== null ? e.memoryId : '-'} ` +
      `candidate=${e.candidateId !== null ? e.candidateId : '-'}`;

// zh strings 逐字(values-zh/strings.xml)
const TEXT_EDIT_TITLE: string = '编辑记忆';
const TEXT_CONTENT_LABEL: string = '记忆内容';
const TEXT_CORE_EMPTY: string = '还没有核心记忆。';
const TEXT_SHORT_EMPTY: string =
  '还没有短期记忆。等某个任务聊得比较久，AmberAgent 会把“这轮在做什么、进展到哪一步”整理到这里。';
const TEXT_LONG_EMPTY: string =
  '还没有长期记忆。稳定偏好、长期项目和反复出现的重要事实，会被整理到这里。';
const TEXT_SHORT_INFO_TITLE: string = '短期记忆怎么形成？';
const TEXT_SHORT_INFO_BODY: string =
  '短期记忆更像一张临时任务便签。比如这轮正在调试短信权限、刚安装了一个 skill 包、某个网页搜索已经做到第几步，AmberAgent 会把这些近期进展整理起来，让接下来的几轮对话能接得上。任务结束后，它可能会被替换、清理，或沉淀成长期记忆。';
const TEXT_LONG_INFO_TITLE: string = '长期记忆怎么形成？';
const TEXT_LONG_INFO_BODY: string =
  '长期记忆保存更稳定的信息。比如你偏好中文简洁回复、正在长期改造 AmberAgent、常用 workspace 是某个目录，或反复提到的重要事实。它会跨会话保留，帮助 AmberAgent 下次更快理解你的习惯和长期项目。';
const TEXT_CORE_INFO_TITLE: string = '核心记忆是什么？';
const TEXT_CORE_INFO_BODY: string =
  '核心记忆、短期记忆和长期记忆是并列的三类记忆。核心记忆优先级最高，适合手动维护稳定偏好、身份设定和长期规则。';
// agents.md / Soul 卡(values-zh/strings.xml:583-588 逐字)
const TEXT_SOUL_TITLE: string = 'agents.md / Soul';
const TEXT_SOUL_DESC: string =
  '这段应用级 Markdown 行为准则会在每次对话前注入到 System Prompt 中。';
const TEXT_SOUL_LABEL: string = '系统行为 Markdown';
const TEXT_SOUL_EMPTY_PREVIEW: string = '还没有写入 agents.md 行为准则。';
const TEXT_SOUL_EDIT_HINT: string = '点击编辑 agents.md';
const TEXT_SOUL_EDIT_TITLE: string = '编辑 agents.md';
// 上下文管理(values-zh/strings.xml:599-603 逐字)
const TEXT_COMPACT_TITLE: string = '自动上下文压缩';
const TEXT_COMPACT_DESC: string = '长对话接近模型窗口时，自动整理旧内容，不删除原文。';
const TEXT_COMPACT_DEFAULTS: string =
  '默认：70% 预压缩，85% 强制压缩，保护最近 8 轮 / 16 条消息。';
const TEXT_COMPACT_NOTIFY_TITLE: string = '仅提醒不压缩';
const TEXT_COMPACT_NOTIFY_DESC: string = '只提示上下文压力，不自动写入摘要。';
// 记忆开关(values-zh/strings.xml:589-598 逐字)
const TEXT_RECALL_CORE_TITLE: string = '启用核心记忆';
const TEXT_RECALL_CORE_DESC: string = '允许 AmberAgent 在不同会话之间保存和复用长期知识。';
const TEXT_RECALL_SHORT_TITLE: string = '短期记忆';
const TEXT_RECALL_SHORT_DESC: string = '保存近期任务或活跃项目的简短总结，帮助会话连续。';
const TEXT_RECALL_LONG_TITLE: string = '长期记忆';
const TEXT_RECALL_LONG_DESC: string = '保存稳定偏好、长期关注点、计划和事实信息。';
const TEXT_RECALL_RECENT_TITLE: string = '参考最近会话';
const TEXT_RECALL_RECENT_DESC: string = '允许 AmberAgent 参考最近会话标题，保持任务连续性。';
const TEXT_RECALL_TIME_TITLE: string = '时间提醒';
const TEXT_RECALL_TIME_DESC: string =
  '当对话间隔较长时注入时间提醒，帮助 Agent 理解上下文变化。';

@Entry
@Component
struct MemoryPage {
  @State loaded: boolean = false;
  @State running: boolean = false;
  @State plan: PersistedMemoryDreamPlan | null = null;
  @State candidates: MemoryCandidate[] = [];
  // worker 开关组(VM settings.agentRuntime.memoryWorker 快照字段)
  @State workerEnabled: boolean = true;
  @State dreamMaintenanceEnabled: boolean = true;
  @State dreamModelEnabled: boolean = false;
  @State runOnlyOnIdle: boolean = true;
  // 记忆库三桶(VM :50-58 三 Flow;快照 Promise 适配,P1 观察)
  @State coreMemories: AssistantMemory[] = [];
  @State shortMemories: AssistantMemory[] = [];
  @State longMemories: AssistantMemory[] = [];
  // 事件(VM :62-64 getRecentEventsFlow(100))
  @State recentEvents: MemoryEvent[] = [];
  @State showEventsDialog: boolean = false;
  // 编辑/新增对话框(Page :127-149;id==0 → 新增核心记忆)
  @State editingMemory: AssistantMemory | null = null;
  @State editDraft: string = '';
  // AgentSoul 卡(Page :792-791 AgentSoulCard;D-090)
  @State agentSoul: string = '';
  @State showSoulEditor: boolean = false;
  @State soulDraft: string = '';
  // 上下文管理(Page :528-568 CompactionSubpage;D-091)
  @State compactionEnabled: boolean = true;
  @State compactionNotifyOnly: boolean = false;
  // 记忆开关(Page :359-431 RecallSubpage;D-092)
  @State enableCoreMemory: boolean = true;
  @State enableShortTermMemory: boolean = true;
  @State enableLongTermMemory: boolean = true;
  @State enableRecentChats: boolean = true;
  @State enableTimeReminder: boolean = false;
  @State recallDebug: boolean = false;
  @State recallMaxItems: number = 12;
  @State recallMaxPromptChars: number = 2000;

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    const store = getMemoryDreamPlanStore();
    store.getPendingPlan().then((p: PersistedMemoryDreamPlan | null): void => {
      this.plan = p;
      this.loaded = true;
    });
    loadPendingMemoryCandidates().then((list: MemoryCandidate[]): void => {
      this.candidates = list;
    });
    loadAgentRuntimeSnapshot(getChatKvStore())
      .then((s: AgentRuntimeSnapshot): void => {
        this.workerEnabled = s.memoryWorker.enabled;
        this.dreamMaintenanceEnabled = s.memoryWorker.dreamMaintenanceEnabled;
        this.dreamModelEnabled = s.memoryWorker.dreamModelEnabled;
        this.runOnlyOnIdle = s.memoryWorker.runOnlyOnIdle;
        this.agentSoul = s.agentSoulMarkdown;
        this.compactionEnabled = s.contextCompaction.enabled;
        this.compactionNotifyOnly = s.contextCompaction.notifyOnly;
        this.enableCoreMemory = s.enableCoreMemory;
        this.enableShortTermMemory = s.enableShortTermMemory;
        this.enableLongTermMemory = s.enableLongTermMemory;
        this.enableRecentChats = s.enableRecentChatsReference;
        this.enableTimeReminder = s.enableTimeReminder;
        this.recallDebug = s.memoryRecall.debug;
        this.recallMaxItems = s.memoryRecall.maxItems;
        this.recallMaxPromptChars = s.memoryRecall.maxPromptChars;
      });
    const lib: MemoryLibraryOps = getMemoryLibraryOps();
    lib.listBucket(MEMORY_BUCKET_GLOBAL).then((l: AssistantMemory[]): void => {
      this.coreMemories = l;
    });
    lib.listBucket(MEMORY_BUCKET_SHORT_TERM).then((l: AssistantMemory[]): void => {
      this.shortMemories = l;
    });
    lib.listBucket(MEMORY_BUCKET_LONG_TERM).then((l: AssistantMemory[]): void => {
      this.longMemories = l;
    });
    lib.getRecentEvents(100).then((l: MemoryEvent[]): void => {
      this.recentEvents = l;
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message, duration: 2500 });
  }

  // VM :143-168 planDream — running 门;三态成功消息;失败消息(全角冒号)
  private onPlan(): void {
    if (this.running) return;
    this.running = true;
    loadMemoryDreamPlanOpDeps()
      .then((deps) => planMemoryDreamOp(deps))
      .then((result: MemoryDreamPlanOpResult): void => {
        if (result.kind === 'saved') {
          this.toast(result.replacedPending
            ? MEMORY_DREAM_PLAN_REPLACED_MESSAGE : MEMORY_DREAM_PLAN_SAVED_MESSAGE);
        } else {
          this.toast(MEMORY_DREAM_PLAN_EMPTY_MESSAGE);
        }
        this.refresh();
      })
      .catch((e: Error): void => {
        logMemoryDreamOpFailure('planDream', e);
        this.toast(memoryDreamPlanFailureMessage(e.message));
      })
      .finally((): void => {
        this.running = false;
      });
  }

  // VM :171-198 applyDreamPlan — 两态消息;no_plan 不可达(按钮门)仍忠实映射
  private onApply(): void {
    if (this.running || this.plan === null) return;
    this.running = true;
    const deps = loadMemoryDreamApplyOpDeps();
    applyMemoryDreamPlanOp(deps)
      .then((result: MemoryDreamApplyOpResult): void => {
        if (result.kind === 'applied') {
          this.toast(MEMORY_DREAM_APPLIED_MESSAGE);
        } else if (result.kind === 'dismissed_empty') {
          this.toast(MEMORY_DREAM_APPLY_EMPTY_MESSAGE);
        }
        this.refresh();
      })
      .catch((e: Error): void => {
        logMemoryDreamOpFailure('applyDreamPlan', e);
        this.toast(memoryDreamApplyFailureMessage(e.message));
      })
      .finally((): void => {
        this.running = false;
      });
  }

  // VM :200-204 dismissDreamPlan — 无操作消息
  private onDismiss(): void {
    if (this.running || this.plan === null) return;
    dismissMemoryDreamPlanOp(getMemoryDreamPlanStore())
      .then((): void => this.refresh());
  }

  // VM :95-99 acceptCandidate(无操作消息)
  private onAccept(id: string): void {
    acceptMemoryCandidate(getMemoryCandidateOpsDeps(), id)
      .then((): void => this.refresh())
      .catch((e: Error): void => logMemoryDreamOpFailure('acceptCandidate', e));
  }

  // VM :101-106 ignoreCandidate(无操作消息)
  private onIgnore(id: string): void {
    ignoreMemoryCandidate(getMemoryCandidateOpsDeps(), id)
      .then((): void => this.refresh())
      .catch((e: Error): void => logMemoryDreamOpFailure('ignoreCandidate', e));
  }

  // VM :108-137 ignoreLowConfidenceCandidates — 两态消息
  private onIgnoreLowConfidence(): void {
    ignoreLowConfidenceMemoryCandidates(getMemoryCandidateOpsDeps())
      .then((result: MemoryIgnoreLowConfidenceResult): void => {
        this.toast(result.kind === 'ignored'
          ? memoryLowConfidenceIgnoredMessage(result.count)
          : MEMORY_NO_LOW_CONFIDENCE_MESSAGE);
        this.refresh();
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('ignoreLowConfidence', e));
  }

  // ===== D-089:worker 开关组(VM :68-74 updateAgentRuntime)=====

  // canRunDream 门(Page :156-159,MemoryWorkerDreamGate.isAnyDreamEnabled)
  private canRunDream(): boolean {
    return this.workerEnabled
      && isAnyDreamEnabled({
        enabled: this.workerEnabled,
        dreamMaintenanceEnabled: this.dreamMaintenanceEnabled,
        dreamModelEnabled: this.dreamModelEnabled,
      });
  }

  // Page :447-451 本地整理建议 Switch(写回联动 dreamEnabled=false 在 prefs 写路径)
  private onToggleMaintenance(v: boolean): void {
    updateMemoryWorkerToggles(getChatKvStore(), { dreamMaintenanceEnabled: v })
      .then((s: AgentRuntimeSnapshot): void => {
        this.dreamMaintenanceEnabled = s.memoryWorker.dreamMaintenanceEnabled;
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('toggleMaintenance', e));
  }

  // Page :473-479 LLM 整理建议 Switch
  private onToggleModelDream(v: boolean): void {
    updateMemoryWorkerToggles(getChatKvStore(), { dreamModelEnabled: v })
      .then((s: AgentRuntimeSnapshot): void => {
        this.dreamModelEnabled = s.memoryWorker.dreamModelEnabled;
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('toggleModelDream', e));
  }

  // Page :489-496 仅设备空闲时自动运行 Switch
  private onToggleRunOnlyOnIdle(v: boolean): void {
    updateMemoryWorkerToggles(getChatKvStore(), { runOnlyOnIdle: v })
      .then((s: AgentRuntimeSnapshot): void => {
        this.runOnlyOnIdle = s.memoryWorker.runOnlyOnIdle;
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('toggleRunOnlyOnIdle', e));
  }

  // ===== D-091:上下文管理开关(Page :536-558 两 Switch)=====

  private onToggleCompaction(v: boolean): void {
    updateContextCompactionToggles(getChatKvStore(), { enabled: v })
      .then((s: AgentRuntimeSnapshot): void => {
        this.compactionEnabled = s.contextCompaction.enabled;
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('toggleCompaction', e));
  }

  private onToggleCompactionNotify(v: boolean): void {
    updateContextCompactionToggles(getChatKvStore(), { notifyOnly: v })
      .then((s: AgentRuntimeSnapshot): void => {
        this.compactionNotifyOnly = s.contextCompaction.notifyOnly;
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('toggleCompactionNotify', e));
  }

  // ===== D-092:记忆开关(Page :366-427 六 Switch)=====

  private onRecallToggle(
    key: string, v: boolean,
  ): void {
    // key ∈ core/short/long/recent/time/debug — patch 直拼(Page onCheckedChange 语义)
    const patch: Record<string, boolean> = {};
    if (key === 'core') patch['enableCoreMemory'] = v;
    else if (key === 'short') patch['enableShortTermMemory'] = v;
    else if (key === 'long') patch['enableLongTermMemory'] = v;
    else if (key === 'recent') patch['enableRecentChatsReference'] = v;
    else if (key === 'time') patch['enableTimeReminder'] = v;
    else patch['memoryRecallDebug'] = v;
    updateRecallToggles(getChatKvStore(), patch)
      .then((s: AgentRuntimeSnapshot): void => {
        this.enableCoreMemory = s.enableCoreMemory;
        this.enableShortTermMemory = s.enableShortTermMemory;
        this.enableLongTermMemory = s.enableLongTermMemory;
        this.enableRecentChats = s.enableRecentChatsReference;
        this.enableTimeReminder = s.enableTimeReminder;
        this.recallDebug = s.memoryRecall.debug;
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('toggleRecall', e));
  }

  // ===== D-089:记忆库 CRUD(VM :86-93 + Page :127-149/:254-269)=====
  // 注:Android core 区 onAddMemory 被 info 图标分支遮蔽(Page :1250-1255
  //   if info else if add),新增入口在 Android UI 不可达 — 忠实移植,无新增按钮;
  //   VM addMemory(:77-84)路径经 memory_* 工具已覆盖(D-085c)。

  private startEditMemory(m: AssistantMemory): void {
    this.editingMemory = m;
    this.editDraft = m.content;
  }

  private confirmEditMemory(): void {
    const editing: AssistantMemory | null = this.editingMemory;
    if (editing === null) return;
    this.editingMemory = null;
    // VM :86-89 updateMemory → repository.updateContent(id, content)
    getMemoryLibraryOps().updateContent(editing.id, this.editDraft)
      .then((): void => this.refresh())
      .catch((e: Error): void => logMemoryDreamOpFailure('updateMemory', e));
  }

  private confirmDeleteMemory(m: AssistantMemory): void {
    AlertDialog.show({
      title: '确认删除',
      message: m.content,
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '确认',
        action: (): void => {
          // VM :91-93 deleteMemory → repository.deleteMemory(id)(未命中 no-op)
          getMemoryLibraryOps().deleteMemory(m.id)
            .then((): void => this.refresh())
            .catch((e: Error): void => logMemoryDreamOpFailure('deleteMemory', e));
        },
      },
    });
  }

  private showInfoDialog(title: string, text: string): void {
    AlertDialog.show({
      title,
      message: text,
      primaryButton: {
        value: '确认',
        action: (): void => {},
      },
    });
  }

  // ===== D-090:AgentSoul 卡(Page :792-791;VM :190-194)=====

  private startEditSoul(): void {
    this.soulDraft = this.agentSoul;
    this.showSoulEditor = true;
  }

  // VM :191-193 onSave → updateAgentRuntime { copy(agentSoulMarkdown = value) }
  private confirmEditSoul(): void {
    this.showSoulEditor = false;
    updateAgentSoulMarkdown(getChatKvStore(), this.soulDraft)
      .then((s: AgentRuntimeSnapshot): void => {
        this.agentSoul = s.agentSoulMarkdown;
      })
      .catch((e: Error): void => logMemoryDreamOpFailure('saveSoul', e));
  }

  private lowConfidenceCount(): number {
    return this.candidates.filter(
      (c: MemoryCandidate): boolean =>
        c.confidence < LOW_CONFIDENCE_CANDIDATE_THRESHOLD).length;
  }

  @Builder
  dreamReviewCard() {
    Column({ space: 10 }) {
      Row() {
        Column({ space: 4 }) {
          Text('手动整理审核')
            .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
          Text('手动或后台生成的 diff 都需要确认；后台 Daydream 不会自动应用整理结果。')
            .fontSize(12).fontColor(INK3)
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        if (this.running) {
          LoadingProgress().width(24).height(24).color(ACCENT)
        }
      }
      .width('100%')

      if (this.plan !== null) {
        Text(memoryDreamReviewSummaryText(this.plan.plan))
          .fontSize(12).fontColor(INK)
        Text(`来源：${this.plan.source === 'auto' ? '自动 Daydream' : '手动生成'}`)
          .fontSize(12).fontColor(INK3)
        ForEach(this.plan.plan.notes.slice(0, 4), (note: string): void => {
          Text(`• ${note}`).fontSize(12).fontColor(INK3)
        }, (note: string): string => note)
        ForEach(this.plan.plan.mergeSuggestions.slice(0, 5),
          (s: MemoryMergeSuggestion): void => {
            Text(mergeLineText(s)).fontSize(12).fontColor(INK)
          }, (s: MemoryMergeSuggestion): string =>
            `${s.targetMemoryId}:${s.duplicateMemoryIds.join(',')}`)
        ForEach(this.plan.plan.supersedeSuggestions.slice(0, 5),
          (s: MemorySupersedeSuggestion): void => {
            Text(supersedeLineText(s))
              .fontSize(12).fontColor(INK3)
              .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
          }, (s: MemorySupersedeSuggestion): string =>
            `${s.oldMemoryIds.join(',')}:${s.newContent}`)
      } else {
        Text('还没有手动整理建议。').fontSize(12).fontColor(INK3)
      }

      Row({ space: 8 }) {
        Text('生成建议')
          .fontSize(13)
          .fontColor(this.running ? INK3 : ACCENT)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .onClick((): void => this.onPlan())
        Text('应用建议')
          .fontSize(13)
          .fontColor(!this.running && this.plan !== null
            && memoryDreamPlanHasChanges(this.plan.plan) ? ACCENT : INK3)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .onClick((): void => this.onApply())
        Text('清除')
          .fontSize(13)
          .fontColor(!this.running && this.plan !== null ? ACCENT : INK3)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .onClick((): void => this.onDismiss())
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(16).backgroundColor(SURFACE).borderRadius(12)
  }

  @Builder
  candidateCard(c: MemoryCandidate) {
    Column({ space: 8 }) {
      Text(candidateMetaText(c)).fontSize(12).fontColor(ACCENT)
      Text(c.content)
        .fontSize(14).fontColor(INK)
        .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
      if (c.reason.trim().length > 0) {
        Text(c.reason)
          .fontSize(12).fontColor(INK3)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      Row({ space: 8 }) {
        Text('接受')
          .fontSize(13).fontColor(ACCENT)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .onClick((): void => this.onAccept(c.id))
        Text('忽略')
          .fontSize(13).fontColor(INK2)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .onClick((): void => this.onIgnore(c.id))
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(16).backgroundColor(SURFACE).borderRadius(12)
  }

  // ===== D-089:worker 开关组(Page :445-514)=====

  // ===== D-090:AgentSoul 卡(Page :799-872)— 点击卡片进入编辑;
  //   预览 maxLines 4 + 圆角边框盒,空白 → 空预览文案
  @Builder
  soulCard() {
    Column({ space: 10 }) {
      Text(TEXT_SOUL_TITLE)
        .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
      Text(TEXT_SOUL_DESC)
        .fontSize(12).fontColor(INK3)
      Text(this.agentSoul.trim().length > 0 ? this.agentSoul : TEXT_SOUL_EMPTY_PREVIEW)
        .fontSize(12)
        .fontColor(this.agentSoul.trim().length > 0 ? INK : INK3)
        .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .padding(14)
        .border({ width: 1, color: LINE, radius: 16 })
      Text(TEXT_SOUL_EDIT_HINT)
        .fontSize(12).fontColor(ACCENT)
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(16).backgroundColor(SURFACE).borderRadius(12)
    .onClick((): void => this.startEditSoul())
  }

  // Page :874-908 编辑对话框(TextField minLines 8;保存/取消)
  @Builder
  soulEditorOverlay() {
    Column() {
      Column({ space: 10 }) {
        Text(TEXT_SOUL_EDIT_TITLE)
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
        TextArea({ text: this.soulDraft, placeholder: TEXT_SOUL_LABEL })
          .fontSize(13)
          .height(240)
          .backgroundColor(BG)
          .onChange((v: string): void => {
            this.soulDraft = v;
          })
        Row({ space: 8 }) {
          Blank()
          Text('取消')
            .fontSize(13).fontColor(INK2)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .onClick((): void => {
              this.showSoulEditor = false;
            })
          Text('保存')
            .fontSize(13).fontColor(ACCENT)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .onClick((): void => this.confirmEditSoul())
        }
        .width('100%')
      }
      .width('86%')
      .padding(16).backgroundColor(SURFACE).borderRadius(16)
    }
    .width('100%').height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#66000000')
  }

  // ===== D-089:worker 开关组(Page :445-514)=====

  @Builder
  switchItem(label: string, desc: string, isOn: boolean, onChange: (v: boolean) => void) {
    Row() {
      Column({ space: 4 }) {
        Text(label).fontSize(14).fontColor(INK)
        Text(desc).fontSize(12).fontColor(INK3)
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: isOn })
        .onChange(onChange)
    }
    .width('100%')
  }

  @Builder
  workerToggleCard() {
    Column({ space: 14 }) {
      this.switchItem(
        '本地整理建议',
        '每 24 小时后台生成待审核整理建议；应用前需要确认。核心记忆不会自动修改。' +
          '自动运行需要联网、电量不低；可按设置要求设备空闲。\n' +
          `当前候选 ${this.candidates.length} 条，最近事件 ${this.recentEvents.length} 条。`,
        this.dreamMaintenanceEnabled,
        (v: boolean): void => this.onToggleMaintenance(v))
      this.switchItem(
        'LLM 整理建议',
        '开启后，Daydream 可以让模型提出冲突替换等高级建议；仍需人工确认后才会应用。',
        this.dreamModelEnabled,
        (v: boolean): void => this.onToggleModelDream(v))
      this.switchItem(
        '仅设备空闲时自动运行',
        '关闭后，自动整理不再要求 Android device idle；仍需要联网和电量不低。',
        this.runOnlyOnIdle,
        (v: boolean): void => this.onToggleRunOnlyOnIdle(v))
      if (!this.canRunDream()) {
        Column({ space: 4 }) {
          Text('立即运行不可用').fontSize(14).fontColor(INK)
          Text('请先开启本地整理或 LLM 整理建议。').fontSize(12).fontColor(INK3)
        }
        .width('100%').alignItems(HorizontalAlign.Start)
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(16).backgroundColor(SURFACE).borderRadius(12)
  }

  // ===== D-092:记忆开关卡(Page :359-431 RecallSubpage 六 Switch)=====

  @Builder
  recallCard() {
    Column({ space: 14 }) {
      this.switchItem(TEXT_RECALL_CORE_TITLE, TEXT_RECALL_CORE_DESC,
        this.enableCoreMemory, (v: boolean): void => this.onRecallToggle('core', v))
      this.switchItem(TEXT_RECALL_SHORT_TITLE, TEXT_RECALL_SHORT_DESC,
        this.enableShortTermMemory, (v: boolean): void => this.onRecallToggle('short', v))
      this.switchItem(TEXT_RECALL_LONG_TITLE, TEXT_RECALL_LONG_DESC,
        this.enableLongTermMemory, (v: boolean): void => this.onRecallToggle('long', v))
      this.switchItem(TEXT_RECALL_RECENT_TITLE, TEXT_RECALL_RECENT_DESC,
        this.enableRecentChats, (v: boolean): void => this.onRecallToggle('recent', v))
      this.switchItem(TEXT_RECALL_TIME_TITLE, TEXT_RECALL_TIME_DESC,
        this.enableTimeReminder, (v: boolean): void => this.onRecallToggle('time', v))
      // :414-427 选择性召回 — supporting 动态插值逐字,Switch 绑 memoryRecall.debug
      this.switchItem('选择性召回',
        `每轮最多 ${this.recallMaxItems} 条、${this.recallMaxPromptChars} 字符，不再全量注入。`,
        this.recallDebug, (v: boolean): void => this.onRecallToggle('debug', v))
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(16).backgroundColor(SURFACE).borderRadius(12)
  }

  // ===== D-091:上下文管理卡(Page :528-568 CompactionSubpage)=====

  @Builder
  compactionCard() {
    Column({ space: 14 }) {
      this.switchItem(
        TEXT_COMPACT_TITLE,
        `${TEXT_COMPACT_DESC}\n${TEXT_COMPACT_DEFAULTS}`,
        this.compactionEnabled,
        (v: boolean): void => this.onToggleCompaction(v))
      this.switchItem(
        TEXT_COMPACT_NOTIFY_TITLE,
        TEXT_COMPACT_NOTIFY_DESC,
        this.compactionNotifyOnly,
        (v: boolean): void => this.onToggleCompactionNotify(v))
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(16).backgroundColor(SURFACE).borderRadius(12)
  }

  // ===== D-089:记忆库三桶(Page :1183-1262 RecordsSection/:1264-1316 MemoryItem)=====
  @Builder
  memoryItem(m: AssistantMemory) {
    Row({ space: 4 }) {
      Column({ space: 4 }) {
        Text(`#${m.id}`).fontSize(12).fontColor(INK)
        Text(m.content)
          .fontSize(14).fontColor(INK)
          .maxLines(5).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text('编辑')
        .fontSize(13).fontColor(ACCENT)
        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
        .onClick((): void => this.startEditMemory(m))
      Text('删除')
        .fontSize(13).fontColor(INK2)
        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
        .onClick((): void => this.confirmDeleteMemory(m))
    }
    .width('100%')
    .padding(16).backgroundColor(SURFACE).borderRadius(12)
  }

  // 三桶共用区(Page :622-648 调用点)— info '?' 三桶皆有(core/short/long);
  //   core 的 add 图标被 info 分支遮蔽(:1250-1255),忠实移植无新增按钮
  @Builder
  recordsSection(
    title: string, emptyText: string, memories: AssistantMemory[],
    infoTitle: string, infoText: string,
  ) {
    Column({ space: 8 }) {
      Row() {
        Text(title)
          .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
        Blank()
        Text('?')
          .fontSize(12).fontColor(INK3)
          .textAlign(TextAlign.Center)
          .width(24).height(24)
          .border({ width: 1, color: LINE, radius: 12 })
          .onClick((): void => this.showInfoDialog(infoTitle, infoText))
      }
      .width('100%')
      if (memories.length === 0) {
        Text(emptyText).fontSize(12).fontColor(INK3)
      }
      ForEach(memories, (m: AssistantMemory): void => {
        this.memoryItem(m)
      }, (m: AssistantMemory): string => `${m.id}`)
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  // ===== D-089:维护工具(Page :1138-1154;导入导出 P1 — 目录选择器未落地)=====

  @Builder
  maintenanceCard() {
    Column({ space: 8 }) {
      Text('维护工具')
        .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
      Row() {
        Column({ space: 4 }) {
          Text('事件日志').fontSize(14).fontColor(INK)
          Text(`查看最近 ${this.recentEvents.length} 条记忆后台事件。`)
            .fontSize(12).fontColor(INK3)
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text('›').fontSize(16).fontColor(INK3)
      }
      .width('100%')
      .padding(16).backgroundColor(SURFACE).borderRadius(12)
      .onClick((): void => {
        this.showEventsDialog = true;
      })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  // ===== D-089:事件日志对话框(Page :1105-1135,take(6))=====

  @Builder
  eventsOverlay() {
    Column() {
      Column({ space: 10 }) {
        Text('事件日志')
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
        if (this.recentEvents.length === 0) {
          Text('暂无记忆事件。').fontSize(12).fontColor(INK3)
        } else {
          ForEach(this.recentEvents.slice(0, 6), (e: MemoryEvent): void => {
            Column({ space: 2 }) {
              Text(e.type).fontSize(13).fontColor(INK)
              Text(eventMessageText(e))
                .fontSize(12).fontColor(INK3)
                .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
          }, (e: MemoryEvent): string => e.id)
        }
        Row() {
          Blank()
          Text('确认')
            .fontSize(13).fontColor(ACCENT)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .onClick((): void => {
              this.showEventsDialog = false;
            })
        }
        .width('100%')
      }
      .width('86%')
      .padding(16).backgroundColor(SURFACE).borderRadius(16)
    }
    .width('100%').height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#66000000')
    .onClick((): void => {
      this.showEventsDialog = false;
    })
  }

  // ===== D-089:编辑记忆对话框(Page :127-149)=====

  @Builder
  editOverlay() {
    Column() {
      Column({ space: 10 }) {
        Text(TEXT_EDIT_TITLE)
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
        TextArea({ text: this.editDraft, placeholder: TEXT_CONTENT_LABEL })
          .fontSize(14)
          .height(120)
          .backgroundColor(BG)
          .onChange((v: string): void => {
            this.editDraft = v;
          })
        Row({ space: 8 }) {
          Blank()
          Text('取消')
            .fontSize(13).fontColor(INK2)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .onClick((): void => {
              this.editingMemory = null;
            })
          Text('保存')
            .fontSize(13).fontColor(ACCENT)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .onClick((): void => this.confirmEditMemory())
        }
        .width('100%')
      }
      .width('86%')
      .padding(16).backgroundColor(SURFACE).borderRadius(16)
    }
    .width('100%').height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#66000000')
  }

  build() {
    Stack() {
      Column() {
        Row() {
          Text('‹ 返回')
            .fontSize(14).fontColor(INK3)
            .onClick((): void => { router.back(); })
          Blank()
          Text('记忆')
            .fontSize(20).fontWeight(FontWeight.Bold).fontColor(INK)
          Blank()
          Text('').width(48)
        }
        .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 12 })

        if (!this.loaded) {
          Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
        } else {
          Scroll() {
            Column({ space: 16 }) {
              this.soulCard()
              this.recallCard()
              this.workerToggleCard()
              this.compactionCard()
              this.dreamReviewCard()

              Column({ space: 8 }) {
                Text('候选记忆审核')
                  .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
                if (this.candidates.length === 0) {
                  Text('暂无待审核候选。').fontSize(12).fontColor(INK3)
                } else {
                  if (this.lowConfidenceCount() > 0) {
                    Row() {
                      Blank()
                      Text(`忽略低置信候选（${this.lowConfidenceCount()}）`)
                        .fontSize(13).fontColor(ACCENT)
                        .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                        .onClick((): void => this.onIgnoreLowConfidence())
                    }
                    .width('100%')
                  }
                  ForEach(this.candidates, (c: MemoryCandidate): void => {
                    this.candidateCard(c)
                  }, (c: MemoryCandidate): string => c.id)
                }
              }
              .width('100%').alignItems(HorizontalAlign.Start)

              this.recordsSection(
                '核心记忆', TEXT_CORE_EMPTY, this.coreMemories,
                TEXT_CORE_INFO_TITLE, TEXT_CORE_INFO_BODY)
              this.recordsSection(
                '短期记忆', TEXT_SHORT_EMPTY, this.shortMemories,
                TEXT_SHORT_INFO_TITLE, TEXT_SHORT_INFO_BODY)
              this.recordsSection(
                '长期记忆', TEXT_LONG_EMPTY, this.longMemories,
                TEXT_LONG_INFO_TITLE, TEXT_LONG_INFO_BODY)
              this.maintenanceCard()
            }
            .width('100%')
            .padding({ left: 16, right: 16, bottom: 24 })
          }
          .layoutWeight(1)
        }
      }
      .width('100%').height('100%')

      if (this.editingMemory !== null) {
        this.editOverlay()
      }
      if (this.showSoulEditor) {
        this.soulEditorOverlay()
      }
      if (this.showEventsDialog) {
        this.eventsOverlay()
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
