// DeepReadArticlePage — 杂志风文章页(真实 run() 数据,非 mock)
// 对照 Android:点击热点 → 文章页触发 run() → 渲染真实 DeepReadOutput
//
// G5-S1 增强(对照 Android DeepReadScreen.kt):
//   - RunningStageNotice:生成中显示当前阶段 + 进度(314-318)
//   - DeepReadPartialErrorNotice:部分 stage 失败但已有基础稿(318-323)
//   - TemplateFallbackNotice:模板降级提示(329-333)
//   - 确认对话框:重新生成前确认覆盖(DeepReadConfirmation 组件)
//   - 字号缩放:标题栏 zoom in/out 按钮(对齐 Android deepReadFontScale)
//
// 保留原有 run() 逻辑,仅增强 UI 层。

import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  DeepReadOutput, RunManagerDeps, UIMessage, AiClient,
} from '@amber/deepread-domain';
import { run, firstFailureMessage, isComplete } from '@amber/deepread-domain';
import { getAppContainer, getNotifier, getPrefetcher,
  getDeepReadPlaybookRepository,
} from '../di/AppContainer.ets';
import { ArticleRenderer } from '../components/ArticleRenderer.ets';
import { DeepReadConfirmationDialog } from '../components/DeepReadConfirmationDialog.ets';
import {
  BG, INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ACCENT_LIGHT,
  BODY, META, TITLE,
} from '../design/tokens.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Article';

// 字号缩放上下限 — 照搬 Android BoardSettings.kt:131-133
const FONT_SCALE_MIN: number = 0.85;
const FONT_SCALE_MAX: number = 1.25;
const FONT_SCALE_STEP: number = 0.05;
const FONT_SCALE_DEFAULT: number = 1.0;

interface ArticleParams {
  topicId: string;
  title: string;
}

interface GenTextParams { model: string; messages: UIMessage[]; }
class MockAiClient implements AiClient {
  async generateText(_p: GenTextParams): Promise<UIMessage[]> { return []; }
}

@Entry
@Component
struct DeepReadArticlePage {
  @State topicId: string = '';
  @State title: string = '';
  @State output: DeepReadOutput | null = null;
  @State running: boolean = true;
  @State errorMsg: string = '';
  @State currentStage: string = '';
  // G5-S1:字号倍率(驱动 ArticleRenderer)
  @State fontScale: number = FONT_SCALE_DEFAULT;
  // G5-S1:重新生成确认对话框
  @State showRegenerateDialog: boolean = false;

  async aboutToAppear(): Promise<void> {
    const rawParams: object = router.getParams() as object;
    if (rawParams === null || rawParams === undefined) {
      hilog.error(DOMAIN, TAG, 'aboutToAppear: router.getParams() is null');
      this.errorMsg = '参数缺失';
      this.running = false;
      return;
    }
    const params: ArticleParams = rawParams as ArticleParams;
    this.topicId = params.topicId ?? '';
    this.title = params.title ?? '';
    hilog.info(DOMAIN, TAG, 'aboutToAppear: topicId=%{public}s title=%{public}s', this.topicId, this.title);
    await this.generateArticle();
  }

