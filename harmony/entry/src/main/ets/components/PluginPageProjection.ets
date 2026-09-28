import type { InstalledPlugin, PluginCapabilities, PluginDescriptor, PluginTrust } from '@amber/chat-domain';

export const pluginStatusLabel = (plugin: InstalledPlugin): string => {
  if (plugin.package === null || plugin.errorCode !== null) return '无效';
  if (plugin.health !== null && plugin.health.quarantinedAt !== null) return '已隔离';
  return plugin.enabled ? '已启用' : '已停用';
};

export const pluginTrustLabel = (trust: PluginTrust): string => {
  if (trust.tier === 'built_in') return '内置';
  if (trust.tier === 'local_unsigned') return '本地未签名';
  return trust.publisherTrusted ? '已签名 · 发布者已信任' : '已签名 · 发布者未信任';
};

export const pluginRuntimeLabel = (tool: PluginDescriptor): string => {
  if (tool.implementation.kind === 'recipe') return '包内 Recipe';
  if (tool.implementation.kind === 'javascript') return 'JSVM';
  if (tool.implementation.kind === 'command') return '本机 embedded_python';
  return tool.implementation.remote.kind === 'mcp' ? 'MCP' : 'OpenAPI';
};

export const pluginCapabilityRows = (capabilities: PluginCapabilities): string[] => {
  const rows: string[] = [];
  for (const value of capabilities.workspaceReadPrefixes) rows.push(`Workspace 读取：${value}`);
  for (const value of capabilities.workspaceWritePrefixes) rows.push(`Workspace 写入：${value}`);
  for (const value of capabilities.networkDomains) rows.push(`网络域：${value}`);
  for (const value of capabilities.webMountActions) rows.push(`WebMount：${value}`);
  for (const value of capabilities.localRuntimes) rows.push(`本机运行环境：${value}`);
  return rows;
};
