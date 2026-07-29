// permission_status — permissions_status 工具 + AgentPermissionBroker/Registry(D-129)
//
// Android 基准:
//   feature/system/.../AgentPermissionBroker.kt(全文 459 行)— 枚举/Spec/
//     Capability/Broker/Registry 24 条
//   core/ai/tools/PermissionsStatusTool.kt(全文 79 行)— 工具工厂
//   LocalTools.kt:94(lazy 创建)+ :180(注册位 = agentCron 之前/无条件)
// 承载层适配(登记):
//   - Context/Build/Settings/AppOps → AgentPermissionPlatformPort 注入
//     (isDebugBuild/currentApiLevel/checkSelfPermission/specialAccessGranted/
//     canOpenSpecialAccessSettings);createSpecialAccessIntent(Intent?) →
//     canOpenSpecialAccessSettings(boolean) 等价
//   - 权限名字符串:android.permission.* → ohos.permission.* 映射;Android
//     minSdk/maxSdk 版本门槛在 HarmonyOS 无对应,同 capability 的多版本条目
//     折叠为单条(RuntimePermissionSpec 模型字段保留;映射表见 REGISTRY 注释)
//   - special access 六类(NotificationListener/UsageStats/Overlay/
//     IgnoreBatteryOptimizations/ExactAlarm/ManageAllFiles)HarmonyOS 无对应
//     概念 → entry port 返回 null → status=Unsupported(D-060 能力降级先例;
//     Android 同语义路径 = minSdk 门 Unsupported)
//   - checkAccessToken 对未声明/不存在的权限名抛 BusinessError → entry
//     adapter catch → Denied(与 Android 未声明 manifest 时
//     checkSelfPermission=PERMISSION_DENIED 同语义)
//   - how_to_grant 文案平台词替换:"Android system settings" →
//     "HarmonyOS system settings"(设置页 UI 待 P1,文案结构逐字)

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

// ===== 枚举(AgentPermissionBroker.kt:19-44,名逐字保留 PascalCase) =====

export type AgentPermissionRisk = 'Normal' | 'Sensitive' | 'High';
export type AgentPermissionStatus = 'Granted' | 'Denied' | 'SpecialNeeded' | 'Unsupported';
export type AgentSpecialAccess =
  'NotificationListener' | 'UsageStats' | 'Overlay' |
  'IgnoreBatteryOptimizations' | 'ExactAlarm' | 'ManageAllFiles';
export type RuntimeGrantMode = 'All' | 'Any';

// ===== RuntimePermissionSpec(:46-53) =====

export interface RuntimePermissionSpec {
  permission: string;
  minSdk: number;   // 默认 1
  maxSdk: number;   // 默认 Int.MAX_VALUE
}

export const makeRuntimePermissionSpec = (
  permission: string, minSdk: number = 1, maxSdk: number = Number.MAX_SAFE_INTEGER,
): RuntimePermissionSpec => ({ permission, minSdk, maxSdk });

// :51-52  Build.VERSION.SDK_INT in minSdk..maxSdk
export const runtimePermissionSpecApplies = (spec: RuntimePermissionSpec, sdkVersion: number): boolean =>
  sdkVersion >= spec.minSdk && sdkVersion <= spec.maxSdk;

// ===== AgentPermissionCapability(:55-69) =====

export interface AgentPermissionCapability {
  id: string;
  title: string;
  description: string;
  runtimePermissions: RuntimePermissionSpec[];
  runtimeGrantMode: RuntimeGrantMode;   // 默认 All
  specialAccess: AgentSpecialAccess | null;
  risk: AgentPermissionRisk;            // 默认 Normal
  toolNames: string[];
  minSdk: number;                       // 默认 1
  debugOnly: boolean;                   // 默认 false
}

export interface AgentPermissionCapabilityInit {
  id: string;
  title: string;
  description: string;
  runtimePermissions?: RuntimePermissionSpec[];
  runtimeGrantMode?: RuntimeGrantMode;
  specialAccess?: AgentSpecialAccess | null;
  risk?: AgentPermissionRisk;
  toolNames?: string[];
  minSdk?: number;
  debugOnly?: boolean;
}

