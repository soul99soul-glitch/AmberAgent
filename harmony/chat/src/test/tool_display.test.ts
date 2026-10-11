// tool_display.test.ts — 工具显示标题 / 待授权摘要 / ok:false 状态判定

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildToolApprovalSummary,
  formatApprovalTime,
  formatDurationSeconds,
  isDestructiveTool,
  modelSlotLabel,
  toolInputDisplayTitle,
  toolStaticLabel,
  wmActStepsText,
} from '../main/ets/chat/tool_display.ts';
import type { ToolApprovalSummary } from '../main/ets/chat/tool_display.ts';
import {
  indicatesFailure,
  toolActivityStatus,
  toolDisplayTitle,
  toolOutputJson,
  toolSandboxInputPreview,
  toolStaticDisplayTitle,
} from '../main/ets/chat/tool_activity.ts';
import type { UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';

const textPart = (text: string): UIMessagePart => ({ type: 'text', text, metadata: null });

const tool = (
  toolName: string,
  input: object = {},
  output: UIMessagePart[] = [],
  approvalState: UIMessagePartTool['approvalState'] = { type: 'auto' },
): UIMessagePartTool => ({
  type: 'tool', toolCallId: `c-${toolName}`, toolName, input: JSON.stringify(input), output, approvalState, metadata: null,
});

const statusOf = (part: UIMessagePartTool): string => toolActivityStatus(part, false, toolOutputJson(part));

const lineMap = (s: ToolApprovalSummary | null): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const l of s?.lines ?? []) out[l.label] = l.value;
  return out;
};

// ===== Finding 1:ok:false =====

test('indicatesFailure:严格 ok === false 视为失败;字符串 false / ok:true 不算', () => {
  assert.ok(indicatesFailure({ ok: false, tool: 'calendar_event_create', reason: 'x', error_code: 'not_found' }));
  assert.ok(!indicatesFailure({ ok: 'false' }));
  assert.ok(!indicatesFailure({ ok: true, tool: 'weather_read' }));
  assert.ok(!indicatesFailure({}));
});

test('toolActivityStatus:新工具 ok:false(reason 键)→ failed;ok:true → succeeded', () => {
  const failed = tool('calendar_event_create', { title: 't' },
    [textPart(JSON.stringify({ ok: false, tool: 'calendar_event_create', reason: '找不到', error_code: 'not_found' }))]);
  assert.equal(statusOf(failed), 'failed');
  const weatherFailed = tool('weather_read', {},
    [textPart(JSON.stringify({ ok: false, tool: 'weather_read', status: 'network_error', reason: 'offline' }))]);
  assert.equal(statusOf(weatherFailed), 'failed');
  const ok = tool('weather_read', {}, [textPart(JSON.stringify({ ok: true, tool: 'weather_read', location: 'x' }))]);
  assert.equal(statusOf(ok), 'succeeded');
});

test('toolActivityStatus:ok:false + cancelled/empty_selection(status/reason/error_code)→ cancelled', () => {
  const byStatus = tool('photos_pick', {},
    [textPart(JSON.stringify({ ok: false, tool: 'photos_pick', status: 'cancelled', reason: 'closed' }))]);
  assert.equal(statusOf(byStatus), 'cancelled');
  const empty = tool('contacts_pick', {},
    [textPart(JSON.stringify({ ok: false, tool: 'contacts_pick', status: 'empty_selection', reason: 'none' }))]);
  assert.equal(statusOf(empty), 'cancelled');
  const byCode = tool('wm_act', {},
    [textPart(JSON.stringify({ ok: false, tool: 'wm_act', error_code: 'cancelled' }))]);
  assert.equal(statusOf(byCode), 'cancelled');
  const byReason = tool('alarm_schedule', {},
    [textPart(JSON.stringify({ ok: false, tool: 'alarm_schedule', reason: 'cancelled' }))]);
  assert.equal(statusOf(byReason), 'cancelled');
  // 非 ok:false 的 cancelled 字段不改变原判定(notification_cancel 成功结果)
  const cancelOk = tool('notification_cancel', {},
    [textPart(JSON.stringify({ ok: true, tool: 'notification_cancel', cancelled: true, had_pending: true }))]);
  assert.equal(statusOf(cancelOk), 'succeeded');
});

