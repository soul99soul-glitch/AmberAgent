// DeepReadArticlePage — 文章渲染 + 进度页(对照设计 §7.1/§7.4)
// ArkTS 兼容。接收 topicId + title params,渲染 DeepReadOutput 摘要。

import { router } from '@kit.ArkUI';
import type { DeepReadOutput } from '@amber/deepread-domain';
import { STAGE_ORDER, STAGE_LABELS, statusOf, displayHeroImageUrl } from '@amber/deepread-domain';

interface ArticleParams {
  topicId: string;
  title: string;
}

@Entry
@Component
struct DeepReadArticlePage {
  @State title: string = '';
  @State articleText: string = '等待数据...';

  aboutToAppear(): void {
    const params: ArticleParams = router.getParams() as ArticleParams;
    this.title = params.title;
  }

  build(): void {
    Column() {
      Row() {
        Button('返回').fontSize(14).onClick(() => router.back())
        Text(this.title).fontSize(18).fontWeight(FontWeight.Medium).margin({ left: 8 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 12, right: 12 })

      Scroll() {
        Text(this.articleText).fontSize(14).lineHeight(22).padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%')
  }
}