export const makeAgentPermissionCapability = (init: AgentPermissionCapabilityInit): AgentPermissionCapability => ({
  id: init.id,
  title: init.title,
  description: init.description,
  runtimePermissions: init.runtimePermissions ?? [],
  runtimeGrantMode: init.runtimeGrantMode ?? 'All',
  specialAccess: init.specialAccess !== undefined ? init.specialAccess : null,
  risk: init.risk ?? 'Normal',
  toolNames: init.toolNames ?? [],
  minSdk: init.minSdk ?? 1,
  debugOnly: init.debugOnly ?? false,
});

// :67-68  runtimePermissions.filter{appliesToCurrentSdk()}.map{permission}.distinct()
export const capabilityCurrentRuntimePermissions = (
  capability: AgentPermissionCapability, sdkVersion: number,
): string[] => {
  const out: string[] = [];
  capability.runtimePermissions.forEach((spec: RuntimePermissionSpec) => {
    if (runtimePermissionSpecApplies(spec, sdkVersion) && out.indexOf(spec.permission) < 0) {
      out.push(spec.permission);
    }
  });
  return out;
};

// ===== AgentPermissionPlatformPort(承载层注入) =====

export interface AgentPermissionPlatformPort {
  isDebugBuild(): boolean;
  // Android: Build.VERSION.SDK_INT;HarmonyOS: deviceInfo.sdkApiVersion
  currentApiLevel(): number;
  // Android: ContextCompat.checkSelfPermission == PERMISSION_GRANTED;
  //   HarmonyOS: atManager.checkAccessToken(无效权限名抛错 → false)
  checkSelfPermission(permission: string): boolean;
  // null = 平台无此 special access 概念 → getStatus 返回 Unsupported
  specialAccessGranted(access: AgentSpecialAccess): boolean | null;
  // Android createSpecialAccessIntent(...) != null 等价
  canOpenSpecialAccessSettings(access: AgentSpecialAccess): boolean;
}

// ===== AgentPermissionBroker(:71-234) =====

export class AgentPermissionBroker {
  readonly capabilities: AgentPermissionCapability[] = AGENT_PERMISSION_CAPABILITIES;

  constructor(
    private readonly port: AgentPermissionPlatformPort,
    private readonly auditLog: (message: string) => void,
  ) {}

  // :77-78
  getCapability(id: string): AgentPermissionCapability {
    const found: AgentPermissionCapability | undefined =
      this.capabilities.find((c: AgentPermissionCapability): boolean => c.id === id);
    if (found === undefined) throw new Error(`Unknown permission capability: ${id}`);
    return found;
  }

  getStatusById(capabilityId: string): AgentPermissionStatus {
    return this.getStatus(this.getCapability(capabilityId));
  }

  // :83-114
  getStatus(capability: AgentPermissionCapability): AgentPermissionStatus {
    if (capability.debugOnly && !this.port.isDebugBuild()) return 'Unsupported';
    if (this.port.currentApiLevel() < capability.minSdk) return 'Unsupported';

    if (capability.specialAccess !== null) {
      const granted: boolean | null = this.port.specialAccessGranted(capability.specialAccess);
      if (granted === null) return 'Unsupported';   // 平台无此概念(登记)
      return granted ? 'Granted' : 'SpecialNeeded';
    }

    const permissions: string[] =
      capabilityCurrentRuntimePermissions(capability, this.port.currentApiLevel());
    if (permissions.length === 0) return 'Granted';

    let grantedCount: number = 0;
    permissions.forEach((permission: string) => {
      if (this.port.checkSelfPermission(permission)) grantedCount += 1;
    });
    if (capability.runtimeGrantMode === 'All') {
      return grantedCount === permissions.length ? 'Granted' : 'Denied';
    }
    return grantedCount > 0 ? 'Granted' : 'Denied';
  }

  // :116-129(错误文案逐字)
  ensureGranted(capabilityId: string, toolName: string, reason: string): void {
    const capability: AgentPermissionCapability = this.getCapability(capabilityId);
    const status: AgentPermissionStatus = this.getStatus(capability);
    if (status !== 'Granted') {
      throw new Error(
        `System permission required: ${capability.title}. ` +
        `Status: ${status}. ` +
        'Open Settings > Agent Runtime > System Access to grant it.',
      );
    }
    this.auditPermissionUse(toolName, capabilityId, reason);
  }

  // :131-132
  runtimePermissionsFor(capability: AgentPermissionCapability): string[] {
    return capabilityCurrentRuntimePermissions(capability, this.port.currentApiLevel());
  }

