// MemoryPage — 记忆管理页首片(D-088):dream 手动整理审核 + 候选记忆审核
// Android 锚点:
//   SettingAgentMemoryPage.kt:993-1080(DreamReviewSection:标题/说明/摘要行/
//     来源行/notes take(4)/merge take(5)/supersede take(5) maxLines 3/空文案/
//     三按钮 enabled 门)
//   SettingAgentMemoryPage.kt:884-906(候选卡:meta 行 %.2f/建议人工审核后缀/
//     content maxLines 4/reason maxLines 2/接受+忽略)
//   SettingAgentMemoryPage.kt:912-991(候选区:SectionLabel/空文案/忽略低置信
//     按钮右对齐仅 lowConfidenceCount>0)
//   SettingAgentMemoryVM.kt:95-204(操作编排 → chat-domain memory_dream_ops)
// 裁剪(P1 登记):worker 开关组(本地/LLM 整理 Switch)、记忆库列表、事件流、
//   导出导入按钮、Agent Soul 卡 = 设置面后续切片;操作反馈 snackbar →
//   promptAction toast(瞬时语义一致)。
import { promptAction, router } from '@kit.ArkUI';
import type {
  MemoryCandidate, MemoryDreamPlanOpResult, MemoryDreamApplyOpResult,
  MemoryIgnoreLowConfidenceResult, MemoryMergeSuggestion, MemorySupersedeSuggestion,
  PersistedMemoryDreamPlan,
} from '@amber/chat-domain';
import {
  acceptMemoryCandidate, applyMemoryDreamPlanOp, dismissMemoryDreamPlanOp,
  ignoreLowConfidenceMemoryCandidates, ignoreMemoryCandidate,
  memoryDreamApplyFailureMessage, memoryDreamPlanFailureMessage,
  memoryDreamReviewSummaryText, memoryDreamPlanHasChanges,
  memoryLowConfidenceIgnoredMessage, planMemoryDreamOp,
  LOW_CONFIDENCE_CANDIDATE_THRESHOLD,
  MEMORY_DREAM_APPLIED_MESSAGE, MEMORY_DREAM_APPLY_EMPTY_MESSAGE,
  MEMORY_DREAM_PLAN_EMPTY_MESSAGE, MEMORY_DREAM_PLAN_REPLACED_MESSAGE,
  MEMORY_DREAM_PLAN_SAVED_MESSAGE, MEMORY_NO_LOW_CONFIDENCE_MESSAGE,
} from '@amber/chat-domain';
import { getMemoryDreamPlanStore } from '../platform_impl/MemoryStore.ets';
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

@Entry
@Component
struct MemoryPage {
  @State loaded: boolean = false;
  @State running: boolean = false;
  @State plan: PersistedMemoryDreamPlan | null = null;
  @State candidates: MemoryCandidate[] = [];

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

  build() {
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
          }
          .width('100%')
          .padding({ left: 16, right: 16, bottom: 24 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
