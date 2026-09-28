import type { ProviderSetting, ProviderSettingGoogle } from '@amber/chat-domain';
import { copyProviderSettingWithModels, loadProviders, saveProviders } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { stageGoogleSecret, deleteGoogleSecret } from './GoogleSecureStore.ets';
import { logoutGoogle, forgetGoogleProvider, updateGoogleAuthConfiguration } from './GoogleAuthSupport.ets';

export type GooglePageAuthMode = 'api_key' | 'service_account' | 'gemini_code_assist_oauth' | 'antigravity_oauth';
export interface GoogleProviderAuthDraft {
  mode: GooglePageAuthMode;
  clientId: string;
  redirectUri: string;
  clientSecret: string;
  clearClientSecret: boolean;
  privateKeyRef: string;
  clearPrivateKey: boolean;
  email: string;
  projectId: string;
  location: string;
}
export interface GoogleProviderSaveResult {
  setting: ProviderSettingGoogle;
  cleanupMessage: string;
}
interface GoogleServiceAccountJson {
  type?: string;
  project_id?: string;
  client_email?: string;
  private_key?: string;
}
export interface GoogleServiceAccountImport {
  projectId: string;
  email: string;
  privateKey: string;
}

export const parseGoogleServiceAccountJson = (raw: string): GoogleServiceAccountImport => {
  const data: GoogleServiceAccountJson = JSON.parse(raw) as GoogleServiceAccountJson;
  const projectId: string = (data.project_id ?? '').trim();
  const email: string = (data.client_email ?? '').trim();
  const privateKey: string = data.private_key ?? '';
  if (data.type !== 'service_account' || projectId.length === 0 || email.indexOf('@') < 1
    || !/^-----BEGIN PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]+-----END PRIVATE KEY-----\r?\n?$/.test(privateKey)) {
    throw new Error('请选择包含 project_id、client_email 和 PKCS#8 private_key 的 Google 服务账号 JSON。');
  }
  return { projectId: projectId, email: email, privateKey: privateKey };
};

