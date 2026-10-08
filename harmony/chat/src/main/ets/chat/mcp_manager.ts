// mcp_manager — McpManager 全文移植(513 行,amber 自有代码)
//
// Android 基准:app/src/main/java/app/amber/core/ai/mcp/McpManager.kt(513 行全文)
//   常量:MAX_RECONNECT_ATTEMPTS=5/BASE 1000ms/MAX 30000ms(:50-52)
//   init:settingsFlow.map{mcpServers}.collect → enable 过滤 + checkDifferent(id)
//     → toAdd launch runCatching addClient / toRemove launch removeClient(:85-114)
//   getClient:id 匹配(:116-118)
//   getAllAvailableTools:enable && id ∈ assistant.mcpServers → tools filter enable(:120-131)
//   callTool:'Failed to execute tool, because no such tool'/'...no such mcp client
//     for the tool'(:136/:139);transport==null → connect;timeout 120s(:143-152);
//     content 映射 Text/Image→file part/else→JsonInstant 串(:153-159)
//   callConfiguredTool:require 'tool_name is required'(:168);error 'MCP tool not
//     found in enabled servers: $toolName'(:175)/'MCP tool not found on
//     ${server.name}: $toolName'(:177)/require 'MCP tool is disabled:
//     ${name}/$toolName'(:178)/'MCP client is not connected: ${name}'(:185)(:162-205)
//   convertImageContentToFilePart:Base64.decode + MimeTypeMap ext ?: 'bin' +
//     saveUploadFromBytes('mcp_image.$ext') + getFile.toUri(:207-219)
//   getTransport:kind 分派 + commonOptions.headers 注入(:221-249)
//   addClient:removeClient 先 + cancelReconnect + attempts=0;transport 回调
//     status==Connected 才 scheduleReconnect;clients[config]=client;
//     runCatching{Connecting→connect→sync→Connected→attempts=0}
//     onFailure → Error(msg ?: 类名)(:251-295)
//   sync:listTools 合并(新增 enable=true/更新 description+inputSchema/removeIf 缺席)
//     + clients re-key(config.clone(:340-347))(:297-360)
//   syncAll:clients.keys 快照逐个 runCatching(:362-370)
//   removeClient:cancelReconnect + id 匹配 entries close+remove+状态摘除 +
//     attempts 摘除(:372-386)
//   scheduleReconnect:>5 → Error('连接断开，已达最大重连次数')(:395);
//     退避 1000*2^(min(attempt-1,10)) cap 30000(:441-445);job:setStatus
//     Reconnecting→delay→配置仍启用?→reconnectClient;CancellationException 重抛/
//     Exception → 递归 scheduleReconnect(:388-434)
//   reconnectClient:旧 entry close+remove → 新 client+回调 → Connecting→connect→
//     sync→Connected→attempts=0(:447-487)
//   McpJson(:501-509)/ToolSchema.toSchema(properties ?: {}, required :511-513)
//
// 端口:settings(SettingsAggregator 快照/更新/订阅)、files(字节落盘+MimeTypeMap)、
//   http(D-118 McpHttpPort);launch/delay 可注入(测试)。
// 偏差登记:
// - clients 键:Kotlin data class 结构相等 → JS 恒等键;sync/addClient/reconnect
//   内部均持同一对象引用,外部查询一律 id 匹配(与 Android 所有使用点语义一致)
// - setStatus/emit 同步化(StateFlow.emit 顺序语义 = 调用序即时生效);getStatus
//   Flow → subscribeStatuses(首发射 = 当前快照,对齐 StateFlow.map 初值)
// - CancellationException:JS 以 cancelled 旗标等价(delay 后检查,静默返回)
// - printStackTrace/Log → log 回调(默认 noop,日志适配同例)
// - Base64.decode 域内纯实现(标准字母表+padding;kotlin.io.encoding.Base64 等价)