test('toolActivityStatus:超长输出(toolOutputJson={})嗅探开头 {"ok":false', () => {
  const big = 'x'.repeat(90000);
  const failed = tool('wm_get', {},
    [textPart(`{"ok":false,"tool":"wm_get","error_code":"x","value":"${big}"}`)]);
  assert.deepEqual(toolOutputJson(failed), {});
  assert.equal(statusOf(failed), 'failed');
  const cancelled = tool('wm_act', {},
    [textPart(`{"ok": false, "tool":"wm_act","error_code":"cancelled","log":"${big}"}`)]);
  assert.equal(statusOf(cancelled), 'cancelled');
  const okBig = tool('wm_get', {}, [textPart(`{"ok":true,"value":"${big}"}`)]);
  assert.equal(statusOf(okBig), 'succeeded');
  const plain = tool('wm_get', {}, [textPart(`plain ${big}`)]);
  assert.equal(statusOf(plain), 'succeeded');
});

test('既有 recipe/python 专项判定不回归', () => {
  const recipe = tool('recipe__x', {}, [textPart(JSON.stringify({ status: 'cancelled' }))]);
  assert.equal(statusOf(recipe), 'cancelled');
  const py = tool('python_execute', {}, [textPart(JSON.stringify({ status: 'interrupted', exit_code: null }))]);
  assert.equal(statusOf(py), 'cancelled');
  const pending = tool('alarm_schedule', {}, [], { type: 'pending' });
  assert.equal(statusOf(pending), 'waiting_for_permission');
});

// ===== Finding 3:标题 =====

test('toolDisplayTitle:display_title 优先(trim/折叠空白/截断),否则静态标签', () => {
  assert.equal(toolDisplayTitle(tool('calendar_event_create', { display_title: '  新建\n周会  ' })), '新建 周会');
  const long = toolDisplayTitle(tool('wm_act', { display_title: '一'.repeat(40) }));
  assert.equal(Array.from(long).length, 24);
  assert.ok(long.endsWith('…'));
  assert.equal(toolDisplayTitle(tool('calendar_event_create', { display_title: '   ' })), '新建日历事件');
  assert.equal(toolDisplayTitle(tool('calendar_event_create', { display_title: 42 })), '新建日历事件');
  assert.equal(toolInputDisplayTitle({}), null);
});

test('静态标签:新工具全覆盖 + 模型槽位', () => {
  const expected: Record<string, string> = {
    calendar_events_list: '查看日历事件', calendar_event_create: '新建日历事件',
    calendar_event_update: '更新日历事件', calendar_event_delete: '删除日历事件',
    reminders_list: '查看提醒事项', reminder_create: '新建提醒事项', reminder_update: '更新提醒事项',
    reminder_delete: '删除提醒事项', reminder_complete: '完成提醒事项',
    notification_schedule: '安排本地通知', notification_cancel: '取消本地通知',
    alarm_schedule: '安排系统闹钟', alarms_list: '查看 Amber 闹钟', alarm_cancel: '取消系统闹钟',
    contacts_pick: '选择联系人', photos_pick: '选择照片', weather_read: '读取天气',
    mcp_describe_tool: '查看 MCP 工具说明', wm_get: '读取网页节点', wm_visual_snapshot: '读取视觉快照',
    wm_visual_read: '视觉核验网页', wm_clear_session: '清除站点登录状态', wm_act: '执行网页动作批',
  };
  for (const name of Object.keys(expected)) {
    assert.equal(toolStaticLabel(name), expected[name], name);
    assert.equal(toolDisplayTitle(tool(name)), expected[name], name);
  }
  assert.equal(toolDisplayTitle(tool('settings_set_model_slot', { slot: 'chat' })), '设置对话模型');
  assert.equal(toolDisplayTitle(tool('settings_set_model_slot', { slot: 'weird' })), '设置默认模型');
  assert.equal(modelSlotLabel('OCR'), '视觉识别模型');
  // 沙盒 inputPreview 仍走同一槽位文案
  assert.equal(toolSandboxInputPreview(tool('settings_set_model_slot', { slot: 'title' })), '设置标题模型');
});

