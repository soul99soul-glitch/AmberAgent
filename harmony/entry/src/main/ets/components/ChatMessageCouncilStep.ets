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
import { MOTION_OVERLAY, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT, META, LABEL, BODY } from '../design/tokens.ets';
import { AgentToolCallCapsule, ToolStatus } from './AgentToolCapsule.ets';

const COUNCIL_TOOL_NAMES: ReadonlySet<string> = new Set<string>([
  'model_council_start', 'model_council_wait', 'model_council_read',
  'model_council_cancel', 'model_council_make_report',
]);

// F12:阶段只在工具 output 提供结构化字段时显示;缺失 → 不伪造轮播。
const COUNCIL_PHASE_KEYS: string[] = ['phase_label', 'phase', 'stage_label', 'stage', 'round'];

const parseLatestCouncilPhase = (tools: UIMessagePartTool[]): string => {
  for (let i: number = tools.length - 1; i >= 0; i--) {
    const t: UIMessagePartTool = tools[i];
    if (!COUNCIL_TOOL_NAMES.has(t.toolName)) continue;
    if (t.output.length === 0) continue;
    const json: JsonObject = toolOutputJson(t);
    for (const key of COUNCIL_PHASE_KEYS) {
      const v: JsonValue = json[key];
      if (typeof v === 'string' && v.trim().length > 0) return v.trim();
      if (typeof v === 'number') return `第 ${v} 轮`;
    }
  }
  return '';
};

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
    const tool: UIMessagePartTool = tools[i];
    if (!COUNCIL_TOOL_NAMES.has(tool.toolName) || tool.output.length === 0) continue;
    const json: JsonObject = toolOutputJson(tool);
    const rawRun: JsonValue = json['run'];
    const run: JsonObject | null = rawRun !== null && typeof rawRun === 'object' && !Array.isArray(rawRun)
      ? rawRun as JsonObject : null;
    const lists: JsonValue[] = [json['seats'], run === null ? null : run['seats'],
      json['turns'], run === null ? null : run['turns']];
    for (const list of lists) {
      if (!Array.isArray(list)) continue;
      const names: string[] = [];
      const ids: string[] = [];
      for (const item of list) {
        if (item === null || typeof item !== 'object' || Array.isArray(item)) continue;
        const seat: JsonObject = item as JsonObject;
        const id: JsonValue = seat['seat_id'] ?? seat['seatId'];
        const name: JsonValue = seat['name'] ?? seat['seat_name'] ?? seat['seatName'] ?? id;
        if (typeof name !== 'string' || name.length === 0) continue;
        const key: string = typeof id === 'string' && id.length > 0 ? id : name;
        if (ids.indexOf(key) < 0) { ids.push(key); names.push(name); }
      }
      if (names.length > 0) return names;
    }
  }
  return [];
};

@Component
export struct ChatMessageCouncilStep {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop tools: UIMessagePartTool[];
  @Prop runId: string = '';
  @Prop canOpenRun: boolean = true;
  onOpenRun: (runId: string) => void = (_id: string): void => {};
  @State objectiveExpanded: boolean = false;

  // F12:真实阶段(结构化 output 字段);无则空,不按墙钟轮播
  private currentPhase(): string {
    return parseLatestCouncilPhase(this.tools);
  }

  private titleText(): string {
    const status: string = councilStatusVerb(this.currentStatus());
    const phase: string = this.currentPhase();
    return phase.length > 0
      ? `@Council ${status} · ${phase}`
      : `@Council ${status}`;
  }

  build(): void {
    Column() {
      // capsule: @Council + status + phase(仅在真实阶段存在时附加)
      AgentToolCallCapsule({
        toolName: 'model_council',
        title: this.titleText(),
        status: councilStatusToToolStatus(this.currentStatus()),
        showApproval: false,
        canOpen: this.canOpenRun,
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
              .onClick((): void => {
                this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.objectiveExpanded = !this.objectiveExpanded;
                });
              })
          }
          .width('100%')

          if (this.objectiveExpanded) {
            Text(this.objective())
              .fontSize(META).fontColor(INK2)
              .lineHeight(18)
              .width('100%')
              .margin({ top: 4 })
            .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
              .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
          } else {
            Text(this.objective())
              .fontSize(META).fontColor(INK2)
              .maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .lineHeight(18)
              .width('100%')
              .margin({ top: 4 })
            .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
              .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
          }
        }
        .width('100%')
        .margin({ top: 4, left: 32 })
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
          if (typeof obj === 'string' && obj.length > 0) return obj;
        }
        const objective: JsonValue = input['objective'];
        if (typeof objective === 'string') return objective;
      }
    }
    return '';
  }
}
