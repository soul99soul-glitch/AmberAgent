// 截图按实际像素均分，连续覆盖内容，不把末尾圆角/padding 单独切成一张。
export interface ImageExportPage {
  top: number;
  height: number;
}

export function imageExportPages(contentHeightPx: number, screenHeightPx: number): ImageExportPage[] {
  if (!Number.isFinite(contentHeightPx) || !Number.isFinite(screenHeightPx)
    || contentHeightPx <= 0 || screenHeightPx < 1) {
    throw new Error('图片尺寸无效');
  }
  const height: number = Math.ceil(contentHeightPx);
  const pageCount: number = Math.ceil(height / Math.floor(screenHeightPx));
  const baseHeight: number = Math.floor(height / pageCount);
  const remainder: number = height % pageCount;
  const pages: ImageExportPage[] = [];
  let top: number = 0;
  for (let index: number = 0; index < pageCount; index++) {
    const pageHeight: number = baseHeight + (index < remainder ? 1 : 0);
    pages.push({ top: top, height: pageHeight });
    top += pageHeight;
  }
  return pages;
}
