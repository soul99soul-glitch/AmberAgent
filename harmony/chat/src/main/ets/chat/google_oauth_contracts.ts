// Google Vertex SA / Gemini Code Assist OAuth 契约(纯逻辑)
//
// Android 基准: google_chat_api 现有 throw 分支的稳定文案与分类。
// 完整 JWT / OAuth 实现不在本切片;契约让调用方可分类 auth_not_implemented。

export type GoogleAuthNotImplementedKind =
  | 'vertex_service_account'
  | 'gemini_code_assist_oauth';

export class GoogleAuthNotImplementedError extends Error {
  readonly kind: GoogleAuthNotImplementedKind;
  constructor(kind: GoogleAuthNotImplementedKind) {
    super(kind === 'vertex_service_account'
      ? 'vertex service account 未落地(P1)'
      : 'Gemini Code Assist OAuth 未落地(P1)');
    this.name = 'GoogleAuthNotImplementedError';
    this.kind = kind;
  }
}

export type GoogleProviderAuthForm =
  | { form: 'api_key' }
  | { form: 'vertex_service_account' }
  | { form: 'gemini_code_assist_oauth' };

/** 由 ProviderSetting 形态判定 auth 形态(纯函数,便于测试与上层分类) */
export const classifyGoogleAuthForm = (input: {
  serviceAccountJson?: string | null;
  codeAssistEnabled?: boolean;
  authMode?: string;
}): GoogleProviderAuthForm => {
  if (input.authMode === 'vertex_service_account' ||
    (input.serviceAccountJson !== undefined && input.serviceAccountJson !== null && input.serviceAccountJson.length > 0)) {
    return { form: 'vertex_service_account' };
  }
  if (input.authMode === 'gemini_code_assist' || input.codeAssistEnabled === true) {
    return { form: 'gemini_code_assist_oauth' };
  }
  return { form: 'api_key' };
};

export const isGoogleAuthNotImplementedError = (
  e: unknown,
): e is GoogleAuthNotImplementedError =>
  e instanceof Error && e.name === 'GoogleAuthNotImplementedError';

/** 与 google_chat_api.ts 既有 throw 文案对齐 */
export const throwGoogleAuthNotImplemented = (kind: GoogleAuthNotImplementedKind): never => {
  throw new GoogleAuthNotImplementedError(kind);
};
