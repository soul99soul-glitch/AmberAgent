// runtime_api — 跨运行时的 AbortSignal/AbortController 抽象接口。
// deepread 用 AbortSignalLike(接口)而非全局 AbortSignal(类型),这样:
// - node:原生 AbortSignal 满足接口(.aborted 字段),450 tests 不破
// - ArkTS:entry adapter 注入实现该接口的对象,无需全局 AbortSignal
//
// 这是双运行时兼容的关键:接口对齐,不依赖全局类型声明。

export interface AbortSignalLike {
  readonly aborted: boolean;
  addEventListener?(type: string, listener: () => void): void;
  removeEventListener?(type: string, listener: () => void): void;
}

export interface AbortControllerLike {
  signal: AbortSignalLike;
  abort(): void;
}
