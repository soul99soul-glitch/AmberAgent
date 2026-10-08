// SubAgentRunSheet — 子代理运行详情 sheet(第三梯队)
// Android 基准: ChatMessageSubAgentStep.kt:144-280 SubAgentRunSheet
//   展示任务目标 + 实时流式文本(从 SubAgentManager.subscribeLiveText 订阅)
//   + 最终 transcript 回退(readSubAgentDisplayTextFromTranscript)
//
// Harmony 后端: SubAgentManager.snapshot(runId) + subscribeLiveText(runId, listener)
//   ChatPage 获取 manager 后注入本组件

import type { UIMessagePartTool } from '@amber/chat-domain';
import { subAgentNameFromTools, subAgentObjectiveFromTools, subAgentStatusFromTools, subAgentStatusLabel, subAgentFinalTextFromTools } from '@amber/chat-domain';
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BODY, META, LABEL } from '../design/tokens.ets';
import { MarkdownText } from './MarkdownText.ets';
import { Icon } from './ui/Icon.ets';
import { SubAgentAvatar } from './SubAgentAvatar.ets';
import { loadSubAgentHistoricalText } from '../platform_impl/SubAgentRunDisplaySupport.ets';

@Component
export struct SubAgentRunSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('loadHistory') runId: string = '';
  @Prop @Watch('loadHistory') tools: UIMessagePartTool[];
  // 父组件注入的实时文本(由 ChatPage subscribeLiveText 更新)
  @Prop liveText: string = '';
  onDismiss: () => void = (): void => {};
  @State private historicalText: string = '';
  @State private historyLoading: boolean = false;
  private sheetAlive: boolean = false;
  private historySequence: number = 0;

  aboutToAppear(): void {
    this.sheetAlive = true;
    this.loadHistory();
  }

  aboutToDisappear(): void {
    this.sheetAlive = false;
    this.historySequence++;
  }

  private loadHistory(): void {
    if (!this.sheetAlive) return;
    const sequence: number = ++this.historySequence;
    const runId: string = this.runId;
    this.historicalText = subAgentFinalTextFromTools(runId, this.tools);
    this.historyLoading = this.liveText.trim().length === 0;
    if (!this.historyLoading) return;
    loadSubAgentHistoricalText(runId, this.tools).then((text: string): void => {
      if (!this.sheetAlive || sequence !== this.historySequence || runId !== this.runId) return;
      this.historicalText = text;
      this.historyLoading = false;
    }).catch((): void => {
      if (!this.sheetAlive || sequence !== this.historySequence || runId !== this.runId) return;
      this.historyLoading = false;
    });
  }

  private displayText(): string {
    return this.liveText.trim().length > 0 ? this.liveText : this.historicalText;
  }

  build(): void {
    Column() {
      Row({ space: 8 }) {
        SubAgentAvatar({
          subagentId: subAgentNameFromTools(this.tools),
          name: subAgentNameFromTools(this.tools),
          avatarSize: 20,
          status: subAgentStatusFromTools(this.tools),
        })
        Column({ space: 2 }) {
          Text(`@${subAgentNameFromTools(this.tools)}`)
            .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(subAgentStatusLabel(subAgentStatusFromTools(this.tools)))
            .fontSize(LABEL).fontColor(INK3)
        }
        .layoutWeight(1).alignItems(HorizontalAlign.Start)
        Row() {
          Icon({ name: 'close', iconSize: 18, color: INK2 })
        }
        .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .onClick((): void => { this.onDismiss(); }).accessibilityText('关闭子代理运行')
      }
      .width('100%')
      .padding({ left: 20, right: 12, top: 16, bottom: 12 })
      Divider().color(LINE).strokeWidth(0.5)

      // 任务和输出一起滚动，长任务不会把输出挤出屏幕。
      Scroll() {
        Column({ space: 16 }) {
          if (subAgentObjectiveFromTools(this.tools).trim().length > 0) {
            Column({ space: 8 }) {
              Text('任务').fontSize(LABEL).fontWeight(FontWeight.Medium).fontColor(INK3)
              Text(subAgentObjectiveFromTools(this.tools))
                .fontSize(META).fontColor(INK2).lineHeight(20)
                .width('100%').copyOption(CopyOptions.LocalDevice)
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(12)
          }
          Column({ space: 8 }) {
            Text('输出').fontSize(LABEL).fontWeight(FontWeight.Medium).fontColor(INK3)
            if (this.displayText().length > 0) {
              MarkdownText({ content: this.displayText(), fontSize: 13, fontColor: INK2 })
            } else {
              Text(this.historyLoading ? '正在读取子代理输出…' : '暂无可显示的子代理输出')
                .fontSize(META).fontColor(INK3)
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .width('100%').alignItems(HorizontalAlign.Start)
        .padding({ left: 20, right: 20, top: 16, bottom: 24 })
      }
      .width('100%').align(Alignment.TopStart)
      .layoutWeight(1).scrollBar(BarState.Auto)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .backgroundColor(SURFACE)
    .borderRadius({ topLeft: 16, topRight: 16 })
  }
}
