import Foundation
@preconcurrency import Shared

/// I-4（快照契约,`docs/IOS_AGENT_HARDENING_PLAN_2026-07-29.md` §W4）:一个 run 内,
/// 模型可见的配置来自 run 开始时的同一冻结快照。
///
/// 范围说明(不是大而全的配置容器,只补已核实的裂缝):
/// - `providerSetting`/`params` 本来就已经作为不可变值在 run 内穿透传递
///   (`start` → `prepareAndStartStreaming` → `executeToolCall` → 下一轮),不回读
///   store,不需要在这里重复冻结。
/// - 这里冻结的是压缩配置(`IOSContextCompactionCoordinator` 的 prepare/finalize
///   两处 live 读 `dependencies.sharedSettings.snapshot`)——用户在生成中途改压缩
///   设置,曾经会在同一个 run 的轮间悄悄生效,这与 2026-06 的 provider dual-source
///   事故同源:两处各自读一份"当前配置",判断哪份对靠人肉,判断错一次就是下一个
///   dual-source。
/// - 工具集已经冻结在同一份 `params.tools` 中：前台 `nextPendingToolCall` 与后台
///   executor map 都只接受该集合里的名字，不再回读全局工具开关。用户审批策略仍
///   可 live 读取，因为那是执行时的人类权限决定，不是模型可见工具集的一部分。
struct ChatRunSnapshot {
    /// 这份快照对应的 run id,用来判断某次读取是否真的落在“这个 run”里——而不是
    /// 上一个已结束的 run 遗留的状态。
    let runId: String
    /// run 开始那一刻的 `IOSSharedSettingsStore.snapshot`。压缩策略等字段只从这里
    /// 读,不再每轮重新 live 读 store。
    let settings: Settings
}
