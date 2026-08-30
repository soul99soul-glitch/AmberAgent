// ChatMessageSubAgentStep — 子代理任务卡片(G4-S2 升级)
//
// Android 基准: ChatMessageSubAgentStep.kt(393 行)
// 升级: phase 轮播 + AgentToolCallCapsule + 任务目标可折叠
// 降级(跳过): SubAgentRunSheet bottom sheet / SearchImageGallery / auto-scroll /
//   liveTextFlow / transcript 回退链

import type { UIMessagePartTool, JsonValue } from '@amber/chat-domain';
import { toolOutputJson, toolInputAsJson } from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, META, LABEL } from '../design/tokens.ets';
import { SubAgentAvatar } from './SubAgentAvatar.ets';
import { AgentToolCallCapsule, ToolStatus } from './AgentToolCapsule.ets';

const SUBAGENT_TOOL_NAMES: ReadonlySet<string> = new Set<string>([
  'subagent_start', 'subagent_wait', 'subagent_read', 'subagent_cancel',
]);

const DEFAULT_PHASES: string[] = ['分析', '搜索', '整理', '汇总'];

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
  @Prop tools: UIMessagePartTool[];
  @Prop runId: string = '';
  @Prop canOpenRun: boolean = true;
  onOpenRun: (runId: string) => void = (_id: string): void => {};
  @State objectiveExpanded: boolean = false;

  private currentPhase(): string {
    if (this.currentStatus() !== 'running') return DEFAULT_PHASES[DEFAULT_PHASES.length - 1];
    const elapsed: number = Date.now() / 5000;
    return DEFAULT_PHASES[Math.floor(elapsed) % DEFAULT_PHASES.length];
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
              .onClick((): void => { this.objectiveExpanded = !this.objectiveExpanded; })
          }
          .width('100%')

          if (this.objectiveExpanded) {
            Text(this.objective())
              .fontSize(META).fontColor(INK2).lineHeight(18)
              .width('100%').margin({ top: 4 })
          } else {
            Text(this.objective())
              .fontSize(META).fontColor(INK2).lineHeight(18)
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .width('100%').margin({ top: 4 })
          }
        }
        .width('100%')
        .margin({ top: 2, left: 32 })
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 0.5, color: LINE })
      }
    }
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
    Text(`@${this.displayName()} ${statusVerb(this.currentStatus())} · ${this.currentPhase()}`)
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
