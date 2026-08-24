// miniapp_models — MiniApp 领域模型 / 权限集合 / 别名映射 / 常量
//
// Android 基准:
//   app/.../feature/miniapp/MiniAppModels.kt(全文)
//   app/.../agent/data/db/entity/MiniAppEntity.kt(5 张表字段;鸿蒙侧不落 Room,改为 Port 记录)
//   MiniAppBridge.kt 协议层(MiniAppBridgeRequest/Response)
// 偏差:
//   - MiniAppEntity.permissionsJson(String)→ MiniAppRecord.permissions(string[])
//     (Port 内联数组;序列化由 adapter 决定)
//   - MiniAppGrantDecision 枚举 → 字符串字面量联合 'ALLOW' | 'DENY'
//   - MiniAppPermission 枚举 → 字符串字面量联合(值即类型)
//   - Uuid → string(idGen 注入)

import type { JsonObject, JsonValue } from '../json.ts';

// ===== 常量(MiniAppModels.kt:6-7)=====
export const MINI_APP_MAX_HTML_BYTES: number = 768 * 1024;
export const MINI_APP_VERSION_KEEP_LIMIT: number = 30;
export const MINI_APP_BOARD_SUMMARY_MAX_CHARS: number = 500;
export const MINI_APP_TITLE_MAX_CHARS: number = 20;
export const MINI_APP_DESCRIPTION_MAX_CHARS: number = 80;
export const MINI_APP_ICON_MAX_CHARS: number = 2;
export const MINI_APP_RENAME_TITLE_MAX_CHARS: number = 40;
export const MINI_APP_RENAME_DESCRIPTION_MAX_CHARS: number = 120;
export const MINI_APP_AUDIT_SUMMARY_MAX_CHARS: number = 180;
export const MINI_APP_CHANGE_NOTE_MAX_CHARS: number = 240;
export const MINI_APP_SHARED_VALUE_BYTES: number = 32 * 1024;
export const MINI_APP_SHARED_NAMESPACE_BYTES: number = 2 * 1024 * 1024;

// ===== 权限(MiniAppModels.kt:9-32)=====
export type MiniAppPermission =
  | 'storage'
  | 'toast'
  | 'theme'
  | 'network'
  | 'externalImages'
  | 'search'
  | 'clipboard.copy'
  | 'host.updateBoardSummary'
  | 'host.context'
  | 'host.sendToConversation'
  | 'host.createArtifact'
  | 'ai.generate'
  | 'sharedStore'
  | 'eventBus'
  | 'launch'
  | 'sensor'
  | 'location'
  | 'clipboard.read';

export const MINI_APP_V2_PERMISSIONS: MiniAppPermission[] = [
  'storage',
  'toast',
  'theme',
  'network',
  'externalImages',
  'search',
  'clipboard.copy',
  'host.updateBoardSummary',
];
export const MINI_APP_V3_PERMISSIONS: MiniAppPermission[] = [
  ...MINI_APP_V2_PERMISSIONS,
  'host.context',
  'host.sendToConversation',
  'host.createArtifact',
  'ai.generate',
  'sharedStore',
  'eventBus',
  'launch',
  'sensor',
  'location',
  'clipboard.read',
];
export const MINI_APP_V1_PERMISSIONS: MiniAppPermission[] = MINI_APP_V3_PERMISSIONS;
export const MINI_APP_CATEGORIES: string[] = ['tool', 'game', 'info', 'custom'];

export const MINI_APP_PERMISSION_ALIASES: Record<string, string> = {
  'fetch': 'network',
  'http': 'network',
  'https': 'network',
  'internet': 'network',
  '联网': 'network',
  'externalimages': 'externalImages',
  'external-images': 'externalImages',
  'external_images': 'externalImages',
  'remoteImages': 'externalImages',
  'gyro': 'sensor',
  'gyroscope': 'sensor',
  'accelerometer': 'sensor',
  'accel': 'sensor',
  'light': 'sensor',
  'ambientLight': 'sensor',
  'ambientlight': 'sensor',
  'ambient-light': 'sensor',
  'ambient_light': 'sensor',
  'illuminance': 'sensor',
};

export type MiniAppGrantDecision = 'ALLOW' | 'DENY';

// ===== 模型(MiniAppModels.kt:56-92 + MiniAppEntity.kt)=====
export interface MiniAppGeneratedOutput {
  title: string;
  description: string;
  icon: string | null;
  category: string;
  permissions: string[];
  html: string;
}

export interface MiniAppCardRef {
  appId: string;
  title: string;
  description: string;
  iconEmoji: string | null;
  category: string | null;
  permissions: string[];
  htmlHash: string | null;
  version: number;
}

export interface MiniAppBridgeRequest {
  id: number;
  token: string;
  method: string;
  params: JsonObject;
}

export interface MiniAppBridgeResponse {
  id: number;
  ok: boolean;
  data: JsonValue | null;
  error: string | null;
}

// 存储层记录(替代 Room MiniAppEntity;permissionsJson → permissions 数组)
export interface MiniAppRecord {
  id: string;
  title: string;
  description: string;
  htmlContent: string;
  sourceConversationId: string | null;
  sourceMessageId: string | null;
  iconEmoji: string | null;
  category: string | null;
  permissions: string[];
  pinned: boolean;
  runCount: number;
  boardSummary: string | null;
  version: number;
  htmlHash: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface MiniAppVersionRecord {
  appId: string;
  versionNumber: number;
  htmlContent: string;
  htmlHash: string;
  changeNote: string | null;
  createdAt: number;
}

export interface MiniAppAuditEntry {
  id: string;
  appId: string;
  method: string;
  permission: string;
  summary: string;
  payloadHash: string;
  createdAt: number;
}

// ===== 异常类型(Android: MiniAppValidationException : IllegalArgumentException /
//   SecurityException;SerializationException) =====
export class MiniAppValidationException extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MiniAppValidationException';
  }
}

export class MiniAppSecurityException extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MiniAppSecurityException';
  }
}

// 对齐 kotlinx SerializationException(JSON 解码失败)
export class MiniAppParseException extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MiniAppParseException';
  }
}

// ===== UTF-8 字节长度(Java String.encodeToByteArray().size 语义) =====
// 域层无 TextEncoder 依赖;手写 UTF-8 长度计数(workspace_artifacts utf8Encode 对称)
export const utf8ByteLength = (text: string): number => {
  let bytes: number = 0;
  for (let i: number = 0; i < text.length; i++) {
    const code: number = text.charCodeAt(i);
    if (code < 0x80) {
      bytes += 1;
    } else if (code < 0x800) {
      bytes += 2;
    } else if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
      const lo: number = text.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        bytes += 4;
        i++;
      } else {
        bytes += 3;
      }
    } else {
      bytes += 3;
    }
  }
  return bytes;
};
