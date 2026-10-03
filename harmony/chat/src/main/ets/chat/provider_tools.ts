// Agent provider configuration uses the same persisted settings as the provider pages.
// Results deliberately expose credential presence only; HTTP error bodies are never returned.
import type { AbortSignalLike, HttpClient } from '@amber/deepread-domain';
import type { JsonObject, JsonValue } from './json.ts';
import type { KeyValueStore } from './kv_store.ts';
import { loadProviders, saveProviders } from './kv_store.ts';
import type { ProviderSetting, ProviderModel, ModalityFull } from './provider_settings.ts';
import {
  copyProviderSettingWithModels, hasUsableAuth, makeProviderModel,
  makeProviderSettingOpenAIVariant, makeProviderSettingClaude, makeProviderSettingGoogle,
  openAIBrandAvailableAuthModes,
} from './provider_settings.ts';
import type { ModelAbility, OpenAIAuthMode, OpenAIBrand } from './provider_model.ts';
import { openAIAuthHeaders } from './openai_chat_api.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';

export interface ProviderManagementToolsDeps {
  store: KeyValueStore;
  http: HttpClient;
  // Entry shares the providers KV mutex across conversations and subagents.
  withLock: (action: () => Promise<UIMessagePart[]>) => Promise<UIMessagePart[]>;
}

class ProviderInputError extends Error {}
function invalid(message: string): never { throw new ProviderInputError(message); }
const objectOf = (value: JsonValue | undefined): JsonObject => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return invalid('Expected an object');
  return value;
};
const stringOf = (doc: JsonObject, key: string, fallback: string = ''): string => {
  const value = doc[key];
  if (value === undefined) return fallback;
  if (typeof value !== 'string') return invalid(`${key} must be a string`);
  return value.trim();
};
const booleanOf = (doc: JsonObject, key: string, fallback: boolean): boolean => {
  const value = doc[key];
  if (value === undefined) return fallback;
  if (typeof value !== 'boolean') return invalid(`${key} must be a boolean`);
  return value;
};
const enumOf = (doc: JsonObject, key: string, choices: string[], fallback: string): string => {
  const value = stringOf(doc, key, fallback);
  if (!choices.includes(value)) return invalid(`${key} has an unsupported value`);
  return value;
};
const arrayOf = (value: JsonValue | undefined): JsonValue[] => {
  if (!Array.isArray(value)) return invalid('Expected an array');
  return value;
};
const enumArray = (value: JsonValue, choices: string[]): string[] => arrayOf(value).map((item): string => {
  if (typeof item !== 'string' || !choices.includes(item)) return invalid('Unsupported model capability or modality');
  return item;
});
const knownFields = (doc: JsonObject, allowed: string[]): void => {
  if (Object.keys(doc).some((key): boolean => !allowed.includes(key))) invalid('Unknown configuration field; use the tool schema');
};
const endpoint = (url: string): string => {
  // Request paths are concatenated by existing provider APIs. Query/userinfo here
  // would both break that contract and leak credentials through configuration summaries.
  if (!/^https?:\/\/[^\s/?#@]+(?:\/[^\s?#]*)?$/.test(url)) {
    return invalid('baseUrl must be an HTTP(S) base URL without credentials, query or fragment');
  }
  return url.replace(/\/+$/, '');
};
const textResult = (value: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(value), metadata: null }];
const safeFailure = (error: object, fallback: string): UIMessagePart[] => textResult({
  status: 'error', message: error instanceof ProviderInputError ? error.message : fallback,
});

