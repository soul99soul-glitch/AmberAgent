import type { JsonObject, JsonValue } from '../json.ts';
import { normalizeWorkspacePath } from '../workspace.ts';
import type { PluginDescriptor } from './models.ts';

// The platform retains the loaded-page domain gate after an action's acknowledgement.
export const supportedPluginWebMountActions: string[] = [
  'wm_open', 'wm_reload', 'wm_tab_select', 'wm_state', 'wm_extract', 'wm_screenshot',
  'wm_read_page', 'wm_find', 'wm_wait', 'wm_observe', 'wm_click', 'wm_back', 'wm_forward',
  'wm_type', 'wm_select', 'wm_keys', 'wm_scroll', 'wm_tab_open', 'wm_tab_close', 'wm_tab_list',
];
const workspaceRead: string[] = ['file_list', 'file_read', 'file_search'];
const workspaceWrite: string[] = ['file_write', 'file_edit', 'file_move'];
export const pluginPrimitiveSupported = (name: string): boolean => workspaceRead.includes(name) ||
  workspaceWrite.includes(name) || supportedPluginWebMountActions.includes(name) ||
  ['scrape_web', 'tool_search', 'tools_list', 'ask_user'].includes(name);
export class PluginBrokerError extends Error {
  readonly code: string = 'capability_denied';
}
const denied = (detail: string): never => { throw new PluginBrokerError(detail); };
const stringArgument = (input: JsonObject, key: string, fallback?: string): string => {
  const value = input[key] ?? fallback;
  if (typeof value !== 'string') return denied(key + ' must be a string.');
  return value;
};
const allowPath = (path: string, prefixes: string[]): void => {
  const normalized = normalizeWorkspacePath(path);
  if (!prefixes.some((prefix) => {
    const allowed = normalizeWorkspacePath(prefix);
    return allowed === '.' || normalized === allowed || normalized.startsWith(allowed + '/');
  })) denied('Workspace path is outside the declared capability: ' + normalized);
};
// Pure domain-side authority check. The platform parses the actual request with its official URL API.
export const pluginURLHost = (raw: string): { scheme: 'http' | 'https'; hostname: string } | null => {
  if (/[\\\u0000-\u001f\u007f]/.test(raw)) return null;
  const match = /^(https?):\/\/([^/?#]+)(?:[/?#]|$)/i.exec(raw);
  if (!match) return null;
  const authority = match[2]!;
  if (authority.includes('@') || authority.includes('%')) return null;
  const hostPort = /^([^:]+)(?::([0-9]+))?$/.exec(authority);
  if (!hostPort) return null;
  if (hostPort[2] !== undefined) {
    const port = Number(hostPort[2]);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return null;
  }
  const hostname = hostPort[1]!.toLowerCase().replace(/\.$/, '');
  if (hostname.length > 253 || !hostname.split('.').every((label) =>
    /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) return null;
  return { scheme: match[1]!.toLowerCase() as 'http' | 'https', hostname };
};
export const pluginNetworkURLAllowed = (url: string, domains: string[]): boolean => {
  const parsed = pluginURLHost(url);
  return parsed !== null && domains.some((domain) => {
    const allowed = domain.toLowerCase().replace(/\.$/, '');
    return parsed.hostname === allowed || parsed.hostname.endsWith('.' + allowed);
  });
};
export const checkPluginHostCall = (descriptor: PluginDescriptor, name: string, value: JsonValue): JsonObject => {
  if (!pluginPrimitiveSupported(name) || !descriptor.primitiveTools.includes(name)) denied('Host tool is not declared or supported: ' + name);
  if (value === null || typeof value !== 'object' || Array.isArray(value)) denied('Host arguments must be a JSON object.');
  const input = value as JsonObject;
  const capabilities = descriptor.capabilities;
  if (workspaceRead.includes(name)) allowPath(stringArgument(input, 'path', name === 'file_read' ? undefined : '.'), capabilities.workspaceReadPrefixes);
  else if (name === 'file_move') {
    const source = stringArgument(input, 'source_path');
    allowPath(source, capabilities.workspaceReadPrefixes); allowPath(source, capabilities.workspaceWritePrefixes);
    allowPath(stringArgument(input, 'target_path'), capabilities.workspaceWritePrefixes);
  } else if (workspaceWrite.includes(name)) {
    allowPath(stringArgument(input, 'path'), capabilities.workspaceWritePrefixes);
    if (name === 'file_edit') allowPath(stringArgument(input, 'path'), capabilities.workspaceReadPrefixes);
  } else if (name === 'scrape_web') {
    if (!pluginNetworkURLAllowed(stringArgument(input, 'url'), capabilities.networkDomains)) denied('URL is outside the declared network domains.');
  } else if (name.startsWith('wm_')) {
    if (!capabilities.webMountActions.includes(name)) denied('WebMount action is not declared: ' + name);
    if (capabilities.networkDomains.length === 0) denied('WebMount requires declared network domains.');
    if (name === 'wm_open' && !pluginNetworkURLAllowed(stringArgument(input, 'url'), capabilities.networkDomains)) {
      denied('WebMount URL is outside the declared network domains.');
    }
  }
  return input;
};
