// ChatMessageAnnotations — 消息引用注释展示(第三梯队)
// Android 基准: ChatMessageCommon.kt:165(MessageAnnotations + citation 点击)
//   展示引用、Google URL retrieval 状态与原 Search Suggestions。
//   MessageSelectionContainer(ArkUI Text 长按原生支持选择,无需封装)

import type { UIMessageAnnotation, UIMessageUrlContextAnnotation } from '@amber/chat-domain';
import { INK3, ACCENT, LABEL, ERROR } from '../design/tokens.ets';
import { common } from '@kit.AbilityKit';
import { GoogleSearchSuggestions } from './GoogleSearchSuggestions.ets';

class MessageAnnotationColors {
  muted: string = INK3;
  error: string = ERROR;
}

@Component
export struct ChatMessageAnnotations {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop annotations: UIMessageAnnotation[];
  @Prop visible: boolean = true;
  @StorageProp('accentColor') accentColor: string = ACCENT;
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @State colors: MessageAnnotationColors = new MessageAnnotationColors();

  private refreshColors(): void { this.colors = new MessageAnnotationColors(); }

  private openUrl(url: string): void {
    const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    ctx.startAbility({ action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri: url })
      .catch((_error: Error): void => {});
  }

  private urlStatus(status: string): string {
    switch (status) {
      case 'URL_RETRIEVAL_STATUS_SUCCESS': case 'SUCCESS': return '已读取';
      case 'URL_RETRIEVAL_STATUS_ERROR': case 'ERROR': return '读取失败';
      case 'URL_RETRIEVAL_STATUS_PAYWALL': case 'PAYWALL': return '付费访问限制';
      case 'URL_RETRIEVAL_STATUS_UNSAFE': case 'UNSAFE': return '因安全原因未读取';
      default: return status.length > 0 ? `读取状态未确认：${status}` : '读取状态未确认';
    }
  }

  private unsafeUrl(status: string): boolean { return status === 'URL_RETRIEVAL_STATUS_UNSAFE' || status === 'UNSAFE'; }

  private failedRetrieval(status: string): boolean {
    return this.unsafeUrl(status) || status === 'URL_RETRIEVAL_STATUS_ERROR' || status === 'ERROR'
      || status === 'URL_RETRIEVAL_STATUS_PAYWALL' || status === 'PAYWALL';
  }

  @Builder
  urlContextSource(ann: UIMessageUrlContextAnnotation) {
    Column() {
      Text(`网址上下文 · ${this.urlStatus(ann.status)}`)
        .fontSize(LABEL).fontColor(this.failedRetrieval(ann.status) ? this.colors.error : this.colors.muted)
        .width('100%')
      Text(ann.url).fontSize(LABEL)
        .fontColor(this.unsafeUrl(ann.status) ? this.colors.muted : this.accentColor)
        .wordBreak(WordBreak.BREAK_ALL).width('100%').margin({ top: 3 })
    }.width('100%').alignItems(HorizontalAlign.Start)
      .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8, top: 6, bottom: 6 })
      .onClick((): void => { if (!this.unsafeUrl(ann.status)) this.openUrl(ann.url); })
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.visible && this.annotations.length > 0) {
      Column() {
        ForEach(this.annotations, (ann: UIMessageAnnotation, i: number) => {
          if (ann.type === 'url_citation') {
            Row() {
              Text(`[${i + 1}]`).fontSize(LABEL).fontColor(this.colors.muted).width(20)
              Text(ann.title.length > 0 ? ann.title : ann.url)
                .fontSize(LABEL).fontColor(this.accentColor)
                .wordBreak(WordBreak.BREAK_ALL)
                .layoutWeight(1).margin({ left: 4 })
            }
            .width('100%')
            .constraintSize({ minHeight: 44 })
            .padding({ left: 8, right: 8, top: 3, bottom: 3 })
            .onClick((): void => {
              this.openUrl(ann.url);
            })
          } else if (ann.type === 'url_context') {
            this.urlContextSource(ann as UIMessageUrlContextAnnotation)
          } else if (ann.type === 'google_search_suggestions') {
            GoogleSearchSuggestions({ html: ann.html, onOpenUrl: (url: string): void => { this.openUrl(url); } })
          }
        }, (ann: UIMessageAnnotation, i: number): string =>
          `ann-${i}-${ann.type}`)
      }
      .width('100%')
      .padding({ top: 4, bottom: 4 })
    }
  }
}
