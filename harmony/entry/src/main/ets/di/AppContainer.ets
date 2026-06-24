// AppContainer — 依赖注入容器(ArkTS 兼容版)
// 组装 deepread 逻辑层与平台实现。
// 当前:用内存 mock 替代真实 @kit 平台实现(它们需逐个修 ArkTS 编译,留 TODO)。
// 真实 LLM 调用需配 API key 后由 OpenAiCompatibleAiClient 接入。
// 通知调度(Phase 7)用 @kit.NotificationKit 接入。

import type {
  RunManagerDeps, AiClient, UIMessage, Tool,
  DeepReadOutput, DeepReadScheduler, HttpClient,
} from '@amber/deepread-domain';
import { run, makeEmptyDeepReadOutput, isInterruptedPhase } from '@amber/deepread-domain';
// 真实平台实现 import(触发 ArkTS 编译验证)
import { createRcpHttpClient } from '../platform_impl/RcpHttpClient.ets';
// TODO(notification): 接真实通知时恢复 import { notificationManager } from '@kit.NotificationKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.Container';

// generateText 参数 interface(ArkTS 不允许对象字面量类型)
interface GenTextParams {
  model: string;
  messages: UIMessage[];
  tools?: Tool[];
  maxSteps?: number;
  autoApproveTools?: boolean;
  autoApproveHighRiskTools?: boolean;
  autoApprovedToolNames?: string[];
  stream?: boolean;
}

// mock AiClient:无 API key 时返回空 → deepread 走 fallbackPlan + writeFallbackSection
// TODO(phase6): 替换为 createOpenAiCompatibleAiClient(需修 ArkTS 编译后)
class MockAiClient implements AiClient {
  async generateText(_params: GenTextParams): Promise<UIMessage[]> {
    return [];
  }
}

// Phase 7:通知调度(对照设计 §6.7 / §8.8)
// TODO(notification): notificationManager 的 ContentType/NotificationContent 类型
// 在 API 26 SDK 有 @ohos.notification vs @ohos.notificationManager 模块冲突,
// 需 DevEco 验证确切类型后接真实系统通知。当前用 hilog 输出(通知逻辑结构完整:
// 3 类通知 running/completed/failed + cancel,调度接入 Index startDeepRead 全生命周期)。
interface DeepReadNotifier {
  notifyRunning(topicId: string, title: string): Promise<void>;
  notifyCompleted(topicId: string, title: string, complete: boolean): Promise<void>;
  notifyFailed(topicId: string, title: string, msg: string): Promise<void>;
  cancelRunning(topicId: string): Promise<void>;
}

// 通知实现:当前用 hilog(标 TODO 接真实 notificationManager)
// ArkTS 要求 interface 用 class implements,不允许对象字面量直接赋值。
class HilogNotifier implements DeepReadNotifier {
  async notifyRunning(topicId: string, title: string): Promise<void> {
    hilog.info(DOMAIN, TAG, '[notifyRunning] topicId=%{public}s title=%{public}s', topicId, title);
  }
  async notifyCompleted(topicId: string, _title: string, complete: boolean): Promise<void> {
    hilog.info(DOMAIN, TAG, '[notifyCompleted] topicId=%{public}s complete=%{public}s', topicId, complete ? 'true' : 'false');
  }
  async notifyFailed(topicId: string, _title: string, msg: string): Promise<void> {
    hilog.warn(DOMAIN, TAG, '[notifyFailed] topicId=%{public}s msg=%{public}s', topicId, msg);
  }
  async cancelRunning(topicId: string): Promise<void> {
    hilog.info(DOMAIN, TAG, '[cancelRunning] topicId=%{public}s', topicId);
  }
}

const createRealNotifier = (): DeepReadNotifier => {
  return new HilogNotifier();
};

// 容器单例
interface Container {
  httpClient: HttpClient;
  aiClient: AiClient;
  notifier: DeepReadNotifier;
}

let cached: Container | null = null;

const buildContainer = (): Container => {
  const httpClient: HttpClient = createRcpHttpClient();
  const aiClient: AiClient = new MockAiClient();
  const notifier: DeepReadNotifier = createRealNotifier();
  // TODO(phase6): 替换 mock AiClient 为真实 OpenAiCompatibleAiClient(需修 ArkTS 编译 + API key)
  return { httpClient, aiClient, notifier };
};

export const getAppContainer = (): Container => {
  if (cached === null) {
    cached = buildContainer();
  }
  return cached;
};

export const getNotifier = (): DeepReadNotifier => getAppContainer().notifier;
export const getAiClient = (): AiClient => getAppContainer().aiClient;