  async generateArticle(): Promise<void> {
    this.running = true;
    this.errorMsg = '';
    this.output = null;
    this.currentStage = '正在抓取来源…';
    const notifier = getNotifier();
    await notifier.notifyRunning(this.topicId, this.title);
    try {
      hilog.info(DOMAIN, TAG, 'generateArticle: start run()');
      // 用 AppContainer 注入的 aiClient(自动读 settings 里配的 API Key + Base URL)
      const container = getAppContainer();
      const aiClient = container.aiClient;
      const storage = container.storage;
      const model = await storage.get<string>('ai_model', 'deepseek-chat');
      const apiKey = await storage.get<string>('ai_api_key', '');
      hilog.info(DOMAIN, TAG, 'AI config: model=%{public}s hasKey=%{public}s', model, String(apiKey.length > 0));
      const prefetcher = getPrefetcher();
      // D-127:DeepReadAgentRunManager.kt:350 — 规划前读 playbook.markdown
      //   (此前硬编码 '' = 登记缺口,本切片闭环)
      const playbook = await getDeepReadPlaybookRepository().read();
      const deps: RunManagerDeps = {
        prefetcher: prefetcher,
        collectRun: async (messages: UIMessage[]): Promise<UIMessage[]> => {
          // 无 API key → 返回原 messages,走 fallbackPlan + writeFallbackSection
          return messages;
        },
        aiClient: aiClient,
        model: model,
        playbookMarkdown: playbook.markdown,
        nowIso: (): string => '2026-06-25',
      };
      const result = await run(deps, this.topicId, this.title, { force: true, seedUrl: null });
      hilog.info(DOMAIN, TAG, 'run() result: ok=%{public}s error=%{public}s',
        String(result.ok), result.error ?? 'null');
      if (result.ok) {
        this.output = result.output;
        this.currentStage = '完成';
        await notifier.notifyCompleted(this.topicId, this.title, true);
      } else {
        // 部分失败但可能有基础稿:Android run() ok=false 但 output 含内容 → 走 partial error
        if (result.output.summary.trim().length > 0) {
          this.output = result.output;
          this.currentStage = '部分完成';
        } else {
          this.errorMsg = result.error ?? '生成失败';
          this.currentStage = '失败';
        }
        await notifier.notifyFailed(this.topicId, this.title, result.error ?? '未知错误');
      }
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'generateArticle EXCEPTION: %{public}s', String(e).substring(0, 120));
      this.errorMsg = String(e).substring(0, 80);
      this.currentStage = '失败';
      await notifier.notifyFailed(this.topicId, this.title, String(e).substring(0, 60));
    } finally {
      this.running = false;
      await notifier.cancelRunning(this.topicId);
    }
  }

  // G5-S1:字号缩放 — zoom in/out
  private zoomIn(): void {
    const next: number = this.fontScale + FONT_SCALE_STEP;
    this.fontScale = next > FONT_SCALE_MAX ? FONT_SCALE_MAX : next;
  }
  private zoomOut(): void {
    const next: number = this.fontScale - FONT_SCALE_STEP;
    this.fontScale = next < FONT_SCALE_MIN ? FONT_SCALE_MIN : next;
  }

  // G5-S1:部分失败判定 — 有 output 但 firstFailureMessage 非空
  private partialError(): string {
    if (this.output === null) { return ''; }
    if (this.currentStage === '完成') { return ''; }
    const msg = firstFailureMessage(this.output);
    if (msg === null) { return ''; }
    return msg;
  }

  // G5-S1:模板降级判定 — 未完成且无部分失败但已有基础稿(走 fallback 模板)
  private templateFallbackMessage(): string {
    if (this.output === null) { return ''; }
    if (this.currentStage === '完成') { return ''; }
    if (this.partialError().length > 0) { return ''; }
    const complete = isComplete(this.output);
    if (complete) { return ''; }
    if (this.output.summary.trim().length > 0) {
      return '使用基础模板生成,内容可能不完整';
    }
    return '';
  }

  build(): void {
    Stack() {
      Column() {
        // ── 顶栏:返回 + 标题(衬线) + 字号缩放按钮 ──
        Row() {
          Text('←').fontSize(18).fontColor(INK3)
            .onClick(() => { router.back(); })
          Text('深度阅读').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
            .fontFamily('serif').margin({ left: 12 }).layoutWeight(1)

          // G5-S1:字号缩放按钮(zoom out / zoom in)
          Row() {
            Text('A-').fontSize(13).fontColor(INK3)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .onClick((): void => { this.zoomOut(); })
            Text('A+').fontSize(15).fontColor(INK3)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .onClick((): void => { this.zoomIn(); })
          }
          .borderRadius(8)
          .backgroundColor(SURFACE2)
        }
        .width('100%').height(56).padding({ left: 24, right: 24 })
        .backgroundColor(BG)

        // ── 内容区 ──
        if (this.running) {
          // G5-S1:生成中 — RunningStageNotice(阶段 + 进度指示)
          Column() {
            LoadingProgress().width(40).height(40).color(ACCENT)
            // RunningStageNotice:阶段文字 + 百分比样式(对齐 Android 494-512)
            Row() {
              Text(this.currentStage)
                .fontSize(META)
                .fontColor(INK2)
            }
            .padding({ left: 14, right: 14, top: 8, bottom: 8 })
            .backgroundColor(SURFACE)
            .borderRadius(50)
            .margin({ top: 16 })
          }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.errorMsg.length > 0) {
          // 错误状态
          Column() {
            Text('生成失败').fontSize(TITLE).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 12 })
            Text(this.errorMsg).fontSize(META).fontColor(INK3).margin({ top: 6 })
            Text('重试').fontSize(BODY).fontColor(ACCENT).margin({ top: 16 })
              .onClick(() => { this.generateArticle(); })
          }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
        } else if (this.output !== null) {
          // 真实文章渲染 — ArticleRenderer 统一渲染全量块
          Scroll() {
            Column() {
              // ── 标题:衬线大字(页级,非文章块) ──
              Text(this.title)
                .fontSize(24).fontWeight(FontWeight.Bold).fontColor(INK)
                .fontFamily('serif')
                .lineHeight(34).maxLines(3)

              // ── 状态 ──
              Text(this.currentStage).fontSize(META).fontColor(INK3).margin({ top: 10 })

              // ── 分隔线 ──
              Column() {}.height(1).width('100%').backgroundColor(LINE).margin({ top: 16, bottom: 4 })

              // ── G5-S1:部分错误提示(DeepReadPartialErrorNotice) ──
              if (this.partialError().length > 0) {
                this.PartialErrorNotice(this.partialError())
              }

              // ── G5-S1:模板降级提示(TemplateFallbackNotice) ──
              if (this.templateFallbackMessage().length > 0) {
                this.TemplateFallbackNotice(this.templateFallbackMessage())
              }

              // ── 文章全量块(Hero/summary/entities/timeline/corePoints/analysis/diagram/refs) ──
              ArticleRenderer({
                output: this.output,
                fontSizeMultiplier: this.fontScale,
              })

              // ── G5-S1:重新生成按钮(已有 output 时显示) ──
              Row() {
                Text('重新生成').fontSize(BODY).fontColor(ACCENT)
              }
              .width('100%')
              .height(48)
              .justifyContent(FlexAlign.Center)
              .margin({ top: 24, bottom: 40 })
              .onClick((): void => { this.showRegenerateDialog = true; })
            }
            .width('100%').padding({ left: 24, right: 24 })
          }
          .layoutWeight(1)
          .backgroundColor(BG)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])

      // G5-S1:重新生成确认对话框(覆盖层)
      DeepReadConfirmationDialog({
        show: this.showRegenerateDialog,
        dialogTitle: '重新生成?',
        message: '重新生成将覆盖当前文章',
        confirmText: '重新生成',
        dismissText: '取消',
        onConfirm: (): void => {
          this.showRegenerateDialog = false;
          this.generateArticle();
        },
        onDismiss: (): void => {
          this.showRegenerateDialog = false;
        },
      })
    }
    .width('100%').height('100%')
  }

  // G5-S1:部分错误提示(对齐 Android DeepReadPartialErrorNotice:515-548)
  @Builder
  PartialErrorNotice(errText: string): void {
    Row() {
      Text(errText.length > 0 ? errText : '后续段落生成中断,已保留已写出的内容')
        .fontSize(META)
        .fontColor('#7A1F1A')
        .layoutWeight(1)
        .maxLines(2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text('重试')
        .fontSize(META)
        .fontColor(ACCENT)
        .padding({ left: 12, right: 12, top: 4, bottom: 4 })
        .onClick((): void => { this.generateArticle(); })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 8, bottom: 8 })
    .backgroundColor('#FBE9E7')
    .borderRadius(22)
    .margin({ top: 12, bottom: 8 })
  }

  // G5-S1:模板降级提示(对齐 Android TemplateFallbackNotice:551-564)
  @Builder
  TemplateFallbackNotice(msg: string): void {
    Row() {
      Text(msg)
        .fontSize(META)
        .fontColor('#7A1F1A')
    }
    .padding({ left: 12, right: 12, top: 7, bottom: 7 })
    .backgroundColor('#FBE9E7')
    .borderRadius(50)
    .margin({ top: 12, bottom: 8 })
  }
}
