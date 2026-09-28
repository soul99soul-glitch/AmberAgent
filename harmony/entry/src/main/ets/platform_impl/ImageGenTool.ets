// ImageGenTool — generate_image 工具(Android ImageGenTool.kt + OpenAIProvider 图像路径)
//
// 链路:任务模型「生图」槽(AppContainer TASK_IMAGE_MODEL_KEY)→ type='image' 模型
//   → OpenAI 兼容 /images/generations(create,JSON)与 /images/edits(edit,multipart)
//   → 落盘 filesDir/chat_images/{conversationId}/{ts}_{i}.png + FilesRepository 登记
//   → 工具结果 = image parts(时间线 GeneratedImageCarousel)+ JSON 摘要 text。
// edit 源图由调用方(ChatPage)从用户最近一条带图消息解析(sourceImageResolver),
//   模型不可伪造本地 URL(Android 同语义);≤5MB 且仅 png/jpg/webp。

import { http } from '@kit.NetworkKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type {
  AgentTool, JsonObject, JsonValue, ProviderModel, ProviderSetting, UIMessagePart,
  UIMessagePartImage, ProviderSettingOpenAIVariant, ProviderSettingGoogle, ProviderSettingClaude, TaskModelReference,
} from '@amber/chat-domain';
import {
  makeAgentTool, makeInputSchemaObj, hasUsableAuth, inputStringValue, loadProviders, taskModelReferenceFromId,
} from '@amber/chat-domain';
import type { ChatProviderChoice } from '../di/AppContainer.ets';
import { getAppContainer, getChatKvStore, readTaskModelReference, resolveConfiguredTaskModel, TASK_IMAGE_MODEL_KEY } from '../di/AppContainer.ets';
import { getFilesRepository } from './ManagedFileStore.ets';

const IMAGE_EDIT_MAX_SOURCE_BYTES: number = 5 * 1024 * 1024;
const CREATE_TIMEOUT_MS: number = 120000;

interface GeneratedItem {
  bytes: Uint8Array;
  mime: string;
}

export interface EditSourceImage {
  bytes: Uint8Array;
  mime: string;
  name: string;
}

const inputInt = (input: JsonValue, key: string): number | null => {
  const raw: string | null = inputStringValue(input, key);
  if (raw === null) return null;
  const n: number = Number(raw);
  return Number.isFinite(n) ? Math.trunc(n) : null;
};

// 生图模型解析(execute 时实时解析,跟随用户中途换槽;Android getCurrentImageGenerationModel)
export const resolveImageGenChoice = async (): Promise<ChatProviderChoice | null> => {
  let reference: TaskModelReference = await readTaskModelReference(TASK_IMAGE_MODEL_KEY);
  if (reference.kind === 'legacy') {
    // 保留旧 scalar 搜索顺序:先排除非 OpenAI/无鉴权容器,再匹配 UUID+image。
    const providers: ProviderSetting[] = (await loadProviders(getChatKvStore())) ?? [];
    const modelId: string = reference.modelId;
    let matched: boolean = false;
    for (const provider of providers) {
      if (provider.type !== 'openai' || !hasUsableAuth(provider)) continue;
      const model: ProviderModel | undefined = provider.models.find((candidate: ProviderModel): boolean =>
        candidate.id === modelId && candidate.type === 'image');
      if (model === undefined) continue;
      reference = taskModelReferenceFromId(model.id, provider.id);
      matched = true;
      break;
    }
    if (!matched) return null;
  }
  const choice: ChatProviderChoice | null = await resolveConfiguredTaskModel(reference, 'image');
  return choice !== null && choice.provider.type === 'openai' && hasUsableAuth(choice.provider) ? choice : null;
};

// 工具是否装配(同步门;execute 内仍实时解析)
export const imageGenToolAvailable = async (): Promise<boolean> =>
  (await resolveImageGenChoice()) !== null;

const sizeString = (aspectRatio: string): string => {
  if (aspectRatio === '16:9') return '1536x1024';
  if (aspectRatio === '9:16') return '1024x1536';
  return '1024x1024';
};

const authHeader = (p: ProviderSetting): string => {
  if (p.type === 'openai') return `Bearer ${(p as ProviderSettingOpenAIVariant).apiKey}`;
  if (p.type === 'google') return `Bearer ${(p as ProviderSettingGoogle).apiKey}`;
  return `Bearer ${(p as ProviderSettingClaude).apiKey}`;
};

const customHeadersOf = (m: ProviderModel): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const h of m.customHeaders) {
    const name: string = h.name.trim();
    if (name.length > 0) out[name] = h.value;
  }
  return out;
};

