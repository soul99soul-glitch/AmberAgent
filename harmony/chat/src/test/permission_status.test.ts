// permission_status.test.ts — D-129 permissions_status 工具 + Broker/Registry
// Android 基准: AgentPermissionBroker.kt(全文)+ PermissionsStatusTool.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type {
  AgentPermissionPlatformPort,
  AgentPermissionCapability,
  AgentSpecialAccess,
} from '../main/ets/chat/permission_status.ts';
import {
  AgentPermissionBroker,
  AGENT_PERMISSION_CAPABILITIES,
  makeRuntimePermissionSpec,
  makeAgentPermissionCapability,
  capabilityCurrentRuntimePermissions,
  createPermissionsStatusTool,
} from '../main/ets/chat/permission_status.ts';

// ===== 假端口 =====

interface FakePortOpts {
  isDebugBuild?: boolean;
  apiLevel?: number;
  granted?: string[];                       // checkSelfPermission → true 的权限名
  special?: Map<string, boolean | null>;    // access → granted/null(缺省 null)
  canOpen?: string[];                       // canOpenSpecialAccessSettings → true
}

const makeFakePort = (opts: FakePortOpts = {}): AgentPermissionPlatformPort => ({
  isDebugBuild: (): boolean => opts.isDebugBuild ?? true,
  currentApiLevel: (): number => opts.apiLevel ?? 20,
  checkSelfPermission: (permission: string): boolean =>
    (opts.granted ?? []).indexOf(permission) >= 0,
  specialAccessGranted: (access: AgentSpecialAccess): boolean | null =>
    opts.special !== undefined && opts.special.has(access)
      ? (opts.special.get(access) as boolean | null)
      : null,
  canOpenSpecialAccessSettings: (access: AgentSpecialAccess): boolean =>
    (opts.canOpen ?? []).indexOf(access) >= 0,
});

const makeBroker = (
  opts: FakePortOpts = {}, auditSink: string[] = [],
): AgentPermissionBroker =>
  new AgentPermissionBroker(makeFakePort(opts), (message: string): void => { auditSink.push(message); });

const textOf = (parts: UIMessagePart[]): JsonObject =>
  JSON.parse((parts[0] as { text: string }).text) as JsonObject;

const capabilityById = (id: string): AgentPermissionCapability => {
  const found: AgentPermissionCapability | undefined =
    AGENT_PERMISSION_CAPABILITIES.find((c: AgentPermissionCapability): boolean => c.id === id);
  assert.ok(found !== undefined);
  return found;
};

test('capabilityCurrentRuntimePermissions: sdk 过滤 + distinct(:67-68)', () => {
  const cap: AgentPermissionCapability = makeAgentPermissionCapability({
    id: 'x', title: 'x', description: 'x',
    runtimePermissions: [
      makeRuntimePermissionSpec('p.old', 1, 10),
      makeRuntimePermissionSpec('p.new', 11),
      makeRuntimePermissionSpec('p.new'),
      makeRuntimePermissionSpec('p.always'),
    ],
  });
  // sdk5:p.new 经默认全范围第三条适用(去重后单次);sdk20:p.old 过期
  assert.deepEqual(capabilityCurrentRuntimePermissions(cap, 5), ['p.old', 'p.new', 'p.always']);
  assert.deepEqual(capabilityCurrentRuntimePermissions(cap, 20), ['p.new', 'p.always']);
});

// ===== Broker.getStatus(:83-114) =====

test('getStatus: debugOnly + release 构建 → Unsupported', () => {
  const broker: AgentPermissionBroker = makeBroker({ isDebugBuild: false });
  assert.equal(broker.getStatus(capabilityById('installed_apps_full_access')), 'Unsupported');
  const debugBroker: AgentPermissionBroker = makeBroker({ isDebugBuild: true });
  // debug 构建下继续走 runtime 分支:无权限声明 → Granted
  assert.equal(debugBroker.getStatus(capabilityById('installed_apps_full_access')), 'Granted');
});

test('getStatus: minSdk 门 → Unsupported', () => {
  const broker: AgentPermissionBroker = makeBroker({ apiLevel: 29 });
  assert.equal(broker.getStatus(capabilityById('manage_all_files')), 'Unsupported');
  const broker30: AgentPermissionBroker = makeBroker({
    apiLevel: 30, special: new Map<string, boolean | null>([['ManageAllFiles', true]]),
  });
  assert.equal(broker30.getStatus(capabilityById('manage_all_files')), 'Granted');
});

test('getStatus: special access — null → Unsupported(平台无概念)', () => {
  const broker: AgentPermissionBroker = makeBroker();   // special 缺省 null
  assert.equal(broker.getStatus(capabilityById('notification_access')), 'Unsupported');
  assert.equal(broker.getStatus(capabilityById('usage_access')), 'Unsupported');
  assert.equal(broker.getStatus(capabilityById('overlay')), 'Unsupported');
  assert.equal(broker.getStatus(capabilityById('battery_optimization')), 'Unsupported');
  assert.equal(broker.getStatus(capabilityById('exact_alarm')), 'Unsupported');
});

test('getStatus: special access — granted/SpecialNeeded', () => {
  const special: Map<string, boolean | null> = new Map<string, boolean | null>();
  special.set('NotificationListener', true);
  special.set('UsageStats', false);
  const broker: AgentPermissionBroker = makeBroker({ special });
  assert.equal(broker.getStatus(capabilityById('notification_access')), 'Granted');
  assert.equal(broker.getStatus(capabilityById('usage_access')), 'SpecialNeeded');
});

