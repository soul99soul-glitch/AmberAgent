// personal_context_tools — contacts_pick / photos_pick(iOS createContactsPickToolDeclaration /
//   createPhotosPickToolDeclaration + IOSPersonalContextPicker 对齐)
//
// iOS 基准:
//   - contacts_pick:max_count 1...8(默认 8);前台系统联系人选择器;仅返回用户选中的联系人,
//     绝不枚举通讯录。结果 {ok,tool,selected_count,contacts[{name,phone_numbers,email_addresses}]}。
//   - photos_pick:max_count 1...4(默认 4);前台系统相册选择器;仅拷贝选中的图片进应用私有
//     存储。结果 {ok,tool,selected_count,photos[...]} + 图片 part(iOS 为 data: URL)。
//   - 失败 {ok:false,tool,status,reason};status ∈ cancelled/empty_selection/busy/
//     invalid_arguments/media_too_large/load_failed(+ Harmony 新增 not_foreground:应用在后台时
//     不拉起系统选择器)。
//   - needsApproval=false(系统选择器本身即用户同意);allowsAutoApproval=false。
//
// 偏差登记:
//   - iOS 选后"预览确认"二次确认步骤不移植:Harmony 系统选择器自带确认按钮,选择即同意。
//   - max_count 越界 iOS 报 invalid_arguments;此处钳制到范围内(模型更易自愈),并在结果中
//     标注 truncated。
//   - photos_pick 额外把图片写入 /workspace/photos_pick/(file_*/image_info/ocr_image 可继续
//     处理),JSON 返回 workspace path。图片 part(data: URL)只在 deps.includeImages=true 时附带
//     (调用方传 `模型支持图片输入 && provider 为 Claude 型`):OpenAI chat.completions 系请求构建只
//     序列化 tool 输出里的 text part,纯文本模型收到 image part 还会被污染。includeImages=false 时
//     结果带 images_attached:false 与改用 ocr_image/image_info 的提示。
//   - GIF 原样直通(不压缩、base64 后膨胀 4/3),故 GIF 字节上限 3.5MB(PHOTOS_PICK_MAX_GIF_BYTES),
//     超限跳过并计入 skipped_too_large;entry 端在 base64 编码前就先按文件大小拒绝。
//   - 纯域层:选择器 / 压缩 / 文件落盘全部经 Port 注入(entry 实现)。

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessagePart } from './message.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { AgentTool } from './tool.ts';

export const CONTACTS_PICK_TOOL_NAME: string = 'contacts_pick';
export const PHOTOS_PICK_TOOL_NAME: string = 'photos_pick';
export const CONTACTS_PICK_MAX: number = 8;
export const PHOTOS_PICK_MAX: number = 4;
// 单张图片(压缩后)上限;超限的图被跳过并计入 skipped_too_large
export const PHOTOS_PICK_MAX_FILE_BYTES: number = 12 * 1024 * 1024;
// GIF 不压缩直通,且 base64 膨胀 4/3 后可能进 image part → 单独收紧
export const PHOTOS_PICK_MAX_GIF_BYTES: number = 3.5 * 1024 * 1024;
export const PHOTOS_WORKSPACE_DIR: string = 'photos_pick';
const CONTACT_FIELD_LIMIT: number = 10;

// ===== Port =====

export interface PickedContact {
  name: string;
  phoneNumbers: string[];
  emailAddresses: string[];
}

export interface PickedPhoto {
  mimeType: string;
  bytes: Uint8Array;
  /** data:<mime>;base64,... 用于图片 part(entry 已压缩过) */
  dataUrl: string;
}

/**
 * status:
 *   ok          — items 为用户的选择(可能为空 = 用户没选)
 *   cancelled   — 用户取消/关闭选择器
 *   busy        — 已有选择器在前台
 *   unavailable — 设备/权限不支持
 *   not_foreground — 应用在后台,不拉起系统选择器(会弹不出来或打断用户)
 *   failed      — 其它失败(reason 给出原因)
 */
export type PickStatus = 'ok' | 'cancelled' | 'busy' | 'unavailable' | 'not_foreground' | 'failed';

export interface PickResult<T> {
  status: PickStatus;
  reason: string;
  items: T[];
  /** photos:选中但读取/解码失败的张数(port 填;缺省 0) */
  skippedUnreadable?: number;
  /** photos:port 在编码前就因体积(如 GIF > 3.5MB)拒绝的张数(缺省 0) */
  skippedTooLarge?: number;
}

