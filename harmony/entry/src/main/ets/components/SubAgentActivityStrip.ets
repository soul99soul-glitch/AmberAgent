// SubAgentActivityStrip — 子代理跨会话活动条(C5-4;iOS ChatSubAgentActivityBar 对齐)
//
// 数据源:SubAgentManager.listActive()(内存态运行表);1s 轮询刷新(计时语义),
//   不往 domain 加响应式管道。点按 → 来源会话(parentConversationId)。
// 无运行中子代理时自隐(空渲染);D5-2:结束后保留 4s 真实终态再自隐。

import { router } from '@kit.ArkUI';
import type { SubAgentManager, SubAgentRun, SubAgentActivityPresentation } from '@amber/chat-domain';
import { subAgentActivityPresentation } from '@amber/chat-domain';
import { getSubAgentManager } from '../di/AppContainer.ets';
import { ACCENT, INK2, INK3, LINE, META, SURFACE, SUCCESS_FG, ERROR, WARNING_FG } from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

@Component
export struct SubAgentActivityStrip {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State runs: SubAgentRun[] = [];
  @State nowMs: number = Date.now();
  // D5-2:结束时刻(>0 时 runs 保存真实终态,4s 后清空)
  @State completedAtMs: number = 0;
  private timer: number = -1;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.refresh();
    this.timer = setInterval((): void => { this.refresh(); }, 1000);
  }

  aboutToDisappear(): void {
    this.alive = false;
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  private refresh(): void {
    getSubAgentManager().then((mgr: SubAgentManager): void => {
      if (!this.alive) return;
      const next: SubAgentRun[] = mgr.listActive();
      if (next.length > 0) {
        this.completedAtMs = 0;
        this.runs = next;
      } else if (this.runs.length > 0 && this.completedAtMs === 0) {
        const ended: SubAgentRun[] = [];
        for (const previous of this.runs) {
          const snapshot: SubAgentRun | null = mgr.snapshot(previous.runId);
          if (snapshot !== null) ended.push(snapshot);
        }
        this.runs = ended;
        this.completedAtMs = ended.length > 0 ? Date.now() : 0;
      } else if (this.completedAtMs > 0 && Date.now() - this.completedAtMs >= 4000) {
        this.runs = [];
        this.completedAtMs = 0;
      }
      this.nowMs = Date.now();
    }).catch((): void => {});
  }

  private elapsed(run: SubAgentRun): string {
    const s: number = Math.max(0, Math.floor((this.nowMs - run.startedAtMs) / 1000));
    if (s < 60) return `${s}s`;
    return `${Math.floor(s / 60)}m${s % 60}s`;
  }

  private topLabel(run: SubAgentRun): string {
    const objective: string = run.task.objective.trim().replace(/\s+/g, ' ');
    const head: string = objective.length > 24 ? `${objective.substring(0, 24)}…` : objective;
    const name: string = run.definition.name.trim();
    return name.length > 0 ? `${name} · ${head}` : head;
  }

  private terminalPresentation(): SubAgentActivityPresentation {
    return subAgentActivityPresentation(this.runs);
  }

  private terminalColor(): string {
    const tone: string = this.terminalPresentation().tone;
    return tone === 'success' ? SUCCESS_FG : (tone === 'error' ? ERROR : WARNING_FG);
  }

  private openSource(run: SubAgentRun): void {
    if (run.parentConversationId.length === 0) return;
    const params: Record<string, string> = {};
    params['conversationId'] = run.parentConversationId;
    router.pushUrl({ url: 'pages/ChatPage', params: params });
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.runs.length > 0) {
      Row() {
        if (this.completedAtMs > 0) {
          Icon({ name: this.terminalPresentation().icon, iconSize: 12, color: this.terminalColor() })
        } else {
          // 呼吸点(1s 轮询自然形成的脉动,不加动画框架)
          Column()
            .width(8).height(8).borderRadius(4)
            .backgroundColor(this.accentColorStore)
            .opacity(this.nowMs % 2000 < 1000 ? 1 : 0.35)
        }
        Text(this.completedAtMs > 0
          ? (this.runs.length === 1
            ? `${this.terminalPresentation().label} · ${this.topLabel(this.runs[0])}`
            : this.terminalPresentation().label)
          : (this.runs.length === 1
            ? `子代理运行中 · ${this.topLabel(this.runs[0])} · ${this.elapsed(this.runs[0])}`
            : `子代理运行中 ${this.runs.length} 个 · ${this.elapsed(this.runs[0])}`))
          .fontSize(META).fontColor(INK2)
          .margin({ left: 8 })
          .layoutWeight(1)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Icon({ name: 'chevron_right', iconSize: 14, color: INK3 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 8, bottom: 8 })
      .margin({ left: 20, right: 20, bottom: 2 })
      .backgroundColor(SURFACE)
      .borderRadius(10)
      .border({ width: 1, color: LINE })
      .onClick((): void => { this.openSource(this.runs[0]); })
      .accessibilityText('子代理运行状态,点击查看来源会话')
    }
  }
}
