// newId — UUID v4 生成
// ArkTS 应用层无全局 crypto(那是 Node/浏览器全局)。用 globalThis 探测,
// Node 测试走 crypto.randomUUID;ArkTS 路径后续由 entry 注入 util.generateRandomUUID
// (见 DECISION_LOG D-002 同款注入模式)。兜底 Math.random 仅用于本地唯一性场景。

const randomUuidV4 = (): string => {
  // 经典 v4 模板,随机源较弱但满足本地消息/节点 id 唯一性
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c: string): string => {
    const r: number = Math.floor(Math.random() * 16);
    const v: number = c === 'x' ? r : (r % 4) + 8;
    return v.toString(16);
  });
};

export const newId = (): string => {
  const g: { crypto?: { randomUUID?: () => string } } =
    globalThis as { crypto?: { randomUUID?: () => string } };
  if (g.crypto !== undefined && g.crypto.randomUUID !== undefined) {
    return g.crypto.randomUUID();
  }
  return randomUuidV4();
};

// ISO 8601 时间戳(UTC, 带 Z)。Android UIMessage.createdAt 是 LocalDateTime(无时区),
// 此处统一用 Instant 语义(带 Z),偏差记 PARITY_DEBT PD-004。
export const nowIso = (): string => new Date().toISOString();
