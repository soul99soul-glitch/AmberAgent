// Google Gemini 请求构建(纯逻辑,无 IO)
//
// Android 基准: GoogleProvider.kt
//   buildCompletionRequestBody(:465-620) / buildContents(:729-741)
//   addModelMessage(:743-782) / addUserMessage(:784-791)
//   toGooglePart(:793-836) / toFunctionCallPart(:838-846)
//   toFunctionResponsePart(:848-859) / commonRoleToGoogleRole(:622-629)
//   ModelRegistry GEMINI_3_SERIES(:195-197) + ModelDsl.kt tokenize/TokenSequenceMatcher
//   Request.kt removeElements(:97-127)
//
// 裁剪(登记):
//   - BuiltInTools(googleSearch/urlContext):使用显式内置工具模式;
//     该模式不注册 functionDeclarations,不启用 Gemini 3 的混用协议
//   - Vertex AI service account / Gemini Code Assist OAuth 传输差异 = adapter
//     关注点(API 层登记);isCodeAssistOAuth 仅保留对 thinkingConfig 的纯逻辑影响
//   - image 编码注入 Port(与 Claude 同构 ClaudeImageEncoder,mime + 无前缀 base64);
//     Video/Audio 编码失败跳过(getOrNull 语义),Image 失败抛错(getOrThrow 语义)

import type { JsonObject, JsonValue } from './json.ts';
import type {
  UIMessage, UIMessagePart, UIMessagePartImage, UIMessagePartTool,
} from './message.ts';
import { isValidToUpload, toolInputAsJson } from './message.ts';
import type { MessageRole } from './message.ts';
import type { TextGenerationParams } from './provider_model.ts';
import { reasoningLevelBudgetTokens } from './provider_model.ts';
import { mergeCustomBody, groupPartsByToolBoundary } from './openai_request.ts';
import type { PartGroup } from './openai_request.ts';
import type { ClaudeEncodedImage, ClaudeImageEncoder } from './claude_request.ts';

// ===== ImageEncoder Port(与 Claude 同构,encodeBase64(withPrefix=false)) =====

export type GoogleImageEncoder = ClaudeImageEncoder;
export type GoogleEncodedImage = ClaudeEncodedImage;

const missingEncoder: GoogleImageEncoder = (url: string): GoogleEncodedImage => {
  throw new Error(`image encoding requires injected GoogleImageEncoder (url: ${url})`);
};

// ===== ModelDsl.kt tokenize + TokenSequenceMatcher(忠实移植,:161-175/:197-229) =====
// tokenize:字母串/数字串/其他单字符;matcher = 有序子序列匹配

const tokenizeModelId = (modelId: string): string[] => {
  const tokens: string[] = [];
  const input: string = modelId.toLowerCase();
  let i: number = 0;
  const isLetter = (c: string): boolean => (c >= 'a' && c <= 'z');
  const isDigit = (c: string): boolean => (c >= '0' && c <= '9');
  while (i < input.length) {
    const ch: string = input[i];
    if (isLetter(ch)) {
      const start: number = i;
      i++;
      while (i < input.length && isLetter(input[i])) i++;
      tokens.push(input.slice(start, i));
    } else if (isDigit(ch)) {
      const start: number = i;
      i++;
      while (i < input.length && isDigit(input[i])) i++;
      tokens.push(input.slice(start, i));
    } else {
      tokens.push(ch);
      i++;
    }
  }
  return tokens;
};

const tokenSequenceMatch = (specs: string[], tokens: string[]): boolean => {
  if (specs.length === 0) return false;
  let specIndex: number = 0;
  for (const token of tokens) {
    if (specs[specIndex] === token) {
      specIndex++;
      if (specIndex === specs.length) return true;
    }
  }
  return false;
};

// GEMINI_3_SERIES(ModelRegistry.kt:195-197):3-pro/3-flash/3.1-pro-preview/
//   3.1-pro-preview-customtools 的 token 序列
const GEMINI_3_SERIES_TOKENS: string[][] = [
  ['gemini', '3', 'pro'],
  ['gemini', '3', 'flash'],
  ['gemini', '3', '1', 'pro', 'preview'],
  ['gemini', '3', '1', 'pro', 'preview', 'customtools'],
];

