// NovelBatchPolishSheet — 批量润色的选择、进度和逐章结果面板。
import { ACCENT, INK, INK2, INK3, LINE, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';

export interface NovelBatchPolishSheetChapter {
  id: string;
  title: string;
  discarded: boolean;
}

export type NovelBatchPolishSheetResultStatus = 'ready' | 'failed' | 'cancelled' | 'adopted';

export interface NovelBatchPolishSheetResult {
  chapterId: string;
  title: string;
  status: NovelBatchPolishSheetResultStatus;
  detail: string;
  messageId: string;
}

@Component
export struct NovelBatchPolishSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop chapters: NovelBatchPolishSheetChapter[] = [];
  @Prop running: boolean = false;
  @Prop current: number = 0;
  @Prop total: number = 0;
  @Prop currentTitle: string = '';
  @Prop results: NovelBatchPolishSheetResult[] = [];
  @State private selectedChapterIds: string[] = [];

  onStart: (chapterIds: string[]) => void = (_chapterIds: string[]): void => {};
  onCancel: () => void = (): void => {};
  onAdopt: (chapterId: string, messageId: string) => void = (_chapterId: string, _messageId: string): void => {};
  onClose: () => void = (): void => {};

  aboutToAppear(): void {
    this.selectedChapterIds = this.activeChapterIds();
  }

  build(): void {
    Stack() {
      Row() {}
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .onClick((): void => {
          if (!this.running) this.onClose();
        })
      Column() {
        Row() {
          Column() {
            Text('批量润色').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
            Text(this.subtitle()).fontSize(12).fontColor(INK3).margin({ top: 4 })
          }
          .alignItems(HorizontalAlign.Start).layoutWeight(1)
          if (!this.running) {
            Text('关闭').fontSize(13).fontColor(INK3)
              .padding(8).onClick((): void => { this.onClose(); })
          }
        }
        .width('100%').margin({ bottom: 14 })

        if (this.running) {
          this.progressBody()
        } else if (this.results.length > 0) {
          this.resultsBody()
        } else {
          this.selectionBody()
        }
      }
      .width('100%').backgroundColor(SURFACE)
      .borderRadius({ topLeft: 20, topRight: 20 })
      .padding({ left: 20, right: 20, top: 18, bottom: 24 })
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').alignContent(Alignment.Bottom)
  }

  @Builder
  private selectionBody(): void {
    Column() {
      if (this.activeChapterIds().length === 0) {
        Text('没有可润色的章节。').fontSize(14).fontColor(INK3)
          .width('100%').padding({ top: 16, bottom: 28 })
      } else {
        Scroll() {
          Column() {
            ForEach(this.chapters.filter((chapter: NovelBatchPolishSheetChapter): boolean => !chapter.discarded),
              (chapter: NovelBatchPolishSheetChapter, index: number) => {
                Row() {
                  Checkbox().select(this.isSelected(chapter.id)).selectedColor(ACCENT)
                    .onChange((_checked: boolean): void => { this.toggle(chapter.id); })
                  Text(`${index + 1}. ${chapter.title.length > 0 ? chapter.title : '未命名章节'}`)
                    .fontSize(14).fontColor(INK).layoutWeight(1).margin({ left: 12 })
                }
                .width('100%').padding({ top: 12, bottom: 12 })
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
                .onClick((): void => { this.toggle(chapter.id); })
              }, (chapter: NovelBatchPolishSheetChapter): string => chapter.id)
          }
          .width('100%')
        }
        .height(280)
        Row() {
          Text(this.selectedChapterIds.length === this.activeChapterIds().length ? '取消全选' : '全选')
            .fontSize(13).fontColor(INK3).padding({ top: 12, bottom: 10 })
            .onClick((): void => { this.toggleAll(); })
          Blank()
          Text(`已选 ${this.selectedChapterIds.length} 章`).fontSize(12).fontColor(INK3)
        }
        .width('100%')
      }
      this.actionRow('开始润色', (): void => {
        if (this.selectedChapterIds.length > 0) this.onStart(this.selectedChapterIds.slice());
      }, this.selectedChapterIds.length > 0)
    }
    .width('100%')
  }

  @Builder
  private progressBody(): void {
    Column() {
      Text(this.currentTitle.length > 0 ? this.currentTitle : '正在准备润色…')
        .fontSize(15).fontColor(INK).width('100%').maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text(`${this.current}/${this.total}`).fontSize(13).fontColor(ACCENT).margin({ top: 8 })
      LoadingProgress().width(28).height(28).color(ACCENT).margin({ top: 20, bottom: 20 })
      this.actionRow('取消', (): void => { this.onCancel(); }, true)
    }
    .width('100%').alignItems(HorizontalAlign.Center)
  }

  @Builder
  private resultsBody(): void {
    Column() {
      Scroll() {
        Column() {
          ForEach(this.results, (result: NovelBatchPolishSheetResult) => {
            Row() {
              Column() {
                Text(result.title).fontSize(14).fontColor(INK).maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
                Text(this.resultText(result)).fontSize(12).fontColor(this.resultColor(result.status))
                  .margin({ top: 4 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              if (result.status === 'ready' && result.messageId.length > 0) {
                Text('采纳').fontSize(13).fontColor('#FFFFFF').padding({ left: 14, right: 14, top: 7, bottom: 7 })
                  .backgroundColor(ACCENT).borderRadius(14).margin({ left: 12 })
              }
            }
            .width('100%').padding({ top: 12, bottom: 12 })
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            .onClick((): void => {
              if (result.status === 'ready' && result.messageId.length > 0) {
                this.onAdopt(result.chapterId, result.messageId);
              }
            })
          }, (result: NovelBatchPolishSheetResult): string => result.chapterId)
        }
        .width('100%')
      }
      .height(280)
      this.actionRow('完成', (): void => { this.onClose(); }, true)
    }
    .width('100%')
  }

  @Builder
  private actionRow(label: string, action: () => void, enabled: boolean): void {
    Text(label).fontSize(14).fontWeight(FontWeight.Medium)
      .fontColor(enabled ? '#FFFFFF' : INK3).textAlign(TextAlign.Center)
      .width('100%').padding({ top: 12, bottom: 12 }).borderRadius(12)
      .backgroundColor(enabled ? ACCENT : SURFACE2).margin({ top: 16 })
      .onClick((): void => { if (enabled) action(); })
  }

  private activeChapterIds(): string[] {
    return this.chapters.filter((chapter: NovelBatchPolishSheetChapter): boolean => !chapter.discarded)
      .map((chapter: NovelBatchPolishSheetChapter): string => chapter.id);
  }

  private isSelected(chapterId: string): boolean {
    return this.selectedChapterIds.indexOf(chapterId) >= 0;
  }

  private toggle(chapterId: string): void {
    if (this.isSelected(chapterId)) {
      this.selectedChapterIds = this.selectedChapterIds.filter((id: string): boolean => id !== chapterId);
    } else {
      this.selectedChapterIds = [...this.selectedChapterIds, chapterId];
    }
  }

  private toggleAll(): void {
    const ids: string[] = this.activeChapterIds();
    this.selectedChapterIds = this.selectedChapterIds.length === ids.length ? [] : ids;
  }

  private subtitle(): string {
    if (this.running) return '正在逐章生成润色稿，原章节不会立刻改写。';
    if (this.results.length > 0) return '逐章检查结果，选择需要采纳的润色稿。';
    return '默认选择全部未废弃章节，结果需逐章采纳。';
  }

  private resultText(result: NovelBatchPolishSheetResult): string {
    if (result.status === 'ready') return '润色稿已就绪';
    if (result.status === 'adopted') return '已采纳';
    if (result.status === 'cancelled') return '已取消';
    return result.detail.length > 0 ? result.detail : '润色失败';
  }

  private resultColor(status: NovelBatchPolishSheetResultStatus): string {
    if (status === 'ready') return ACCENT;
    if (status === 'adopted') return '#43A047';
    if (status === 'failed') return '#E53935';
    return INK3;
  }
}
