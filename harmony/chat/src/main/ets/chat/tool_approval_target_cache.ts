// tool_approval_target_cache — 审批卡用的 SSH 配置摘要缓存(同步读)
//
// 为什么需要:
//   审批卡在 build() 里同步渲染,而 SSHProfileStore.snapshot() 是异步初始化的。
//   「改哪一条」需要保存的 profile 名称/主机,本模块让组合根在 profile 变更时预热,
//   UI 侧只做同步查表,不引入异步渲染。
//
// 边界:
//   - 只存三个非敏感字段(name/host/port);不存凭据 ref、不存指纹。
//   - 纯内存,进程内生命周期;未命中时返回 null,卡片退回只显示 id。

export interface ApprovalTargetProfile {
  name: string;
  host: string;
  port: number;
}

let targets: Map<string, ApprovalTargetProfile> = new Map();

/** 由组合根在 store 初始化与每次变更后调用;保持快照顺序无关。 */
export const setApprovalTargetProfiles = (
  profiles: Array<{ id: string; name: string; host: string; port: number }>,
): void => {
  const next: Map<string, ApprovalTargetProfile> = new Map();
  for (const profile of profiles) {
    next.set(profile.id, { name: profile.name, host: profile.host, port: profile.port });
  }
  targets = next;
};

/** 审批卡同步查询:找不到 → null。 */
export const approvalTargetProfile = (id: string | null): ApprovalTargetProfile | null =>
  id === null ? null : targets.get(id) ?? null;
