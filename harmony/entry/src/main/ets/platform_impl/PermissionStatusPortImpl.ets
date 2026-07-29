// PermissionStatusPortImpl — AgentPermissionPlatformPort 的真实实现(D-129)
//
// Android 基准: AgentPermissionBroker.kt 内 ContextCompat.checkSelfPermission/
//   Build.VERSION.SDK_INT/Settings.canDrawOverlays 等平台调用 → 端口下沉到此
// HarmonyOS 映射(登记):
//   - checkSelfPermission → atManager.checkAccessTokenSync(tokenId, name)
//     (未声明/不存在的权限名抛 BusinessError → catch → false,与 Android
//     未声明 manifest 时 PERMISSION_DENIED 同语义)
//   - currentApiLevel → deviceInfo.sdkApiVersion
//   - isDebugBuild → BuildProfile.DEBUG(hvigor 生成)
//   - special access 六类 HarmonyOS 无对应概念 → granted=null(→Unsupported)、
//     canOpen=false(D-060 能力降级先例;域层头注有完整映射表)

import { abilityAccessCtrl, bundleManager, Permissions } from '@kit.AbilityKit';
import { deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { AgentPermissionPlatformPort, AgentSpecialAccess } from '@amber/chat-domain';
import BuildProfile from 'BuildProfile';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.PermissionPort';

export const createEntryPermissionPlatformPort = (): AgentPermissionPlatformPort => {
  const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
  let cachedTokenId: number = -1;
  const tokenId = (): number => {
    if (cachedTokenId < 0) {
      const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION,
      );
      cachedTokenId = info.appInfo.accessTokenId;
    }
    return cachedTokenId;
  };
  return {
    isDebugBuild: (): boolean => BuildProfile.DEBUG,
    currentApiLevel: (): number => deviceInfo.sdkApiVersion,
    checkSelfPermission: (permission: string): boolean => {
      try {
        const status: abilityAccessCtrl.GrantStatus =
          atManager.checkAccessTokenSync(tokenId(), permission as Permissions);
        return status === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
      } catch (e) {
        // 未声明/平台不存在的权限名 → Denied(与 Android 未声明 manifest 同语义)
        hilog.debug(DOMAIN, TAG, 'checkAccessTokenSync denied/unknown permission');
        return false;
      }
    },
    specialAccessGranted: (_access: AgentSpecialAccess): boolean | null => null,
    canOpenSpecialAccessSettings: (_access: AgentSpecialAccess): boolean => false,
  };
};

// auditPermissionUse 的 Logging.log 等价(仅工具名/能力 id/理由,无隐私数据)
export const auditPermissionUseLog = (message: string): void => {
  hilog.info(DOMAIN, TAG, '[audit] %{public}s', message);
};