  // :134-138
  runtimePermissionsForCoreBatch(): string[] {
    const out: string[] = [];
    this.capabilities.forEach((capability: AgentPermissionCapability) => {
      if (capability.debugOnly) return;
      if (capability.specialAccess !== null) return;
      if (capability.risk === 'High') return;
      capabilityCurrentRuntimePermissions(capability, this.port.currentApiLevel())
        .forEach((permission: string) => {
          if (out.indexOf(permission) < 0) out.push(permission);
        });
    });
    return out;
  }

  // :140-141  createSpecialAccessIntent(capabilityId) != null 等价
  canOpenSpecialAccessSettings(capabilityId: string): boolean {
    const access: AgentSpecialAccess | null = this.getCapability(capabilityId).specialAccess;
    if (access === null) return false;
    return this.port.canOpenSpecialAccessSettings(access);
  }

  // :143-145  Logging.log(TAG, "tool=$toolName capability=$capabilityId reason=$reason")
  auditPermissionUse(toolName: string, capabilityId: string, reason: string): void {
    this.auditLog(`tool=${toolName} capability=${capabilityId} reason=${reason}`);
  }
}

// ===== AgentPermissionRegistry(:236-459,24 条逐字;权限名映射见头注) =====
//
// 权限名映射(Android → HarmonyOS;折叠的多版本条目用 * 标注):
//   READ_CONTACTS → ohos.permission.READ_CONTACTS
//   WRITE_CONTACTS → ohos.permission.WRITE_CONTACTS
//   READ_SMS → ohos.permission.READ_SMS(受限 ACL;未声明 → check 抛错 → Denied)
//   SEND_SMS → ohos.permission.SEND_SMS(同上)
//   READ_PHONE_STATE(+READ_PHONE_NUMBERS*)→ ohos.permission.GET_TELEPHONY_STATE(受限)
//   READ_CALL_LOG → ohos.permission.READ_CALL_LOG(无三方对应 → 恒 Denied)
//   CALL_PHONE → ohos.permission.CALL_PHONE(受限)
//   READ_CALENDAR → ohos.permission.READ_CALENDAR
//   WRITE_CALENDAR → ohos.permission.WRITE_CALENDAR
//   READ_MEDIA_IMAGES/VIDEO(三件*)→ ohos.permission.READ_IMAGEVIDEO(受限 ACL)
//   READ_MEDIA_AUDIO(两件*)→ ohos.permission.READ_AUDIO(受限)
//   ACCESS_COARSE_LOCATION → ohos.permission.APPROXIMATELY_LOCATION
//   ACCESS_FINE_LOCATION → ohos.permission.LOCATION
//   RECORD_AUDIO → ohos.permission.MICROPHONE
//   BLUETOOTH_SCAN/CONNECT/ADVERTISE/NEARBY_WIFI_DEVICES* → ohos.permission.ACCESS_BLUETOOTH
//   ACTIVITY_RECOGNITION → ohos.permission.ACTIVITY_MOTION