export interface PersonalContextPort {
  pickContacts(maxCount: number, signal?: AbortSignalLike): Promise<PickResult<PickedContact>>;
  pickPhotos(maxCount: number, signal?: AbortSignalLike): Promise<PickResult<PickedPhoto>>;
}

export interface PersonalContextDeps {
  port: PersonalContextPort;
  /**
   * 是否把选中的图片作为 image part 返回给模型。调用方传
   * `rt.modelSupportsImageInput && provider 为 Claude 型`(其它请求路径不会序列化 tool 结果里的 image part,
   * 纯文本模型收到还会被污染)。false → 只返回 workspace 路径文本 + images_attached:false。
   */
  includeImages: boolean;
  /** 把字节写入 workspace(相对路径,如 photos_pick/x.jpg);返回规范化后的 workspace 相对路径 */
  writeWorkspaceBytes: (relativePath: string, bytes: Uint8Array, mimeType: string) => Promise<string>;
  /** 测试注入;默认 Date.now */
  now?: () => number;
  /** 测试注入;默认 Math.random,用于文件名随机后缀(避免同秒覆盖) */
  random?: () => number;
}

// ===== helpers =====

const textPart = (text: string): UIMessagePart => ({ type: 'text', text, metadata: null });

const failureJson = (tool: string, status: string, reason: string): string =>
  JSON.stringify({ ok: false, tool, status, reason });

const pickStatusToFailure = (status: PickStatus): string => {
  switch (status) {
    case 'cancelled': return 'cancelled';
    case 'busy': return 'busy';
    case 'unavailable': return 'unavailable';
    case 'not_foreground': return 'not_foreground';
    default: return 'load_failed';
  }
};

// max_count:缺省 → maximum;非数字 → null(invalid);整数化后钳制到 1...maximum
export const parseMaxCount = (input: JsonValue, maximum: number): number | null => {
  if (input === null || input === undefined || typeof input !== 'object' || Array.isArray(input)) return maximum;
  const raw: JsonValue | undefined = (input as JsonObject)['max_count'];
  if (raw === undefined || raw === null) return maximum;
  let n: number;
  if (typeof raw === 'number') {
    n = raw;
  } else if (typeof raw === 'string' && raw.trim().length > 0) {
    n = Number(raw.trim());
  } else {
    return null;
  }
  if (!Number.isFinite(n)) return null;
  return Math.min(Math.max(Math.round(n), 1), maximum);
};

const cleanList = (values: string[]): string[] => {
  const out: string[] = [];
  for (const v of values) {
    const t: string = v.trim();
    if (t.length > 0 && out.indexOf(t) < 0) out.push(t);
    if (out.length >= CONTACT_FIELD_LIMIT) break;
  }
  return out;
};

export const photoExtension = (mimeType: string): string => {
  const m: string = mimeType.toLowerCase();
  if (m === 'image/png') return 'png';
  if (m === 'image/gif') return 'gif';
  if (m === 'image/webp') return 'webp';
  if (m === 'image/heic' || m === 'image/heif') return 'heic';
  return 'jpg';
};

const makeAbortError = (): Error => {
  const e: Error = new Error('The operation was aborted.');
  e.name = 'AbortError';
  return e;
};

const isAbort = (e: Error, signal?: AbortSignalLike): boolean =>
  signal?.aborted === true || (e instanceof Error && e.name === 'AbortError');

// 选择器 promise 与 AbortSignal 竞速(系统选择器本身不可取消,但工具循环不再被它拖住)
const raceAbort = <T>(task: Promise<T>, signal: AbortSignalLike | undefined): Promise<T> =>
  new Promise<T>((resolve: (value: T) => void, reject: (reason: Error) => void): void => {
    if (signal?.aborted === true) {
      reject(makeAbortError());
      return;
    }
    let settled: boolean = false;
    const onAbort = (): void => {
      if (settled) return;
      settled = true;
      reject(makeAbortError());
    };
    const detach = (): void => {
      if (signal?.removeEventListener !== undefined) signal.removeEventListener('abort', onAbort);
    };
    if (signal?.addEventListener !== undefined) signal.addEventListener('abort', onAbort);
    task.then((value: T): void => {
      detach();
      if (settled) return;
      settled = true;
      resolve(value);
    }, (err: Error): void => {
      detach();
      if (settled) return;
      settled = true;
      reject(err);
    });
  });

