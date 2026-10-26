import { notificationManager } from '@kit.NotificationKit';
import { abilityAccessCtrl, bundleManager, common, PermissionRequestResult, Permissions } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { DeviceScheduleError } from '@amber/chat-domain';

export const DOMAIN: number = 0x0001;
export const TAG: string = 'DeviceSchedulePort';

export const READ_CALENDAR: Permissions = 'ohos.permission.READ_CALENDAR';
export const WRITE_CALENDAR: Permissions = 'ohos.permission.WRITE_CALENDAR';

// BusinessError 码(SDK d.ts @throws)
const ERR_PERMISSION: number = 201;
const ERR_PARAM: number = 401;
const ERR_CAPABILITY: number = 801;
const ERR_REMINDER_NOTIFICATION_DISABLED: number = 1700001;
export const ERR_REMINDER_LIMIT: number = 1700002;
export const ERR_REMINDER_NOT_EXIST: number = 1700003;
const ERR_CALENDAR_ACCOUNT_NOT_FOUND: number = 23900003;

// ===== 错误映射 =====

export const toScheduleError = (err: Error, action: string): DeviceScheduleError => {
  if (err instanceof DeviceScheduleError) return err;
  const code: number = (err as BusinessError).code;
  hilog.warn(DOMAIN, TAG, '%{public}s failed: code=%{public}d msg=%{public}s', action, code, err.message);
  if (code === ERR_PERMISSION) {
    return new DeviceScheduleError('permission_denied', `${action}失败:未获得所需权限,可在 设置 > 应用和元服务 > Amber > 权限 中开启。`);
  }
  if (code === ERR_CAPABILITY) return new DeviceScheduleError('unavailable', `${action}失败:当前设备不支持该能力。`);
  if (code === ERR_PARAM) return new DeviceScheduleError('invalid_arguments', `${action}失败:系统拒绝了参数(${err.message})。`);
  if (code === ERR_REMINDER_LIMIT) {
    return new DeviceScheduleError('capacity_reached', '系统提醒数量已达上限,请先取消一个闹钟或通知。');
  }
  if (code === ERR_REMINDER_NOTIFICATION_DISABLED) {
    return new DeviceScheduleError('permission_denied', '未开启 Amber 通知,无法发布提醒;请在系统设置中允许通知。');
  }
  if (code === ERR_REMINDER_NOT_EXIST || code === ERR_CALENDAR_ACCOUNT_NOT_FOUND) {
    return new DeviceScheduleError('not_found', `${action}失败:目标不存在或已被删除。`);
  }
  const detail: string = err.message !== undefined && err.message.length > 0 ? err.message : 'unknown error';
  return new DeviceScheduleError('platform_error', `${action}失败:${detail}${code !== undefined ? `(${code})` : ''}`);
};

// ===== Context / 权限 =====

export const abilityContext = (): common.UIAbilityContext => {
  const ctx: common.UIAbilityContext | undefined = AppStorage.get<common.UIAbilityContext>('abilityContext');
  if (ctx === undefined) {
    throw new DeviceScheduleError('unavailable', '应用界面尚未就绪,暂时无法访问系统日历或提醒。');
  }
  return ctx;
};

const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
let cachedTokenId: number = -1;

const selfTokenId = (): number => {
  if (cachedTokenId < 0) {
    const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
    cachedTokenId = info.appInfo.accessTokenId;
  }
  return cachedTokenId;
};

let cachedBundleName: string = '';

export const selfBundleName = (): string => {
  if (cachedBundleName.length === 0) {
    cachedBundleName = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT).name;
  }
  return cachedBundleName;
};

const isGranted = (perm: Permissions): boolean => {
  try {
    return atManager.checkAccessTokenSync(selfTokenId(), perm) === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  } catch (e) {
    hilog.debug(DOMAIN, TAG, 'checkAccessTokenSync failed: %{public}s', (e as BusinessError).message);
    return false;
  }
};

export const ensurePermissions = async (perms: Permissions[], label: string): Promise<void> => {
  const missing: Permissions[] = perms.filter((p: Permissions): boolean => !isGranted(p));
  if (missing.length === 0) return;
  const ctx: common.UIAbilityContext = abilityContext();
  let granted: boolean = false;
  // 仅「本次被拒且系统未弹框」(= 用户此前已拒绝)的权限可走设置弹框;混入未被拒/已授予的
  // 权限会让 requestPermissionOnSetting 直接抛 12100012 / 12100011
  const settingCandidates: Permissions[] = [];
  try {
    const result: PermissionRequestResult = await atManager.requestPermissionsFromUser(ctx, missing);
    const auth: number[] = result.authResults;
    granted = auth.length === missing.length && auth.every((r: number): boolean => r === 0);
    const shown: boolean[] | undefined = result.dialogShownResults;
    if (!granted && shown !== undefined) {
      for (let i: number = 0; i < missing.length; i++) {
        const denied: boolean = i >= auth.length || auth[i] !== 0;
        if (denied && i < shown.length && shown[i] === false) settingCandidates.push(missing[i]);
      }
    }
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'requestPermissionsFromUser failed: %{public}s', (e as BusinessError).message);
  }
  if (!granted && settingCandidates.length > 0) {
    // 用户此前已拒绝:系统不再弹首次授权框 → 用权限设置弹框二次引导(API 12)
    try {
      await atManager.requestPermissionOnSetting(ctx, settingCandidates);
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'requestPermissionOnSetting failed: %{public}s', (e as BusinessError).message);
    }
    granted = missing.every((p: Permissions): boolean => isGranted(p));
  }
  if (!granted) {
    throw new DeviceScheduleError('permission_denied',
      `未获准访问${label},可在 设置 > 应用和元服务 > Amber > 权限 中开启。`);
  }
};

const notificationsEnabled = async (): Promise<boolean> => {
  try {
    return await notificationManager.isNotificationEnabled();
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'isNotificationEnabled failed: %{public}s', (e as BusinessError).message);
    return false;
  }
};

export const ensureNotificationsEnabled = async (): Promise<void> => {
  if (await notificationsEnabled()) return;
  try {
    await notificationManager.requestEnableNotification(abilityContext());
  } catch (e) {
    if (e instanceof DeviceScheduleError) throw e;
    hilog.warn(DOMAIN, TAG, 'requestEnableNotification failed: %{public}s', (e as BusinessError).message);
  }
  if (!(await notificationsEnabled())) {
    throw new DeviceScheduleError('permission_denied', '未获得通知权限,请在系统设置中允许 Amber 发送通知。');
  }
};

// ===== 日历 helper =====

export const parseNumericId = (raw: string): number | null => {
  const trimmed: string = raw.trim();
  return /^\d+$/.test(trimmed) ? Number(trimmed) : null;
};