test('既有工具标题不回归:终端/文件/MCP/recipe/plugin/未知', () => {
  assert.equal(toolDisplayTitle(tool('terminal_execute', { command: 'ls' })), '执行 SSH 命令');
  assert.equal(toolDisplayTitle(tool('file_read', { path: 'a.txt' })), '读取文件 a.txt');
  assert.equal(toolDisplayTitle(tool('mcp__srv__do')), '调用 MCP srv__do');
  assert.equal(toolDisplayTitle(tool('recipe__daily')), '运行 Recipe daily');
  assert.equal(toolDisplayTitle(tool('plugin__acme__x')), '运行插件 acme__x');
  assert.equal(toolDisplayTitle(tool('recipe_import')), '安装 Recipe');
  assert.equal(toolDisplayTitle(tool('some_tool')), 'some_tool');
  // 授权标题不取模型 display_title
  assert.equal(toolStaticDisplayTitle(tool('calendar_event_delete', { display_title: '查看日历' })), '删除日历事件');
});

// ===== Finding 2:待授权摘要 =====

test('日历摘要:标题/开始/结束/地点/重复/事件 ID;删除为破坏性', () => {
  const s = buildToolApprovalSummary('calendar_event_create', {
    title: '周会', start_at: '2026-10-09T09:00:00+08:00', end_at: '2026-10-09T10:00:00+08:00',
    location: '会议室 A', recurrence: 'weekly', recurrence_interval: 2, notes: 'secret notes',
  });
  assert.deepEqual(lineMap(s), {
    '标题': '周会', '开始': '2026-10-09 09:00 +08:00', '结束': '2026-10-09 10:00 +08:00',
    '地点': '会议室 A', '重复': '每 2 周',
  });
  assert.equal(s?.destructive, false);
  const del = buildToolApprovalSummary('calendar_event_delete', { event_id: 'e1' });
  assert.equal(lineMap(del)['事件 ID'], 'e1');
  assert.equal(del?.destructive, true);
  assert.ok((del?.warning ?? '').length > 0);
});

test('提醒/通知摘要', () => {
  const r = buildToolApprovalSummary('reminder_update', { reminder_id: 'r1', priority: 1, due_at: '2026-10-10T08:30:00Z' });
  assert.deepEqual(lineMap(r), { '到期': '2026-10-10 08:30 UTC', '优先级': '高（1）', '提醒 ID': 'r1' });
  const n = buildToolApprovalSummary('notification_schedule', { title: '喝水', body: '起来走走', fire_at: '2026-10-10T08:30:00' });
  assert.deepEqual(lineMap(n), { '标题': '喝水', '正文': '起来走走', '时间': '2026-10-10 08:30' });
  const nc = buildToolApprovalSummary('notification_cancel', {});
  assert.equal(nc?.destructive, true);
  assert.equal(nc?.note, '无需额外参数');
  assert.ok(isDestructiveTool('reminder_delete'));
  assert.ok(!isDestructiveTool('reminder_complete'));
});

