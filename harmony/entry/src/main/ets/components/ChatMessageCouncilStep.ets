// ChatMessageCouncilStep — Model Council 任务卡片(第三梯队)
// Android 基准: ChatMessageCouncilStep.kt(705 行)— phase 轮播 + 席位 sheet
// Harmony 后端: toolOutputJson(tool_activity.ts) 提取 status
//
// 功能(MVP):
//   ✅ 从 model_council_start/wait/read 输出解析最新 status
//   ✅ 显示 "Model Council" + 状态动词
//   ✅ 点击展开(待接入 run sheet)
//   ❌ 席位副标题/轮次分隔/综合裁决(后续迭代,依赖 council 运行时提取纯函数)
//   ❌ phase 轮播动画(后续迭代)

import type { UIMessagePartTool, JsonValue } from '@amber/chat-domain';
import { toolOutputJson, toolInputAsJson } from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import { INK2, INK3, ACCENT, SURFACE2, META, LABEL } from '../design/tokens.ets';

const COUNCIL_TOOL_NAMES: ReadonlySet<string> = new Set<string>([
  'model_council_start', 'model_council_wait', 'model_council_read',
  'model_council_cancel', 'model_council_make_report',
]);

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
  if (status === 'running') return '正在合议';
  if (status === 'completed') return '已完成';
  if (status === 'failed') return '失败';
  if (status === 'cancelled') return '已取消';
  if (status === 'timed_out') return '超时';
  if (status === 'interrupted') return '已中断';
  return '正在合议';
};

@Component
export struct ChatMessageCouncilStep {
  @Prop tools: UIMessagePartTool[];
  @Prop runId: string = '';
  onOpenRun: (runId: string) => void = (_id: string): void => {};

  build(): void {
    Column() {
      Row() {
        Text('⚖️').fontSize(14)
        Text(`Model Council ${councilStatusVerb(this.currentStatus())}`)
          .fontSize(META).fontColor(INK2)
          .margin({ left: 6 })
          .layoutWeight(1)
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

      // 显示议题(从 start 工具 input 的 task.objective)
      Text(this.objective())
        .fontSize(LABEL).fontColor(INK3)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .padding({ left: 10, right: 10, bottom: 6 })
    }
    .width('100%')
  }

  private currentStatus(): string {
    return parseLatestCouncilStatus(this.tools);
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