// create:POST {baseUrl}/images/generations {model, prompt, n, size}
const requestImagesCreate = async (
  choice: ChatProviderChoice, prompt: string, size: string, count: number,
): Promise<GeneratedItem[]> => {
  const body: JsonObject = {
    model: choice.model.modelId,
    prompt: prompt,
    n: count,
    size: size,
  };
  // mergeCustomBody 语义:模型级自定义 body 顶层键并入
  for (const b of choice.model.customBodies) {
    const key: string = b.key.trim();
    if (key.length > 0) body[key] = b.value;
  }
  const headers: Record<string, string> = customHeadersOf(choice.model);
  headers['Content-Type'] = 'application/json';
  headers['Authorization'] = authHeader(choice.provider);
  const resp = await getAppContainer().httpClient.fetch({
    url: `${choice.provider.baseUrl}/images/generations`,
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  }, { signal: undefined });
  if (resp.status < 200 || resp.status >= 300) {
    throw new Error(`图像生成失败(${resp.status}): ${resp.body.substring(0, 200)}`);
  }
  return parseImageResponse(resp.body);
};

interface MultipartTextField {
  name: string;
  value: string;
}

// edit:POST {baseUrl}/images/edits multipart(model/prompt/n/size + image 文件)
const requestImagesEdit = async (
  choice: ChatProviderChoice, prompt: string, size: string, count: number,
  source: EditSourceImage,
): Promise<GeneratedItem[]> => {
  const boundary: string = `----AmberImageGen${Date.now()}`;
  const enc = util.TextEncoder.create('utf-8');
  const chunks: Uint8Array[] = [];
  const push = (s: string): void => { chunks.push(enc.encodeInto(s)); };
  const fields: MultipartTextField[] = [
    { name: 'model', value: choice.model.modelId },
    { name: 'prompt', value: prompt },
    { name: 'n', value: `${count}` },
    { name: 'size', value: size },
  ];
  for (const f of fields) {
    push(`--${boundary}\r\nContent-Disposition: form-data; name="${f.name}"\r\n\r\n${f.value}\r\n`);
  }
  push(`--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="${source.name}"\r\n` +
    `Content-Type: ${source.mime}\r\n\r\n`);
  chunks.push(source.bytes);
  push(`\r\n--${boundary}--\r\n`);
  let total: number = 0;
  for (const c of chunks) total += c.length;
  const merged: Uint8Array = new Uint8Array(total);
  let off: number = 0;
  for (const c of chunks) {
    merged.set(c, off);
    off += c.length;
  }
  const headers: Record<string, string> = customHeadersOf(choice.model);
  headers['Content-Type'] = `multipart/form-data; boundary=${boundary}`;
  headers['Authorization'] = authHeader(choice.provider);
  const req: http.HttpRequest = http.createHttp();
  try {
    const options: http.HttpRequestOptions = {
      method: http.RequestMethod.POST,
      header: headers,
      extraData: merged.buffer as ArrayBuffer,
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: CREATE_TIMEOUT_MS,
      readTimeout: CREATE_TIMEOUT_MS,
      usingCache: false,
    };
    const resp: http.HttpResponse =
      await req.request(`${choice.provider.baseUrl}/images/edits`, options);
    const text: string = typeof resp.result === 'string' ? resp.result : '';
    if (resp.responseCode < 200 || resp.responseCode >= 300) {
      throw new Error(`图像编辑失败(${resp.responseCode}): ${text.substring(0, 200)}`);
    }
    return parseImageResponse(text);
  } finally {
    try { req.destroy(); } catch { /* 已销毁 */ }
  }
};

// {data:[{b64_json} | {url}]} 两种返回形态;url 形态经二进制端口下载
const parseImageResponse = async (text: string): Promise<GeneratedItem[]> => {
  const parsed = JSON.parse(text) as JsonObject;
  const data = parsed['data'] as JsonValue[];
  if (!Array.isArray(data) || data.length === 0) {
    throw new Error('图像生成返回为空');
  }
  const out: GeneratedItem[] = [];
  for (const item of data) {
    const obj = item as JsonObject;
    const b64: string = typeof obj['b64_json'] === 'string' ? obj['b64_json'] as string : '';
    if (b64.length > 0) {
      const bytes: Uint8Array = new util.Base64Helper().decodeSync(b64);
      out.push({ bytes, mime: 'image/png' });
      continue;
    }
    const url: string = typeof obj['url'] === 'string' ? obj['url'] as string : '';
    if (url.length > 0) {
      const bytes: Uint8Array = await downloadBinary(url);
      out.push({ bytes, mime: 'image/png' });
    }
  }
  if (out.length === 0) throw new Error('图像生成返回无可用图片');
  return out;
};

const downloadBinary = async (url: string): Promise<Uint8Array> => {
  const req: http.HttpRequest = http.createHttp();
  try {
    const resp: http.HttpResponse = await req.request(url, {
      method: http.RequestMethod.GET,
      expectDataType: http.HttpDataType.ARRAY_BUFFER,
      connectTimeout: 30000,
      readTimeout: 60000,
      usingCache: false,
    });
    if (resp.responseCode < 200 || resp.responseCode >= 300) {
      throw new Error(`图片下载失败(${resp.responseCode})`);
    }
    if (resp.result instanceof ArrayBuffer) return new Uint8Array(resp.result);
    throw new Error('图片下载返回非二进制');
  } finally {
    try { req.destroy(); } catch { /* 已销毁 */ }
  }
};

