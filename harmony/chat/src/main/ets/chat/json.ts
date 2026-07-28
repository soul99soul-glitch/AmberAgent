// JsonValue — 对应 kotlinx.serialization.json.JsonObject 的最小 ArkTS 表达
// 用途:UIMessagePart.metadata 等需要任意 JSON 对象的字段
// ArkTS 约束:禁止 any,递归类型别名表达 JSON 值域

export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue };

export type JsonObject = { [key: string]: JsonValue };
