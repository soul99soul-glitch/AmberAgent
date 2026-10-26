import type { NovelProject, NovelChapter } from '@amber/deepread-domain';
import { novelChapterOrdinal } from '@amber/deepread-domain';
import { NovelManuscriptSession } from '../../platform_impl/novel/NovelManuscriptSession.ets';
import { ACCENT, ACCENT_INK, ERROR, INK, INK2, INK3, INK4, LINE } from '../../design/tokens.ets';
import { Icon } from '../ui/Icon.ets';
import { StudioInkStamp } from '../studio/StudioInkStamp.ets';

@Component
export struct NovelManuscriptView {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @ObjectLink session: NovelManuscriptSession;
  @Prop project: NovelProject | null = null;
  @Prop collectionStamp: number = 0;
  @Prop stampActive: boolean = false;
  @Prop showStamp: boolean = false;
  @Prop consistencyMessage: string = '';
  onTools: () => void = (): void => {};
  onOpenChapter: (chapterId: string) => void = (_chapterId: string): void => {};
  @BuilderParam polishContent: () => void = this.emptyPolishContent;

  @Builder
  private emptyPolishContent(): void {}

  build() {
    Column() {
      if (this.session.collectionFeedback.length > 0) {
        Text(this.session.collectionFeedback).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
          .padding({ left: 16, right: 16, top: 10, bottom: 10 })
      }
      // 目录(iOS NovelChapterManagementView):标题 + 共 N 章;次要操作收「更多」
      Row() {
        Text('目录').fontSize(15).fontWeight(FontWeight.Bold).fontColor(INK2)
        if (this.project !== null) {
          Text(`共 ${this.project.chapters.length} 章`).fontSize(12).fontColor(INK3)
            .margin({ left: 8 })
        }
        if (this.showStamp) {
          StudioInkStamp({ word: '收稿', trigger: this.collectionStamp, stampSize: 32,
            active: this.stampActive })
            .margin({ left: 12, right: 8 })
        }
        Blank()
        Text('更多 ▾').fontSize(12).fontColor(INK3)
          .constraintSize({ minWidth: 44 }).height(44)
          .padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
          .onClick((): void => { this.onTools(); })
        Text('新建').fontSize(13).fontColor(ACCENT_INK)
          .constraintSize({ minWidth: 64 }).height(36)
          .padding({ left: 14, right: 14 }).textAlign(TextAlign.Center)
          .backgroundColor(ACCENT).borderRadius(18)
          .onClick(() => { this.session.openNewChapter(); })
      }
      .width('100%').padding({ left: 20, right: 20, top: 12, bottom: 10 })
      .alignItems(VerticalAlign.Center)

      if (this.consistencyMessage.length > 0) {
        Text(this.consistencyMessage).fontSize(12).fontColor(INK3).lineHeight(18)
          .width('100%').padding({ left: 20, right: 20, bottom: 10 })
      }

      this.polishContent()

      if (this.project !== null && this.project.chapters.length === 0) {
        Text('在创作对话中收录的正文会按章节出现在这里。')
          .fontSize(13).fontColor(INK3).padding({ left: 20, right: 20, top: 20 })
      } else {
        List({ space: 0 }) {
          if (this.project !== null) {
            ForEach(this.project.chapters, (c: NovelChapter, idx: number) => {
              ListItem() {
                Row() {
                  Text(novelChapterOrdinal(c, idx + 1).toString().padStart(2, '0'))
                    .fontSize(13).fontColor(INK4).width(30)
                  Column() {
                    Text((c.discarded ? '[已废弃] ' : '') + (c.title.length > 0 ? c.title : '未命名章节'))
                      .fontSize(15).fontColor(c.discarded ? INK4 : INK).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(`${this.session.charCount(c.content)} 字`).fontSize(11).fontColor(INK3).margin({ top: 4 })
                    if (c.suggestionWarning !== undefined && c.suggestionWarning !== null && c.suggestionWarning.length > 0) {
                      Text('正文已保存 · 资料分析未完成').fontSize(11).fontColor(ERROR).lineHeight(16).margin({ top: 4 })
                    }
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)
                  Icon({ name: 'chevron_right', iconSize: 16, color: INK4 })
                }
                .width('100%').padding({ left: 20, right: 20, top: 14, bottom: 14 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .onClick(() => { this.onOpenChapter(c.id); })
              }
            }, (c: NovelChapter) => c.id)
          }
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%')
  }
}
