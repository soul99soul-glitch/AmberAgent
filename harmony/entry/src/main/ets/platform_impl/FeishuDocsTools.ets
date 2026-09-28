// FeishuDocsTools — 飞书云文档工具族(D6-3;Android FeishuDocsClient.kt 对齐)
//
// 端点(逐字对照 Android):
//   list   GET  drive/v1/files
//   read   GET  docx/v1/documents/{id}/raw_content
//   blocks GET  docx/v1/documents/{id}/blocks
//   search POST suite/docs-api/search/object
//   create POST docx/v1/documents
//   append POST docx/v1/documents/{id}/blocks/{parent}/children
// 凭据:KV webmount_feishu_token(OAuth PKCE,设置页登录);
//   过期用 refresh_token + 应用凭据(webmount_feishu_app_id/secret,登录时持久化)刷新。

import type { AgentTool, JsonValue, JsonObject, UIMessagePart, InputSchemaObj } from '@amber/chat-domain';
import { makeAgentTool, makeInputSchemaObj } from '@amber/chat-domain';
import {
  webMountTokenUsable, feishuTokenEndpoint, parseFeishuTokenResponse,
} from '@amber/chat-domain';
import { createKvWebMountTokenStore } from './WebMountOAuthSupport.ets';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';

const DRIVE_BASE: string = 'https://open.feishu.cn/open-apis/drive';
const DOCX_BASE: string = 'https://open.feishu.cn/open-apis/docx';
const SUITE_BASE: string = 'https://open.feishu.cn/open-apis/suite';

export const FEISHU_APP_ID_KEY: string = 'webmount_feishu_app_id';
export const FEISHU_APP_SECRET_KEY: string = 'webmount_feishu_app_secret';

const strOf = (input: JsonValue, key: string, fallback: string): string => {
  if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
    const v: JsonValue | undefined = (input as JsonObject)[key];
    if (typeof v === 'string') return v;
  }
  return fallback;
};

const numOf = (input: JsonValue, key: string, fallback: number): number => {
  if (typeof input === 'object' && input !== null && !Array.isArray(input)) {
    const v: JsonValue | undefined = (input as JsonObject)[key];
    if (typeof v === 'number') return v;
  }
  return fallback;
};

const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

// 有效 access_token(过期 → refresh;失败 → 诚实报错引导重新登录)
const resolveAccessToken = async (): Promise<string> => {
  const kv = getChatKvStore();
  const store = createKvWebMountTokenStore(kv);
  let token = await store.get();
  if (token === null) throw new Error('飞书未登录:请到 设置 → WebMount 完成飞书授权');
  if (webMountTokenUsable(token, Date.now())) return token.accessToken;
  if (token.refreshToken === null || token.refreshToken.length === 0) {
    throw new Error('飞书授权已过期,请重新登录');
  }
  const appId: string | null = await kv.get(FEISHU_APP_ID_KEY);
  const appSecret: string | null = await kv.get(FEISHU_APP_SECRET_KEY);
  if (appId === null || appId.length === 0) {
    throw new Error('飞书授权已过期且缺少应用凭据,请重新登录');
  }
  const body: JsonObject = {
    grant_type: 'refresh_token',
    app_id: appId,
    client_id: appId,
    refresh_token: token.refreshToken,
  };
  if (appSecret !== null && appSecret.length > 0) {
    body['app_secret'] = appSecret;
    body['client_secret'] = appSecret;
  }
  const resp = await getAppContainer().httpClient.fetch({
    url: feishuTokenEndpoint, method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }, { signal: undefined });
  const refreshed = parseFeishuTokenResponse(resp.body, Date.now());
  if (refreshed === null) throw new Error('飞书授权刷新失败,请重新登录');
  await store.set(refreshed);
  token = refreshed;
  return token.accessToken;
};

