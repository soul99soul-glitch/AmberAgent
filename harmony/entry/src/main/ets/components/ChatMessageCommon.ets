// ChatMessageAnnotations — 消息引用注释展示(第三梯队)
// Android 基准: ChatMessageCommon.kt:165(MessageAnnotations + citation 点击)
//   展示 UIMessageAnnotation.url_citation 列表,可点击打开 URL
//   MessageSelectionContainer(ArkUI Text 长按原生支持选择,无需封装)

import type { UIMessageAnnotation } from '@amber/chat-domain';
import { INK2, INK3, ACCENT, SURFACE2, LINE, META, LABEL } from '../design/tokens.ets';

@Component
export struct ChatMessageAnnotations {
  @Prop annotations: UIMessageAnnotation[];
  @Prop visible: boolean = true;

  build(): void {
    if (this.visible && this.annotations.length > 0) {
      Column() {
        ForEach(this.annotations, (ann: UIMessageAnnotation, i: number) => {
          if (ann.type === 'url_citation') {
            Row() {
              Text(`[${i + 1}]`).fontSize(LABEL).fontColor(INK3).width(20)
              Text(ann.title.length > 0 ? ann.title : ann.url)
                .fontSize(LABEL).fontColor(ACCENT)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                .layoutWeight(1).margin({ left: 4 })
            }
            .width('100%')
            .padding({ left: 8, right: 8, top: 3, bottom: 3 })
            .onClick((): void => {
              // 打开 URL(HarmonyOS 用系统浏览器)
              try {
                const context: Context = getContext(this) as Context;
                context.startAbility({
                  bundleName: 'com.huawei.hmos.browser',
                  abilityName: 'com.huawei.hmos.browser.MainAbility',
                  uri: ann.url,
                });
              } catch (_e) {
                // 浏览器不可用时静默降级(不伪成功)
              }
            })
          }
        }, (ann: UIMessageAnnotation, i: number): string =>
          `ann-${i}-${ann.type}`)
      }
      .width('100%')
      .padding({ top: 4, bottom: 4 })
    }
  }
}