export const AGENT_PERMISSION_CAPABILITIES: AgentPermissionCapability[] = [
  makeAgentPermissionCapability({
    id: 'contacts_read',
    title: '通讯录读取',
    description: '搜索联系人姓名、电话和邮箱。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.READ_CONTACTS')],
    risk: 'Sensitive',
    toolNames: ['contacts_search'],
  }),
  makeAgentPermissionCapability({
    id: 'contacts_write',
    title: '通讯录写入',
    description: '创建或更新联系人。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.WRITE_CONTACTS')],
    risk: 'High',
    toolNames: ['contacts_write'],
  }),
  makeAgentPermissionCapability({
    id: 'sms_read',
    title: '短信读取',
    description: '读取本机短信列表和短信内容。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.READ_SMS')],
    risk: 'High',
    toolNames: ['sms_list', 'sms_read'],
  }),
  makeAgentPermissionCapability({
    id: 'sms_send',
    title: '短信发送',
    description: '直接从本机号码发送短信。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.SEND_SMS')],
    risk: 'High',
    toolNames: ['sms_send'],
  }),
  makeAgentPermissionCapability({
    id: 'phone_state',
    title: '电话状态',
    description: '读取 SIM 和电话状态。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.GET_TELEPHONY_STATE')],
    runtimeGrantMode: 'Any',
    risk: 'Sensitive',
    toolNames: ['device_phone_state'],
  }),
  makeAgentPermissionCapability({
    id: 'call_log_read',
    title: '通话记录读取',
    description: '读取最近通话记录。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.READ_CALL_LOG')],
    risk: 'High',
    toolNames: ['call_log_list'],
  }),
  makeAgentPermissionCapability({
    id: 'call_phone',
    title: '直接拨号',
    description: '无需再经过拨号盘确认，直接发起电话呼叫。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.CALL_PHONE')],
    risk: 'High',
    toolNames: ['call_phone'],
  }),
  makeAgentPermissionCapability({
    id: 'calendar_read',
    title: '日历读取',
    description: '读取系统日历事件。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.READ_CALENDAR')],
    risk: 'Sensitive',
    toolNames: ['calendar_list'],
  }),
  makeAgentPermissionCapability({
    id: 'calendar_write',
    title: '日历写入',
    description: '创建系统日历事件。',
    runtimePermissions: [
      makeRuntimePermissionSpec('ohos.permission.READ_CALENDAR'),
      makeRuntimePermissionSpec('ohos.permission.WRITE_CALENDAR'),
    ],
    risk: 'High',
    toolNames: ['calendar_create'],
  }),
  makeAgentPermissionCapability({
    id: 'media_images',
    title: '图片媒体',
    description: '搜索系统图片媒体库。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.READ_IMAGEVIDEO')],
    runtimeGrantMode: 'Any',
    risk: 'Sensitive',
    toolNames: ['media_search'],
  }),
  makeAgentPermissionCapability({
    id: 'media_video',
    title: '视频媒体',
    description: '搜索系统视频媒体库。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.READ_IMAGEVIDEO')],
    runtimeGrantMode: 'Any',
    risk: 'Sensitive',
    toolNames: ['media_search'],
  }),
  makeAgentPermissionCapability({
    id: 'media_audio',
    title: '音频媒体',
    description: '搜索系统音频媒体库。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.READ_AUDIO')],
    runtimeGrantMode: 'Any',
    risk: 'Sensitive',
    toolNames: ['media_search'],
  }),
  makeAgentPermissionCapability({
    id: 'location_current',
    title: '当前位置',
    description: '读取最近一次系统定位结果。',
    runtimePermissions: [
      makeRuntimePermissionSpec('ohos.permission.APPROXIMATELY_LOCATION'),
      makeRuntimePermissionSpec('ohos.permission.LOCATION'),
    ],
    runtimeGrantMode: 'Any',
    risk: 'Sensitive',
    toolNames: ['location_current'],
  }),
  makeAgentPermissionCapability({
    id: 'audio_record',
    title: '麦克风录音',
    description: '录制一段短音频作为工具产物。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.MICROPHONE')],
    risk: 'High',
    toolNames: ['audio_record_once'],
  }),
  makeAgentPermissionCapability({
    id: 'nearby_devices',
    title: '附近设备',
    description: '扫描或连接蓝牙、附近 Wi-Fi 设备。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.ACCESS_BLUETOOTH')],
    runtimeGrantMode: 'Any',
    risk: 'Sensitive',
  }),
  makeAgentPermissionCapability({
    id: 'activity_recognition',
    title: '活动识别',
    description: '读取步行、跑步、乘车等活动识别结果。',
    runtimePermissions: [makeRuntimePermissionSpec('ohos.permission.ACTIVITY_MOTION')],
    risk: 'Sensitive',
  }),
  makeAgentPermissionCapability({
    id: 'notification_access',
    title: '通知读取',
    description: '读取当前活跃通知摘要。',
    specialAccess: 'NotificationListener',
    risk: 'High',
    toolNames: ['notification_list'],
  }),
  makeAgentPermissionCapability({
    id: 'usage_access',
    title: '使用情况访问',
    description: '读取最近使用过的应用和使用时长。',
    specialAccess: 'UsageStats',
    risk: 'Sensitive',
    toolNames: ['usage_stats_list'],
  }),
  makeAgentPermissionCapability({
    id: 'overlay',
    title: '悬浮窗',
    description: '允许显示覆盖在其他应用上方的 Agent 控件。',
    specialAccess: 'Overlay',
    risk: 'Sensitive',
  }),
  makeAgentPermissionCapability({
    id: 'battery_optimization',
    title: '忽略电池优化',
    description: '降低长时间 Agent 任务被系统暂停的概率。',
    specialAccess: 'IgnoreBatteryOptimizations',
    risk: 'Sensitive',
  }),
  makeAgentPermissionCapability({
    id: 'exact_alarm',
    title: '精确闹钟',
    description: '允许 Agent 未来按精确时间恢复任务。',
    specialAccess: 'ExactAlarm',
    risk: 'Sensitive',
  }),
  makeAgentPermissionCapability({
    id: 'manage_all_files',
    title: '全文件访问',
    description: '高级实验权限。必须用户手动授权，且 Agent 只可访问设置中加入的外部路径。',
    specialAccess: 'ManageAllFiles',
    risk: 'High',
    minSdk: 30,   // Build.VERSION_CODES.R(保留原门槛值,登记)
    toolNames: ['external_file_list', 'external_file_read', 'external_file_write', 'external_file_delete'],
  }),
  makeAgentPermissionCapability({
    id: 'apps',
    title: '应用列表与启动',
    description: '读取可启动应用列表并打开指定应用。',
    risk: 'Normal',
    toolNames: ['apps_list', 'app_open', 'app_info'],
  }),
  makeAgentPermissionCapability({
    id: 'installed_apps_full_access',
    title: '全量应用列表',
    description: '高级实验能力。读取设备上更完整的已安装包列表；Google Play 会限制此权限。',
    risk: 'High',
    debugOnly: true,
    toolNames: ['apps_installed_list'],
  }),
];