import type { JsonObject } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { McpClient } from './mcp_protocol.ts';
import type { McpTransport, McpServerTool, McpContentBlock } from './mcp_protocol.ts';
import {
  McpSseClientTransport, McpStreamableHttpTransport,
} from './mcp_transports.ts';
import type { McpHttpPort } from './mcp_transports.ts';
import {
  makeMcpTool, cloneMcpServerConfig, MCP_STATUS_IDLE,
  MCP_STATUS_CONNECTING, MCP_STATUS_CONNECTED,
  mcpStatusReconnecting, mcpStatusError,
} from './mcp_config.ts';
import type { McpServerConfig, McpTool, McpStatus } from './mcp_config.ts';
import type { UIMessagePartText, UIMessagePartImage } from './message.ts';
import type { InputSchemaObj } from './tool.ts';

export type McpMessagePart = UIMessagePartText | UIMessagePartImage;

// ===== 端口 =====

export interface McpSettingsPort {
  // settingsStore.settingsFlow.value 快照
  getMcpServers(): McpServerConfig[];
  getCurrentAssistantMcpServerIds(): string[];
  // settingsStore.update { old -> new }(mcpServers 字段级 RMW 由 entry 保证)
  updateMcpServers(updater: (old: McpServerConfig[]) => McpServerConfig[]): void;
  // settingsFlow.map{mcpServers}.collect(每次发射,含初值)
  subscribeMcpServers(listener: (configs: McpServerConfig[]) => void): () => void;
}

export interface McpFilesPort {
  // FilesManager.saveUploadFromBytes + getFile(entity).toUri() 折叠为一次(返回 uri 串)
  saveUploadFromBytes(bytes: Uint8Array, displayName: string, mimeType: string): Promise<string>;
  // android.webkit.MimeTypeMap.getExtensionFromMimeType(无 → null,domain 补 'bin')
  extensionFromMimeType(mimeType: string): string | null;
}

export interface McpManagerDeps {
  http: McpHttpPort;
  settings: McpSettingsPort;
  files: McpFilesPort;
  // appScope.launch 等价(默认:浮空 promise,catch 已内挂)
  launch?: (task: () => Promise<void>) => void;
  // delay(默认真实 setTimeout;测试注入瞬发)
  delay?: (ms: number) => Promise<void>;
  // Log/printStackTrace 适配(默认 noop)
  log?: (msg: string) => void;
}

// ===== 常量(:50-52) =====

export const MCP_MAX_RECONNECT_ATTEMPTS: number = 5;
export const MCP_BASE_RECONNECT_DELAY_MS: number = 1000;
export const MCP_MAX_RECONNECT_DELAY_MS: number = 30000;

// 退避(:441-445 逐字):1000 * 2^(min(attempt-1,10)),cap 30000
export const mcpCalculateBackoffDelay = (attempt: number): number => {
  const shift: number = Math.min(attempt - 1, 10);
  return Math.min(MCP_BASE_RECONNECT_DELAY_MS * (2 ** shift), MCP_MAX_RECONNECT_DELAY_MS);
};

// checkDifferent(core/utils):toAdd = other 中 this 无;toRemove = this 中 other 无
const checkDifferent = <T>(current: T[], other: T[],
  eq: (a: T, b: T) => boolean): { toAdd: T[]; toRemove: T[] } => ({
  toAdd: other.filter((n: T): boolean => !current.some((c: T): boolean => eq(c, n))),
  toRemove: current.filter((c: T): boolean => !other.some((n: T): boolean => eq(c, n))),
});

// kotlin.io.encoding.Base64.decode 等价(标准字母表)
export const base64Decode = (data: string): Uint8Array => {
  const alphabet: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const clean: string = data.replace(/[\r\n]/g, '');
  const out: number[] = [];
  let acc: number = 0;
  let bits: number = 0;
  for (const ch of clean) {
    if (ch === '=') break;
    const v: number = alphabet.indexOf(ch);
    if (v < 0) throw new Error(`Invalid base64 character: ${ch}`);
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out.push((acc >> bits) & 0xff);
    }
  }
  return new Uint8Array(out);
};

