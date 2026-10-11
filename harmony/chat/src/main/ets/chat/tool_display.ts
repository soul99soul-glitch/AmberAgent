// tool_display.ts — 工具调用的用户可见文案(纯函数,无 UI 依赖)
//
// 职责:
//   - 工具显示标题:display_title(模型提供) → 静态中文标签 → 调用方兜底
//   - 模型槽位中文名(settings_set_model_slot;原先只在沙盒 inputPreview 内部可见)
//   - 待授权摘要:按工具产出「标签: 值」行 + 破坏性标记 + 后果提示
//
// iOS 基准:
//   - ChatToolSupport.swift displayName(for:)(静态标签表)/ isDestructiveAppleAction
//     (_delete/_cancel/_remove 后缀)/ appleConsequenceWarning(闹钟响铃/取消提示)
//   - ChatToolSupport.swift appleArgumentsPreview(标签行;值 trim + 截 160;无行 →
//     「无需额外参数」)
// 偏差登记:
//   - 时间值做轻量格式化(ISO-8601 → "YYYY-MM-DD HH:mm"),iOS 原样显示
//   - wm_act 只展示步骤动作序列,绝不展示 type 步骤的输入文本(可能含账号/口令)
//   - 未知工具的兜底摘要只取顶层标量参数(先经敏感键脱敏);provider_* 工具不展示参数
//     (提供商名/URL/身份可能含凭据,与 toolSandboxInputPreview 同口径)

import type { JsonObject, JsonValue } from './json.ts';
import { maskSensitiveJsonObject } from './conversation_export.ts';

// ===== 模型槽位 =====

const MODEL_SLOT_LABELS: Record<string, string> = {
  'chat': '对话模型',
  'assistant_chat': '助手模型',
  'title': '标题模型',
  'ocr': '视觉识别模型',
  'compress': '压缩模型',
  'suggestion': '建议模型',
  'image_generation': '生图模型',
};

/** 槽位 id → 中文名;未知/空 → null */
export const modelSlotLabel = (slot: string | null): string | null => {
  if (slot === null) return null;
  const key: string = slot.trim().toLowerCase();
  if (key.length === 0) return null;
  const label: string | undefined = MODEL_SLOT_LABELS[key];
  return label !== undefined ? label : null;
};

/** settings_set_model_slot 的动作标题:「设置对话模型」;槽位未知 → 「设置默认模型」 */
export const modelSlotActionTitle = (slot: string | null): string => {
  const label: string | null = modelSlotLabel(slot);
  return label !== null ? `设置${label}` : '设置默认模型';
};

// ===== 静态标签 =====

const TOOL_STATIC_LABELS: Record<string, string> = {
  'calendar_events_list': '查看日历事件',
  'calendar_event_create': '新建日历事件',
  'calendar_event_update': '更新日历事件',
  'calendar_event_delete': '删除日历事件',
  'reminders_list': '查看提醒事项',
  'reminder_create': '新建提醒事项',
  'reminder_update': '更新提醒事项',
  'reminder_delete': '删除提醒事项',
  'reminder_complete': '完成提醒事项',
  'notification_schedule': '安排本地通知',
  'notification_cancel': '取消本地通知',
  'alarm_schedule': '安排系统闹钟',
  'alarms_list': '查看 Amber 闹钟',
  'alarm_cancel': '取消系统闹钟',
  'contacts_pick': '选择联系人',
  'photos_pick': '选择照片',
  'weather_read': '读取天气',
  'mcp_describe_tool': '查看 MCP 工具说明',
  'wm_get': '读取网页节点',
  'wm_visual_snapshot': '读取视觉快照',
  'wm_visual_read': '视觉核验网页',
  'wm_clear_session': '清除站点登录状态',
  'wm_act': '执行网页动作批',
};