// ===== PermissionsStatusTool.kt(全文 79 行) =====

// :44-45  contentOrNull(键缺/非 primitive → undefined)
const toolContentOrNull = (input: JsonValue, key: string): string | undefined => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return undefined;
  const raw: JsonValue | undefined = (input as JsonObject)[key];
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw === 'string') return raw;
  if (typeof raw === 'boolean' || typeof raw === 'number') return String(raw);
  return undefined;   // object/array → 非 primitive
};

// :45  contentOrNull?.toBooleanStrictOrNull() ?: true
//   ('true'→true,'false'→false,其余 → null → 默认 true)
const toolStrictBooleanOrDefault = (input: JsonValue, key: string, fallback: boolean): boolean => {
  const content: string | undefined = toolContentOrNull(input, key);
  if (content === undefined) return fallback;
  if (content === 'true') return true;
  if (content === 'false') return false;
  return fallback;
};

export const createPermissionsStatusTool = (permissionBroker: AgentPermissionBroker): AgentTool =>
  makeAgentTool({
    name: 'permissions_status',
    description: 'List AmberAgent Android permission capability status and how to grant missing permissions.',
    parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
      capability_id: {
        type: 'string',
        description: 'Optional capability id to inspect',
      },
      include_how_to_grant: {
        type: 'boolean',
        description: 'Include user-facing grant guidance. Defaults to true.',
      },
    }),
    execute: (input: JsonValue): Promise<UIMessagePart[]> => {
      const id: string | undefined = toolContentOrNull(input, 'capability_id');
      const includeHowToGrant: boolean = toolStrictBooleanOrDefault(input, 'include_how_to_grant', true);
      const capabilities: AgentPermissionCapability[] = permissionBroker.capabilities.filter(
        (c: AgentPermissionCapability): boolean => id === undefined || c.id === id,
      );
      const allFilesCapability: AgentPermissionCapability = permissionBroker.getCapability('manage_all_files');
      const capabilityJsons: JsonValue[] = capabilities.map(
        (capability: AgentPermissionCapability): JsonObject => {
          const entry: JsonObject = {
            capability_id: capability.id,
            title: capability.title,
            description: capability.description,
            risk: capability.risk.toLowerCase(),
            status: permissionBroker.getStatus(capability).toLowerCase(),
            runtime_permissions: permissionBroker.runtimePermissionsFor(capability),
          };
          if (capability.specialAccess !== null) {
            entry['special_access'] = capability.specialAccess;
          }
          entry['tools'] = capability.toolNames;
          if (includeHowToGrant) {
            entry['how_to_grant'] =
              'Open AmberAgent Settings > Agent 设置 > 系统权限, then grant ' +
              `${capability.title}. Special access items open HarmonyOS system settings.`;
          }
          return entry;
        },
      );
      const payload: JsonObject = {
        all_files_access_declared: permissionBroker.getStatus(allFilesCapability) !== 'Unsupported',
        all_files_access_granted: permissionBroker.getStatus(allFilesCapability) === 'Granted',
        all_files_access_manage_intent_available:
          permissionBroker.canOpenSpecialAccessSettings('manage_all_files'),
        capabilities: capabilityJsons,
      };
      return Promise.resolve([{ type: 'text', text: JSON.stringify(payload), metadata: null }]);
    },
  });
