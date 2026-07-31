// ChatMessageSubAgentStep — 子代理任务卡片(第三梯队)
// Android 基准: ChatMessageSubAgentStep.kt(393 行)— phase 轮播 + run sheet
// Harmony 后端: subagent_models.ts + subagent_manager.ts(snapshot/subscribeLiveText)
//   + tool_activity.ts(toolOutputJson 提取 status)
//
// 功能(MVP):
//   ✅ 从 subagent_start/wait/read 输出解析最新 status
//   ✅ 显示 @displayName + 状态动词(running→正在工作/completed→已完成/...)
//   ✅ 点击展开 run sheet(显示任务目标 + 实时文本)
//   ❌ phase 轮播动画(后续迭代,需定时器)
//   ✅ SubAgentAvatar(已接入)
//   ❌ 搜索图片画廊(后续迭代)

import type { UIMessagePartTool, JsonValue } from '@amber/chat-domain';
import { toolOutputJson, toolInputAsJson } from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import { INK, INK2, INK3, ACCENT, SURFACE, SURFACE2, LINE, BODY, META, LABEL } from '../design/tokens.ets';
import { SubAgentAvatar } from './SubAgentAvatar.ets';

// 从 subagent_start/wait/read 工具输出解析最新 status
const parseLatestStatus = (tools: UIMessagePartTool[]): string => {
  for (let i: number = tools.length - 1; i >= 0; i--) {
    const t: UIMessagePartTool = tools[i];
    if (t.toolName !== 'subagent_start' && t.toolName !== 'subagent_wait' &&
      t.toolName !== 'subagent_read' && t.toolName !== 'subagent_cancel') continue;
    if (t.output.length === 0) continue;
    const json: JsonObject = toolOutputJson(t);
    const status: JsonValue = json['status'];
    if (typeof status === 'string' && status.length > 0) return status;
  }
  return 'running'; // 无结果 → running
};

// 从 start 工具的 input 解析显示名:
//   优先 custom_subagent.name(动态子代理),其次 subagent_id(roster),回退 'subagent'
const extractSubAgentId = (tools: UIMessagePartTool[]): string => {
  for (const t of tools) {
    if (t.toolName === 'subagent_start') {
      const input: JsonObject = toolInputAsJson(t);
      // 动态子代理:custom_subagent.name
      const custom: JsonValue = input['custom_subagent'];
      if (custom !== null && typeof custom === 'object' && !Array.isArray(custom)) {
        const name: JsonValue = (custom as JsonObject)['name'];
        if (typeof name === 'string' && name.length > 0) return name;
      }
      // roster 子代理:subagent_id
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

@Component
export struct ChatMessageSubAgentStep {
  @Prop tools: UIMessagePartTool[];
  @Prop runId: string = '';
  onOpenRun: (runId: string) => void = (_id: string): void => {};

  build(): void {
    Column() {
      Row() {
        SubAgentAvatar({
          subagentId: this.displayName(),
          name: this.displayName(),
          avatarSize: 20,
          status: this.currentStatus(),
        })
        Text(`@${this.displayName()} ${statusVerb(this.currentStatus())}`)
          .fontSize(META).fontColor(INK2)
          .margin({ left: 6 })
          .layoutWeight(1)
        // 运行中显示加载指示
        if (this.currentStatus() === 'running') {
          Text('…').fontSize(META).fontColor(ACCENT)
        }
      }
      .width('100%')
      .padding({ left: 10, right: 10, top: 8, bottom: 8 })
      .backgroundColor(SURFACE2)
      .borderRadius(8)
      .margin({ bottom: 4 })
      .onClick((): void => { this.onOpenRun(this.runId); })

      // 显示任务目标(从 start 工具 input 的 task.objective)
      Text(this.objective())
        .fontSize(LABEL).fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .padding({ left: 10, right: 10, bottom: 6 })
    }
    .width('100%')
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