interface ReconnectHandle {
  cancel: () => void;
}

interface ClientEntry {
  config: McpServerConfig;
  client: McpClient;
  intentionalClose: boolean;
}

const connectionConfigKey = (config: McpServerConfig): string => JSON.stringify({
  kind: config.kind,
  url: config.url,
  headers: config.commonOptions.headers,
});

export class McpManager {
  private readonly deps: Required<Omit<McpManagerDeps, 'http' | 'settings' | 'files'>>;
  private readonly http: McpHttpPort;
  private readonly settings: McpSettingsPort;
  private readonly files: McpFilesPort;
  private readonly clients: Map<string, ClientEntry> = new Map();
  private readonly generations: Map<string, number> = new Map<string, number>();
  private readonly reconnectJobs: Map<string, ReconnectHandle> = new Map();
  private readonly reconnectAttempts: Map<string, number> = new Map();
  private reconcileTail: Promise<void> = Promise.resolve();
  // MutableStateFlow<Map<Uuid, McpStatus>>(:83)
  private syncingStatus: Map<string, McpStatus> = new Map();
  private readonly statusListeners: Array<(m: ReadonlyMap<string, McpStatus>) => void> = [];
  private readonly unsubscribeSettings: () => void;

  constructor(deps: McpManagerDeps) {
    this.http = deps.http;
    this.settings = deps.settings;
    this.files = deps.files;
    this.deps = {
      launch: deps.launch ?? ((task: () => Promise<void>): void => {
        task().catch((): void => {});
      }),
      delay: deps.delay ?? ((ms: number): Promise<void> => new Promise(
        (resolve): void => {
          setTimeout(resolve, ms);
        })),
      log: deps.log ?? ((): void => {}),
    };

    // init(:85-114). Settings callbacks are serialized; each queued pass reads
    // the latest requested snapshot before mutating clients.
    this.unsubscribeSettings = this.settings.subscribeMcpServers(
      (mcpServerConfigs: McpServerConfig[]): void => {
        const snapshot: McpServerConfig[] = mcpServerConfigs.map(
          (config: McpServerConfig): McpServerConfig => cloneMcpServerConfig(config));
        const run: Promise<void> = this.reconcileTail
          .catch((): void => {})
          .then((): Promise<void> => this.reconcileConfigs(snapshot));
        this.reconcileTail = run;
        this.deps.launch(async (): Promise<void> => {
          try {
            await run;
          } catch (e) {
            this.deps.log(String(e));
          }
        });
      });
  }

  private async reconcileConfigs(configs: McpServerConfig[]): Promise<void> {
    const enabled: McpServerConfig[] = configs.filter(
      (config: McpServerConfig): boolean => config.commonOptions.enable);
    const desiredIds: string[] = enabled.map((config: McpServerConfig): string => config.id);
    for (const entry of [...this.clients.values()]) {
      const desired: McpServerConfig | undefined = enabled.find(
        (config: McpServerConfig): boolean => config.id === entry.config.id);
      if (desired === undefined ||
        connectionConfigKey(desired) !== connectionConfigKey(entry.config)) {
        await this.removeClient(entry.config);
      }
    }
    for (const config of enabled) {
      const entry: ClientEntry | undefined = this.clients.get(config.id);
      if (entry === undefined) {
        await this.addClient(config);
      } else {
        entry.config = config;
      }
    }
    this.deps.log(`reconciled configs: ${desiredIds}`);
  }

  // :116-118
  getClient(config: McpServerConfig): McpClient | null {
    return this.clients.get(config.id)?.client ?? null;
  }

  // :120-131
  // assistantIds:调用方(工具构建/执行)的按轮快照 — 全局 provider 会被
  // 并存的多个 ChatPage 互相改写,读侧必须以本轮 assistant 为准
  getAllAvailableTools(assistantIds?: string[]): McpTool[] {
    const ids: string[] = assistantIds ?? this.settings.getCurrentAssistantMcpServerIds();
    return this.settings.getMcpServers()
      .filter((c: McpServerConfig): boolean =>
        c.commonOptions.enable && ids.includes(c.id))
      .flatMap((c: McpServerConfig): McpTool[] =>
        c.commonOptions.tools
          .filter((t: McpTool): boolean => t.enable)
          .map((t: McpTool): McpTool => ({ ...t, serverId: c.id })));
  }

  // :133-160
  async callTool(toolName: string, args: JsonObject, serverId?: string,
    signal?: AbortSignalLike, assistantIds?: string[]): Promise<McpMessagePart[]> {
    const available: McpTool[] = this.getAllAvailableTools(assistantIds).filter(
      (candidate: McpTool): boolean => candidate.name === toolName && candidate.enable &&
        (serverId === undefined || candidate.serverId === serverId));
    if (available.length === 0) {
      return [{ type: 'text', text: 'Failed to execute tool, because no such tool', metadata: null }];
    }
    if (available.length > 1) {
      throw new Error(`Failed to execute tool, because tool is ambiguous: ${toolName}`);
    }
    const tool: McpTool = available[0];
    const scopedIds: string[] = assistantIds ?? this.settings.getCurrentAssistantMcpServerIds();
    const config: McpServerConfig | undefined = this.settings.getMcpServers().find(
      (candidate: McpServerConfig): boolean => candidate.id === tool.serverId &&
        candidate.commonOptions.enable && scopedIds.includes(candidate.id) &&
        candidate.commonOptions.tools.some(
          (candidateTool: McpTool): boolean => candidateTool.name === toolName && candidateTool.enable));
    if (config === undefined) {
      return [{
        type: 'text', text: 'Failed to execute tool, because no such mcp client for the tool', metadata: null,
      }];
    }
    const entry: ClientEntry | undefined = this.clients.get(config.id);
    if (entry === undefined) {
      return [{
        type: 'text', text: 'Failed to execute tool, because no such mcp client for the tool', metadata: null,
      }];
    }
    const client: McpClient = entry.client;
    this.deps.log(`callTool: ${toolName} keys=${Object.keys(args)}`);
    if (!client.hasTransport) await client.connect(this.getTransport(config));
    const result = await client.callTool(tool.name, args, 120000, signal);
    return this.mapContent(result.content, result.isError, result.structuredContent);
  }

  // :162-205
  async callConfiguredTool(serverId: string | null, serverName: string | null,
    toolName: string, args: JsonObject,
    signal?: AbortSignalLike, assistantIds?: string[]): Promise<McpMessagePart[]> {
    if (toolName.trim().length === 0) throw new Error('tool_name is required');
    let candidates: McpServerConfig[] = this.settings.getMcpServers()
      .filter((config: McpServerConfig): boolean =>
        config.commonOptions.enable &&
        (assistantIds === undefined || assistantIds.includes(config.id)) &&
        (serverId === null || serverId.trim().length === 0 || config.id === serverId) &&
        (serverName === null || serverName.trim().length === 0 ||
          config.commonOptions.name === serverName) &&
        config.commonOptions.tools.some((tool: McpTool): boolean => tool.name === toolName));
    if (candidates.length > 1) {
      const callable: McpServerConfig[] = candidates.filter((config: McpServerConfig): boolean =>
        config.commonOptions.tools.some(
          (tool: McpTool): boolean => tool.name === toolName && tool.enable));
      if (callable.length === 1) candidates = callable;
      else throw new Error(`MCP tool is ambiguous across enabled servers: ${toolName}`);
    }
    const server: McpServerConfig | undefined = candidates[0];
    if (server === undefined) {
      throw new Error(`MCP tool not found in enabled servers: ${toolName}`);
    }
    const tool: McpTool | undefined = server.commonOptions.tools
      .find((t: McpTool): boolean => t.name === toolName);
    if (tool === undefined) {
      throw new Error(`MCP tool not found on ${server.commonOptions.name}: ${toolName}`);
    }
    if (!tool.enable) {
      throw new Error(`MCP tool is disabled: ${server.commonOptions.name}/${toolName}`);
    }

    let client: McpClient | null = this.getClient(server);
    if (client === null) {
      await this.addClient(server);
      client = this.getClient(server);
    }
    if (client === null) {
      throw new Error(`MCP client is not connected: ${server.commonOptions.name}`);
    }
    const liveConfig: McpServerConfig = this.clients.get(server.id)?.config ?? server;
    if (!client.hasTransport) await client.connect(this.getTransport(liveConfig));
    this.deps.log(
      `callConfiguredTool: ${server.commonOptions.name}/${toolName} keys=${Object.keys(args)}`);
    const result = await client.callTool(tool.name, args, 120000, signal);
    return this.mapContent(result.content, result.isError, result.structuredContent);
  }

  // content 映射(:153-159/:198-204 同一 when)
  private async mapContent(content: McpContentBlock[], isError: boolean | null = null,
    structuredContent: JsonObject | null = null): Promise<McpMessagePart[]> {
    const metadata: JsonObject | null = structuredContent === null
      ? null : { structuredContent };
    const out: McpMessagePart[] = [];
    for (const block of content) {
      if (block.type === 'text') {
        out.push({ type: 'text', text: block.text, metadata });
      } else if (block.type === 'image') {
        const image: UIMessagePartImage = await this.convertImageContentToFilePart(
          block.data, block.mimeType);
        out.push({ ...image, metadata });
      } else {
        out.push({ type: 'text', text: JSON.stringify(block.raw), metadata });
      }
    }
    if (out.length === 0 && structuredContent !== null) {
      out.push({ type: 'text', text: JSON.stringify(structuredContent), metadata });
    }
    if (isError === true) {
      const message: string = out
        .filter((part: McpMessagePart): boolean => part.type === 'text')
        .map((part: McpMessagePart): string =>
          part.type === 'text' ? part.text : '')
        .filter((text: string): boolean => text.trim().length > 0)
        .join('\n') || 'MCP tool returned an error';
      out.push({
        type: 'text',
        text: JSON.stringify({ status: 'failed', message, recoverable: true }),
        metadata: structuredContent === null ? { isError: true } : { isError: true, structuredContent },
      });
    }
    return out;
  }

  // :207-219
  private async convertImageContentToFilePart(data: string,
    mimeType: string): Promise<UIMessagePartImage> {
    const bytes: Uint8Array = base64Decode(data);
    const ext: string = this.files.extensionFromMimeType(mimeType) ?? 'bin';
    const uri: string = await this.files.saveUploadFromBytes(
      bytes, `mcp_image.${ext}`, mimeType);
    this.deps.log(`convertImageContentToFilePart: saved mcp image to ${uri}`);
    return { type: 'image', url: uri, metadata: null };
  }

  // :221-249
  private getTransport(config: McpServerConfig): McpTransport {
    if (config.kind === 'sse') {
      return new McpSseClientTransport(this.http, config.url, config.commonOptions.headers);
    }
    return new McpStreamableHttpTransport(this.http, config.url, config.commonOptions.headers);
  }

  // :251-295
  // generation token:await client.close()/connect() 期间并发的 add/remove/reconnect
  // 会为同一 server 装入新 client;旧流程恢复后不得误删新 client/覆盖新状态
  private nextGeneration(serverId: string): number {
    const next: number = (this.generations.get(serverId) ?? 0) + 1;
    this.generations.set(serverId, next);
    return next;
  }

  private isCurrentClient(serverId: string, generation: number, client: McpClient): boolean {
    const entry: ClientEntry | undefined = this.clients.get(serverId);
    return this.generations.get(serverId) === generation
      && entry !== undefined && entry.client === client;
  }