const modelSummary = (model: ProviderModel): JsonObject => ({
  id: model.id, modelId: model.modelId, displayName: model.displayName, type: model.type,
  abilities: model.abilities, inputModalities: model.inputModalities,
  outputModalities: model.outputModalities, contextWindowTokens: model.contextWindowTokens,
});
const providerSummary = (provider: ProviderSetting): JsonObject => {
  // Older/manual configurations may contain query credentials; don't echo those URLs.
  let baseUrl: string = '';
  try { baseUrl = endpoint(provider.baseUrl); } catch { baseUrl = '[invalid base URL]'; }
  const out: JsonObject = {
    id: provider.id, name: provider.name, type: provider.type, enabled: provider.enabled, baseUrl,
    hasApiKey: provider.apiKey.trim().length > 0, usableAuth: hasUsableAuth(provider),
    models: provider.models.map(modelSummary),
  };
  if (provider.type === 'openai') {
    out['authMode'] = provider.authMode;
    out['brand'] = provider.brand;
    out['chatCompletionsPath'] = provider.chatCompletionsPath;
    out['useResponseApi'] = provider.useResponseApi;
  } else if (provider.type === 'google') out['authMode'] = provider.authMode;
  else out['promptCaching'] = provider.promptCaching;
  return out;
};

const mergeModels = (provider: ProviderSetting, values: JsonValue[]): ProviderModel[] => {
  const models = provider.models.slice();
  const seen: string[] = [];
  for (const value of values as JsonValue[]) {
    const doc = objectOf(value);
    knownFields(doc, ['modelId', 'displayName', 'type', 'abilities', 'inputModalities', 'outputModalities',
      'contextWindowTokens', 'customHeaders', 'customBodies']);
    const modelId = stringOf(doc, 'modelId');
    if (!modelId || seen.includes(modelId)) invalid('modelId must be nonempty and unique in each provider patch');
    seen.push(modelId);
    const index = models.findIndex((model): boolean => model.modelId === modelId);
    const type = enumOf(doc, 'type', ['chat', 'image', 'embedding'], index >= 0 ? models[index].type : 'chat');
    const model = index >= 0 ? makeProviderModel(models[index]) : makeProviderModel({
      modelId, displayName: modelId, type: type as ProviderModel['type'],
    });
    model.type = type as ProviderModel['type'];
    model.displayName = stringOf(doc, 'displayName', model.displayName);
    if (doc['abilities'] !== undefined) model.abilities = enumArray(doc['abilities'], ['tool', 'reasoning']) as ModelAbility[];
    if (doc['inputModalities'] !== undefined) model.inputModalities = enumArray(doc['inputModalities'], ['text', 'image', 'audio']) as ModalityFull[];
    if (doc['outputModalities'] !== undefined) model.outputModalities = enumArray(doc['outputModalities'], ['text', 'image', 'audio']) as ModalityFull[];
    const context = doc['contextWindowTokens'];
    if (context !== undefined) {
      if (context !== null && (typeof context !== 'number' || !Number.isInteger(context) || context <= 0)) {
        invalid('contextWindowTokens must be null or a positive integer');
      }
      model.contextWindowTokens = context as number | null;
    }
    if (doc['customHeaders'] !== undefined) model.customHeaders = arrayOf(doc['customHeaders']).map((value) => {
      const header = objectOf(value);
      knownFields(header, ['name', 'value']);
      const name = stringOf(header, 'name');
      const headerValue = stringOf(header, 'value');
      if (!/^[\w!#$%&'*+.^`|~-]+$/.test(name) || /[\r\n]/.test(headerValue)) invalid('Invalid custom header');
      return { name, value: headerValue };
    });
    if (doc['customBodies'] !== undefined) model.customBodies = arrayOf(doc['customBodies']).map((value) => {
      const body = objectOf(value);
      knownFields(body, ['key', 'value']);
      const key = stringOf(body, 'key');
      if (!key || typeof body['value'] !== 'string') invalid('customBodies needs key and a JSON-encoded value string');
      let parsed: JsonValue;
      try { parsed = JSON.parse(body['value']) as JsonValue; }
      catch { return invalid('Custom body value must be valid JSON text'); }
      return { key, value: parsed };
    });
    if (index >= 0) models[index] = model;
    else models.push(model);
  }
  return models;
};

const configureProvider = (providers: ProviderSetting[], doc: JsonObject): ProviderSetting => {
  knownFields(doc, ['id', 'name', 'type', 'baseUrl', 'apiKey', 'enabled', 'models',
    'authMode', 'brand', 'chatCompletionsPath', 'useResponseApi', 'promptCaching']);
  const id = stringOf(doc, 'id');
  const name = stringOf(doc, 'name');
  const matches = providers.filter((provider): boolean => id ? provider.id === id : provider.name === name);
  if (id && matches.length === 0) invalid('Provider id not found; list providers before updating');
  if (matches.length > 1) invalid('Provider name is ambiguous; specify an id from provider_list');
  const existing = matches[0];
  const type = enumOf(doc, 'type', ['openai', 'google', 'claude'], existing?.type ?? '');
  if (existing !== undefined && existing.type !== type) invalid('Changing provider protocol is unsupported; create a separately named provider');
  const provider: ProviderSetting = existing !== undefined
    ? copyProviderSettingWithModels(existing, existing.models.slice())
    : type === 'google' ? makeProviderSettingGoogle()
    : type === 'claude' ? makeProviderSettingClaude() : makeProviderSettingOpenAIVariant();
  if (provider.type === 'google' && (provider.authMode !== 'api_key' || provider.useServiceAccount || provider.vertexAI)) {
    invalid('OAuth/Vertex/service-account providers require the existing provider configuration flow');
  }
  provider.name = stringOf(doc, 'name', provider.name);
  if (!provider.name) invalid('name must be nonempty');
  if (!id && !name) invalid('Specify a provider name or existing id');
  provider.baseUrl = endpoint(stringOf(doc, 'baseUrl', provider.baseUrl));
  provider.apiKey = stringOf(doc, 'apiKey', provider.apiKey);
  if (!provider.apiKey || /[\r\n]/.test(provider.apiKey)) invalid('apiKey must be nonempty and contain no line breaks');
  provider.enabled = booleanOf(doc, 'enabled', provider.enabled);
  if (provider.type === 'openai') {
    const brands = ['generic', 'openai', 'deepseek', 'zhipu', 'kimi', 'mimo', 'minimax'];
    provider.brand = enumOf(doc, 'brand', brands, provider.brand) as OpenAIBrand;
    provider.authMode = enumOf(doc, 'authMode', ['api_key', 'zhipu_coding_plan', 'kimi_coding_plan',
      'mimo_coding_plan', 'minimax_token_plan'], provider.authMode) as OpenAIAuthMode;
    if (provider.authMode !== 'api_key' && doc['baseUrl'] === undefined
      && (existing === undefined || (existing.type === 'openai' && existing.authMode !== provider.authMode))) {
      invalid('Specify the documented baseUrl when configuring a Coding/Token Plan');
    }
    if (!openAIBrandAvailableAuthModes(provider.brand).includes(provider.authMode)) {
      invalid('authMode does not match brand; set the corresponding provider brand');
    }
    provider.chatCompletionsPath = stringOf(doc, 'chatCompletionsPath', provider.chatCompletionsPath);
    if (!/^\/(?!\/)[^\s?#]*$/.test(provider.chatCompletionsPath)) invalid('chatCompletionsPath must be an absolute path without query or fragment');
    provider.useResponseApi = booleanOf(doc, 'useResponseApi', provider.useResponseApi);
    if (doc['promptCaching'] !== undefined) invalid('promptCaching is only supported for Claude');
  } else {
    if (['brand', 'chatCompletionsPath', 'useResponseApi'].some((key): boolean => doc[key] !== undefined)) {
      invalid('OpenAI-only options require type openai');
    }
    if (doc['authMode'] !== undefined && doc['authMode'] !== 'api_key') invalid('Only API-key authentication is supported for this protocol');
    if (provider.type === 'claude') provider.promptCaching = booleanOf(doc, 'promptCaching', provider.promptCaching);
    else if (doc['promptCaching'] !== undefined) invalid('promptCaching is only supported for Claude');
  }
  if (doc['models'] !== undefined) provider.models = mergeModels(provider, arrayOf(doc['models']));
  return provider;
};

const MODEL_PROPERTIES: JsonObject = {
  modelId: { type: 'string' }, displayName: { type: 'string' },
  type: { type: 'string', enum: ['chat', 'image', 'embedding'] },
  abilities: { type: 'array', items: { type: 'string', enum: ['tool', 'reasoning'] }, description: 'Chat models default to tool. Set [] to explicitly disable tools.' },
  inputModalities: { type: 'array', items: { type: 'string', enum: ['text', 'image', 'audio'] } },
  outputModalities: { type: 'array', items: { type: 'string', enum: ['text', 'image', 'audio'] } },
  contextWindowTokens: { type: 'integer', minimum: 1 },
  customHeaders: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' }, value: { type: 'string' } }, required: ['name', 'value'], additionalProperties: false } },
  customBodies: { type: 'array', items: { type: 'object', properties: { key: { type: 'string' },
    value: { type: 'string', description: 'JSON-encoded request-body value, e.g. true, 0.5, or "text".' } }, required: ['key', 'value'], additionalProperties: false } },
};
const PROVIDER_PROPERTIES: JsonObject = {
  id: { type: 'string', description: 'Existing id from provider_list. Omit to create or update by unique exact name.' },
  name: { type: 'string' }, type: { type: 'string', enum: ['openai', 'claude', 'google'], description: 'Required for new providers. OpenAI-compatible gateways use openai.' },
  apiKey: { type: 'string', description: 'Required for new providers. Omit to retain saved key. Never repeat credentials in your reply.' },
  baseUrl: { type: 'string', description: 'Protocol base URL from documentation; exclude /chat/completions, /responses, /messages, query and credentials. Defaults to protocol official endpoint for new providers.' },
  enabled: { type: 'boolean' }, brand: { type: 'string', enum: ['generic', 'openai', 'deepseek', 'zhipu', 'kimi', 'mimo', 'minimax'] },
  authMode: { type: 'string', enum: ['api_key', 'zhipu_coding_plan', 'kimi_coding_plan', 'mimo_coding_plan', 'minimax_token_plan'], description: 'OpenAI Token/Coding Plans require corresponding brand and explicit baseUrl from documentation. OAuth login is not supported by this tool.' },
  chatCompletionsPath: { type: 'string', description: 'OpenAI only; default /chat/completions. Concatenated with baseUrl.' },
  useResponseApi: { type: 'boolean', description: 'OpenAI only; use /responses instead of chat completions.' },
  promptCaching: { type: 'boolean', description: 'Claude only.' },
  models: { type: 'array', description: 'Merge models by modelId; existing model ids/options and unlisted models are retained. Empty list never deletes models.',
    items: { type: 'object', properties: MODEL_PROPERTIES, required: ['modelId'], additionalProperties: false } },
};

const discoverModels = async (
  deps: ProviderManagementToolsDeps, input: JsonValue, signal?: AbortSignalLike,
): Promise<UIMessagePart[]> => {
  const id = stringOf(objectOf(input), 'id');
  const provider = ((await loadProviders(deps.store)) ?? []).find((item): boolean => item.id === id);
  if (provider === undefined) invalid('Provider id not found');
  if ((provider.type === 'google' && (provider.authMode !== 'api_key' || provider.vertexAI || provider.useServiceAccount))
    || (provider.type === 'openai' && ['codex_oauth', 'grok_oauth'].includes(provider.authMode))) {
    invalid('Use the existing login/configuration flow to discover models for this authentication mode');
  }
  if (!hasUsableAuth(provider)) invalid('Provider must be enabled with a saved API key');
  const baseUrl = endpoint(provider.baseUrl);
  const headers: Record<string, string> = provider.type === 'openai'
    ? openAIAuthHeaders(provider.authMode, baseUrl, provider.apiKey)
    : provider.type === 'google' ? { 'x-goog-api-key': provider.apiKey }
    : { 'x-api-key': provider.apiKey, 'anthropic-version': '2023-06-01' };
  const response = await deps.http.fetch({ url: `${baseUrl}/models`, method: 'GET', headers }, { signal });
  if (response.status < 200 || response.status >= 300) return textResult({ status: 'error', id,
    httpStatus: response.status, message: 'Model discovery failed. Some providers do not expose /models; configure documented model IDs instead.' });
  let parsed: JsonObject;
  try { parsed = objectOf(JSON.parse(response.body) as JsonValue); }
  catch { return textResult({ status: 'error', id, message: 'Model catalog response is not a valid JSON object' }); }
  const values = parsed[provider.type === 'google' ? 'models' : 'data'];
  if (!Array.isArray(values)) invalid('Model catalog has no model list; configure documented model IDs instead');
  const models: string[] = [];
  for (const value of values) {
    const doc = objectOf(value);
    const rawId = stringOf(doc, provider.type === 'google' ? 'name' : 'id');
    const modelId = provider.type === 'google' && rawId.startsWith('models/') ? rawId.substring(7) : rawId;
    if (modelId && !models.includes(modelId)) models.push(modelId);
  }
  return textResult({ status: 'ok', id, models, saved: false,
    message: 'Catalog fetched. Use provider_configure to add selected models; this does not verify chat or tool-call support.' });
};

export const createProviderManagementTools = (deps: ProviderManagementToolsDeps): AgentTool[] => [
  makeAgentTool({
    name: 'provider_list', description: 'List configured AI providers and models (服务商、接口配置、API key 状态). Credentials and custom headers are omitted.',
    parameters: () => makeInputSchemaObj({}),
    systemPrompt: () => 'When the user supplies API keys and API documentation to configure AI providers, use provider_list, provider_configure and provider_models. Read the documentation first; do not guess model IDs, protocols or endpoints. Never repeat API keys in replies. Configuration success is not a successful chat request.',
    execute: async (): Promise<UIMessagePart[]> => {
      try { return textResult({ status: 'ok', providers: ((await loadProviders(deps.store)) ?? []).map(providerSummary) }); }
      catch (error) { return safeFailure(error as object, 'Unable to load provider settings'); }
    },
  }),
  makeAgentTool({
    name: 'provider_configure', description: 'Batch create/update AI providers with user-supplied API keys and documented endpoints/models. All entries are validated before saving. Existing models and unspecified options are preserved. Supports API keys and OpenAI Coding/Token Plans; OAuth/Vertex login uses the existing settings flow.',
    needsApproval: true,
    parameters: () => makeInputSchemaObj({ providers: { type: 'array', minItems: 1,
      items: { type: 'object', properties: PROVIDER_PROPERTIES, additionalProperties: false } } }, ['providers']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      try {
        const docs = arrayOf(objectOf(input)['providers']);
        if (docs.length === 0) invalid('providers must contain at least one configuration');
        return await deps.withLock(async (): Promise<UIMessagePart[]> => {
          const providers = ((await loadProviders(deps.store)) ?? []).slice();
          const configured: ProviderSetting[] = [];
          for (const value of docs) {
            const next = configureProvider(providers, objectOf(value));
            if (configured.some((provider): boolean => provider.id === next.id)) invalid('Each provider may appear only once in a batch');
            const index = providers.findIndex((provider): boolean => provider.id === next.id);
            if (index >= 0) providers[index] = next;
            else providers.push(next);
            configured.push(next);
          }
          await saveProviders(deps.store, providers);
          return textResult({ status: 'ok', providers: configured.map(providerSummary),
            message: 'Provider settings saved. Models are available for selection on the next turn. No chat request was tested.' });
        });
      } catch (error) { return safeFailure(error as object, 'Unable to save provider settings'); }
    },
  }),
  makeAgentTool({
    name: 'provider_models', description: 'Fetch a saved provider model catalog using its saved API key and base URL. Returns IDs without saving. If /models is unsupported, use the model IDs in documentation. Does not test chat or tool calls.',
    needsApproval: true,
    parameters: () => makeInputSchemaObj({ id: { type: 'string', description: 'Provider id from provider_list/provider_configure.' } }, ['id']),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      try { return await discoverModels(deps, input, signal); }
      catch (error) { return safeFailure(error as object, signal?.aborted ? 'Model discovery cancelled' : 'Unable to fetch model catalog'); }
    },
  }),
];
