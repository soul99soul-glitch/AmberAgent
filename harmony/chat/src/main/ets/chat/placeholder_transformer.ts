// placeholder_transformer — 占位符替换(D-073)
// Android 基准: app/.../core/ai/transformers/PlaceholderTransformer.kt(全文 182 行)
//   - DefaultPlaceholderProvider:13 键插入序(cur_date/cur_time/cur_datetime/
//     model_id/model_name/locale/timezone/system_version/device_info/
//     battery_level/nickname/char/user)
//   - replacePlaceholders(:160-181):逐键 replace("{{key}}") + replace("{key}"),
//     ignoreCase=true,Kotlin replace = 全部替换;顺序执行(后键可命中前键产出文本)
//   - nickname/user:displaySetting.userNickname.ifBlank { "user" }
//   - char:assistant.name.ifBlank { "assistant" }
// 偏差:
//   - displayName 属性(Compose UI)不移植;平台取值(battery/device/locale/
//       timezone/version/时间格式化)注入 PlaceholderValues(entry 实现,登记偏差)
//   - ctx.model 不在 TransformerContext → model_id/model_name 由 values 供给
import type { UIMessage, UIMessagePart } from './message.ts';
import type { MessageTransformer, TransformerContext } from './transformer_pipeline.ts';

// 13 键插入序(:62-114 逐字序;LinkedHashMap 迭代序)
export const PLACEHOLDER_KEYS: string[] = [
  'cur_date',
  'cur_time',
  'cur_datetime',
  'model_id',
  'model_name',
  'locale',
  'timezone',
  'system_version',
  'device_info',
  'battery_level',
  'nickname',
  'char',
  'user',
];

// 平台相关取值(entry 注入;Android resolver 逐字语义在引擎侧保持)
export interface PlaceholderValues {
  curDate: () => string; // LocalDate.now() MEDIUM locale
  curTime: () => string; // LocalTime.now() MEDIUM locale
  curDatetime: () => string; // LocalDateTime.now() MEDIUM locale
  modelId: string; // ctx.model.modelId
  modelName: string; // ctx.model.displayName
  localeName: () => string; // Locale.getDefault().displayName
  timezoneName: () => string; // TimeZone.getDefault().displayName
  systemVersion: string; // "Android SDK v${SDK_INT} (${RELEASE})" → Harmony 等价(登记)
  deviceInfo: string; // "${Build.BRAND} ${Build.MODEL}"
  batteryLevel: () => string; // BATTERY_PROPERTY_CAPACITY.toString()
  nickname: () => string; // displaySetting.userNickname(blank → 引擎补 'user')
}

const escapeRegExp = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// Kotlin replace(oldValue, newValue, ignoreCase=true):全部非重叠替换,字面语义
//   (replacement 不解释 $ — JS 用函数 replacer 规避)
export const replaceAllIgnoreCase = (text: string, needle: string, value: string): string =>
  text.replace(new RegExp(escapeRegExp(needle), 'gi'), (): string => value);

// 单键解析(:173-178;顺序执行,后键可命中前键产出)
export const resolvePlaceholderValue = (
  key: string, values: PlaceholderValues, assistantName: string,
): string => {
  switch (key) {
    case 'cur_date': return values.curDate();
    case 'cur_time': return values.curTime();
    case 'cur_datetime': return values.curDatetime();
    case 'model_id': return values.modelId;
    case 'model_name': return values.modelName;
    case 'locale': return values.localeName();
    case 'timezone': return values.timezoneName();
    case 'system_version': return values.systemVersion;
    case 'device_info': return values.deviceInfo;
    case 'battery_level': return values.batteryLevel();
    case 'nickname': {
      const n: string = values.nickname();
      return n.trim().length > 0 ? n : 'user';
    }
    case 'char': return assistantName.trim().length > 0 ? assistantName : 'assistant';
    case 'user': {
      const n: string = values.nickname();
      return n.trim().length > 0 ? n : 'user';
    }
    default: return '';
  }
};

// replacePlaceholders(:160-181 全文忠实)
export const replacePlaceholders = (
  text: string, values: PlaceholderValues, assistantName: string,
): string => {
  let result: string = text;
  for (const key of PLACEHOLDER_KEYS) {
    const value: string = resolvePlaceholderValue(key, values, assistantName);
    result = replaceAllIgnoreCase(result, `{{${key}}}`, value);
    result = replaceAllIgnoreCase(result, `{${key}}`, value);
  }
  return result;
};

// transform(:140-158):全量 copy(无恒等短路,忠实 Android map+copy)
export const createPlaceholderTransformer = (values: PlaceholderValues): MessageTransformer => ({
  transform: (ctx: TransformerContext, messages: UIMessage[]): UIMessage[] =>
    messages.map((m: UIMessage): UIMessage => ({
      ...m,
      parts: m.parts.map((p: UIMessagePart): UIMessagePart => {
        if (p.type !== 'text') return p;
        return { ...p, text: replacePlaceholders(p.text, values, ctx.assistant.name) };
      }),
    })),
});
