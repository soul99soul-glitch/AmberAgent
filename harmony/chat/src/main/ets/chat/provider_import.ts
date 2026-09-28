// amber-provider v1 脱敏导出文件的导入入口：新 ID、空凭证。
import type { ProviderSetting, ProviderModel } from './provider_settings.ts';
import type { JsonObject, JsonValue } from './json.ts';
import {
  makeProviderModel, makeProviderSettingOpenAIVariant,
  makeProviderSettingGoogle, makeProviderSettingClaude,
} from './provider_settings.ts';

const importObject = (value: JsonValue): JsonObject => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Provider 导出内容必须是对象');
  }
  return value;
};

const importString = (doc: JsonObject, key: string, fallback?: string): string => {
  const value = doc[key];
  if (value === undefined && fallback !== undefined) return fallback;
  if (typeof value !== 'string') throw new Error(`${key} 必须是字符串`);
  return value;
};

const importBoolean = (doc: JsonObject, key: string): boolean => {
  const value = doc[key];
  if (value === undefined) return false;
  if (typeof value !== 'boolean') throw new Error(`${key} 必须是布尔值`);
  return value;
};

export const providerFromImportJson = (json: string): ProviderSetting => {
  const doc = importObject(JSON.parse(json) as JsonValue);
  if (doc['format'] !== 'amber-provider' || doc['version'] !== 1) {
    throw new Error('文件不是有效的 Provider 导出');
  }
  const providerType = doc['type'];
  if (providerType !== 'openai' && providerType !== 'google' && providerType !== 'claude') {
    throw new Error('Provider type 不支持');
  }
  const name = importString(doc, 'name');
  const baseUrl = importString(doc, 'baseUrl');
  if (name.trim().length === 0 || baseUrl.trim().length === 0) {
    throw new Error('name 和 baseUrl 不能为空');
  }
  const modelDocs = doc['models'];
  if (!Array.isArray(modelDocs)) throw new Error('models 必须是数组');
  const models: ProviderModel[] = modelDocs.map((value: JsonValue): ProviderModel => {
    const model = importObject(value);
    const modelId = importString(model, 'modelId');
    if (modelId.trim().length === 0) throw new Error('modelId 不能为空');
    const modelType = model['type'];
    if (modelType !== 'chat' && modelType !== 'image' && modelType !== 'embedding') {
      throw new Error('model type 不支持');
    }
    const displayName = importString(model, 'displayName', '');
    return makeProviderModel({
      modelId, displayName: displayName.length > 0 ? displayName : modelId, type: modelType,
    });
  });
  if (providerType === 'google') {
    const authMode = importString(doc, 'authMode', 'api_key');
    if (authMode !== 'api_key' && authMode !== 'gemini_code_assist_oauth' && authMode !== 'antigravity_oauth') {
      throw new Error('Google 授权模式不支持');
    }
    return makeProviderSettingGoogle({
      name, baseUrl, models,
      vertexAI: importBoolean(doc, 'vertexAI'),
      useServiceAccount: importBoolean(doc, 'useServiceAccount'),
      authMode,
      serviceAccountEmail: importString(doc, 'serviceAccountEmail', ''),
      oauthClientId: importString(doc, 'oauthClientId', ''),
      oauthRedirectUri: importString(doc, 'oauthRedirectUri', ''),
      location: importString(doc, 'location', 'us-central1'),
      projectId: importString(doc, 'projectId', ''),
    });
  }
  if (providerType === 'claude') {
    return makeProviderSettingClaude({
      name, baseUrl, models, promptCaching: importBoolean(doc, 'promptCaching'),
    });
  }
  return makeProviderSettingOpenAIVariant({
    name, baseUrl, models,
    chatCompletionsPath: importString(doc, 'chatCompletionsPath', '/chat/completions'),
    useResponseApi: importBoolean(doc, 'useResponseApi'),
  });
};
