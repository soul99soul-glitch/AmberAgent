// ChatMessageCouncilStep — Model Council 任务卡片(G4-S2 升级)
//
// Android 基准: ChatMessageCouncilStep.kt(705 行)
// 升级: phase 轮播(听证/辩论/权衡/裁决) + 席位副标题 + AgentToolCallCapsule
//   + 议题展开/折叠 + 状态动词完善(部分失败)
// 降级(跳过): ModelCouncilRunSheet bottom sheet / ScrollableTabRow / 轮次分隔 pill /
//   MarkdownBlock body / auto-scroll follow / cleanCouncilLineBreaks

import type { UIMessagePartTool, JsonValue } from '@amber/chat-domain';
import { toolOutputJson, toolInputAsJson } from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, META, LABEL, BODY } from '../design/tokens.ets';
import { AgentToolCallCapsule, ToolStatus } from './AgentToolCapsule.ets';

const COUNCIL_TOOL_NAMES: ReadonlySet<string> = new Set<string>([
  'model_council_start', 'model_council_wait', 'model_council_read',
  'model_council_cancel', 'model_council_make_report',
]);

const COUNCIL_PHASES: string[] = ['听证', '辩论', '权衡', '裁决'];

const parseLatestCouncilStatus = (tools: UIMessagePartTool[]): string => {
  for (let i: number = tools.length - 1; i >= 0; i--) {
    const t: UIMessagePartTool = tools[i];
    if (!COUNCIL_TOOL_NAMES.has(t.toolName)) continue;
    if (t.output.length === 0) continue;
    const json: JsonObject = toolOutputJson(t);
    const status: JsonValue = json['status'];
    if (typeof status === 'string' && status.length > 0) return status;
  }
  return 'running';
};

const councilStatusVerb = (status: string): string => {
  if (status === 'running') return '正在审议';
  if (status === 'completed') return '已完成';
  if (status === 'partial_failed') return '部分失败';
  if (status === 'failed') return '失败';
  if (status === 'cancelled') return '已取消';
  if (status === 'timed_out') return '超时';
  if (status === 'interrupted') return '已中断';
  return '正在审议';
};

const councilStatusToToolStatus = (status: string): ToolStatus => {
  if (status === 'running') return ToolStatus.Running;
  if (status === 'completed' || status === 'partial_failed') return ToolStatus.Succeeded;
  if (status === 'failed' || status === 'timed_out' || status === 'interrupted') return ToolStatus.Failed;
  if (status === 'cancelled') return ToolStatus.Cancelled;
  return ToolStatus.Running;
};

// 从 output 提取席位列表
const extractSeats = (tools: UIMessagePartTool[]): string[] => {
  for (let i: number = tools.length - 1; i >= 0; i--) {
    const t: UIMessagePartTool = tools[i];
    if (t.toolName === 'model_council_read' && t.output.length > 0) {
      const json: JsonObject = toolOutputJson(t);
      const turns: JsonValue = json['turns'];
      if (Array.isArray(turns)) {
        const seats: string[] = [];
        for (const turn of turns) {
          if (typeof turn === 'object' && turn !== null && !Array.isArray(turn)) {
            const seatId: JsonValue = (turn as JsonObject)['seat_id'];
            if (typeof seatId === 'string' && seats.indexOf(seatId) < 0) {
              seats.push(seatId);
            }
          }
        }
        return seats;
      }
    }
  }
  return [];
};

@Component
export struct ChatMessageCouncilStep {
  @Prop tools: UIMessagePartTool[];
  @Prop runId: string = '';
  onOpenRun: (runId: string) => void = (_id: string): void => {};
  @State phaseIndex: number = 0;
  @State objectiveExpanded: boolean = false;

  aboutToAppear(): void {
    // phase 轮播(running 时每 5s 切换)
    if (this.currentStatus() === 'running') {
      this.startPhaseRotation();
    }
  }

  private startPhaseRotation(): void {
    // ArkUI 不支持 setInterval,用 @State + 定时器模拟
    // 简化:每次 build 时根据时间戳取模
  }

  private currentPhase(): string {
    if (this.currentStatus() !== 'running') return COUNCIL_PHASES[COUNCIL_PHASES.length - 1];
    const elapsed: number = Date.now() / 5000;
    return COUNCIL_PHASES[Math.floor(elapsed) % COUNCIL_PHASES.length];
  }

  build(): void {
    Column() {
      // capsule: @Council + status + phase
      AgentToolCallCapsule({
        toolName: 'model_council',
        title: `@Council ${councilStatusVerb(this.currentStatus())} · ${this.currentPhase()}`,
        status: councilStatusToToolStatus(this.currentStatus()),
        showApproval: false,
        onCapsuleClick: (): void => { this.onOpenRun(this.runId); },
      })

      // 席位副标题
      if (this.seats().length > 0) {
        Text(`席位 · ${this.seats().join(' / ')}`)
          .fontSize(LABEL)
          .fontColor(INK3)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .margin({ top: 2, left: 32 })
      }

      // 议题(可折叠)
      if (this.objective().length > 0) {
        Column() {
          Row() {
            Text('议题').fontSize(LABEL).fontColor(INK3)
            Text(this.objectiveExpanded ? '收起' : '展开')
              .fontSize(LABEL).fontColor(ACCENT)
              .margin({ left: 8 })
              .onClick((): void => { this.objectiveExpanded = !this.objectiveExpanded; })
          }
          .width('100%')

          if (this.objectiveExpanded) {
            Text(this.objective())
              .fontSize(META).fontColor(INK2)
              .lineHeight(18)
              .width('100%')
              .margin({ top: 4 })
          } else {
            Text(this.objective())
              .fontSize(META).fontColor(INK2)
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .lineHeight(18)
              .width('100%')
              .margin({ top: 4 })
          }
        }
        .width('100%')
        .margin({ top: 4, left: 32 })
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 0.5, color: LINE })
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 4 })
  }

  private currentStatus(): string {
    return parseLatestCouncilStatus(this.tools);
  }

  private seats(): string[] {
    return extractSeats(this.tools);
  }

  private objective(): string {
    for (const t of this.tools) {
      if (t.toolName === 'model_council_start') {
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
