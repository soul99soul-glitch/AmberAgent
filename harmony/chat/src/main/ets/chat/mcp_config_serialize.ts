// mcp_config_serialize — McpServerConfig kotlinx JSON 线格式(Settings blob 内嵌)
//
// Android 基准:
//   McpConfig.kt(@Serializable,声明序键序;sealed 鉴别名 @SerialName 'sse'/
//     'streamable_http',classDiscriminator 默认 'type' 首键)
//   ai/util/Json.kt(JsonInstant):encodeDefaults=true(全默认亦写出)/
//     explicitNulls=false(null 字段省略)/ignoreUnknownKeys=true
//   kotlinx Pair<String,String> → {"first":..,"second":..}
//   InputSchema.Obj(@SerialName 'object')→ {"type":"object","properties":..,"required":..}
//
// 浮点口径:properties 为任意 JsonObject(模式描述,罕见浮点),JSON.stringify
//   直传(D-016 同款登记;kotlinx Double 1.0 → '1.0' 边缘不逐字)。

import type { JsonObject, JsonValue } from './json.ts';
import type { InputSchemaObj } from './tool.ts';
import { toolParametersToJson } from './tool.ts';
import type { McpCommonOptions, McpServerConfig, McpTool } from './mcp_config.ts';
import {
  makeMcpCommonOptions, makeMcpSseServer, makeMcpStreamableHttpServer, makeMcpTool,
} from './mcp_config.ts';
import { newId } from './ids.ts';

// ===== 工具 =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const esc = (s: string): string => JSON.stringify(s);

const strOr = (o: JsonObject, k: string, dflt: string): string =>
  typeof o[k] === 'string' ? o[k] as string : dflt;

const boolOr = (o: JsonObject, k: string, dflt: boolean): boolean =>
  typeof o[k] === 'boolean' ? o[k] as boolean : dflt;

// ===== InputSchema(Tool.kt:28-36) =====

const serializeInputSchema = (s: InputSchemaObj): string =>
  JSON.stringify(toolParametersToJson(s));

const parseInputSchema = (v: JsonValue | undefined): InputSchemaObj | null => {
  if (!isObj(v)) return null;
  const properties: JsonObject = isObj(v['properties']) ? v['properties'] : {};
  const requiredRaw = Array.isArray(v['required']) ? v['required'] as JsonValue[] : null;
  const required: string[] | null = requiredRaw === null
    ? null
    : requiredRaw.filter((x: JsonValue): boolean => typeof x === 'string')
      .map((x: JsonValue): string => x as string);
  const schema: InputSchemaObj = { type: 'object', properties, required };
  if (Object.keys(v).some((key: string): boolean =>
    key !== 'type' && key !== 'properties' && key !== 'required')) schema.jsonSchema = v;
  return schema;
};

// ===== McpTool(McpConfig.kt:16-25) =====

const serializeMcpTool = (t: McpTool): string =>
  `{"enable":${t.enable},"name":${esc(t.name)}` +
  (t.description !== null ? `,"description":${esc(t.description)}` : '') +
  (t.inputSchema !== null ? `,"inputSchema":${serializeInputSchema(t.inputSchema)}` : '') +
  (t.annotations !== undefined ? `,"annotations":${JSON.stringify(t.annotations)}` : '') +
  `,"needsApproval":${t.needsApproval}}`;

const parseMcpTool = (v: JsonValue): McpTool => {
  const o: JsonObject = isObj(v) ? v : {};
  return makeMcpTool({
    enable: boolOr(o, 'enable', true),
    name: strOr(o, 'name', ''),
    description: typeof o['description'] === 'string' ? o['description'] as string : null,
    inputSchema: parseInputSchema(o['inputSchema']),
    annotations: isObj(o['annotations']) ? o['annotations'] : undefined,
    needsApproval: boolOr(o, 'needsApproval', true),
  });
};

// ===== McpCommonOptions(McpConfig.kt:9-14) =====

const serializeHeaders = (headers: Array<[string, string]>): string =>
  `[${headers.map((h: [string, string]): string =>
    `{"first":${esc(h[0])},"second":${esc(h[1])}}`).join(',')}]`;

const serializeCommonOptions = (c: McpCommonOptions): string =>
  `{"enable":${c.enable},"name":${esc(c.name)},` +
  `"headers":${serializeHeaders(c.headers)},` +
  `"tools":[${c.tools.map(serializeMcpTool).join(',')}]}`;

const parseHeaders = (v: JsonValue | undefined): Array<[string, string]> => {
  if (!Array.isArray(v)) return [];
  const out: Array<[string, string]> = [];
  for (const item of v as JsonValue[]) {
    if (!isObj(item)) continue;
    const first: JsonValue | undefined = item['first'];
    const second: JsonValue | undefined = item['second'];
    if (typeof first === 'string' && typeof second === 'string') {
      out.push([first, second]);
    }
  }
  return out;
};

const parseCommonOptions = (v: JsonValue | undefined): McpCommonOptions => {
  const o: JsonObject = isObj(v) ? v : {};
  const toolsRaw = Array.isArray(o['tools']) ? o['tools'] as JsonValue[] : [];
  return makeMcpCommonOptions({
    enable: boolOr(o, 'enable', true),
    name: strOr(o, 'name', ''),
    headers: parseHeaders(o['headers']),
    tools: toolsRaw.map(parseMcpTool),
  });
};

// ===== McpServerConfig sealed(McpConfig.kt:27-59) =====

const serializeOne = (c: McpServerConfig): string =>
  `{"type":${esc(c.kind)},"id":${esc(c.id)},` +
  `"commonOptions":${serializeCommonOptions(c.commonOptions)},"url":${esc(c.url)}}`;

// kotlinx:sealed 缺 'type' / 未知鉴别名 → SerializationException;此处同抛错
const parseOne = (v: JsonValue): McpServerConfig => {
  const o: JsonObject = isObj(v) ? v : {};
  const type: string = strOr(o, 'type', '');
  const id: string = strOr(o, 'id', newId());
  const commonOptions: McpCommonOptions = parseCommonOptions(o['commonOptions']);
  const url: string = strOr(o, 'url', '');
  if (type === 'sse') {
    return makeMcpSseServer({ id, commonOptions, url });
  }
  if (type === 'streamable_http') {
    return makeMcpStreamableHttpServer({ id, commonOptions, url });
  }
  throw new Error(`Serializer for subclass '${type}' is not found in the polymorphic scope of 'McpServerConfig'`);
};

export const serializeMcpServerConfigList = (list: McpServerConfig[]): string =>
  `[${list.map(serializeOne).join(',')}]`;

export const parseMcpServerConfigList = (raw: string): McpServerConfig[] => {
  const parsed: JsonValue = JSON.parse(raw) as JsonValue;
  if (!Array.isArray(parsed)) return [];
  return (parsed as JsonValue[]).map(parseOne);
};
