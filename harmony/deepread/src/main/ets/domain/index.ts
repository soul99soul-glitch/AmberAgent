// Deep Read Domain 层公共 API — barrel re-export
// oh-package.json5 的 main 指向此文件。
// ArkTS 安全:不导出 topic_id_node.ts(它 import 'crypto',仅 node 测试用)。
// ArkTS 入口用 createTopicIdWithRuntime + 注入 runtime 得到 deriveTopicId。
// node 测试直接 import topic_id_node.ts。

export * from './models.ts';
export * from './enums.ts';
export * from './helpers.ts';
export * from './topic_id.ts';
