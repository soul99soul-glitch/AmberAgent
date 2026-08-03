// top_model_menu — 顶部模型快速切换菜单的数据装配纯逻辑
//
// Android 基准: feature/ui/components/ai/TopModelMenu.kt(275 行)
//   - 顶层 @Composable 接收已过滤的 enabled providers + ModelType(调用点传 CHAT),
//     内部 ProviderGroup 再按 modelType 过滤 provider.models,无该类型模型的 provider
//     整组不渲染。
//   - currentProviderId/currentModelId = Uuid;调用点 ChatPage.kt:985-996:
//       chatModelIdForMenu = assistant.chatModelId ?: setting.chatModelId
//       chatProvidersForMenu = setting.providers.filter { p.enabled && p.models.any{it.type==CHAT} }
//       currentProviderIdForMenu = chatProvidersForMenu.firstOrNull{ p.models.any{it.id==chatModelIdForMenu} }?.id
//     选中态仅用 accent 色表达(无加粗/对勾/signal 绿)。
//
// 鸿蒙差异(已对齐既有契约):
//   - 选择层非 Uuid:Harmony 用 Preferences 键 chat_selected_provider_id/model_id,
//     值为人类可读 model.modelId 字符串(非 model.id uuid)——与 ChatModelPickerPage
//     既有契约一致,不做 uuid 化改造。
//   - 故 currentProviderId = provider.id(字符串),currentModelId = model.modelId(字符串),
//     与 getChatModelSelection() 返回的 {providerId, modelId} 直接对齐。
//   - hasUsableAuth 复用 provider_settings.ts 既有判定(:389-408)。
//
// 纯逻辑职责(零 UI/零 IO):
//   - 遍历 providers,丢弃 !hasUsableAuth 的;
//   - 组内 models 过滤为指定 ModelType,组无模型则整组丢弃(对齐 ProviderGroup 早返回);
//   - 标记 selected = providerId 命中 && modelId 命中(空 currentModelId 视为无选中);
//   - contextWindowTokens 原样透传(null = 未知,UI 显示空)。
//   - 显示格式化(formatNumberInt)属 UI 层关注点,不进本层。

import type { ProviderSetting, ProviderModel, ModelType } from './provider_settings.ts';
import { hasUsableAuth } from './provider_settings.ts';

// 单个模型行(UI 用)— modelId 为人类可读字符串(model.modelId)
export interface TopModelMenuModel {
  modelId: string;
  displayName: string;
  contextWindowTokens: number | null;
  selected: boolean;
}

// 一个 provider 分组(UI 用)— provider.id + provider.name + 该 type 下的模型列表
export interface TopModelMenuGroup {
  providerId: string;
  providerName: string;
  models: TopModelMenuModel[];
}

// buildTopModelMenuGroups — 装配 TopModelMenu 的展示数据
//   providers: loadProviders() 结果(null = 未配置任何 provider)
//   modelType: 目标模型类型(调用点传 'chat')
//   currentProviderId/currentModelId: 当前选中('' = 无选中,与 getChatModelSelection null 等价)
// 返回:每个 hasUsableAuth 且含目标类型模型的 provider 一组,组内仅目标类型模型;
//   无任何可用 provider → [](UI 层显示空态)
export const buildTopModelMenuGroups = (
  providers: ProviderSetting[] | null,
  modelType: ModelType,
  currentProviderId: string,
  currentModelId: string,
): TopModelMenuGroup[] => {
  if (providers === null) return [];
  const hasCurrent: boolean = currentProviderId.length > 0 && currentModelId.length > 0;
  const groups: TopModelMenuGroup[] = [];
  for (const p of providers) {
    if (!hasUsableAuth(p)) continue;
    const models: TopModelMenuModel[] = [];
    for (const m of p.models) {
      if (m.type !== modelType) continue;
      const selected: boolean = hasCurrent
        && p.id === currentProviderId && m.modelId === currentModelId;
      models.push({
        modelId: m.modelId,
        displayName: m.displayName,
        contextWindowTokens: m.contextWindowTokens,
        selected,
      });
    }
    // 对齐 Android ProviderGroup:无目标类型模型则整组不渲染
    if (models.length === 0) continue;
    groups.push({
      providerId: p.id,
      providerName: p.name,
      models,
    });
  }
  return groups;
};