  async addClient(config: McpServerConfig): Promise<void> {
    await this.removeClient(config); // Remove first
    this.cancelReconnect(config.id);
    this.reconnectAttempts.set(config.id, 0);
    const generation: number = this.nextGeneration(config.id);

    const transport: McpTransport = this.getTransport(config);
    const client: McpClient = new McpClient({ name: config.commonOptions.name, version: '1.0' });
    this.installReconnectHooks(config, transport, client);

    this.clients.set(config.id, { config, client, intentionalClose: false });
    try {
      this.setStatus(config, MCP_STATUS_CONNECTING);
      await client.connect(transport);
      if (!this.isCurrentClient(config.id, generation, client)) return; // 已被替换
      await this.sync(config);
      if (!this.isCurrentClient(config.id, generation, client)) return;
      this.setStatus(config, MCP_STATUS_CONNECTED);
      this.reconnectAttempts.set(config.id, 0); // 重置重连计数
      this.deps.log(`addClient: connected ${config.commonOptions.name}`);
    } catch (e) {
      if (this.isCurrentClient(config.id, generation, client)) {
        this.deps.log(String(e));
        const msg: string = e instanceof Error ? (e.message || e.name) : String(e);
        this.setStatus(config, mcpStatusError(msg));
      }
    }
  }

  // transport 回调(:265-281/:464-478 同一对)
  private installReconnectHooks(config: McpServerConfig, transport: McpTransport,
    client: McpClient): void {
    transport.onClose((): void => {
      this.deps.log(`Transport closed for ${config.commonOptions.name}`);
      const entry: ClientEntry | undefined = this.clients.get(config.id);
      const currentStatus: McpStatus | undefined = this.syncingStatus.get(config.id);
      if (entry !== undefined && entry.client === client && !entry.intentionalClose &&
        currentStatus !== undefined && currentStatus.kind === 'connected') {
        this.scheduleReconnect(config);
      }
    });
    transport.onError((error: Error): void => {
      this.deps.log(`Transport error for ${config.commonOptions.name}: ${error.message}`);
      const entry: ClientEntry | undefined = this.clients.get(config.id);
      const currentStatus: McpStatus | undefined = this.syncingStatus.get(config.id);
      if (entry !== undefined && entry.client === client && !entry.intentionalClose &&
        currentStatus !== undefined && currentStatus.kind === 'connected') {
        this.scheduleReconnect(config);
      }
    });
  }

  // :297-360
  async sync(config: McpServerConfig): Promise<void> {
    const entry: ClientEntry | undefined = this.clients.get(config.id);
    if (entry === undefined) return;
    const client: McpClient = entry.client;
    // sync 全程异步:listTools/写设置期间 client 可能被移除/替换 →
    // 写回前必须确认仍是当前 client(防旧 server 工具表污染设置)
    this.setStatus(config, MCP_STATUS_CONNECTING);

    let serverTools: McpServerTool[];
    try {
      if (!client.hasTransport) await client.connect(this.getTransport(config));
      if (this.clients.get(config.id)?.client !== client) return;
      serverTools = await client.listTools();
    } catch (error) {
      if (this.clients.get(config.id)?.client === client) {
        this.setStatus(config, mcpStatusError(error instanceof Error ? error.message : String(error)));
      }
      throw error;
    }
    if (this.clients.get(config.id)?.client !== client) return;
    this.deps.log(`sync: tools: ${JSON.stringify(serverTools)}`);
    this.settings.updateMcpServers((old: McpServerConfig[]): McpServerConfig[] =>
      old.map((serverConfig: McpServerConfig): McpServerConfig => {
        if (serverConfig.id !== config.id) return serverConfig;
        const common = serverConfig.commonOptions;
        const tools: McpTool[] = [...common.tools];

        // 基于server对比
        for (const serverTool of serverTools) {
          const existing: McpTool | undefined = tools.find(
            (t: McpTool): boolean => t.name === serverTool.name);
          // ToolSchema.toSchema(:511-513)→ InputSchemaObj(type 'object')
          const schema: InputSchemaObj = {
            type: 'object' as const,
            properties: serverTool.inputSchema.properties,
            required: serverTool.inputSchema.required,
          };
          if (serverTool.inputSchema.jsonSchema !== undefined) schema.jsonSchema = serverTool.inputSchema.jsonSchema;
          if (existing === undefined) {
            tools.push(makeMcpTool({
              serverId: config.id,
              name: serverTool.name,
              description: serverTool.description,
              enable: true,
              inputSchema: schema,
              annotations: serverTool.annotations,
            }));
          } else {
            const index: number = tools.indexOf(existing);
            tools[index] = makeMcpTool({
              serverId: config.id,
              name: existing.name,
              description: serverTool.description,
              enable: existing.enable, // copy 仅改 description+inputSchema(:329-332)
              needsApproval: existing.needsApproval,
              inputSchema: schema,
              annotations: serverTool.annotations,
            });
          }
        }

        // 删除不在server内的
        const merged: McpTool[] = tools.filter((tool: McpTool): boolean =>
          serverTools.some((st: McpServerTool): boolean => st.name === tool.name));

        // Tool sync updates the config snapshot, not the client identity.
        const newCommon = { ...common, tools: merged };
        const liveEntry: ClientEntry | undefined = this.clients.get(config.id);
        if (liveEntry !== undefined && liveEntry.client === client) {
          liveEntry.config = cloneMcpServerConfig(config, config.id, newCommon);
        }

        // 返回新的serverConfig，更新到settings store
        return cloneMcpServerConfig(serverConfig, serverConfig.id, newCommon);
      }));

    this.setStatus(config, MCP_STATUS_CONNECTED);
  }

