import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { getNovelCreation } from '../di/AppContainer.ets';
import { novelStorySeedText } from '@amber/deepread-domain';
import type { NovelProject, NovelWorkspaceCas, NovelWorkspaceSnapshot } from '@amber/deepread-domain';
import { BG, INK, INK3, ACCENT, ERROR, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar, PlainSection, PageSpacer } from '../components/PageTemplate.ets';

interface ProjectSetupParams { projectId: string; }

@Entry
@Component
struct NovelProjectSetupPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State project: NovelProject | null = null;
  @State preference: string = '';
  @State guidance: string = '';
  @State coreIdeaOverride: string = '';
  @State loading: boolean = true;
  @State busy: boolean = false;
  @State error: string = '';
  @State savedMessage: string = '';
  private projectId: string = '';
  private expectedCas: NovelWorkspaceCas | null = null;
  private alive: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    const params: ProjectSetupParams = router.getParams() as ProjectSetupParams;
    this.projectId = params.projectId;
    this.load();
  }
  onPageShow(): void { this.enableKeyboardAvoidance(); }
  onPageHide(): void { this.restoreKeyboardAvoidance(); }
  aboutToDisappear(): void { this.alive = false; this.restoreKeyboardAvoidance(); }

  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }
  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }

  private async load(): Promise<void> {
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.alive) return;
      this.project = snapshot.project;
      this.preference = snapshot.project.polishPreference === undefined ? '' : snapshot.project.polishPreference;
      this.expectedCas = snapshot.status.cas;
      this.error = '';
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.loading = false; }
  }

  private seedText(): string {
    if (this.project === null || this.project.quickStartSeed === undefined || this.project.quickStartSeed === null) return '';
    return novelStorySeedText(this.project.quickStartSeed);
  }
  private canRegenerate(): boolean {
    return this.project !== null && this.project.creationMode === 'quickStart' && this.seedText().length > 0;
  }

  private async savePreference(): Promise<void> {
    if (this.loading || this.busy || this.expectedCas === null) return;
    this.busy = true;
    try {
      const project: NovelProject = await getNovelCreation().setProjectPolishPreference(
        this.projectId, this.preference, this.expectedCas);
      const status = await getNovelCreation().workspaceStatus(this.projectId);
      if (!this.alive) return;
      this.project = project;
      this.preference = project.polishPreference === undefined ? '' : project.polishPreference;
      this.expectedCas = status.cas;
      this.savedMessage = '项目润色偏好已保存';
      this.error = '';
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }

  private async regenerate(): Promise<void> {
    if (this.loading || this.busy || !this.canRegenerate()) return;
    this.busy = true;
    try {
      await getNovelCreation().quickStartFromSeed(this.projectId, this.guidance, this.coreIdeaOverride);
      if (this.alive) router.back();
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          PageTitleBar({ title: '项目设置' })
          Scroll() {
            Column({ space: 12 }) {
              if (this.loading) { Text('正在读取项目…').fontSize(14).fontColor(INK3).padding(20) }
              if (this.project !== null) {
                PlainSection({ label: '创建方式' })
                Text(this.project.creationMode === 'quickStart' ? '快速开始' : '空白项目')
                  .fontSize(15).fontColor(INK).width('100%').padding({ left: 20, right: 20 })
                if (this.canRegenerate()) {
                  PlainSection({ label: '原始故事种子' })
                  Text(this.seedText()).fontSize(14).lineHeight(22).fontColor(INK).width('100%')
                    .padding({ left: 20, right: 20 }).copyOption(CopyOptions.LocalDevice)
                  PlainSection({ label: '重新生成设定建议' })
                  Text('原始种子永久保留。下面的修改只用于本轮生成；生成结果仍需在对话中确认。')
                    .fontSize(13).fontColor(INK3).width('100%').padding({ left: 20, right: 20 })
                  TextArea({ text: this.guidance, placeholder: '本轮调整方向（可选）' })
                    .height(96).fontSize(14).fontColor(INK).placeholderColor(INK3)
                    .backgroundColor(SURFACE2).borderRadius(10).margin({ left: 20, right: 20 })
                    .enabled(!this.busy).onChange((value: string): void => { this.guidance = value; })
                  TextArea({ text: this.coreIdeaOverride, placeholder: '本轮核心想法（可选；留空沿用原始想法）' })
                    .height(112).fontSize(14).fontColor(INK).placeholderColor(INK3)
                    .backgroundColor(SURFACE2).borderRadius(10).margin({ left: 20, right: 20 })
                    .enabled(!this.busy).onChange((value: string): void => { this.coreIdeaOverride = value; })
                  Text(this.busy ? '处理中…' : '从原种子重新生成建议').fontSize(15).fontColor(ACCENT)
                    .constraintSize({ minHeight: 48 }).padding({ left: 20, right: 20 })
                    .enabled(!this.loading && !this.busy).onClick((): void => { this.regenerate(); })
                }
                PlainSection({ label: '项目润色偏好' })
                TextArea({ text: this.preference, placeholder: '例如：保留叙述节奏，人物对白简洁' })
                  .height(112).fontSize(14).fontColor(INK).placeholderColor(INK3)
                  .backgroundColor(SURFACE2).borderRadius(10).margin({ left: 20, right: 20 })
                  .enabled(!this.busy).onChange((value: string): void => { this.preference = value; })
                Text(this.busy ? '保存中…' : '保存项目润色偏好').fontSize(15).fontColor(ACCENT)
                  .constraintSize({ minHeight: 48 }).padding({ left: 20, right: 20 })
                  .enabled(!this.loading && !this.busy).onClick((): void => { this.savePreference(); })
                if (this.savedMessage.length > 0) {
                  Text(this.savedMessage).fontSize(13).fontColor(INK3).width('100%').padding({ left: 20, right: 20 })
                }
              }
              if (this.error.length > 0) {
                Text(this.error).fontSize(13).fontColor(ERROR).width('100%').padding({ left: 20, right: 20 })
              }
              PageSpacer()
            }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: this.navBarHeight })
          }.layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
        }.width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