// A fresh lookup preserves model parameter edits made in the provider detail page.
export const saveGoogleProviderAuthDraft = async (
  providerId: string, draft: GoogleProviderAuthDraft,
): Promise<GoogleProviderSaveResult> => {
  let all: ProviderSetting[];
  try { all = (await loadProviders(getChatKvStore())) ?? []; }
  catch (error) {
    if (draft.privateKeyRef.length > 0) await deleteGoogleSecret(providerId, 'service_account_private_key', draft.privateKeyRef);
    throw (error as Error);
  }
  const index: number = all.findIndex((item: ProviderSetting): boolean => item.id === providerId);
  if (index < 0 || all[index].type !== 'google') {
    if (draft.privateKeyRef.length > 0) await deleteGoogleSecret(providerId, 'service_account_private_key', draft.privateKeyRef);
    throw new Error('服务商已不存在，请返回服务商列表。');
  }
  const prior: ProviderSettingGoogle = all[index] as ProviderSettingGoogle;
  const updated: ProviderSettingGoogle = copyProviderSettingWithModels(prior, prior.models) as ProviderSettingGoogle;
  const staged: string[] = [];
  // An imported reference is owned by this save once handed over by the page.
  if (draft.privateKeyRef.length > 0) staged.push(draft.privateKeyRef);
  try {
    if (draft.clearPrivateKey) {
      updated.privateKeyRef = '';
      updated.privateKey = '';
    } else if (draft.privateKeyRef.length > 0) {
      updated.privateKeyRef = draft.privateKeyRef;
      updated.privateKey = '';
    } else if (draft.mode === 'service_account' && prior.privateKey.length > 0) {
      updated.privateKeyRef = await stageGoogleSecret(providerId, 'service_account_private_key', prior.privateKey);
      staged.push(updated.privateKeyRef);
      updated.privateKey = '';
    }
    if (draft.clearClientSecret) updated.oauthClientSecretRef = '';
    else if (draft.clientSecret.length > 0) {
      updated.oauthClientSecretRef = await stageGoogleSecret(providerId, 'oauth_client_secret', draft.clientSecret);
    }
    updated.authMode = draft.mode === 'service_account' ? 'api_key' : draft.mode;
    updated.useServiceAccount = draft.mode === 'service_account';
    if (updated.useServiceAccount) updated.vertexAI = true;
    updated.oauthClientId = draft.clientId.trim();
    updated.oauthRedirectUri = draft.redirectUri.trim();
    updated.serviceAccountEmail = draft.email.trim();
    updated.projectId = draft.projectId.trim();
    updated.location = draft.location.trim();
    all[index] = updated;
    await saveProviders(getChatKvStore(), all);
  } catch (error) {
    const cleanup: Promise<void>[] = staged.map((ref: string): Promise<void> =>
      deleteGoogleSecret(providerId, 'service_account_private_key', ref));
    if (updated.oauthClientSecretRef !== prior.oauthClientSecretRef && updated.oauthClientSecretRef.length > 0) {
      cleanup.push(deleteGoogleSecret(providerId, 'oauth_client_secret', updated.oauthClientSecretRef));
    }
    const outcomes = await Promise.allSettled(cleanup);
    if (outcomes.some((outcome): boolean => outcome.status === 'rejected')) {
      throw new Error('配置未保存，原配置仍有效；新凭据清理失败，请重新进入后检查。');
    }
    throw (error as Error);
  }
  // Persisted configuration is the authority; stale detached run snapshots cannot rebind it.
  let configurationUpdateFailed: boolean = false;
  try { await updateGoogleAuthConfiguration(updated); }
  catch (_error) { configurationUpdateFailed = true; }
  const cleanup: Promise<void>[] = [];
  if (prior.privateKeyRef.length > 0 && prior.privateKeyRef !== updated.privateKeyRef) {
    cleanup.push(deleteGoogleSecret(providerId, 'service_account_private_key', prior.privateKeyRef));
  }
  if (prior.oauthClientSecretRef.length > 0 && prior.oauthClientSecretRef !== updated.oauthClientSecretRef) {
    cleanup.push(deleteGoogleSecret(providerId, 'oauth_client_secret', prior.oauthClientSecretRef));
  }
  if (prior.authMode !== updated.authMode || prior.oauthClientId !== updated.oauthClientId
    || prior.oauthRedirectUri !== updated.oauthRedirectUri || prior.oauthClientSecretRef !== updated.oauthClientSecretRef) {
    if (prior.authMode !== 'api_key') cleanup.push(logoutGoogle(prior));
  }
  const outcomes = await Promise.allSettled(cleanup);
  return {
    setting: updated,
    cleanupMessage: configurationUpdateFailed || outcomes.some((outcome): boolean => outcome.status === 'rejected')
      ? '配置已保存，但本地授权更新或旧凭据清理失败。请重新进入并检查授权状态。' : '',
  };
};

// Call only after provider metadata deletion commits successfully.
export const cleanupDeletedGoogleProvider = async (setting: ProviderSettingGoogle): Promise<void> => {
  const cleanup: Promise<void>[] = [forgetGoogleProvider(setting)];
  if (setting.privateKeyRef.length > 0) {
    cleanup.push(deleteGoogleSecret(setting.id, 'service_account_private_key', setting.privateKeyRef));
  }
  if (setting.oauthClientSecretRef.length > 0) {
    cleanup.push(deleteGoogleSecret(setting.id, 'oauth_client_secret', setting.oauthClientSecretRef));
  }
  const outcomes = await Promise.allSettled(cleanup);
  if (outcomes.some((outcome): boolean => outcome.status === 'rejected')) {
    throw new Error('服务商已删除，但本地 Google 凭据清理失败。');
  }
};