  // :362-370
  async syncAll(): Promise<void> {
    for (const config of [...this.clients.values()].map((entry: ClientEntry): McpServerConfig => entry.config)) {
      try {
        await this.sync(config);
      } catch (e) {
        this.deps.log(String(e));
      }
    }
  }

  // :372-386
  async removeClient(config: McpServerConfig): Promise<void> {
    this.cancelReconnect(config.id);
    const entry: ClientEntry | undefined = this.clients.get(config.id);
    if (entry !== undefined) {
      const generation: number = this.nextGeneration(config.id);
      entry.intentionalClose = true;
      try {
        await entry.client.close();
      } catch (e) {
        this.deps.log(String(e));
      }
      // close 等待期间新 client 可能已装入 → 只清理仍是自己的一代(含状态面,
      // 否则会把并发装入的新 client 的状态一并摘除)
      if (this.isCurrentClient(config.id, generation, entry.client)) {
        this.clients.delete(config.id);
        this.generations.delete(config.id);
        const next: Map<string, McpStatus> = new Map(this.syncingStatus);
        next.delete(config.id);
        this.syncingStatus = next;
        this.emitStatuses();
        this.deps.log(`removeClient: ${entry.config.commonOptions.name}`);
      }
    }
    this.reconnectAttempts.delete(config.id);
  }

  // :388-434
  private scheduleReconnect(config: McpServerConfig): void {
    const configId: string = config.id;
    const currentAttempt: number = (this.reconnectAttempts.get(configId) ?? 0) + 1;

    if (currentAttempt > MCP_MAX_RECONNECT_ATTEMPTS) {
      this.deps.log(`Max reconnect attempts reached for ${config.commonOptions.name}`);
      this.setStatus(config, mcpStatusError('连接断开，已达最大重连次数'));
      return;
    }

    this.reconnectAttempts.set(configId, currentAttempt);

    // 取消之前的重连任务
    this.reconnectJobs.get(configId)?.cancel();

    // 计算指数退避延迟
    const delayMs: number = mcpCalculateBackoffDelay(currentAttempt);
    this.deps.log(`Scheduling reconnect for ${config.commonOptions.name}, ` +
      `attempt ${currentAttempt}/${MCP_MAX_RECONNECT_ATTEMPTS}, delay ${delayMs}ms`);

    let cancelled: boolean = false;
    const task = async (): Promise<void> => {
      try {
        this.setStatus(config, mcpStatusReconnecting(currentAttempt, MCP_MAX_RECONNECT_ATTEMPTS));
        await this.deps.delay(delayMs);
        if (cancelled) return; // CancellationException 等价(头注偏差)

        // 检查配置是否仍然启用
        const currentConfig: McpServerConfig | undefined = this.settings.getMcpServers()
          .find((c: McpServerConfig): boolean => c.id === configId && c.commonOptions.enable);
        if (currentConfig === undefined) {
          this.deps.log(`Config disabled or removed, cancelling reconnect for ${config.commonOptions.name}`);
          return;
        }

        this.deps.log(`Attempting reconnect for ${config.commonOptions.name}`);
        await this.reconnectClient(currentConfig);
      } catch (e) {
        if (cancelled) return;
        this.deps.log(`Reconnect failed for ${config.commonOptions.name}: ${String(e)}`);
        // 继续尝试重连
        this.scheduleReconnect(config);
      }
    };
    const handle: ReconnectHandle = {
      cancel: (): void => {
        cancelled = true;
      },
    };
    this.reconnectJobs.set(configId, handle);
    task().catch((): void => {});
  }