// 落盘 + FilesRepository 登记(与 Android chat_images/{conversationId} 同构)
const saveImagesForConversation = async (
  conversationId: string, items: GeneratedItem[],
): Promise<string[]> => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  const dir: string = `${ctx.filesDir}/chat_images/${conversationId}`;
  fileIo.mkdirSync(dir, true);
  const repo = getFilesRepository();
  const urls: string[] = [];
  let index: number = 0;
  for (const item of items) {
    const name: string = `${Date.now()}_${index}.png`;
    const rel: string = `chat_images/${conversationId}/${name}`;
    const abs: string = `${ctx.filesDir}/${rel}`;
    const file: fileIo.File = fileIo.openSync(
      abs, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    fileIo.writeSync(file.fd, item.bytes.buffer as ArrayBuffer);
    fileIo.closeSync(file.fd);
    await repo.insert({
      id: 0,
      folder: 'chat_images',
      relativePath: rel,
      displayName: name,
      mimeType: item.mime,
      sizeBytes: item.bytes.length,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    });
    urls.push(`file://${abs}`);
    index += 1;
  }
  return urls;
};

export interface ImageGenToolDeps {
  conversationId: string;
  // edit 源图解析:用户最近一条带图消息(ChatPage 注入);null = 无可用源
  sourceImageResolver: () => Promise<EditSourceImage | null>;
}

export const createImageGenTool = (deps: ImageGenToolDeps): AgentTool =>
  makeAgentTool({
    name: 'generate_image',
    description:
      'Generate photographic, painted, illustrated, or otherwise textured raster imagery ' +
      'from a text prompt using the user\'s configured image-generation model. Prefer detailed ' +
      'English prompts specifying subject, style, composition, lighting, and mood. For purely ' +
      'structural visualizations (flowcharts, diagrams, schematics, simple line-art, math/data ' +
      'plots) prefer emitting an SVG block instead. Generated images are bound to this ' +
      'conversation; the user sees them inline. To MODIFY an image the user attached or that ' +
      'was just generated, call with mode="edit" and the modification instruction as the prompt ' +
      '— the source image is resolved automatically from the latest message with an image.',
    parameters: () => makeInputSchemaObj(
      {
        prompt: {
          type: 'string',
          description: 'Detailed description of the desired image. For edits: describe the modification.',
        },
        aspect_ratio: {
          type: 'string',
          description: 'Image aspect ratio: 1:1 (default), 16:9, or 9:16.',
        },
        count: {
          type: 'integer',
          description: 'Number of variants, 1-4. Default 1. Only request multiple when the user asks for choices.',
        },
        mode: {
          type: 'string',
          description: 'create (default) generates a new image; edit modifies the image from the latest message with an image.',
        },
      },
      ['prompt'],
    ),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const prompt: string = (inputStringValue(input, 'prompt') ?? '').trim();
      if (prompt.length === 0) throw new Error('prompt is required and must not be blank');
      const aspectRaw: string = (inputStringValue(input, 'aspect_ratio') ?? '1:1').trim();
      const size: string = sizeString(aspectRaw);
      const countRaw: number = inputInt(input, 'count') ?? 1;
      const count: number = Math.min(Math.max(countRaw, 1), 4);
      const isEdit: boolean = (inputStringValue(input, 'mode') ?? 'create') === 'edit';

      const choice: ChatProviderChoice | null = await resolveImageGenChoice();
      if (choice === null) {
        throw new Error('未配置生图模型,请用户在 设置 > 任务模型 > 生图模型 中配置');
      }
      let source: EditSourceImage | null = null;
      if (isEdit) {
        source = await deps.sourceImageResolver();
        if (source === null) {
          throw new Error('未找到可编辑的源图片(用户最近一条带图消息)');
        }
        if (source.bytes.length > IMAGE_EDIT_MAX_SOURCE_BYTES) {
          throw new Error('源图片超过 5MB 编辑上限');
        }
      }
      const items: GeneratedItem[] = isEdit
        ? await requestImagesEdit(choice, prompt, size, count, source as EditSourceImage)
        : await requestImagesCreate(choice, prompt, size, count);
      const urls: string[] = await saveImagesForConversation(deps.conversationId, items);

      const parts: UIMessagePart[] = [];
      for (const url of urls) {
        parts.push({ type: 'image', url, metadata: null } as UIMessagePartImage);
      }
      const summary: JsonObject = {
        status: 'ok',
        count: urls.length,
        model: choice.model.displayName,
        prompt: prompt,
        aspect_ratio: aspectRaw,
        mode: isEdit ? 'edit' : 'create',
      };
      parts.push({ type: 'text', text: JSON.stringify(summary), metadata: null });
      return parts;
    },
  });