export const isGemini3Series = (modelId: string): boolean => {
  const tokens: string[] = tokenizeModelId(modelId);
  return GEMINI_3_SERIES_TOKENS.some((spec: string[]): boolean =>
    tokenSequenceMatch(spec, tokens));
};

// isGeminiPro(:500-501):Regex("2\\.5.*pro", IGNORE_CASE)
export const isGemini25Pro = (modelId: string): boolean => /2\.5.*pro/i.test(modelId);

// ===== removeElements(Request.kt:97-127,keepOnly=false 递归移除) =====

export const removeJsonElements = (el: JsonValue, keys: string[]): JsonValue => {
  if (Array.isArray(el)) {
    return el.map((item: JsonValue): JsonValue => removeJsonElements(item, keys));
  }
  if (typeof el === 'object' && el !== null) {
    const out: JsonObject = {};
    for (const k of Object.keys(el as JsonObject)) {
      if (keys.indexOf(k) >= 0) continue;
      out[k] = removeJsonElements((el as JsonObject)[k], keys);
    }
    return out;
  }
  return el;
};

// Gemini functionDeclarations parameters 不允许的 schema 键(:556-565,逐字)
const GEMINI_SCHEMA_DROP_KEYS: string[] = [
  'const', 'exclusiveMaximum', 'exclusiveMinimum', 'format', 'additionalProperties', 'enum',
];

// ===== commonRoleToGoogleRole(:622-629) =====

export const commonRoleToGoogleRole = (role: MessageRole): string => {
  switch (role) {
    case 'user': return 'user';
    case 'system': return 'system';
    case 'assistant': return 'model';
    case 'tool': return 'user'; // google api 中,tool 结果是用户 role 发送的
  }
};

// ===== toGooglePart(:793-836) =====

const metadataThoughtSignature = (part: UIMessagePart): string | null => {
  if (part.metadata === null || typeof part.metadata !== 'object' || Array.isArray(part.metadata)) {
    return null;
  }
  const v: JsonValue | undefined = (part.metadata as JsonObject)['thoughtSignature'];
  return typeof v === 'string' ? v : null;
};

export const toGooglePart = (
  part: UIMessagePart, encodeImage: GoogleImageEncoder,
): JsonObject | null => {
  if (part.type === 'text') {
    return { text: part.text };
  }
  if (part.type === 'image') {
    // getOrThrow 语义:失败即抛
    const encoded: GoogleEncodedImage = encodeImage((part as UIMessagePartImage).url);
    const obj: JsonObject = {
      inlineData: { mimeType: encoded.mimeType, data: encoded.base64 },
    };
    const sig: string | null = metadataThoughtSignature(part);
    if (sig !== null) obj['thoughtSignature'] = sig;
    return obj;
  }
  if (part.type === 'video') {
    // getOrNull 语义:编码失败跳过该 part
    try {
      const encoded: GoogleEncodedImage = encodeImage(part.url);
      const mediaType: string = part.mime.startsWith('video/') ? part.mime : 'video/mp4';
      return { inlineData: { mimeType: mediaType, data: encoded.base64 } };
    } catch {
      return null;
    }
  }
  if (part.type === 'audio') {
    try {
      const encoded: GoogleEncodedImage = encodeImage(part.url);
      const mediaType: string = part.mime.startsWith('audio/') ? part.mime : 'audio/mpeg';
      return { inlineData: { mimeType: mediaType, data: encoded.base64 } };
    } catch {
      return null;
    }
  }
  // reasoning/document/mini_app/tool 按 Android else -> null 丢弃
  return null;
};

// ===== toFunctionCallPart / toFunctionResponsePart(:838-859) =====