const randomSuffix = (random: () => number): string => {
  let out: string = '';
  for (let i: number = 0; i < 4; i++) out += Math.floor(random() * 36).toString(36);
  return out;
};

const two = (n: number): string => (n < 10 ? `0${n}` : String(n));

const stamp = (ms: number): string => {
  const d: Date = new Date(ms);
  return `${d.getUTCFullYear()}${two(d.getUTCMonth() + 1)}${two(d.getUTCDate())}`
    + `-${two(d.getUTCHours())}${two(d.getUTCMinutes())}${two(d.getUTCSeconds())}`;
};

// ===== contacts_pick =====

export const createContactsPickTool = (deps: PersonalContextDeps): AgentTool => makeAgentTool({
  name: CONTACTS_PICK_TOOL_NAME,
  description:
    'Present the foreground system contact picker and return only the contacts the user selects ' +
    '(name, phone numbers, email addresses) to the current agent. Never enumerates the address book. ' +
    'The user chooses in the picker; if they dismiss it nothing is returned.',
  parameters: () => makeInputSchemaObj({
    max_count: {
      type: 'integer',
      description: `Optional maximum selected contacts, 1...${CONTACTS_PICK_MAX}. Defaults to ${CONTACTS_PICK_MAX}.`,
    },
  }, null),
  needsApproval: false,
  allowsAutoApproval: false,
  execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
    if (signal?.aborted === true) throw makeAbortError();
    const maxCount: number | null = parseMaxCount(input, CONTACTS_PICK_MAX);
    if (maxCount === null) {
      return [textPart(failureJson(CONTACTS_PICK_TOOL_NAME, 'invalid_arguments',
        `max_count must be an integer between 1 and ${CONTACTS_PICK_MAX}.`))];
    }
    let result: PickResult<PickedContact>;
    try {
      result = await raceAbort(deps.port.pickContacts(maxCount, signal), signal);
    } catch (e) {
      if (isAbort(e as Error, signal)) throw (e instanceof Error ? e : makeAbortError());
      return [textPart(failureJson(CONTACTS_PICK_TOOL_NAME, 'load_failed',
        e instanceof Error && e.message.length > 0 ? e.message : 'Contact picker failed.'))];
    }
    if (result.status !== 'ok') {
      return [textPart(failureJson(CONTACTS_PICK_TOOL_NAME, pickStatusToFailure(result.status),
        result.reason.length > 0 ? result.reason : 'Contact selection did not complete.'))];
    }
    if (result.items.length === 0) {
      return [textPart(failureJson(CONTACTS_PICK_TOOL_NAME, 'empty_selection',
        'The user did not select any contacts.'))];
    }
    const picked: PickedContact[] = result.items.slice(0, maxCount);
    const payload: JsonObject = {
      ok: true,
      tool: CONTACTS_PICK_TOOL_NAME,
      selected_count: picked.length,
      contacts: picked.map((c: PickedContact): JsonObject => ({
        name: c.name.trim(),
        phone_numbers: cleanList(c.phoneNumbers),
        email_addresses: cleanList(c.emailAddresses),
      })),
    };
    if (result.items.length > maxCount) {
      payload['truncated'] = true;
      payload['picked_count'] = result.items.length;
    }
    return [textPart(JSON.stringify(payload))];
  },
});

// ===== photos_pick =====

