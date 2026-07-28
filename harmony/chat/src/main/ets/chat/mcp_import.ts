// mcp_import — McpImportParser.kt 全文 24 行逐字
//
// Android 基准: app/core/ai/mcp/McpImportParser.kt
//   parseMcpServersFromJson(json: String): List<McpServerConfig>
//   - root jsonObject(非对象 → kotlinx jsonObject 抛 IllegalArgumentException,同抛)
//   - root['mcpServers'] 缺失 → emptyList;存在但非对象 → 抛(同 jsonObject 语义)
//   - type 缺省 'streamable_http';url 缺失/非串 → mapNotNull 跳过
//   - headers obj entries → Pair(k, contentOrNull ?: '')
//   - when(type):'sse' → Sse;else → StreamableHTTP(未知类型同落 streamable)
//   - McpCommonOptions(name, headers):id 随机/enable 默认 true/tools 空

import type { JsonObject, JsonValue } from './json.ts';
import type { McpServerConfig } from './mcp_config.ts';
import {
  makeMcpCommonOptions, makeMcpSseServer, makeMcpStreamableHttpServer,
} from './mcp_config.ts';

const asJsonObject = (v: JsonValue, what: string): JsonObject => {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) {
    throw new Error(`Element ${what} is not a JsonObject`);
  }
  return v;
};

export const parseMcpServersFromJson = (json: string): McpServerConfig[] => {
  const root: JsonObject = asJsonObject(JSON.parse(json) as JsonValue, 'root');
  const serversValue: JsonValue | undefined = root['mcpServers'];
  if (serversValue === undefined || serversValue === null) return [];
  const mcpServers: JsonObject = asJsonObject(serversValue, 'mcpServers');
  const out: McpServerConfig[] = [];
  for (const name of Object.keys(mcpServers)) {
    const obj: JsonObject = asJsonObject(mcpServers[name] as JsonValue, name);
    const typeRaw: JsonValue | undefined = obj['type'];
    const type: string = typeof typeRaw === 'string' ? typeRaw : 'streamable_http';
    const urlRaw: JsonValue | undefined = obj['url'];
    if (typeof urlRaw !== 'string') continue; // mapNotNull:url 缺失跳过
    const headersRaw: JsonValue | undefined = obj['headers'];
    const headers: Array<[string, string]> = [];
    if (typeof headersRaw === 'object' && headersRaw !== null && !Array.isArray(headersRaw)) {
      for (const k of Object.keys(headersRaw)) {
        const hv: JsonValue = headersRaw[k];
        // contentOrNull:原始值(number/bool 亦 → 其字面串);非原始 → null → ''
        if (typeof hv === 'string') {
          headers.push([k, hv]);
        } else if (typeof hv === 'number' || typeof hv === 'boolean') {
          headers.push([k, String(hv)]);
        } else {
          headers.push([k, '']);
        }
      }
    }
    const commonOptions = makeMcpCommonOptions({ name, headers });
    if (type === 'sse') {
      out.push(makeMcpSseServer({ commonOptions, url: urlRaw }));
    } else {
      out.push(makeMcpStreamableHttpServer({ commonOptions, url: urlRaw }));
    }
  }
  return out;
};
