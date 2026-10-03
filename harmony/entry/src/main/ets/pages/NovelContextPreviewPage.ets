import { router } from '@kit.ArkUI';
import { getNovelCreation } from '../di/AppContainer.ets';
import { classifyNovelMaterials } from '@amber/deepread-domain';
import type {
  NovelChatMode, NovelGenerationGranularity, NovelContextPreviewReceipt, NovelContextPreviewResult,
  NovelMaterialInjectionDecision, NovelMaterialInjectionReason, NovelInjectionOverrides,
  NovelContextPreviewSection,
} from '@amber/deepread-domain';
import { BG, INK, INK3, ACCENT, ERROR, LINE, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';

interface ContextPreviewParams {
  projectId: string;
  userText: string;
  mode: string;
  granularity: string;
  forceIncludeMaterialIds?: string;
  forceExcludeMaterialIds?: string;
}
interface ContextOverrideReturn extends NovelInjectionOverrides { projectId: string; receiptToken: string; }

@Entry
@Component
struct NovelContextPreviewPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State receipt: NovelContextPreviewReceipt | null = null;
  @State materials: NovelMaterialInjectionDecision[] = [];
  @State forceIncludes: string[] = [];
  @State forceExcludes: string[] = [];
  @State busy: boolean = false;
  @State error: string = '';
  @State userText: string = '';
  private projectId: string = '';
  private branchId: string = '';
  private mode: NovelChatMode = 'discuss';
  private granularity: NovelGenerationGranularity | null = null;
  private alive: boolean = false;
  private previewToken: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    const params: ContextPreviewParams = router.getParams() as ContextPreviewParams;
    this.projectId = params.projectId;
    this.userText = params.userText ?? '';
    this.mode = params.mode === 'write' ? 'write' : 'discuss';
    this.granularity = params.granularity === 'whole_chapter' ? 'whole_chapter'
      : params.granularity === 'continuation' ? 'continuation' : null;
    try {
      this.forceIncludes = params.forceIncludeMaterialIds === undefined ? [] : JSON.parse(params.forceIncludeMaterialIds) as string[];
      this.forceExcludes = params.forceExcludeMaterialIds === undefined ? [] : JSON.parse(params.forceExcludeMaterialIds) as string[];
    } catch (error) { this.error = `资料选择无法读取：${String(error)}`; return; }
    this.preview();
  }
  aboutToDisappear(): void { this.alive = false; this.previewToken += 1; }

  private async preview(): Promise<void> {
    const token: number = ++this.previewToken;
    this.busy = true;
    this.receipt = null;
    this.error = '';
    try {
      const snapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      if (!this.alive || token !== this.previewToken) return;
      if (this.branchId.length > 0 && this.branchId !== snapshot.status.activeBranchId) {
        throw new Error('当前分支已切换，请返回工作区重新预览');
      }
      this.branchId = snapshot.status.activeBranchId;
      const overrides: NovelInjectionOverrides = {
        branchId: this.branchId, forceIncludeMaterialIds: this.forceIncludes, forceExcludeMaterialIds: this.forceExcludes,
      };
      this.materials = classifyNovelMaterials(snapshot.project, this.userText.trim(), overrides);
      const result: NovelContextPreviewResult = await getNovelCreation().previewContext(
        this.projectId, this.userText, this.mode, this.granularity, overrides);
      if (!this.alive || token !== this.previewToken) return;
      this.receipt = result.receipt;
      this.materials = result.receipt.materialDecisions;
    } catch (error) { if (this.alive && token === this.previewToken) this.error = String(error); }
    finally { if (this.alive && token === this.previewToken) this.busy = false; }
  }

  private choose(materialId: string, choice: string): void {
    if (this.busy) return;
    this.forceIncludes = this.forceIncludes.filter((id: string): boolean => id !== materialId);
    this.forceExcludes = this.forceExcludes.filter((id: string): boolean => id !== materialId);
    if (choice === 'include') this.forceIncludes = [...this.forceIncludes, materialId];
    if (choice === 'exclude') this.forceExcludes = [...this.forceExcludes, materialId];
    this.preview();
  }

  private apply(): void {
    if (this.busy || this.branchId.length === 0) return;
    const result: ContextOverrideReturn = {
      projectId: this.projectId, branchId: this.branchId,
      forceIncludeMaterialIds: this.forceIncludes, forceExcludeMaterialIds: this.forceExcludes,
      receiptToken: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    };
    router.back({ url: 'pages/NovelWorkspacePage', params: {
      projectId: this.projectId, novelContextOverrides: JSON.stringify(result),
    } });
  }

  private reasonLabel(reason: NovelMaterialInjectionReason): string {
    if (reason === 'branchOverride') return '分支覆盖：必须包含';
    if (reason === 'always') return '始终注入';
    if (reason === 'forceIncluded') return '本次强制包含';
    if (reason === 'forceExcluded') return '本次排除';
    if (reason === 'disabled') return '已关闭';
    if (reason === 'noSmartMatch') return '未匹配当前输入';
    if (reason === 'budgetTrimmed') return '模型预算不足，整块排除';
    return '智能匹配';
  }
  private materialTokens(id: string): string {
    if (this.receipt === null) return '';
    const material = this.receipt.materialDecisions.find(item => item.materialId === id);
    return material === undefined ? '' : ` · 约 ${material.estimatedTokens ?? 0} tokens`;
  }
  private sectionLabel(key: string): string {
    if (key === 'instruction') return '固定指令';
    if (key === 'chapter_plan') return '本章计划';
    if (key === 'preferences') return '写作偏好';
    if (key === 'author_plot') return '作者剧情资料';
    if (key === 'structured_state') return '取证事件与身份确认';
    if (key === 'confirmed_decisions') return '作者确认决定';
    if (key === 'foreshadows') return '未解伏笔';
    if (key === 'future_plan') return '未来方向';
    if (key === 'discussion_archive') return '讨论归档';
    if (key.startsWith('chapter:')) return '章节正文';
    if (key.startsWith('plot:')) return '正文剧情指针';
    return key;
  }

  @Builder
  private SectionHeader(label: string): void {
    Text(label).fontSize(12).fontWeight(600).fontColor(INK3).lineHeight(18).textAlign(TextAlign.Start)
      .width('100%').padding({ top: 14, bottom: 2 })
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          PageTitleBar({ title: '本次上下文' })
          Scroll() {
            Column({ space: 12 }) {
              Text('这里使用当前模型的真实发送预算。应用后仅保留本次资料选择；输入、模型或工作区变化时，发送会重新计算。')
                .fontSize(13).lineHeight(21).fontColor(INK3).textAlign(TextAlign.Start).width('100%')
              this.SectionHeader('当前输入')
              Text(this.userText.length > 0 ? this.userText : '还没有输入内容')
                .fontSize(14).lineHeight(22).fontColor(INK).textAlign(TextAlign.Start).width('100%').maxLines(6).textOverflow({ overflow: TextOverflow.Ellipsis })
              this.SectionHeader('发送预算')
              if (this.busy) { Text('正在解析模型与上下文…').fontSize(14).fontColor(INK3) }
              if (this.receipt !== null) {
                Text(this.receipt.modelLabel).fontSize(15).fontColor(INK).textAlign(TextAlign.Start).width('100%')
                Text(`预计输入 ${this.receipt.estimatedInputTokens} / ${this.receipt.tokenBudget} tokens`)
                  .fontSize(14).fontColor(INK).textAlign(TextAlign.Start).width('100%')
                Text(`输出上限 ${this.receipt.maxOutputTokens} tokens · 发送历史 ${this.receipt.historyMessagesIncluded} 条 · 排除历史 ${this.receipt.historyMessagesExcluded} 条`)
                  .fontSize(13).lineHeight(21).fontColor(INK3).textAlign(TextAlign.Start).width('100%')
              }
              if (this.error.length > 0) {
                Text(this.error).fontSize(13).lineHeight(21).fontColor(ERROR).textAlign(TextAlign.Start).width('100%')
              }
              this.SectionHeader('资料选择')
              if (this.forceIncludes.length > 0 || this.forceExcludes.length > 0) {
                Text('重置本次选择').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
                  .enabled(!this.busy).onClick((): void => {
                    this.forceIncludes = [];
                    this.forceExcludes = [];
                    this.preview();
                  })
              }
              if (this.materials.length === 0 && !this.busy) { Text('当前分支没有资料。').fontSize(14).fontColor(INK3) }
              ForEach(this.materials, (material: NovelMaterialInjectionDecision): void => {
                Column({ space: 8 }) {
                  Text(material.title).fontSize(15).fontColor(INK).textAlign(TextAlign.Start).width('100%')
                  Text(`${material.included ? '包含' : '排除'} · ${this.reasonLabel(material.reason)}${this.materialTokens(material.materialId)}`)
                    .fontSize(13).lineHeight(20).fontColor(INK3).textAlign(TextAlign.Start).width('100%')
                  if (material.reason === 'smartMatch' || material.reason === 'noSmartMatch') {
                    Text(`相关性分数 ${material.relevanceScore}`).fontSize(12).fontColor(INK3)
                  }
                  if (material.reason !== 'branchOverride') {
                    Row({ space: 16 }) {
                      Text('默认').fontSize(14).fontColor(!this.forceIncludes.includes(material.materialId)
                        && !this.forceExcludes.includes(material.materialId) ? ACCENT : INK3)
                        .constraintSize({ minHeight: 44 }).enabled(!this.busy)
                        .onClick((): void => { this.choose(material.materialId, 'default'); })
                      Text('本次包含').fontSize(14).fontColor(this.forceIncludes.includes(material.materialId) ? ACCENT : INK3)
                        .constraintSize({ minHeight: 44 }).enabled(!this.busy)
                        .onClick((): void => { this.choose(material.materialId, 'include'); })
                      Text('本次排除').fontSize(14).fontColor(this.forceExcludes.includes(material.materialId) ? ACCENT : INK3)
                        .constraintSize({ minHeight: 44 }).enabled(!this.busy)
                        .onClick((): void => { this.choose(material.materialId, 'exclude'); })
                    }.width('100%')
                  }
                }.width('100%').alignItems(HorizontalAlign.Start).padding(12).backgroundColor(SURFACE2).borderRadius(10)
              }, (material: NovelMaterialInjectionDecision): string => `${material.materialId}:${material.reason}:${material.included}`)
              if (this.receipt !== null) {
                this.SectionHeader('领域事实与正文块')
                ForEach(this.receipt.sections.filter(item => !item.key.startsWith('material:')),
                  (section: NovelContextPreviewSection): void => {
                    Row({ space: 12 }) {
                      Text(this.sectionLabel(section.key)).fontSize(13).fontColor(INK).layoutWeight(1)
                      Text(`${section.included ? '包含' : '预算排除'} · ${section.estimatedTokens}`)
                        .fontSize(12).fontColor(INK3)
                    }.width('100%').padding({ top: 8, bottom: 8 }).border({ width: { bottom: 0.5 }, color: LINE })
                  }, (section: NovelContextPreviewSection): string => section.key)
              }
              Text(this.busy ? '计算中…' : '应用本次资料选择').fontSize(15).fontColor(ACCENT)
                .constraintSize({ minHeight: 48 }).width('100%').enabled(!this.busy && this.branchId.length > 0)
                .onClick((): void => { this.apply(); })
              PageSpacer()
            }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, bottom: this.navBarHeight })
          }.layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
        }.width('100%').height('100%').backgroundColor(BG)
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
