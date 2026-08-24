// miniapp_sandbox — 权限门:全局开关 → 声明集 → 设置映射 → grant 记忆
//
// Android 基准: feature/miniapp/MiniAppSandbox.kt(全文 55 行)
// 偏差:
//   - SecurityException → MiniAppSecurityException(models 定义)
//   - settingProvider?  → deps.setting()(getter 注入,读取时机 = require 调用时,
//     对齐 MiniAppPromptTransformer deps 注入写法)
//   - grantDecision 默认 null → deps.grantLookup 缺省返回 null

import { MiniAppSecurityException } from './miniapp_models.ts';
import type { MiniAppGrantDecision, MiniAppPermission } from './miniapp_models.ts';
import { MINI_APP_V3_PERMISSIONS } from './miniapp_models.ts';
import { isPermissionGloballyEnabled } from './miniapp_setting.ts';
import type { MiniAppSetting } from './miniapp_setting.ts';
import { makeMiniAppSetting } from './miniapp_setting.ts';

export interface MiniAppSandboxDeps {
  setting?: () => MiniAppSetting;
  grantLookup?: (permission: string) => MiniAppGrantDecision | null;
}

// MiniAppSandbox.kt:5-31
export class MiniAppSandbox {
  private readonly declared: Set<string>;

  constructor(
    private readonly appId: string,
    declaredPermissions: string[],
    private readonly deps: MiniAppSandboxDeps = {},
  ) {
    // MiniAppSandbox.kt:12 declaredPermissions.intersect(MiniAppV3Permissions)
    this.declared = new Set<string>(
      declaredPermissions.filter((p: string): boolean =>
        (MINI_APP_V3_PERMISSIONS as string[]).includes(p)));
  }

  require(permission: MiniAppPermission): void {
    const setting: MiniAppSetting =
      this.deps.setting !== undefined ? this.deps.setting() : makeMiniAppSetting();
    if (!setting.enabled) {
      throw new MiniAppSecurityException('MiniApp is disabled');
    }
    if (!this.declared.has(permission)) {
      throw new MiniAppSecurityException(`Permission denied for ${this.appId}: ${permission}`);
    }
    if (!isPermissionGloballyEnabled(permission, setting)) {
      throw new MiniAppSecurityException(`Permission disabled: ${permission}`);
    }
    const decision: MiniAppGrantDecision | null =
      this.deps.grantLookup !== undefined ? this.deps.grantLookup(permission) : null;
    if (decision === 'DENY') {
      throw new MiniAppSecurityException(`Permission denied: ${permission}`);
    }
  }
}
