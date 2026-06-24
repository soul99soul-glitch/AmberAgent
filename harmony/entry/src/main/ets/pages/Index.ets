// DeepReadHomePage — deepread 集成版(Index.ets)
// '开始深读' 按钮触发完整 Deep Read 流程(端到端:topic_id → run → 产出 DeepReadOutput → 渲染摘要)。
// 用 mock collectRun + 预置 sources,无需真实 API key 即可端到端跑通。

import {
  createTopicIdWithRuntime, normalizeUrlArkTs,
  createSourcePrefetcher, run,
  makeEmptyDeepReadOutput,
} from '@amber/deepread-domain';
import type { TopicIdRuntime, RunManagerDeps, DeepReadOutput, DeepReadSource, UIMessage, AiClient, Tool } from '@amber/deepread-domain';
import { promptAction } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getAppContainer, getNotifier, getPrefetcher } from '../di/AppContainer.ets';

// generateText 参数 interface(ArkTS 不允许对象字面量类型)
interface GenerateTextParams {
  model: string;
  messages: UIMessage[];
  tools?: Tool[];
  maxSteps?: number;
  autoApproveTools?: boolean;
  autoApproveHighRiskTools?: boolean;
  autoApprovedToolNames?: string[];
  stream?: boolean;
}

// mock AiClient 实现(ArkTS class,满足 AiClient interface)
class MockAiClient implements AiClient {
  async generateText(_params: GenerateTextParams): Promise<UIMessage[]> {
    return [];
  }
}

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Index';

// ArkTS topic_id runtime:FNV-1a hash(确定性,够 topic_id 用)。
const arktsTopicRuntime: TopicIdRuntime = {
  normalizeUrlInput: (url: string): string => normalizeUrlArkTs(url),
  sha256Hex32: (input: string): string => {
    let h = 0x811c9dc5;
    for (let i = 0; i < input.length; i++) {
      h ^= input.charCodeAt(i);
      h = (h + ((h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24))) >>> 0;
    }
    const hex = h.toString(16).padStart(8, '0');
    return hex + hex + hex + hex;
  },
};

const deriveTopicIdArkTs = createTopicIdWithRuntime(arktsTopicRuntime);

// 真实抓取:用 AppContainer 的 prefetcher(createSourcePrefetcher → Tavily 搜索 → RCP scrape → reader 提取)
// 对照 Android DeepReadSourcePrefetcher.collect 全流程

@Entry
@Component
struct DeepReadHomePage {
  @State title: string = '';
  @State status: string = '准备就绪';
  @State running: boolean = false;
  @State articleText: string = '';

  // 触发完整 deepread run(用 mock collectRun 模拟 LLM 写入各段)
  async startDeepRead(): Promise<void> {
    if (this.title.trim().length === 0) return;
    this.running = true;
    this.status = '正在生成深度阅读…';
    this.articleText = '';

    // Phase 7:通知调度 — 运行中通知
    const notifier = getNotifier();
    const topicId = deriveTopicIdArkTs({ url: null, title: this.title });
    await notifier.notifyRunning(topicId, this.title);

    try {
      // 构造 RunManagerDeps:mock prefetcher 返回 mockSources,
      // mock collectRun 模拟 LLM 调 writer tool 写入各段内容。
      // 关键:这会真实跑 deepread 的 generateStages → buildEvidencePack →
      // generateArticlePlan → supervisor loops → finishIfPossible 完整流程。
      // mock AiClient(无 key 时,generateText 返回空 → articlePlan 走 fallbackPlan)
      const mockAi: AiClient = new MockAiClient();
      const prefetcher = getPrefetcher();

      const deps: RunManagerDeps = {
        prefetcher: prefetcher,
        collectRun: async (messages: UIMessage[]): Promise<UIMessage[]> => {
          // 无 API key → collectRun 不调 LLM,返回原 messages。
          // supervisor loop 走 fallback(writeFallbackSection)用抓取到的 source 正文生成 BASIC 段。
          return messages;
        },
        aiClient: mockAi,
        model: 'mock-model',
        playbookMarkdown: '',
        nowIso: (): string => '2026-06-24',
      };

      const result = await run(deps, topicId, this.title, { force: true, seedUrl: null });

      if (result.ok) {
        const out = result.output;
        // 渲染生成的文章摘要(证明完整 run 产出了 DeepReadOutput)
        const summary = out.summary.length > 0 ? out.summary : '(概览待生成)';
        const sections = [
          `概览: ${summary}`,
          out.timeline !== null && out.timeline.length > 0 ? `时间线: ${out.timeline.length} 条事件` : '时间线: 无',
          out.corePoints !== null && out.corePoints.length > 0 ? `核心要点: ${out.corePoints.length} 条` : '核心要点: 无',
          out.analysis.coreDispute !== null ? `核心分歧: ${out.analysis.coreDispute}` : '分析: 无',
          `扩展阅读: ${out.extendedReading.length} 条链接`,
          `阶段完成: OVERVIEW=${out.sectionStates.OVERVIEW?.status ?? '无'}`,
        ];
        this.articleText = sections.join('\n\n');
        this.status = `生成完成 ✓ (topicId: ${topicId.substring(0, 8)}…)`;
        // Phase 7:完成通知
        await notifier.notifyCompleted(topicId, this.title, true);
      } else {
        this.status = `生成失败: ${result.error ?? '未知错误'}`;
        // Phase 7:失败通知
        await notifier.notifyFailed(topicId, this.title, result.error ?? '未知错误');
      }
    } catch (e) {
      hilog.error(DOMAIN, TAG, 'run failed: %{public}s', String(e));
      this.status = `出错: ${String(e).substring(0, 80)}`;
      promptAction.showToast({ message: '生成失败,请重试' });
      // Phase 7:失败通知
      await notifier.notifyFailed(topicId, this.title, String(e).substring(0, 80));
    } finally {
      // Phase 7:取消运行中通知
      await notifier.cancelRunning(topicId);
      this.running = false;
    }
  }

  build(): void {
    Column() {
      Text('深度阅读')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
        .margin({ top: 32, bottom: 16 })

      TextInput({ text: this.title, placeholder: '输入话题标题开始深读' })
        .width('90%')
        .height(48)
        .onChange((v: string) => { this.title = v; })

      Button(this.running ? '生成中…' : '开始深读')
        .type(ButtonType.Capsule)
        .margin({ top: 16 })
        .enabled(this.title.trim().length > 0 && !this.running)
        .onClick(() => { this.startDeepRead(); })

      if (this.running) {
        LoadingProgress().width(40).height(40).margin({ top: 16 })
      }

      Text(this.status)
        .fontSize(13)
        .fontColor('#1976D2')
        .margin({ top: 16, left: 24, right: 24 })
        .textAlign(TextAlign.Center)

      // 文章渲染区域(滚动)
      if (this.articleText.length > 0) {
        Scroll() {
          Text(this.articleText)
            .fontSize(13)
            .lineHeight(22)
            .padding(16)
        }
        .layoutWeight(1)
        .margin({ top: 16 })
        .backgroundColor('#FAFAFA')
        .borderRadius(12)
      }
    }
    .width('100%')
    .height('100%')
    .alignItems(HorizontalAlign.Center)
  }
}

