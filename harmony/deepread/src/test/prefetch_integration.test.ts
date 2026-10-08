// 端到端集成测试:prefetch → buildEvidencePack → generateArticlePlan
// 用 mock HttpClient + mock SearchProviderRegistry + mock AiClient,
// 模拟完整 Deep Read 研究阶段流程。

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createSourcePrefetcher } from '../main/ets/research/source_prefetcher.ts';
import { buildEvidencePack, cardsFor } from '../main/ets/research/evidence_pack.ts';
import { generateArticlePlan } from '../main/ets/research/article_plan.ts';
import type { HttpClient, HttpRequest, HttpResponse } from '../main/ets/platform/http.ts';
import type { SearchProviderRegistry, SearchHit } from '../main/ets/platform/search.ts';
import type { AiClient } from '../main/ets/platform/ai_client.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { STAGE_EVIDENCE_MAX } from '../main/ets/domain/enums.ts';

// 12 段不同内容,确保提取后 > MIN_SOURCE_CHARS(280)
const longArticle = (topic: string): string => {
  const paras = [
    `${topic}的第一段背景介绍详细说明了事件的起因和发展脉络供读者理解上下文背景信息。`,
    `第二段描述了关键时间节点和重要进展包括各方的表态和行动决策过程分析。`,
    `第三段提供了利益相关方的立场分析和它们之间存在的核心矛盾与争议焦点。`,
    `第四段总结了事件的影响范围涉及的用户群体行业变化和长期发展趋势预测。`,
    `第五段补充了专家观点和权威来源引用以增强论述的可信度和说服力。`,
    `第六段列出了反方证据和不确定性因素提醒读者审慎对待部分结论。`,
    `第七段提供了后续观察点和待解决问题引导读者持续关注事态发展。`,
    `第八段附上数据图表说明关键指标变化趋势和对比分析的结论总结。`,
    `第九段讨论了国际影响和全球视角下的意义扩展了讨论的广度和深度。`,
    `第十段回顾历史相似案例作为参照帮助读者建立更完整的认知框架。`,
    `第十一段展望未来可能的走向和潜在风险为决策提供参考依据。`,
    `第十二段以总结性陈述收束全文给出整体性的判断与展望建议。`,
  ];
  return `<html><head><title>${topic}深度报道</title></head><body><p>${paras.join('</p><p>')}</p></body></html>`;
};

const ok = (body: string): HttpResponse => ({ status: 200, headers: {}, body });

// 按路由返回不同页面的 mock http
const mockHttp = (routeFn: (url: string) => HttpResponse): HttpClient => ({
  fetch: async (req: HttpRequest): Promise<HttpResponse> => routeFn(req.url),
  fetchStream: async () => { throw new Error('not used'); },
});

const mockRegistry = (hits: SearchHit[]): SearchProviderRegistry => ({
  enabled: () => [{
    name: 'tavily',
    search: async (): Promise<SearchHit[]> => hits,
  }],
  fallback: () => [{
    name: 'jina',
    search: async (): Promise<SearchHit[]> => [],
  }],
});

const mockAi = (responseText: string): AiClient => ({
  generateText: async (): Promise<UIMessage[]> => [
    makeAssistantMessage(responseText),
  ],
});

// ===== end-to-end: cardsFor 消费 plan.stageSourceIds (P0-D) =====

test('end-to-end: cardsFor returns plan-routed evidence for each stage', async () => {
  const topic = 'AI芯片竞争';
  const hits: SearchHit[] = [];
  for (let i = 0; i < 6; i++) {
    hits.push({ title: `${topic}-${i}`, url: `https://tech.example.com/${i}`, snippet: null, source: 'tavily' });
  }
  const http = mockHttp(() => ok(longArticle(topic)));
  const registry = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-e2e-2', topic, null, true);
  const pack = buildEvidencePack(sources);
  const validIds = sources.map(s => s.sourceId);

  // plan: 每个显式 stage 指定 ids
  const plan = await generateArticlePlan(mockAi(JSON.stringify({
    overview_angle: 'angle',
    stage_source_ids: {
      overview: validIds.slice(0, 2),
      narrative: validIds.slice(2, 4),
    },
  })), 'm', topic, pack, '');

  // cardsFor(overview) 应包含 plan 指定的 ids
  const overviewCards = cardsFor(pack, 'OVERVIEW', plan);
  assert.ok(overviewCards.length > 0);
  assert.ok(overviewCards.length <= STAGE_EVIDENCE_MAX);
  // 至少有 plan 指定的 overview id 出现(只要它在 pack 内)
  const overviewIds = new Set(overviewCards.map(c => c.sourceId));
  for (const id of validIds.slice(0, 2)) {
    assert.ok(overviewIds.has(id), `plan-routed overview id ${id} should appear`);
  }
});

// ===== end-to-end: LLM 失败 → fallback plan 仍可用 =====

test('end-to-end: LLM failure → fallback plan with pack-based stageSourceIds', async () => {
  const topic = '容错话题';
  const hits: SearchHit[] = [
    { title: 't1', url: 'https://x.example.com/1', snippet: null, source: 'tavily' },
    { title: 't2', url: 'https://y.example.com/2', snippet: null, source: 'tavily' },
  ];
  const http = mockHttp(() => ok(longArticle(topic)));
  const registry = mockRegistry(hits);
  const pf = createSourcePrefetcher(http, registry);

  const sources = await pf.collect('topic-e2e-5', topic, null, true);
  const pack = buildEvidencePack(sources);

  const throwingAi: AiClient = {
    generateText: async (): Promise<UIMessage[]> => { throw new Error('LLM down'); },
  };
  const plan = await generateArticlePlan(throwingAi, 'm', topic, pack, '');
  // fallback plan 用 pack 各 stage 的 sourceId
  assert.ok(plan.overviewAngle.includes(topic));
  assert.ok(plan.stageSourceIds.overview.length > 0, 'fallback fills overview from pack');
});