export const toGoogleFunctionCallPart = (tool: UIMessagePartTool): JsonObject => {
  const call: JsonObject = { name: tool.toolName, args: toolInputAsJson(tool) };
  const wireId: JsonValue | undefined = tool.metadata?.['gemini_wire_call_id'];
  if (typeof wireId === 'string' && wireId.trim().length > 0) call['id'] = wireId;
  const obj: JsonObject = {
    functionCall: call,
  };
  if (tool.metadata !== null && typeof tool.metadata === 'object' &&
    !Array.isArray(tool.metadata)) {
    const sig: JsonValue | undefined = (tool.metadata as JsonObject)['thoughtSignature'];
    if (sig !== undefined) obj['thoughtSignature'] = sig;
  }
  return obj;
};

export const toGoogleFunctionResponsePart = (tool: UIMessagePartTool): JsonObject => {
  const result: string = tool.output
    .filter((p: UIMessagePart): p is UIMessagePart & { type: 'text' } => p.type === 'text')
    .map((p): string => p.text)
    .join('\n');
  const response: JsonObject = { name: tool.toolName, response: { result } };
  const wireId: JsonValue | undefined = tool.metadata?.['gemini_wire_call_id'];
  if (typeof wireId === 'string' && wireId.trim().length > 0) response['id'] = wireId;
  return { functionResponse: response };
};

// ===== buildContents(:729-791) =====

export const buildGoogleContents = (
  messages: UIMessage[],
  encodeImage: GoogleImageEncoder = missingEncoder,
): JsonObject[] => {
  const out: JsonObject[] = [];
  for (const message of messages) {
    if (message.role === 'system' || !isValidToUpload(message)) continue;

    if (message.role !== 'assistant') {
      // addUserMessage
      const parts: JsonValue[] = [];
      for (const part of message.parts) {
        const block: JsonObject | null = toGooglePart(part, encodeImage);
        if (block !== null) parts.push(block);
      }
      out.push({
        role: commonRoleToGoogleRole(message.role),
        parts: parts as unknown as JsonValue,
      });
      continue;
    }

    // addModelMessage
    const groups: PartGroup[] = groupPartsByToolBoundary(message.parts);
    let partsBuffer: JsonObject[] = [];
    for (const group of groups) {
      if (group.kind === 'content') {
        for (const part of group.parts) {
          const block: JsonObject | null = toGooglePart(part, encodeImage);
          if (block !== null) partsBuffer.push(block);
        }
      } else {
        for (const tool of group.tools) {
          partsBuffer.push(toGoogleFunctionCallPart(tool));
        }
        // 输出 model 消息
        out.push({ role: 'model', parts: partsBuffer as unknown as JsonValue });
        partsBuffer = [];
        // 紧跟 functionResponse(user 角色)
        const responses: JsonValue[] = [];
        for (const tool of group.tools) {
          responses.push(toGoogleFunctionResponsePart(tool));
        }
        out.push({ role: 'user', parts: responses as unknown as JsonValue });
      }
    }
    if (partsBuffer.length > 0) {
      out.push({ role: 'model', parts: partsBuffer as unknown as JsonValue });
    }
  }
  return out;
};

// ===== safetySettings(:597-619,逐字 5 条 OFF) =====

const GOOGLE_SAFETY_SETTINGS: JsonObject[] = [
  { category: 'HARM_CATEGORY_HARASSMENT', threshold: 'OFF' },
  { category: 'HARM_CATEGORY_HATE_SPEECH', threshold: 'OFF' },
  { category: 'HARM_CATEGORY_SEXUALLY_EXPLICIT', threshold: 'OFF' },
  { category: 'HARM_CATEGORY_DANGEROUS_CONTENT', threshold: 'OFF' },
  { category: 'HARM_CATEGORY_CIVIC_INTEGRITY', threshold: 'OFF' },
];

// ===== buildCompletionRequestBody(:465-620) =====

export interface BuildGoogleCompletionRequestInput {
  messages: UIMessage[];
  params: TextGenerationParams;
  // cloudcode-pa OAuth 传输差异(:513):OFF 时不强制 thinkingLevel=minimal
  isCodeAssistOAuth?: boolean;
  encodeImage?: GoogleImageEncoder;
}