  // :436-439
  private cancelReconnect(configId: string): void {
    this.reconnectJobs.get(configId)?.cancel();
    this.reconnectJobs.delete(configId);
  }

  // :447-487
  private async reconnectClient(config: McpServerConfig): Promise<void> {
    // 先关闭旧客户端;close 等待期间并发 add/reconnect 可能已装入新 client,
    // 旧流程恢复后不得误删 — 先失效旧代,删除前做身份校验
    const oldEntry: ClientEntry | undefined = this.clients.get(config.id);
    if (oldEntry !== undefined) {
      oldEntry.intentionalClose = true;
      try {
        await oldEntry.client.close();
      } catch (e) {
        this.deps.log(String(e));
      }
      if (this.clients.get(config.id)?.client === oldEntry.client) {
        this.clients.delete(config.id);
      }
    }
    const generation: number = this.nextGeneration(config.id);

    const transport: McpTransport = this.getTransport(config);
    const client: McpClient = new McpClient({ name: config.commonOptions.name, version: '1.0' });
    this.installReconnectHooks(config, transport, client);

    this.clients.set(config.id, { config, client, intentionalClose: false });
    this.setStatus(config, MCP_STATUS_CONNECTING);
    await client.connect(transport);
    if (!this.isCurrentClient(config.id, generation, client)) return;
    await this.sync(config);
    if (!this.isCurrentClient(config.id, generation, client)) return;
    this.setStatus(config, MCP_STATUS_CONNECTED);
    this.reconnectAttempts.set(config.id, 0); // 重置重连计数
    this.deps.log(`Reconnected successfully: ${config.commonOptions.name}`);
  }

  // :489-493(StateFlow.emit → 同步映射+通知,头注偏差)
  private setStatus(config: McpServerConfig, status: McpStatus): void {
    const next: Map<string, McpStatus> = new Map(this.syncingStatus);
    next.set(config.id, status);
    this.syncingStatus = next;
    this.emitStatuses();
  }

  private emitStatuses(): void {
    for (const l of this.statusListeners) l(this.syncingStatus);
  }

  // :495-497 — Flow.map ?: Idle → 快照 + 订阅(首发射对齐 StateFlow 初值)
  getStatus(config: McpServerConfig): McpStatus {
    return this.syncingStatus.get(config.id) ?? MCP_STATUS_IDLE;
  }

  getStatusMap(): ReadonlyMap<string, McpStatus> {
    return this.syncingStatus;
  }

  subscribeStatuses(listener: (m: ReadonlyMap<string, McpStatus>) => void): () => void {
    this.statusListeners.push(listener);
    listener(this.syncingStatus);
    return (): void => {
      const i: number = this.statusListeners.indexOf(listener);
      if (i >= 0) this.statusListeners.splice(i, 1);
    };
  }

  // 测试/生命周期辅助:退订 settings + 取消全部重连(Android 无对应,appScope 常驻)
  dispose(): void {
    this.unsubscribeSettings();
    for (const id of [...this.reconnectJobs.keys()]) this.cancelReconnect(id);
  }
}