const stringField = (obj: JsonObject, key: string): string | null => {
  const v: JsonValue | undefined = obj[key];
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

/** 工具名(+ 输入)→ 静态中文标签;无映射 → null。不读 display_title。 */
export const toolStaticLabel = (toolName: string, input?: JsonObject): string | null => {
  if (toolName === 'settings_set_model_slot') {
    return modelSlotActionTitle(input !== undefined ? stringField(input, 'slot') : null);
  }
  const label: string | undefined = TOOL_STATIC_LABELS[toolName];
  return label !== undefined ? label : null;
};

export const DISPLAY_TITLE_MAX_CHARS: number = 24;

/** 输入里的 display_title:仅字符串;折叠空白 + trim;空 → null;超长截断加 … */
export const toolInputDisplayTitle = (input: JsonObject): string | null => {
  const raw: JsonValue | undefined = input['display_title'];
  if (typeof raw !== 'string') return null;
  // 去掉控制字符(含换行),避免单行胶囊被撑高或注入不可见字符
  const compact: string = raw.replace(/[\u0000-\u001f\u007f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim();
  if (compact.length === 0) return null;
  const chars: string[] = Array.from(compact);
  if (chars.length <= DISPLAY_TITLE_MAX_CHARS) return compact;
  return `${chars.slice(0, DISPLAY_TITLE_MAX_CHARS - 1).join('')}…`;
};

// ===== 破坏性判定 =====

/** iOS isDestructiveAppleAction 同口径(_delete/_cancel/_remove)+ wm_clear_session */
export const isDestructiveTool = (toolName: string): boolean =>
  toolName.endsWith('_delete') || toolName.endsWith('_cancel') || toolName.endsWith('_remove')
    || toolName === 'wm_clear_session';

// ===== 待授权摘要 =====

export type ToolApprovalWarningLevel = 'none' | 'caution' | 'danger';

export interface ToolApprovalLine {
  label: string;
  value: string;
}

export interface ToolApprovalSummary {
  /** 静态中文标签(不取模型给的 display_title,授权界面以工具真实身份为准) */
  title: string;
  lines: ToolApprovalLine[];
  /** 无参数行时的补充说明(如「无需额外参数」);有行时为 '' */
  note: string;
  destructive: boolean;
  warning: string;
  warningLevel: ToolApprovalWarningLevel;
}

export const APPROVAL_VALUE_MAX_CHARS: number = 160;
const APPROVAL_GENERIC_MAX_LINES: number = 4;

const compactValue = (text: string, max: number = APPROVAL_VALUE_MAX_CHARS): string => {
  const compact: string = text.replace(/\s+/g, ' ').trim();
  const chars: string[] = Array.from(compact);
  return chars.length > max ? `${chars.slice(0, max - 1).join('')}…` : compact;
};

const scalarText = (obj: JsonObject, key: string): string | null => {
  const v: string | null = stringField(obj, key);
  if (v === null) return null;
  const t: string = compactValue(v);
  return t.length > 0 ? t : null;
};

const ISO_RE: RegExp = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/;

/** ISO-8601 → "YYYY-MM-DD HH:mm"(带时区后缀时保留,Z → UTC);非 ISO 原样 */
export const formatApprovalTime = (raw: string): string => {
  const t: string = raw.trim();
  const m: RegExpExecArray | null = ISO_RE.exec(t);
  if (m === null) return compactValue(t);
  const zone: string | undefined = m[3];
  if (zone === undefined) return `${m[1]} ${m[2]}`;
  return `${m[1]} ${m[2]} ${zone === 'Z' ? 'UTC' : zone}`;
};

const timeText = (obj: JsonObject, key: string): string | null => {
  const v: string | null = scalarText(obj, key);
  return v !== null ? formatApprovalTime(v) : null;
};

const intOf = (obj: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = obj[key];
  if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
  if (typeof v === 'string' && /^-?\d+$/.test(v.trim())) return parseInt(v.trim(), 10);
  return null;
};

const numberOf = (obj: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = obj[key];
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  if (typeof v === 'string' && v.trim().length > 0 && Number.isFinite(Number(v.trim()))) return Number(v.trim());
  return null;
};

const pad2 = (n: number): string => (n < 10 ? `0${n}` : `${n}`);

const WEEKDAY_LABELS: Record<string, string> = {
  'sunday': '周日', 'monday': '周一', 'tuesday': '周二', 'wednesday': '周三',
  'thursday': '周四', 'friday': '周五', 'saturday': '周六',
};

const weekdaysText = (obj: JsonObject): string | null => {
  const v: JsonValue | undefined = obj['weekdays'];
  if (!Array.isArray(v)) return null;
  const out: string[] = [];
  for (const item of v) {
    if (typeof item !== 'string') continue;
    const key: string = item.trim().toLowerCase();
    const label: string | undefined = WEEKDAY_LABELS[key];
    out.push(label !== undefined ? label : compactValue(item, 12));
  }
  return out.length > 0 ? out.join('、') : null;
};

/** 秒 → 「1 小时 30 分」「5 分钟」「45 秒」 */
export const formatDurationSeconds = (seconds: number): string => {
  const total: number = Math.max(0, Math.round(seconds));
  const h: number = Math.floor(total / 3600);
  const m: number = Math.floor((total % 3600) / 60);
  const s: number = total % 60;
  const parts: string[] = [];
  if (h > 0) parts.push(`${h} 小时`);
  if (m > 0) parts.push(h > 0 ? `${m} 分` : `${m} 分钟`);
  if (s > 0 || parts.length === 0) parts.push(`${s} 秒`);
  return parts.join(' ');
};

const ALARM_KIND_LABELS: Record<string, string> = {
  'one_time': '一次性闹钟',
  'weekly': '每周重复',
  'timer': '倒计时',
};

const RECURRENCE_LABELS: Record<string, string> = {
  'daily': '天', 'weekly': '周', 'monthly': '月',
};

const recurrenceText = (obj: JsonObject): string | null => {
  const raw: string | null = scalarText(obj, 'recurrence');
  if (raw === null) return null;
  const freq: string = raw.toLowerCase();
  if (freq === 'none') return '不重复';
  const unit: string | undefined = RECURRENCE_LABELS[freq];
  if (unit === undefined) return raw;
  const interval: number | null = intOf(obj, 'recurrence_interval');
  let text: string = interval !== null && interval > 1 ? `每 ${interval} ${unit}` : `每${unit}`;
  const end: string | null = timeText(obj, 'recurrence_end_at');
  if (end !== null) text += `，至 ${end}`;
  return text;
};

const priorityText = (obj: JsonObject): string | null => {
  const p: number | null = intOf(obj, 'priority');
  if (p === null) return scalarText(obj, 'priority');
  if (p <= 0) return '无';
  if (p <= 4) return `高（${p}）`;
  if (p === 5) return `中（${p}）`;
  return `低（${p}）`;
};

class LineCollector {
  readonly lines: ToolApprovalLine[] = [];

  add(label: string, value: string | null): void {
    if (value === null) return;
    const v: string = value.trim();
    if (v.length === 0) return;
    this.lines.push({ label, value: v });
  }
}

const WM_ACT_KNOWN_ACTIONS: string[] = ['find', 'wait', 'click', 'tap', 'type', 'keys', 'scroll', 'select'];
const WM_ACT_INPUT_ACTIONS: string[] = ['click', 'tap', 'type', 'keys', 'select'];

/** wm_act 步骤动作序列:「find → click → type（共 3 步）」;只读 action,绝不读 text */
export const wmActStepsText = (input: JsonObject): string | null => {
  const raw: JsonValue | undefined = input['steps'];
  if (!Array.isArray(raw) || raw.length === 0) return null;
  const actions: string[] = [];
  for (const step of raw) {
    let action: string = '?';
    if (step !== null && typeof step === 'object' && !Array.isArray(step)) {
      const a: JsonValue | undefined = (step as JsonObject)['action'];
      if (typeof a === 'string') {
        const key: string = a.trim().toLowerCase();
        action = WM_ACT_KNOWN_ACTIONS.indexOf(key) >= 0 ? key : '?';
      }
    }
    actions.push(action);
  }
  const shown: string[] = actions.slice(0, 12);
  const tail: string = actions.length > shown.length ? ' → …' : '';
  return `${shown.join(' → ')}${tail}（共 ${actions.length} 步）`;
};

const wmActHasInput = (input: JsonObject): boolean => {
  const raw: JsonValue | undefined = input['steps'];
  if (!Array.isArray(raw)) return false;
  for (const step of raw) {
    if (step !== null && typeof step === 'object' && !Array.isArray(step)) {
      const a: JsonValue | undefined = (step as JsonObject)['action'];
      if (typeof a === 'string' && WM_ACT_INPUT_ACTIONS.indexOf(a.trim().toLowerCase()) >= 0) return true;
    }
  }
  return false;
};

const GENERIC_SKIP_TOOLS: string[] = ['ask_user', 'novel_workspace_write'];
const GENERIC_SKIP_KEYS: string[] = ['display_title'];

const genericLines = (toolName: string, input: JsonObject, out: LineCollector): void => {
  if (toolName.startsWith('provider_')) return;
  const masked: JsonObject = maskSensitiveJsonObject(input);
  for (const key of Object.keys(masked)) {
    if (out.lines.length >= APPROVAL_GENERIC_MAX_LINES) break;
    if (GENERIC_SKIP_KEYS.indexOf(key) >= 0) continue;
    out.add(key, scalarText(masked, key));
  }
};

const summaryOf = (
  title: string,
  toolName: string,
  out: LineCollector,
  warning: string = '',
  warningLevel: ToolApprovalWarningLevel = 'none',
): ToolApprovalSummary => ({
  title,
  lines: out.lines,
  note: out.lines.length === 0 ? '无需额外参数' : '',
  destructive: isDestructiveTool(toolName),
  warning,
  warningLevel: warning.length > 0 ? warningLevel : 'none',
});

/**
 * 待授权摘要。已知工具 → 逐工具标签行;未知工具 → 顶层标量参数兜底(最多 4 行,先脱敏)。
 * 不适用(ask_user 等已有专用卡片 / 兜底无内容)→ null。
 * recipe/plugin/SSH 由宿主专用摘要处理,调用方应先判定。
 */
export const buildToolApprovalSummary = (
  toolName: string,
  input: JsonObject,
): ToolApprovalSummary | null => {
  const out: LineCollector = new LineCollector();
  const staticLabel: string | null = toolStaticLabel(toolName, input);
  const title: string = staticLabel ?? toolName;

  if (toolName === 'calendar_events_list') {
    out.add('开始', timeText(input, 'start_at'));
    out.add('结束', timeText(input, 'end_at'));
    const s: ToolApprovalSummary = summaryOf(title, toolName, out);
    if (s.lines.length === 0) s.note = '默认读取从现在起 7 天内的事件';
    return s;
  }
  if (toolName === 'calendar_event_create' || toolName === 'calendar_event_update'
    || toolName === 'calendar_event_delete') {
    out.add('标题', scalarText(input, 'title'));
    out.add('开始', timeText(input, 'start_at'));
    out.add('结束', timeText(input, 'end_at'));
    out.add('地点', scalarText(input, 'location'));
    out.add('重复', recurrenceText(input));
    out.add('事件 ID', scalarText(input, 'event_id'));
    if (toolName === 'calendar_event_delete') {
      return summaryOf(title, toolName, out, '批准后将从系统日历删除这个事件，无法撤销。', 'caution');
    }
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'reminders_list') {
    const inc: JsonValue | undefined = input['include_completed'];
    out.add('包含已完成', inc === true || inc === 'true' ? '是' : null);
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'reminder_create' || toolName === 'reminder_update' || toolName === 'reminder_delete'
    || toolName === 'reminder_complete') {
    out.add('标题', scalarText(input, 'title'));
    const removeDue: JsonValue | undefined = input['remove_due_date'];
    out.add('到期', removeDue === true || removeDue === 'true' ? '清除到期时间' : timeText(input, 'due_at'));
    out.add('优先级', priorityText(input));
    out.add('提醒 ID', scalarText(input, 'reminder_id'));
    if (toolName === 'reminder_delete') {
      return summaryOf(title, toolName, out, '批准后将删除这条提醒事项，无法撤销。', 'caution');
    }
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'notification_schedule') {
    out.add('标题', scalarText(input, 'title'));
    out.add('正文', scalarText(input, 'body'));
    out.add('时间', timeText(input, 'fire_at'));
    return summaryOf(title, toolName, out, '同一时间只保留一条 Amber 通知，新安排会替换之前的。', 'caution');
  }
  if (toolName === 'notification_cancel') {
    return summaryOf(title, toolName, out, '批准后将取消 Amber 已安排的本地通知。', 'caution');
  }
  if (toolName === 'alarm_schedule') {
    out.add('标题', scalarText(input, 'title'));
    const kindRaw: string | null = scalarText(input, 'kind');
    const kind: string = kindRaw !== null ? kindRaw.toLowerCase() : '';
    const kindLabel: string | undefined = ALARM_KIND_LABELS[kind];
    out.add('类型', kindLabel !== undefined ? kindLabel : kindRaw);
    const fireAt: string | null = timeText(input, 'fire_at');
    const weekdays: string | null = weekdaysText(input);
    const hour: number | null = intOf(input, 'hour');
    const minute: number | null = intOf(input, 'minute');
    const duration: number | null = numberOf(input, 'duration_seconds');
    if (fireAt !== null) out.add('时间', fireAt);
    if (weekdays !== null || hour !== null) {
      const clock: string = hour !== null ? `${pad2(hour)}:${pad2(minute ?? 0)}` : '';
      out.add('时间', [weekdays ?? '', clock].filter((p: string): boolean => p.length > 0).join(' '));
    }
    if (duration !== null) out.add('时长', formatDurationSeconds(duration));
    return summaryOf(title, toolName, out, '这是会响铃的系统闹钟，仅在你批准后创建。', 'caution');
  }
  if (toolName === 'alarms_list') {
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'alarm_cancel') {
    out.add('闹钟 ID', scalarText(input, 'alarm_id'));
    return summaryOf(title, toolName, out, '批准后会立即取消这个 Amber 闹钟。', 'caution');
  }
  if (toolName === 'contacts_pick' || toolName === 'photos_pick') {
    out.add('最多选择', scalarText(input, 'max_count'));
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'weather_read') {
    out.add('地点', scalarText(input, 'location'));
    const useCurrent: JsonValue | undefined = input['use_current_location'];
    out.add('位置', useCurrent === true || useCurrent === 'true' ? '使用当前大致位置' : null);
    const lat: number | null = numberOf(input, 'latitude');
    const lng: number | null = numberOf(input, 'longitude');
    out.add('坐标', lat !== null && lng !== null ? `${lat}, ${lng}` : null);
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'settings_set_model_slot') {
    const slot: string | null = scalarText(input, 'slot');
    out.add('槽位', modelSlotLabel(slot) ?? slot);
    const ref: string | null = scalarText(input, 'model_ref');
    const id: string | null = scalarText(input, 'model_id');
    out.add('模型', ref ?? (id !== null ? `ID ${id}` : null));
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'mcp_describe_tool') {
    out.add('服务器', scalarText(input, 'server') ?? scalarText(input, 'server_id') ?? scalarText(input, 'name'));
    out.add('工具', scalarText(input, 'tool') ?? scalarText(input, 'tool_name'));
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'wm_get') {
    out.add('目标', scalarText(input, 'target') ?? scalarText(input, 'selector'));
    const kind: string | null = scalarText(input, 'kind');
    const attr: string | null = scalarText(input, 'attr_name');
    out.add('读取', kind === 'attr' && attr !== null ? `属性 ${attr}` : kind);
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'wm_visual_snapshot') {
    return summaryOf(title, toolName, out);
  }
  if (toolName === 'wm_visual_read') {
    out.add('问题', scalarText(input, 'question') ?? '（默认）核验页面当前可见内容');
    return summaryOf(title, toolName, out, '当前页面截图将发送给视觉模型服务商。', 'caution');
  }
  if (toolName === 'wm_clear_session') {
    out.add('站点', scalarText(input, 'site_id'));
    const scopeRaw: string | null = scalarText(input, 'scope');
    const all: boolean = scopeRaw !== null && scopeRaw.toLowerCase() === 'all';
    out.add('范围', all ? '所有站点' : '仅此站点');
    if (all) {
      return summaryOf(title, toolName, out, '将清除所有站点 Cookie 与网站数据，所有网站都需要重新登录。', 'danger');
    }
    return summaryOf(title, toolName, out, '将清除该站点的 Cookie 与网站数据，需要重新登录。', 'caution');
  }
  if (toolName === 'wm_act') {
    out.add('步骤', wmActStepsText(input));
    if (wmActHasInput(input)) {
      return summaryOf(title, toolName, out, '将在网页中执行点击或输入操作。', 'caution');
    }
    return summaryOf(title, toolName, out);
  }

  // 未知工具:顶层标量参数兜底
  if (GENERIC_SKIP_TOOLS.indexOf(toolName) >= 0) return null;
  genericLines(toolName, input, out);
  if (out.lines.length === 0) return null;
  return summaryOf(title, toolName, out);
};