export const buildGoogleCompletionRequestBody = (
  input: BuildGoogleCompletionRequestInput,
): JsonObject => {
  const { messages, params } = input;
  const isCodeAssistOAuth: boolean = input.isCodeAssistOAuth ?? false;
  const encodeImage: GoogleImageEncoder = input.encodeImage ?? missingEncoder;
  const hasImageOutput: boolean = params.model.outputModalities.indexOf('image') >= 0;

  const req: JsonObject = {};

  // systemInstruction(:471-483):图片输出模型不带 system
  const systemMessage: UIMessage | undefined = messages.filter(
    (m: UIMessage): boolean => m.role === 'system')[0];
  if (systemMessage !== undefined && !hasImageOutput) {
    const text: string = systemMessage.parts
      .filter((p: UIMessagePart): p is UIMessagePart & { type: 'text' } => p.type === 'text')
      .map((p): string => p.text)
      .join('\n\n');
    req['systemInstruction'] = { parts: [{ text }] };
  }

  // generationConfig(:486-536)
  const generationConfig: JsonObject = {};
  if (params.temperature !== null) generationConfig['temperature'] = params.temperature;
  if (params.topP !== null) generationConfig['topP'] = params.topP;
  if (params.maxTokens !== null) generationConfig['maxOutputTokens'] = params.maxTokens;
  if (hasImageOutput) {
    generationConfig['responseModalities'] = ['TEXT', 'IMAGE'] as unknown as JsonValue;
  }
  if (params.model.abilities.indexOf('reasoning') >= 0) {
    const thinkingConfig: JsonObject = { includeThoughts: true };
    const geminiPro: boolean = isGemini25Pro(params.model.modelId);
    const gemini3: boolean = isGemini3Series(params.model.modelId);
    const level = params.reasoningLevel;

    if (level === 'auto') {
      // 自动模式,不设置参数
    } else if (level === 'off') {
      if (gemini3) {
        // cloudcode-pa / Code Assist OAuth 对部分 3.x preview 拒绝
        // thinkingLevel=MINIMAL → 该传输下不强制(Android 注释 :507-512)
        if (!isCodeAssistOAuth) {
          thinkingConfig['thinkingLevel'] = 'minimal';
        }
      } else if (!geminiPro) {
        thinkingConfig['thinkingBudget'] = 0;
        thinkingConfig['includeThoughts'] = false;
      }
    } else {
      if (gemini3) {
        // LOW→low / MEDIUM→medium / HIGH、XHIGH、MAX→high(:523-528)
        thinkingConfig['thinkingLevel'] =
          level === 'low' ? 'low' : (level === 'medium' ? 'medium' : 'high');
      } else {
        thinkingConfig['thinkingBudget'] = reasoningLevelBudgetTokens(level);
      }
    }
    generationConfig['thinkingConfig'] = thinkingConfig;
  }
  req['generationConfig'] = generationConfig;

  // contents(:538-542)
  req['contents'] = buildGoogleContents(messages, encodeImage) as unknown as JsonValue;

  // model.tools 的 Google 内置模式;image_generation 不是 Google 工具声明。
  const builtInTools: JsonValue[] = [];
  if (params.model.tools.indexOf('search') >= 0) builtInTools.push({ googleSearch: {} });
  if (params.model.tools.indexOf('url_context') >= 0) builtInTools.push({ urlContext: {} });
  if (builtInTools.length > 0) {
    req['tools'] = builtInTools;
  } else if (params.tools.length > 0 && params.model.abilities.indexOf('tool') >= 0) {
    // 没有 Google 内置工具时保留 functionDeclarations + schema 键裁剪。
    const declarations: JsonValue[] = params.tools.map((tool): JsonObject => {
      const declaration: JsonObject = { name: tool.name, description: tool.description };
      if (tool.parametersJsonSchema !== undefined) {
        declaration['parametersJsonSchema'] = tool.parametersJsonSchema;
      } else {
        declaration['parameters'] = removeJsonElements(tool.parameters, GEMINI_SCHEMA_DROP_KEYS);
      }
      return declaration;
    });
    req['tools'] = [{ functionDeclarations: declarations as unknown as JsonValue }] as unknown as JsonValue;
  }

  // safetySettings(:597-619)
  req['safetySettings'] = GOOGLE_SAFETY_SETTINGS as unknown as JsonValue;

  return mergeCustomBody(req, params.customBody);
};
