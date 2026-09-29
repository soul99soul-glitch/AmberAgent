// ChatMessageSubAgentStep — 子代理任务卡片(G4-S2 升级)
//
// Android 基准: ChatMessageSubAgentStep.kt(393 行)
// 升级: phase 轮播 + AgentToolCallCapsule + 任务目标可折叠
// 降级(跳过): SubAgentRunSheet bottom sheet / SearchImageGallery / auto-scroll /
//   liveTextFlow / transcript 回退链

import type { UIMessagePartTool, JsonValue } from '@amber/chat-domain';
import { toolOutputJson, toolInputAsJson } from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import { MOTION_OVERLAY, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, META, LABEL } from '../design/tokens.ets';
import { SubAgentAvatar } from './SubAgentAvatar.ets';
import { AgentToolCallCapsule, ToolStatus } from './AgentToolCapsule.ets';

const SUBAGENT_TOOL_NAMES: ReadonlySet<string> = new Set<string>([
  'subagent_start', 'subagent_wait', 'subagent_read', 'subagent_cancel',
]);

// F12:阶段只在工具 output 提供结构化字段时显示;缺失 → 不伪造,仅显示运行状态。
//   取自 output 的 phase/phase_label/stage/stage_label(字符串)。
const SUBAGENT_PHASE_KEYS: string[] = ['phase_label', 'phase', 'stage_label', 'stage'];

const parseLatestPhase = (tools: UIMessagePartTool[]): string => {
  for (let i: number = tools.length - 1; i >= 0; i--) {
    const t: UIMessagePartTool = tools[i];
    if (!SUBAGENT_TOOL_NAMES.has(t.toolName)) continue;
    if (t.output.length === 0) continue;
    const json: JsonObject = toolOutputJson(t);
    for (const key of SUBAGENT_PHASE_KEYS) {
      const v: JsonValue = json[key];
      if (typeof v === 'string' && v.trim().length > 0) return v.trim();
    }
  }
  return '';
};

const parseLatestStatus = (tools: UIMessagePartTool[]): string => {
  for (let i: number = tools.length - 1; i >= 0; i--) {
    const t: UIMessagePartTool = tools[i];
    if (!SUBAGENT_TOOL_NAMES.has(t.toolName)) continue;
    if (t.output.length === 0) continue;
    const json: JsonObject = toolOutputJson(t);
    const status: JsonValue = json['status'];
    if (typeof status === 'string' && status.length > 0) return status;
  }
  return 'running';
};

const extractSubAgentId = (tools: UIMessagePartTool[]): string => {
  for (const t of tools) {
    if (t.toolName === 'subagent_start') {
      const input: JsonObject = toolInputAsJson(t);
      const custom: JsonValue = input['custom_subagent'];
      if (custom !== null && typeof custom === 'object' && !Array.isArray(custom)) {
        const name: JsonValue = (custom as JsonObject)['name'];
        if (typeof name === 'string' && name.length > 0) return name;
      }
      const id: JsonValue = input['subagent_id'];
      if (typeof id === 'string' && id.length > 0) return id;
    }
  }
  return 'subagent';
};

const statusVerb = (status: string): string => {
  if (status === 'running') return '正在工作';
  if (status === 'completed') return '已完成';
  if (status === 'failed') return '失败';
  if (status === 'cancelled') return '已取消';
  if (status === 'timed_out') return '超时';
  if (status === 'approval_required') return '等待审批';
  if (status === 'interrupted') return '已中断';
  return '正在工作';
};

const statusToToolStatus = (status: string): ToolStatus => {
  if (status === 'running') return ToolStatus.Running;
  if (status === 'approval_required') return ToolStatus.WaitingApproval;
  if (status === 'completed') return ToolStatus.Succeeded;
  if (status === 'failed' || status === 'timed_out' || status === 'interrupted') return ToolStatus.Failed;
  if (status === 'cancelled') return ToolStatus.Cancelled;
  return ToolStatus.Running;
};

