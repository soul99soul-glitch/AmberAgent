import type { Context } from '@kit.AbilityKit';

export type ProductKind = 'agent' | 'novel' | 'deepread';

// Read the installed bundle, including when a WorkAbility starts without the UIAbility.
export const getAppBundleName = (): string => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) throw new Error('应用上下文尚未初始化');
  return context.applicationInfo.name;
};

export const productKindForBundle = (bundleName: string): ProductKind => {
  if (bundleName === 'app.amber.novel') return 'novel';
  if (bundleName === 'app.amber.deepread.reader') return 'deepread';
  return 'agent';
};

export const getProductKind = (): ProductKind =>
  AppStorage.get<ProductKind>('productKind') ?? 'agent';

export const productRootPage = (): string => {
  const kind: ProductKind = getProductKind();
  if (kind === 'novel') return 'pages/NovelProjectsPage';
  if (kind === 'deepread') return 'pages/DeepReadRootPage';
  return 'pages/ChatListPage';
};