test('getStatus: All 模式 — 全授才 Granted', () => {
  const none: AgentPermissionBroker = makeBroker();
  assert.equal(none.getStatus(capabilityById('calendar_write')), 'Denied');
  const partial: AgentPermissionBroker = makeBroker({ granted: ['ohos.permission.READ_CALENDAR'] });
  assert.equal(partial.getStatus(capabilityById('calendar_write')), 'Denied');
  const all: AgentPermissionBroker = makeBroker({
    granted: ['ohos.permission.READ_CALENDAR', 'ohos.permission.WRITE_CALENDAR'],
  });
  assert.equal(all.getStatus(capabilityById('calendar_write')), 'Granted');
});

test('getStatus: Any 模式 — 任一授权即 Granted', () => {
  const none: AgentPermissionBroker = makeBroker();
  assert.equal(none.getStatus(capabilityById('location_current')), 'Denied');
  const coarse: AgentPermissionBroker = makeBroker({ granted: ['ohos.permission.APPROXIMATELY_LOCATION'] });
  assert.equal(coarse.getStatus(capabilityById('location_current')), 'Granted');
});

// ===== ensureGranted/runtimePermissionsForCoreBatch(:116-138) =====

test('ensureGranted: 已授权 → audit 日志;未授权 → 错误文案逐字', () => {
  const audit: string[] = [];
  const broker: AgentPermissionBroker = makeBroker({}, audit);
  // apps 无权限声明 → Granted → 通过 + audit
  broker.ensureGranted('apps', 'apps_list', 'list apps');
  assert.deepEqual(audit, ['tool=apps_list capability=apps reason=list apps']);

  assert.throws(
    () => broker.ensureGranted('contacts_read', 'contacts_search', 'find'),
    /System permission required: 通讯录读取\. Status: Denied\. Open Settings > Agent Runtime > System Access to grant it\./,
  );
  assert.equal(audit.length, 1);   // 未授权不 audit
});

test('runtimePermissionsForCoreBatch: 排除 debugOnly/special/High,distinct', () => {
  const broker: AgentPermissionBroker = makeBroker();
  const batch: string[] = broker.runtimePermissionsForCoreBatch();
  // 含: contacts_read(S)/phone_state(S)/calendar_read(S)/media_*(S)/
  //   location(S)/nearby(S)/activity(S)
  // 排除: contacts_write/sms_*/call_*/calendar_write/audio_record(High),
  //   六 special(manage_all_files 也 special), installed_apps_full_access(debugOnly)
  assert.deepEqual(batch, [
    'ohos.permission.READ_CONTACTS',
    'ohos.permission.GET_TELEPHONY_STATE',
    'ohos.permission.READ_CALENDAR',
    'ohos.permission.READ_IMAGEVIDEO',
    'ohos.permission.READ_AUDIO',
    'ohos.permission.APPROXIMATELY_LOCATION',
    'ohos.permission.LOCATION',
    'ohos.permission.ACCESS_BLUETOOTH',
    'ohos.permission.ACTIVITY_MOTION',
  ]);
});

test('tool: capability_id 过滤', async () => {
  const broker: AgentPermissionBroker = makeBroker();
  const tool = createPermissionsStatusTool(broker);
  const payload: JsonObject = textOf(await tool.execute({ capability_id: 'calendar_read' }));
  const caps: JsonValue[] = payload['capabilities'] as JsonValue[];
  assert.equal(caps.length, 1);
  assert.equal((caps[0] as JsonObject)['capability_id'], 'calendar_read');
  // 未命中任何项 → 空数组
  const empty: JsonObject = textOf(await tool.execute({ capability_id: 'nope' }));
  assert.deepEqual(empty['capabilities'], []);
});

test('tool: include_how_to_grant=false → 键省略;严格布尔解析', async () => {
  const broker: AgentPermissionBroker = makeBroker();
  const tool = createPermissionsStatusTool(broker);
  const off: JsonObject = textOf(await tool.execute({ include_how_to_grant: false }));
  const firstOff: JsonObject = (off['capabilities'] as JsonValue[])[0] as JsonObject;
  assert.ok(!('how_to_grant' in firstOff));

  // 字符串 'false'(contentOrNull → toBooleanStrictOrNull)
  const strOff: JsonObject = textOf(await tool.execute({ include_how_to_grant: 'false' }));
  const firstStrOff: JsonObject = (strOff['capabilities'] as JsonValue[])[0] as JsonObject;
  assert.ok(!('how_to_grant' in firstStrOff));

  // 非法字符串 → toBooleanStrictOrNull null → 默认 true
  const bogus: JsonObject = textOf(await tool.execute({ include_how_to_grant: 'yes' }));
  const firstBogus: JsonObject = (bogus['capabilities'] as JsonValue[])[0] as JsonObject;
  assert.ok('how_to_grant' in firstBogus);
});

test('tool: all_files 三键 — Unsupported(HarmonyOS 默认)→ false/false/false', async () => {
  const broker: AgentPermissionBroker = makeBroker();   // special 缺省 null → Unsupported
  const tool = createPermissionsStatusTool(broker);
  const payload: JsonObject = textOf(await tool.execute({}));
  assert.equal(payload['all_files_access_declared'], false);
  assert.equal(payload['all_files_access_granted'], false);
  assert.equal(payload['all_files_access_manage_intent_available'], false);
});
