import { router } from '@kit.ArkUI';
import type { NovelProject, NovelChapter, NovelProjectRecoveryPreview } from '@amber/deepread-domain';
import { getNovelProjectRepository } from '../di/AppContainer.ets';
import { BG, INK, INK3, SURFACE2, ACCENT, ERROR, BODY, META, TITLE } from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct NovelRecoveryPreviewPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State project: NovelProject | null = null;
  @State selectedChapterId: string = '';
  @State error: string = '';
  @State loading: boolean = true;
  private alive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    const preview: NovelProjectRecoveryPreview = router.getParams() as NovelProjectRecoveryPreview;
    if (!preview || !preview.projectId || !preview.sourceToken) {
      this.error = '恢复预览参数缺失'; this.loading = false; return;
    }
    getNovelProjectRepository().readProjectRecovery(preview).then((project: NovelProject): void => {
      if (!this.alive) return;
      this.project = project;
      this.selectedChapterId = project.chapters.length > 0 ? project.chapters[0].id : '';
      this.loading = false;
    }).catch((error: Error): void => {
      if (this.alive) { this.error = error.message; this.loading = false; }
    });
  }

  aboutToDisappear(): void { this.alive = false; }

  private selectedChapter(): NovelChapter | null {
    if (this.project === null) return null;
    return this.project.chapters.find((chapter: NovelChapter): boolean => chapter.id === this.selectedChapterId) ?? null;
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          Row() {
            Row() { Icon({ name: 'back', iconSize: 20, color: INK3 }) }
              .width(44).height(44).justifyContent(FlexAlign.Center).onClick((): void => { router.back(); })
            Text('旧稿 · 只读预览').fontSize(TITLE).fontColor(INK).layoutWeight(1)
          }.width('100%').padding({ top: this.statusBarHeight, left: 12, right: 20, bottom: 10 })
          Scroll() {
            Column({ space: 12 }) {
              Text('这里显示可恢复快照的完整正文，不修改当前项目。查看后返回上一页，确认恢复才会替换损坏目录。')
                .fontSize(META).fontColor(INK3).width('100%')
              if (this.loading) { Text('正在读取旧稿…').fontSize(BODY).fontColor(INK3) }
              if (this.error.length > 0) { Text(this.error).fontSize(META).fontColor(ERROR).width('100%') }
              if (this.project !== null) {
                Text(this.project.name).fontSize(TITLE).fontColor(INK).width('100%')
                if (this.project.chapters.length === 0) { Text('这个快照没有章节正文').fontSize(BODY).fontColor(INK3).width('100%') }
                ForEach(this.project.chapters, (chapter: NovelChapter, index: number): void => {
                  Text(`${chapter.discarded ? '废稿' : `第 ${chapter.ordinal ?? index + 1} 章`} · ${chapter.title}`)
                    .fontSize(BODY).fontColor(this.selectedChapterId === chapter.id ? ACCENT : INK)
                    .width('100%').padding(12).constraintSize({ minHeight: 44 }).backgroundColor(SURFACE2).borderRadius(8)
                    .onClick((): void => { this.selectedChapterId = chapter.id; })
                }, (chapter: NovelChapter): string => chapter.id)
                if (this.selectedChapter() !== null) {
                  Text(this.selectedChapter()!.content).fontSize(BODY).fontColor(INK).width('100%')
                    .copyOption(CopyOptions.InApp).lineHeight(28)
                }
              }
            }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, bottom: this.navBarHeight + 28 })
          }.layoutWeight(1).width('100%').align(Alignment.TopStart)
        }.width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(BG)
  }
}
