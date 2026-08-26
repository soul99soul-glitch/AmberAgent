// SubAgentRunSheet — 子代理运行详情 sheet(第三梯队)
// Android 基准: ChatMessageSubAgentStep.kt:144-280 SubAgentRunSheet
//   展示任务目标 + 实时流式文本(从 SubAgentManager.subscribeLiveText 订阅)
//   + 最终 transcript 回退(readSubAgentDisplayTextFromTranscript)
//
// Harmony 后端: SubAgentManager.snapshot(runId) + subscribeLiveText(runId, listener)
//   ChatPage 获取 manager 后注入本组件

import type { UIMessagePartTool, JsonValue } from '@amber/chat-domain';
import { toolInputAsJson } from '@amber/chat-domain';
import type { JsonObject } from '@amber/chat-domain';
import { INK, INK2, INK3, ACCENT, SURFACE, SURFACE2, LINE, BODY, META, LABEL } from '../design/tokens.ets';
import { MarkdownText } from './MarkdownText.ets';

@Component
export struct SubAgentRunSheet {
  @Prop runId: string = '';
  @Prop tools: UIMessagePartTool[];
  // 父组件注入的实时文本(由 ChatPage subscribeLiveText 更新)
  @Prop liveText: string = '';
  onDismiss: () => void = (): void => {};

  build(): void {
    Column() {
      // 标题行
      Row() {
        Text('子代理运行').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        Blank()
        Text('关闭').fontSize(META).fontColor(INK3)
          .onClick((): void => { this.onDismiss(); })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      // 任务目标
      Text(this.objective())
        .fontSize(META).fontColor(INK3)
        .width('100%')
        .padding({ left: 16, right: 16, bottom: 8 })

      Divider().color(LINE).strokeWidth(0.5)

      // 实时/最终文本(MarkdownText 原生渲染)
      Scroll() {
        if (this.liveText.length > 0) {
          MarkdownText({ content: this.liveText, fontSize: 13, fontColor: INK2 })
        } else {
          Text('等待子代理输出…').fontSize(LABEL).fontColor(INK3)
            .padding(16)
        }
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
      .padding({ left: 16, right: 16, bottom: 16 })
    }
    .width('100%')
    .height('70%')
    .backgroundColor(SURFACE)
    .borderRadius({ topLeft: 16, topRight: 16 })
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