test('闹钟摘要:一次性/每周/倒计时 + 响铃提示;取消为破坏性', () => {
  const once = buildToolApprovalSummary('alarm_schedule', { title: '起床', kind: 'one_time', fire_at: '2026-10-10T07:00:00+08:00' });
  assert.deepEqual(lineMap(once), { '标题': '起床', '类型': '一次性闹钟', '时间': '2026-10-10 07:00 +08:00' });
  assert.ok((once?.warning ?? '').includes('响铃'));
  const weekly = buildToolApprovalSummary('alarm_schedule', { title: 'w', kind: 'weekly', weekdays: ['monday', 'friday'], hour: 7, minute: 5 });
  assert.equal(lineMap(weekly)['时间'], '周一、周五 07:05');
  const timer = buildToolApprovalSummary('alarm_schedule', { title: 't', kind: 'timer', duration_seconds: 5400 });
  assert.equal(lineMap(timer)['时长'], '1 小时 30 分');
  assert.equal(formatDurationSeconds(300), '5 分钟');
  assert.equal(formatDurationSeconds(45), '45 秒');
  const cancel = buildToolApprovalSummary('alarm_cancel', { alarm_id: '12' });
  assert.equal(cancel?.destructive, true);
  assert.equal(lineMap(cancel)['闹钟 ID'], '12');
});

test('模型槽位 / 视觉核验 / 清除站点 / wm_act 摘要', () => {
  const slot = buildToolApprovalSummary('settings_set_model_slot', { slot: 'chat', model_ref: 'gpt-5' });
  assert.equal(slot?.title, '设置对话模型');
  assert.deepEqual(lineMap(slot), { '槽位': '对话模型', '模型': 'gpt-5' });

  const vr = buildToolApprovalSummary('wm_visual_read', { question: '登录框可见吗' });
  assert.equal(lineMap(vr)['问题'], '登录框可见吗');
  assert.ok((vr?.warning ?? '').includes('视觉模型服务商'));

  const site = buildToolApprovalSummary('wm_clear_session', { site_id: 'zhihu' });
  assert.equal(site?.destructive, true);
  assert.equal(site?.warningLevel, 'caution');
  assert.equal(lineMap(site)['范围'], '仅此站点');
  const all = buildToolApprovalSummary('wm_clear_session', { site_id: 'zhihu', scope: 'all' });
  assert.equal(all?.warningLevel, 'danger');
  assert.ok((all?.warning ?? '').includes('将清除所有站点 Cookie 与网站数据'));

  const act = buildToolApprovalSummary('wm_act', { steps: [
    { action: 'find', text: '登录' }, { action: 'click' }, { action: 'type', text: 'hunter2-password' },
  ] });
  assert.equal(lineMap(act)['步骤'], 'find → click → type（共 3 步）');
  assert.ok(!JSON.stringify(act).includes('hunter2'));
  assert.equal(wmActStepsText({ steps: 'nope' }), null);
});

test('未知工具兜底:顶层标量 + 脱敏 + 最多 4 行;provider_/ask_user 不展示', () => {
  const g = buildToolApprovalSummary('mcp__srv__x', { query: 'abc', api_key: 'sk-123', nested: { a: 1 }, display_title: 'x' });
  assert.deepEqual(lineMap(g), { query: 'abc', api_key: '***' });
  const many = buildToolApprovalSummary('foo', { a: 1, b: 2, c: 3, d: 4, e: 5 });
  assert.equal(many?.lines.length, 4);
  assert.equal(buildToolApprovalSummary('provider_configure', { base_url: 'https://u:p@x' }), null);
  assert.equal(buildToolApprovalSummary('ask_user', { question: 'q' }), null);
  assert.equal(buildToolApprovalSummary('foo', {}), null);
  const longVal = buildToolApprovalSummary('foo', { code: 'x'.repeat(500) });
  assert.equal(Array.from(lineMap(longVal)['code']).length, 160);
});

test('formatApprovalTime:非 ISO 原样', () => {
  assert.equal(formatApprovalTime('明天上午'), '明天上午');
  assert.equal(formatApprovalTime('2026-10-09T09:00'), '2026-10-09 09:00');
});
