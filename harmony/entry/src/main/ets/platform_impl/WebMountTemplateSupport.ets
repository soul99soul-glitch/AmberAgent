// WebMountTemplateSupport — replay template 收集与摘要的共享小模块(E12)
//
// 供 WebMountTools(wm_network_inspect)与 WebMountAdvancedTools(wm_fetch_replay)
// 共用,避免双向 import。template 只来自两个 host 身份来源:当前文档页内
// fetch/XHR 台账(page_fetch_xhr)与设置页导入的完整 HAR(imported_har);
// 模型不能自行注册 URL/body 作为"观察结果"。摘要一律脱敏 query 值。

import type { JsonObject, WebMountNetworkSummary, WebMountReplayTemplate } from '@amber/chat-domain';
import { webMountHarTemplates, webMountOriginOf, webMountReplayAllowed, webMountRedactedDisplayUrl } from '@amber/chat-domain';
import type { WebMountSession } from './WebMountSession.ets';
import { listWebMountHarArchives } from './WebMountOAuthRuntime.ets';

const SESSION_ID: string = 'main';

export const collectWebMountReplayTemplates = async (
  session: WebMountSession,
): Promise<WebMountReplayTemplate[]> => {
  const templates: WebMountReplayTemplate[] = [];
  const documentId: string = session.getDocumentId();
  if (documentId.length > 0) {
    const ledger: JsonObject[] = await session.getNetworkLedger();
    for (const record of ledger) {
      const url: string = String(record['url'] ?? '');
      const origin: string | null = webMountOriginOf(url);
      if (origin === null) continue;
      templates.push({
        id: `page-${String(record['seq'] ?? '0')}`,
        sessionId: SESSION_ID,
        sourceId: `ledger-${documentId}`,
        source: 'page_fetch_xhr',
        documentId: documentId,
        origin: origin,
        method: String(record['method'] ?? 'GET').toUpperCase(),
        url: url,
      });
    }
  }
  for (const archive of listWebMountHarArchives()) {
    for (const template of webMountHarTemplates(archive, SESSION_ID)) templates.push(template);
  }
  return templates;
};

const replayDeniedReason = (template: WebMountReplayTemplate, currentUrl: string): string | null => {
  if (template.method !== 'GET' && template.method !== 'HEAD') return 'method_not_replayable';
  const currentOrigin: string | null = webMountOriginOf(currentUrl);
  if (currentOrigin === null || currentOrigin !== template.origin) return 'cross_origin';
  if (!webMountReplayAllowed(template, currentUrl)) return 'mutation_like_path';
  return null;
};

export const webMountTemplateSummary = (
  template: WebMountReplayTemplate, status: number | null, currentUrl: string,
): WebMountNetworkSummary => {
  const reason: string | null = replayDeniedReason(template, currentUrl);
  return {
    requestTemplateId: template.id,
    sourceId: template.sourceId,
    captureCoverage: template.source,
    method: template.method,
    displayUrl: webMountRedactedDisplayUrl(template.url),
    status: status,
    replayable: reason === null,
    reason: reason,
  };
};

export const webMountTemplateSummaries = async (
  session: WebMountSession,
): Promise<WebMountNetworkSummary[]> => {
  const currentUrl: string = session.getUrl();
  const templates: WebMountReplayTemplate[] = await collectWebMountReplayTemplates(session);
  const statusById: Map<string, number | null> = new Map();
  const documentId: string = session.getDocumentId();
  if (documentId.length > 0) {
    const ledger: JsonObject[] = await session.getNetworkLedger();
    for (const record of ledger) {
      const status: number | null = typeof record['status'] === 'number' ? record['status'] as number : null;
      statusById.set(`page-${String(record['seq'] ?? '0')}`, status);
    }
  }
  const out: WebMountNetworkSummary[] = [];
  for (const template of templates) {
    out.push(webMountTemplateSummary(template, statusById.get(template.id) ?? null, currentUrl));
  }
  return out;
};
