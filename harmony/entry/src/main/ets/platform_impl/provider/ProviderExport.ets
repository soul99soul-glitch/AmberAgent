import type { common } from '@kit.AbilityKit';
import { picker, fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { promptAction } from '@kit.ArkUI';
import type {
  ProviderSetting, ProviderSettingOpenAIVariant, ProviderSettingGoogle, ProviderSettingClaude, ProviderModel
} from '@amber/chat-domain';

interface ProviderExportModel {
  modelId: string;
  displayName: string;
  type: string;
}
interface ProviderExportPayload {
  format: string;
  version: number;
  type: string;
  name: string;
  baseUrl: string;
  chatCompletionsPath?: string;
  useResponseApi?: boolean;
  vertexAI?: boolean;
  location?: string;
  projectId?: string;
  authMode?: string;
  useServiceAccount?: boolean;
  serviceAccountEmail?: string;
  oauthClientId?: string;
  oauthRedirectUri?: string;
  promptCaching?: boolean;
  models: ProviderExportModel[];
}

export function exportProvider(raw: ProviderSetting | null, ctx: common.Context): void {
    if (raw === null) {
      promptAction.showToast({ message: '请先保存后再导出', duration: 1600 });
      return;
    }
    const payload: ProviderExportPayload = {
      format: 'amber-provider',
      version: 1,
      type: raw.type,
      name: raw.name,
      baseUrl: raw.baseUrl,
      models: raw.models.map((m: ProviderModel): ProviderExportModel => ({
        modelId: m.modelId, displayName: m.displayName, type: m.type,
      })),
    };
    if (raw.type === 'openai') {
      const v: ProviderSettingOpenAIVariant = raw as ProviderSettingOpenAIVariant;
      payload.chatCompletionsPath = v.chatCompletionsPath;
      payload.useResponseApi = v.useResponseApi;
    } else if (raw.type === 'google') {
      const v: ProviderSettingGoogle = raw as ProviderSettingGoogle;
      payload.vertexAI = v.vertexAI;
      payload.location = v.location;
      payload.projectId = v.projectId;
      payload.authMode = v.authMode;
      payload.useServiceAccount = v.useServiceAccount;
      payload.serviceAccountEmail = v.serviceAccountEmail;
      payload.oauthClientId = v.oauthClientId;
      payload.oauthRedirectUri = v.oauthRedirectUri;
    } else {
      const v: ProviderSettingClaude = raw as ProviderSettingClaude;
      payload.promptCaching = v.promptCaching;
    }
    const json: string = JSON.stringify(payload, null, 2);
    const safeName: string = (raw.name.trim().length > 0 ? raw.name.trim() : 'provider')
      .replace(/[\\/:*?"<>|]/g, '_');
    const fileName: string = `provider-${safeName}.json`;
    const docPicker = new picker.DocumentViewPicker(ctx);
    docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
      if (uris.length > 0) {
        writeExportFile(uris[0], json, fileName);
      } else {
        exportToAppDir(ctx, json, fileName);
      }
    }).catch((): void => {
      exportToAppDir(ctx, json, fileName);
    });
  }

function writeExportFile(uri: string, content: string, fileName: string): void {
    try {
      const file: fileIo.File = fileIo.openSync(
        uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已导出 ${fileName}`, duration: 2000 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }

function exportToAppDir(ctx: common.Context, content: string, fileName: string): void {
    const dir: string = `${ctx.filesDir}/exports`;
    try {
      fileIo.mkdirSync(dir, true);
    } catch {
      // 已存在
    }
    const path: string = `${dir}/${fileName}`;
    try {
      const file: fileIo.File = fileIo.openSync(
        path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已保存到应用目录 ${fileName}`, duration: 2200 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }
