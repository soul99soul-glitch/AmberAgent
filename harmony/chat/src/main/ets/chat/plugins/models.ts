import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePartTool } from '../message.ts';
import type { RecipeEnvelope, RecipeInputType, RecipeManifest, RecipeRunCheckpoint } from '../recipes/models.ts';

export const PLUGIN_SCHEMA: string = 'amber.plugin.v1';
export const PLUGIN_ARCHIVE_SCHEMA: string = 'amber.plugin.archive.v1';
export const PLUGIN_MAX_FILES: number = 32;
export const PLUGIN_MAX_FILE_BYTES: number = 256 * 1024;
export const PLUGIN_MAX_PACKAGE_BYTES: number = 1024 * 1024;
export const PLUGIN_MAX_ARCHIVE_BYTES: number = 2 * 1024 * 1024;
export const PLUGIN_PACKAGE_HASH_DOMAIN: string = 'amber.plugin.package.v1\0';
export const PLUGIN_SIGNATURE_DOMAIN: string = 'amber.plugin.signature.v1\0';

export type PluginOutputType = 'json' | 'object' | 'array' | 'string' | 'number' | 'boolean';
export type PluginHttpMethod = 'GET' | 'HEAD' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
export type PluginEnvelope = RecipeEnvelope;
export interface PluginIssue { code: string; path: string; message: string; }
/** Wire fields deliberately retain the iOS manifest spelling. */
export interface PluginRemoteManifest {
  kind: 'mcp' | 'openapi'; server?: string; tool?: string; url?: string; method?: string;
}
export interface PluginCommandManifest { runtime: string; entry: string; stdin_input?: string; }
export interface PluginToolManifest {
  name: string; description?: string; recipe?: string; script?: string;
  remote?: PluginRemoteManifest; command?: PluginCommandManifest;
  host_tools: string[]; inputs: Record<string, RecipeInputType>; output: PluginOutputType;
  input_schema?: JsonObject; output_schema?: JsonObject; timeout_ms: number; max_output_chars: number;
}
export interface PluginCapabilities {
  workspaceReadPrefixes: string[]; workspaceWritePrefixes: string[]; networkDomains: string[];
  webMountActions: string[]; localRuntimes: string[];
}
export interface PluginDirectoryMetadata {
  publisher: string; homepage_url?: string; support_url?: string; privacy_url?: string; minimum_age?: number;
}
export interface PluginManifest {
  schema: string; id: string; name: string; version: string; description: string;
  tools: PluginToolManifest[]; capabilities: PluginCapabilities; backgroundAllowed: boolean;
  directory?: PluginDirectoryMetadata;
}
/** JSON-safe bytes: checkpoints must never contain native handles or typed-array objects. */
export interface PluginFile { path: string; data: number[]; }
export type PluginImplementation =
  | { kind: 'recipe'; manifest: RecipeManifest }
  | { kind: 'javascript'; source: string; hostTools: string[] }
  | { kind: 'command'; runtime: 'embedded_python'; entry: string; source: string; stdinInput: string | null }
  | { kind: 'remote'; remote: PluginRemoteManifest };
export interface PluginResolvedTool {
  name: string; description: string; inputSchema: JsonObject; outputSchema: JsonObject | null;
  output: PluginOutputType; timeoutMs: number; maxOutputChars: number;
  implementation: PluginImplementation; primitiveTools: string[]; envelope: PluginEnvelope;
}
export interface PluginDescriptor extends PluginResolvedTool {
  pluginId: string; toolId: string; version: string; packageHash: string;
  capabilities: PluginCapabilities; backgroundAllowed: boolean;
}
export interface PluginPackage {
  manifest: PluginManifest; hash: string; files: PluginFile[]; fileHashes: Record<string, string>;
  tools: PluginDescriptor[]; envelope: PluginEnvelope;
}
export interface PluginValidationResult {
  issues: PluginIssue[]; tools: PluginResolvedTool[]; primitiveTools: string[]; envelope: PluginEnvelope | null;
}
export interface PluginSource { kind: 'directory' | 'archive'; workspacePath: string; }
export interface PluginSignature {
  algorithm: 'ed25519'; keyId: string; publicKeyBase64: string; signatureBase64: string;
}
export interface PluginTrust {
  tier: 'built_in' | 'signed' | 'local_unsigned'; publisherTrusted: boolean;
  keyId: string | null; fingerprint: string | null; signature: PluginSignature | null;
}
export interface PluginTrustedKey { fingerprint: string; publicKeyBase64: string; label: string; }
export type PluginFailureKind = 'timeout' | 'schema' | 'exception' | 'remote';
export interface PluginDiagnostic {
  id: string; timeMs: number; toolId: string; kind: PluginFailureKind; detail: string;
}
export interface PluginHealth {
  pluginId: string; packageHash: string; consecutiveFailures: number;
  quarantinedAt: number | null; quarantineReason: string | null; diagnostics: PluginDiagnostic[];
}
export interface InstalledPlugin {
  id: string; currentHash: string | null; package: PluginPackage | null; configuredEnabled: boolean; enabled: boolean;
  trust: PluginTrust; health: PluginHealth | null; errorCode: string | null; errorMessage: string | null;
}
export interface PluginReadResult { candidate: PluginPackage; trust: PluginTrust; }
export interface PluginImportPreview extends PluginReadResult {
  source: PluginSource; baseHash: string | null; permissionExpanded: boolean; permissionDiff: string[]; enable: boolean;
}
export interface PluginReceipt {
  id: string; hash: string; changed: boolean; enabled: boolean; permissionExpanded: boolean; trust: PluginTrust;
}
export interface PluginTestContext {
  kind: 'candidate_test'; candidateHash: string; expectedProvided: boolean; expectedResult: JsonValue;
}
export interface PluginTestPlan {
  descriptor: PluginDescriptor; candidateHash: string; inputs: JsonObject;
  expectedProvided: boolean; expectedResult: JsonValue;
}
export interface PluginRunCheckpoint {
  kind: 'run'; executionId: string; descriptor: PluginDescriptor; inputs: JsonObject;
  phase: 'ready' | 'running' | 'awaiting_approval' | 'started' | 'finished';
  pendingStep: UIMessagePartTool | null; pendingCallId: string | null;
  recipeState: RecipeRunCheckpoint | null; test: PluginTestContext | null;
}
export interface PluginImportCheckpoint { kind: 'import'; preview: PluginImportPreview; }
export type PluginCheckpoint = PluginRunCheckpoint | PluginImportCheckpoint;

export interface PluginJsRequest {
  executionId: string; source: string; inputJson: string; hostTools: string[];
  timeoutMs: number; maxOutputChars: number;
}
export type PluginJsEvent =
  | { type: 'host_call'; sessionId: string; callId: string; toolName: string; argsJson: string }
  | { type: 'finished'; sessionId: string; resultJson: string; logs: string[] }
  | { type: 'failed'; sessionId: string; errorCode: string; message: string; logs: string[]; abandoned: boolean };
export interface PluginHttpRequest {
  url: string; method: PluginHttpMethod; input: JsonObject; networkDomains: string[];
  timeoutMs: number; maxOutputChars: number;
}
/** Body is complete UTF-8. The runner owns JSON/output-schema interpretation. */
export interface PluginHttpResult { status: number; body: string; }
export class PluginHttpError extends Error {
  readonly code: string; readonly mayHaveApplied: boolean;
  constructor(code: string, message: string, mayHaveApplied: boolean) {
    super(message); this.name = 'PluginHttpError'; this.code = code; this.mayHaveApplied = mayHaveApplied;
  }
}
