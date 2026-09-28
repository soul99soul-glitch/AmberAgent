import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool } from '../tool.ts';
import type { RecipeExecutionPort, RecipeLoopAdapter } from '../recipes/ports.ts';
import type { InstalledPlugin, PluginFailureKind, PluginHealth, PluginHttpRequest, PluginHttpResult,
  PluginImportPreview, PluginJsEvent, PluginJsRequest, PluginPackage, PluginReadResult, PluginReceipt,
  PluginSource, PluginTrust, PluginTrustedKey } from './models.ts';

export type PluginExecutionPort = RecipeExecutionPort;
export type PluginLoopAdapter = RecipeLoopAdapter;

export interface PluginStore {
  listInstalled(primitives?: AgentTool[]): Promise<InstalledPlugin[]>;
  readPackage(source: PluginSource, primitives: AgentTool[]): Promise<PluginReadResult>;
  prepareImport(source: PluginSource, primitives: AgentTool[], enable: boolean): Promise<PluginImportPreview>;
  applyImport(preview: PluginImportPreview, primitives: AgentTool[]): Promise<PluginReceipt>;
  setEnabled(id: string, expectedHash: string, enabled: boolean): Promise<void>;
  remove(id: string, expectedHash: string): Promise<void>;
  rollback(id: string, expectedCurrentHash: string): Promise<PluginReceipt>;
  restore(id: string, expectedHash: string): Promise<void>;
  exportArchive(id: string, expectedHash: string): Promise<Uint8Array>;
  recordSuccess(id: string, hash: string): Promise<PluginHealth>;
  recordFailure(id: string, hash: string, toolId: string, kind: PluginFailureKind, detail: string): Promise<PluginHealth>;
  listTrustedKeys(): Promise<PluginTrustedKey[]>;
  addTrustedKey(publicKeyBase64: string, label: string): Promise<PluginTrustedKey>;
  removeTrustedKey(fingerprint: string): Promise<void>;
}
export interface PluginHashPort { sha256(bytes: Uint8Array): Promise<string>; }
export interface PluginSignaturePort {
  verifyEd25519(publicKey: Uint8Array, signature: Uint8Array, message: Uint8Array): Promise<boolean>;
}
export interface PluginArchivePort {
  decode(bytes: Uint8Array, primitives: AgentTool[]): Promise<PluginReadResult>;
  encode(candidate: PluginPackage, trust: PluginTrust): Promise<Uint8Array>;
}
/** All methods resolve at the next event. sessionId equals request.executionId. */
export interface PluginJsPort {
  start(request: PluginJsRequest, signal?: AbortSignalLike): Promise<PluginJsEvent>;
  reply(sessionId: string, callId: string, resultJson: string): Promise<PluginJsEvent>;
  reject(sessionId: string, callId: string, reason: string): Promise<PluginJsEvent>;
  cancel(sessionId: string): void;
  hasSession(sessionId: string): boolean;
}
export interface PluginHttpPort {
  execute(request: PluginHttpRequest, signal?: AbortSignalLike): Promise<PluginHttpResult>;
}
export interface PluginWebMountPort {
  withScope<T>(networkDomains: string[], operation: () => Promise<T>, requireCurrent?: boolean): Promise<T>;
}
/** Resolved against this turn's assistant-permitted MCP snapshot, never guessed aliases. */
export interface PluginMcpPort { resolveMcpPrimitive(serverId: string, toolName: string): AgentTool | null; }