@Component
export struct ChatMessageSubAgentStep {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop tools: UIMessagePartTool[];
  @Prop runId: string = '';
  @Prop canOpenRun: boolean = true;
  onOpenRun: (runId: string) => void = (_id: string): void => {};
  @State objectiveExpanded: boolean = false;

  // F12:真实阶段(结构化 output 字段);无则空,不按墙钟伪造轮播
  private currentPhase(): string {
    return parseLatestPhase(this.tools);
  }

  private headerText(): string {
    const status: string = statusVerb(this.currentStatus());
    const phase: string = this.currentPhase();
    return phase.length > 0
      ? `@${this.displayName()} ${status} · ${phase}`
      : `@${this.displayName()} ${status}`;
  }

  build(): void {
    Column() {
      // capsule: avatar + @name + status + phase
      if (this.canOpenRun) {
        Row() { this.HeaderContent(); }
          .padding({ left: 7, right: 7, top: 3, bottom: 3 })
          .backgroundColor(SURFACE2)
          .borderRadius(50)
          .border({ width: 1, color: LINE })
          .alignItems(VerticalAlign.Center)
          .onClick((): void => { this.onOpenRun(this.runId); })
          .margin({ bottom: 4 })
      } else {
        Row() { this.HeaderContent(); }
          .padding({ left: 7, right: 7, top: 3, bottom: 3 })
          .backgroundColor(SURFACE2)
          .borderRadius(50)
          .border({ width: 1, color: LINE })
          .alignItems(VerticalAlign.Center)
          .margin({ bottom: 4 })
      }

      // 任务目标(可折叠)
      if (this.objective().length > 0) {
        Column() {
          Row() {
            Text('任务').fontSize(LABEL).fontColor(INK3)
            Text(this.objectiveExpanded ? '收起' : '展开')
              .fontSize(LABEL).fontColor(ACCENT)
              .margin({ left: 8 })
              .onClick((): void => {
                this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.objectiveExpanded = !this.objectiveExpanded;
                });
              })
          }
          .width('100%')

          if (this.objectiveExpanded) {
            Text(this.objective())
              .fontSize(META).fontColor(INK2).lineHeight(18)
              .width('100%').margin({ top: 4 })
            .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
              .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
          } else {
            Text(this.objective())
              .fontSize(META).fontColor(INK2).lineHeight(18)
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%').margin({ top: 4 })
            .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
              .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
          }
        }
        .width('100%')
        .margin({ top: 2, left: 32 })
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 0.5, color: LINE })
        .clip(true)
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  private HeaderContent(): void {
    SubAgentAvatar({
      subagentId: this.displayName(),
      name: this.displayName(),
      avatarSize: 16,
      status: this.currentStatus(),
    })
    Text(this.headerText())
      .fontSize(META)
      .fontColor(INK2)
      .fontWeight(FontWeight.Medium)
      .fontFamily('JetBrainsMono')
      .margin({ left: 6 })
      .maxLines(1)
      .textOverflow({ overflow: TextOverflow.Ellipsis })
      .layoutWeight(1)

    if (this.currentStatus() === 'running') {
      Text('●')
        .fontSize(10)
        .fontColor(ACCENT)
    }
  }

  private displayName(): string {
    return extractSubAgentId(this.tools);
  }

  private currentStatus(): string {
    return parseLatestStatus(this.tools);
  }

  private objective(): string {
    for (const t of this.tools) {
      if (t.toolName === 'subagent_start') {
        const input: JsonObject = toolInputAsJson(t);
        const task: JsonValue = input['task'];
        if (task !== null && typeof task === 'object' && !Array.isArray(task)) {
          const obj: JsonValue = (task as JsonObject)['objective'];
          if (typeof obj === 'string') return obj;
        }
      }
    }
    return '';
  }
}