const feishuFetch = async (
  url: string, method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH',
  accessToken: string, body?: JsonObject,
): Promise<JsonObject> => {
  const resp = await getAppContainer().httpClient.fetch({
    url: url, method: method,
    headers: { 'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  }, { signal: undefined });
  const env: JsonObject = JSON.parse(resp.body) as JsonObject;
  const code: JsonValue | undefined = env['code'];
  if (typeof code === 'number' && code !== 0) {
    const msg: JsonValue | undefined = env['msg'];
    throw new Error(`feishu api error ${code}${typeof msg === 'string' ? `: ${msg}` : ''}`);
  }
  return env;
};

const dataOf = (env: JsonObject): JsonObject => {
  const data: JsonValue | undefined = env['data'];
  if (data !== undefined && typeof data === 'object' && !Array.isArray(data)) {
    return data as JsonObject;
  }
  return {};
};

const truncate = (text: string, max: number): string =>
  text.length > max ? `${text.substring(0, max)}…(truncated)` : text;

export const createFeishuDocsTools = (): AgentTool[] => {
  const listTool: AgentTool = makeAgentTool({
    name: 'feishu_docs_list',
    description: 'List files in the user\'s Feishu Drive (docx documents and folders).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'folder_token': { type: 'string', description: 'Folder token (default: root folder).' },
      'count': { type: 'number', description: 'Page size 1-50 (default 20).' },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const token: string = await resolveAccessToken();
      const count: number = Math.min(50, Math.max(1, numOf(input, 'count', 20)));
      const folder: string = strOf(input, 'folder_token', '');
      const query: string = folder.length > 0 ? `&folder_token=${encodeURIComponent(folder)}` : '';
      const env: JsonObject = await feishuFetch(
        `${DRIVE_BASE}/v1/files?page_size=${count}${query}`, 'GET', token);
      return textPart({ ok: true, data: env['data'] ?? {} } as JsonObject);
    },
  });

  const readTool: AgentTool = makeAgentTool({
    name: 'feishu_docs_read',
    description: 'Read the plain-text raw content of a Feishu docx document.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'document_id': { type: 'string', description: 'docx document token.' },
    } as JsonObject, ['document_id']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const id: string = strOf(input, 'document_id', '');
      if (id.length === 0) throw new Error('document_id is required');
      const token: string = await resolveAccessToken();
      const env: JsonObject = await feishuFetch(
        `${DOCX_BASE}/v1/documents/${encodeURIComponent(id)}/raw_content`, 'GET', token);
      const data: JsonObject = dataOf(env);
      const content: JsonValue | undefined = data['content'];
      return textPart({
        ok: true,
        document_id: id,
        content: truncate(typeof content === 'string' ? content : '', 12000),
      } as JsonObject);
    },
  });

  const blocksTool: AgentTool = makeAgentTool({
    name: 'feishu_docs_blocks',
    description: 'List block structure of a Feishu docx document (block_id, type, text preview).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'document_id': { type: 'string', description: 'docx document token.' },
    } as JsonObject, ['document_id']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const id: string = strOf(input, 'document_id', '');
      if (id.length === 0) throw new Error('document_id is required');
      const token: string = await resolveAccessToken();
      const env: JsonObject = await feishuFetch(
        `${DOCX_BASE}/v1/documents/${encodeURIComponent(id)}/blocks?page_size=100`, 'GET', token);
      const data: JsonObject = dataOf(env);
      const items: JsonValue | undefined = data['items'];
      const out: string[] = [];
      if (Array.isArray(items)) {
        for (const raw of items as JsonValue[]) {
          const b: JsonObject = raw as JsonObject;
          const blockId: JsonValue | undefined = b['block_id'];
          const blockType: JsonValue | undefined = b['block_type'];
          // 文本预览:text.elements[].text_run.content 拼接(限 60 字)
          let preview: string = '';
          const text: JsonValue | undefined = b['text'];
          if (text !== undefined && typeof text === 'object' && !Array.isArray(text)) {
            const elements: JsonValue | undefined = (text as JsonObject)['elements'];
            if (Array.isArray(elements)) {
              for (const el of elements as JsonValue[]) {
                const tr: JsonValue | undefined = (el as JsonObject)['text_run'];
                if (tr !== undefined && typeof tr === 'object' && !Array.isArray(tr)) {
                  const c: JsonValue | undefined = (tr as JsonObject)['content'];
                  if (typeof c === 'string') preview += c;
                }
              }
            }
          }
          out.push(`${blockId} | type=${blockType} | ${preview.substring(0, 60)}`);
          if (out.length >= 100) break;
        }
      }
      return textPart({ ok: true, count: out.length, blocks: out.join('\n') } as JsonObject);
    },
  });

  const searchTool: AgentTool = makeAgentTool({
    name: 'feishu_docs_search',
    description: 'Search Feishu docs by keyword (suite search/object).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'query': { type: 'string', description: 'Search keyword.' },
      'count': { type: 'number', description: '1-50 (default 20).' },
    } as JsonObject, ['query']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const query: string = strOf(input, 'query', '');
      if (query.length === 0) throw new Error('query is required');
      const count: number = Math.min(50, Math.max(1, numOf(input, 'count', 20)));
      const token: string = await resolveAccessToken();
      const env: JsonObject = await feishuFetch(`${SUITE_BASE}/docs-api/search/object`, 'POST', token, {
        search_key: query, count: count, offset: 0,
      } as JsonObject);
      const data: JsonObject = dataOf(env);
      const entities: JsonValue | undefined = data['docs_entities'];
      const out: string[] = [];
      if (Array.isArray(entities)) {
        for (const raw of entities as JsonValue[]) {
          const e: JsonObject = raw as JsonObject;
          const t: JsonValue | undefined = e['docs_token'];
          const title: JsonValue | undefined = e['title'];
          const dtype: JsonValue | undefined = e['docs_type'];
          const url: JsonValue | undefined = e['url'];
          out.push(`${t} | ${dtype} | ${typeof title === 'string' ? title : ''} | ${typeof url === 'string' ? url : ''}`);
        }
      }
      return textPart({ ok: true, count: out.length, results: out.join('\n') } as JsonObject);
    },
  });

  const createTool: AgentTool = makeAgentTool({
    name: 'feishu_docs_create',
    description: 'Create a new Feishu docx document.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'title': { type: 'string', description: 'Document title.' },
      'folder_token': { type: 'string', description: 'Target folder token (default: root).' },
    } as JsonObject, ['title']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const title: string = strOf(input, 'title', '');
      if (title.length === 0) throw new Error('title is required');
      const folder: string = strOf(input, 'folder_token', '');
      const token: string = await resolveAccessToken();
      const body: JsonObject = { title: title };
      if (folder.length > 0) body['folder_token'] = folder;
      const env: JsonObject = await feishuFetch(`${DOCX_BASE}/v1/documents`, 'POST', token, body);
      const data: JsonObject = dataOf(env);
      const doc: JsonValue | undefined = data['document'];
      return textPart({ ok: true, document: doc ?? {} } as JsonObject);
    },
  });

  const appendTool: AgentTool = makeAgentTool({
    name: 'feishu_docs_append',
    description: 'Append a plain-text block to a Feishu docx document (default: at document end).',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'document_id': { type: 'string', description: 'docx document token.' },
      'text': { type: 'string', description: 'Text content to append.' },
      'parent_block_id': { type: 'string', description: 'Parent block id (default: document root block).' },
    } as JsonObject, ['document_id', 'text']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const id: string = strOf(input, 'document_id', '');
      const text: string = strOf(input, 'text', '');
      if (id.length === 0) throw new Error('document_id is required');
      if (text.length === 0) throw new Error('text is required');
      let parent: string = strOf(input, 'parent_block_id', '');
      const token: string = await resolveAccessToken();
      if (parent.length === 0) {
        // resolveRootBlockId(Android:312):根 block = blocks[0].block_id
        const env0: JsonObject = await feishuFetch(
          `${DOCX_BASE}/v1/documents/${encodeURIComponent(id)}/blocks?page_size=1`, 'GET', token);
        const items0: JsonValue | undefined = dataOf(env0)['items'];
        if (Array.isArray(items0) && items0.length > 0) {
          const bid: JsonValue | undefined = (items0[0] as JsonObject)['block_id'];
          if (typeof bid === 'string') parent = bid;
        }
        if (parent.length === 0) throw new Error('cannot resolve document root block');
      }
      const block: JsonObject = {
        block_type: 2,
        text: {
          elements: [{ text_run: { content: text } as JsonObject } as JsonObject],
          style: {},
        } as JsonObject,
      };
      const env: JsonObject = await feishuFetch(
        `${DOCX_BASE}/v1/documents/${encodeURIComponent(id)}/blocks/${encodeURIComponent(parent)}/children`,
        'POST', token, { index: -1, children: [block] } as JsonObject);
      return textPart({ ok: true, data: env['data'] ?? {} } as JsonObject);
    },
  });

  return [listTool, readTool, blocksTool, searchTool, createTool, appendTool];
};
