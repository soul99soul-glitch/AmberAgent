import type { NovelProject, NovelChapter } from '@amber/deepread-domain';
import { novelChapterOrdinal } from '@amber/deepread-domain';
import { NovelManuscriptSession } from '../../platform_impl/novel/NovelManuscriptSession.ets';
import { ACCENT, ACCENT_INK, ERROR, INK, INK2, INK3, INK4, LINE, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2 } from '../../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';
import { Seg, CardGroup } from '../PageTemplate.ets';

@Component
export struct NovelManuscriptSheets {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @ObjectLink session: NovelManuscriptSession;
  @Prop project: NovelProject | null = null;
  @Prop busy: boolean = false;
  @Prop errorMsg: string = '';
  @Prop navBarHeight: number = 0;

  @Builder
  private SheetLabel(title: string): void {
    Text(title).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
      .width('100%').margin({ bottom: 8 })
  }

  build() {
    Stack() {
      if (this.session.collectMessageId.length > 0) { this.collectSheet() }
      if (this.session.chapterEditorOpen) { this.chapterEditor() }
    }.width('100%').height('100%')
  }

  @Builder
  collectSheet(): void {
    Column() {
      Blank().onClick((): void => { this.session.confirmDiscardCollect(); })
      Column() {
        Row() {
          Text('收录正文').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .layoutWeight(1)
          Text(`${this.session.collectSelectedCount()}/${this.session.collectParas.length} 段`)
            .fontSize(12).fontColor(INK3)
        }.width('100%').alignItems(VerticalAlign.Center).margin({ bottom: 10 })
        Scroll() {
          Column() {
            Row() {
              Text('选择段落').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .layoutWeight(1)
              Text('全选').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
                .constraintSize({ minHeight: 36 }).padding({ left: 10 })
                .onClick((): void => {
                  this.session.selectCollectParagraphs(true);
                })
              Text('清空').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .constraintSize({ minHeight: 36 }).padding({ left: 10 })
                .onClick((): void => {
                  this.session.selectCollectParagraphs(false);
                })
            }.width('100%').alignItems(VerticalAlign.Center)
            ForEach(this.session.collectParas, (para: string, idx: number): void => {
              Row({ space: 10 }) {
                Row() {
                  if (this.session.collectSelected[idx]) {
                    Icon({ name: 'check', iconSize: 12, color: ACCENT_INK })
                  }
                }
                .width(20).height(20).borderRadius(10)
                .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
                .backgroundColor(this.session.collectSelected[idx] ? ACCENT : Color.Transparent)
                .border({ width: 1, color: this.session.collectSelected[idx] ? ACCENT : LINE })
                Text(para).fontSize(13).fontColor(INK2).lineHeight(20).layoutWeight(1)
                  .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
              }
              .width('100%').alignItems(VerticalAlign.Top)
              .padding({ top: 8, bottom: 8 })
              .onClick((): void => { this.session.togglePara(idx); })
            }, (para: string, idx: number): string => `para-${idx}`)

            Column() {}.height(14)
            Row() {
              Text('收录前编辑').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
                .layoutWeight(1)
              Text('按当前选择重置').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
                .constraintSize({ minHeight: 36 })
                .onClick((): void => { this.session.resetDraftFromSelection(); })
            }.width('100%').alignItems(VerticalAlign.Center)
            TextArea({ text: this.session.collectDraft, placeholder: '收录进章节的正文…',
              controller: this.session.collectDraftController })
              .width('100%').height(150).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .onChange((v: string): void => {
                if (v !== this.session.collectDraft) {
                  this.session.collectManuallyEdited = true;
                  this.session.collectDraft = v;
                }
              })
            if (this.session.collectManuallyEdited) {
              Text('已保留你的编辑；段落调整后可用“按当前选择重置”重建正文。')
                .fontSize(11).fontColor(INK3).lineHeight(16).margin({ top: 6 }).width('100%')
            }

            Column() {}.height(14)
            this.SheetLabel('章节位置')
            Seg({ options: ['新开章节', '并入', '替换'], selected: this.session.collectMode,
              onSelect: (idx: number): void => { this.session.collectMode = idx; } })
            if (this.session.collectMode === 0) {
              TextInput({ text: this.session.collectTitle, placeholder: '章节标题',
                controller: this.session.collectTitleController })
                .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
                .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
                .margin({ top: 10 })
                .onChange((v: string): void => { this.session.collectTitle = v; })
            } else if (this.project !== null) {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.CollectChapterRows(); } })
                .margin({ top: 10 })
            }
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(13).fontColor(ERROR).width('100%').margin({ top: 8 })
            }
            Column() {}.height(8)
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
        Row() {
          Text('取消').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.session.confirmDiscardCollect(); })
          Text(this.busy ? '收录中…' : '收录').fontSize(14).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.session.collectDisabled() ? INK4 : ACCENT)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.session.collectDisabled())
            .onClick((): void => { this.session.submitCollect(); })
        }.width('100%').margin({ top: 8 })
      }
      .width('100%').height('85%')
      .padding({ left: 18, right: 18, top: 16, bottom: 14 + Math.max(this.navBarHeight, 24) })
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .onClick((): void => {})
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  CollectChapterRows(): void {
    Column() {
      ForEach(this.project !== null ? this.project.chapters : [] as NovelChapter[],
        (c: NovelChapter, idx: number): void => {
          Row() {
            Column({ space: 2 }) {
              Text(`第 ${novelChapterOrdinal(c, idx + 1)} 章${c.title.length > 0 ? ' · ' + c.title : ''}`)
                .fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text(`${this.session.charCount(c.content)} 字`).fontSize(11).fontColor(INK3)
            }.alignItems(HorizontalAlign.Start).layoutWeight(1)
            if (this.session.collectChapterId === c.id) {
              Icon({ name: 'check', iconSize: 16, color: ACCENT })
            }
          }
          .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 48 })
          .padding({ left: 14, right: 14 })
          .onClick((): void => { this.session.collectChapterId = c.id; })
          if (this.project !== null && idx < this.project.chapters.length - 1) {
            Row() {
              Row().width('100%').height(1).backgroundColor(LINE)
            }.width('100%').padding({ left: 14, right: 14 })
          }
        }, (c: NovelChapter): string => c.id)
    }.width('100%')
  }

  @Builder
  chapterEditor(): void {
    Column() {
      Column() {
        Text(this.session.editingChapterId.length > 0 ? '编辑本章' : '新建章节')
          .fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK).margin({ bottom: 16 })
        TextInput({ text: this.session.chapterTitle, placeholder: '章节标题', controller: this.session.chapterTitleController })
          .height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string) => { this.session.chapterTitle = v; })
        TextArea({ text: this.session.chapterContent, placeholder: '章节正文', controller: this.session.chapterContentController })
          .height(180).fontSize(14).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 12 })
          .enabled(!this.busy)
          .onChange((v: string) => { this.session.chapterContent = v; })
        Text(`${this.session.charCount(this.session.chapterContent)} 字`).fontSize(11).fontColor(INK3).margin({ top: 6 })
        if (this.errorMsg.length > 0) {
          Scroll() {
            Text(this.errorMsg).fontSize(12).fontColor(ERROR).width('100%').wordBreak(WordBreak.BREAK_ALL)
          }
          .width('100%').constraintSize({ maxHeight: 54 }).align(Alignment.TopStart).margin({ top: 8 })
        }
        Row() {
          Text('取消').fontSize(14).fontColor(INK3).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44).enabled(!this.busy)
            .onClick(() => { this.session.confirmDiscardChapter(); })
          Text(this.busy ? '保存中…' : '保存').fontSize(14).fontColor(ACCENT).layoutWeight(1).textAlign(TextAlign.Center)
            .height(44).enabled(!this.busy)
            .onClick(() => { this.session.saveChapter(); })
        }
        .width('100%').margin({ top: 16 })
      }
      .width('88%').padding(22).backgroundColor(SURFACE).borderRadius(16)
      .transition(TransitionEffect.translate({ y: 24 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.Center)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }
}