export const createPhotosPickTool = (deps: PersonalContextDeps): AgentTool => makeAgentTool({
  name: PHOTOS_PICK_TOOL_NAME,
  description:
    'Present the foreground system photo picker, copy only the chosen images into app-owned ' +
    'storage under /workspace/photos_pick/, and return the saved file paths to the current agent. ' +
    'Image pixels are attached only when the active model can receive images (see `images_attached` ' +
    'in the result); otherwise use ocr_image or image_info on the returned paths. ' +
    'Never searches or enumerates the photo library.',
  parameters: () => makeInputSchemaObj({
    max_count: {
      type: 'integer',
      description: `Optional maximum selected images, 1...${PHOTOS_PICK_MAX}. Defaults to ${PHOTOS_PICK_MAX}.`,
    },
  }, null),
  needsApproval: false,
  allowsAutoApproval: false,
  execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
    if (signal?.aborted === true) throw makeAbortError();
    const maxCount: number | null = parseMaxCount(input, PHOTOS_PICK_MAX);
    if (maxCount === null) {
      return [textPart(failureJson(PHOTOS_PICK_TOOL_NAME, 'invalid_arguments',
        `max_count must be an integer between 1 and ${PHOTOS_PICK_MAX}.`))];
    }
    let result: PickResult<PickedPhoto>;
    try {
      result = await raceAbort(deps.port.pickPhotos(maxCount, signal), signal);
    } catch (e) {
      if (isAbort(e as Error, signal)) throw (e instanceof Error ? e : makeAbortError());
      return [textPart(failureJson(PHOTOS_PICK_TOOL_NAME, 'load_failed',
        e instanceof Error && e.message.length > 0 ? e.message : 'Photo picker failed.'))];
    }
    if (result.status !== 'ok') {
      return [textPart(failureJson(PHOTOS_PICK_TOOL_NAME, pickStatusToFailure(result.status),
        result.reason.length > 0 ? result.reason : 'Photo selection did not complete.'))];
    }
    if (result.items.length === 0 && (result.skippedTooLarge ?? 0) > 0) {
      return [textPart(failureJson(PHOTOS_PICK_TOOL_NAME, 'media_too_large',
        `Selected images exceed the size limit (${PHOTOS_PICK_MAX_FILE_BYTES} bytes; GIF ${PHOTOS_PICK_MAX_GIF_BYTES} bytes).`))];
    }
    if (result.items.length === 0) {
      return [textPart(failureJson(PHOTOS_PICK_TOOL_NAME, 'empty_selection',
        'The user did not select any photos.'))];
    }
    const picked: PickedPhoto[] = result.items.slice(0, maxCount);
    const now: number = (deps.now ?? Date.now)();
    const random: () => number = deps.random ?? Math.random;
    const photos: JsonObject[] = [];
    const imageParts: UIMessagePart[] = [];
    let skippedTooLarge: number = result.skippedTooLarge ?? 0;
    const skippedUnreadable: number = result.skippedUnreadable ?? 0;
    let failedWrites: number = 0;
    for (let i: number = 0; i < picked.length; i++) {
      const photo: PickedPhoto = picked[i];
      const isGif: boolean = photo.mimeType.toLowerCase() === 'image/gif';
      if (photo.bytes.length > (isGif ? PHOTOS_PICK_MAX_GIF_BYTES : PHOTOS_PICK_MAX_FILE_BYTES)) {
        skippedTooLarge += 1;
        continue;
      }
      const name: string =
        `photo-${stamp(now)}-${i + 1}-${randomSuffix(random)}.${photoExtension(photo.mimeType)}`;
      let stored: string;
      try {
        stored = await deps.writeWorkspaceBytes(`${PHOTOS_WORKSPACE_DIR}/${name}`, photo.bytes, photo.mimeType);
      } catch (_e) {
        failedWrites += 1;
        continue;
      }
      photos.push({
        index: photos.length + 1,
        path: `/workspace/${stored}`,
        mime_type: photo.mimeType,
        byte_count: photo.bytes.length,
      });
      if (deps.includeImages) imageParts.push({ type: 'image', url: photo.dataUrl, metadata: null });
    }
    if (photos.length === 0) {
      return [textPart(failureJson(PHOTOS_PICK_TOOL_NAME,
        skippedTooLarge > 0 ? 'media_too_large' : 'load_failed',
        skippedTooLarge > 0
          ? `Selected images exceed the size limit (${PHOTOS_PICK_MAX_FILE_BYTES} bytes; GIF ${PHOTOS_PICK_MAX_GIF_BYTES} bytes).`
          : 'Selected images could not be saved.'))];
    }
    const payload: JsonObject = {
      ok: true,
      tool: PHOTOS_PICK_TOOL_NAME,
      selected_count: photos.length,
      photos,
      images_attached: deps.includeImages,
    };
    if (!deps.includeImages) {
      payload['hint'] = 'Image pixels are not attached for the current model. Use ocr_image to read text or '
        + 'image_info to inspect an image, passing the `path` values above.';
    }
    if (skippedUnreadable > 0) payload['skipped_unreadable'] = skippedUnreadable;
    if (skippedTooLarge > 0) payload['skipped_too_large'] = skippedTooLarge;
    if (failedWrites > 0) payload['skipped_save_failed'] = failedWrites;
    if (result.items.length > maxCount) payload['truncated'] = true;
    return [...imageParts, textPart(JSON.stringify(payload))];
  },
});

// ===== 组合 =====

export const createPersonalContextTools = (deps: PersonalContextDeps): AgentTool[] => [
  createContactsPickTool(deps),
  createPhotosPickTool(deps),
];
