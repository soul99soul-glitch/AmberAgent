// Current iOS DeepReadApp palette. Keep the standalone product separate from the host.
import { BG, SURFACE, INK, INK2, LINE, ACCENT, ERROR, SUCCESS_FG, WARNING_FG } from './tokens.ets';
import { FONT_SERIF, FONT_SANS } from './fonts.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { font } from '@kit.ArkUI';
import type { Font } from '@kit.ArkUI';

export class DeepReadDesign {
  // SwiftUI's serif design keeps Chinese shell titles in the system face.
  // The Latin-only subset lets ArkUI use the same fallback without changing reading typography.
  static get serif(): string { return getProductKind() === 'deepread' ? 'DeepReadUISerif' : FONT_SERIF; }
  static readonly readingSerif: string = FONT_SERIF;
  static readonly sans: string = FONT_SANS;
  static readonly gutter: number = 18;
  static readonly radius: number = 18;

  static registerFonts(host: Font | null = null): void {
    if (getProductKind() !== 'deepread') return;
    if (host !== null) {
      host.registerFont({ familyName: 'DeepReadUISerif', familySrc: $rawfile('fonts/DeepReadUISerif.otf') });
    } else {
      font.registerFont({ familyName: 'DeepReadUISerif', familySrc: $rawfile('fonts/DeepReadUISerif.otf') });
    }
  }

  private static color(light: string, dark: string, host: string): string {
    if (getProductKind() !== 'deepread') return host;
    return (AppStorage.get<boolean>('darkMode') ?? false) ? dark : light;
  }

  static get paper(): string { return DeepReadDesign.color('#FBF7F1', '#201C19', BG); }
  static get card(): string { return DeepReadDesign.color('#FFFDF9', '#2A2521', SURFACE); }
  static get ink(): string { return DeepReadDesign.color('#2A2320', '#F1E9DF', INK); }
  static get muted(): string { return DeepReadDesign.color('#6E6254', '#B9AA98', INK2); }
  static get rule(): string { return DeepReadDesign.color('#E4D9CB', '#3D3530', LINE); }
  static get accent(): string {
    // The existing default is terracotta. Standalone DeepRead uses iOS cinnabar;
    // an explicitly chosen custom accent continues to use the persisted value.
    const selected: string = AppStorage.get<string>('accentColor') ?? '';
    if (getProductKind() === 'deepread') {
      switch (selected) {
        case '#C8402F': return DeepReadDesign.color('#C8402F', '#E67261', ACCENT);
        case '#2F5D8A': return DeepReadDesign.color('#2F5D8A', '#7FA8D6', ACCENT);
        case '#2F6B57': return DeepReadDesign.color('#2F6B57', '#7DBFA4', ACCENT);
        case '#6B4C9A': return DeepReadDesign.color('#6B4C9A', '#B39DDB', ACCENT);
        case '#2A2320': return DeepReadDesign.color('#2A2320', '#F1E9DF', ACCENT);
      }
    }
    if (selected.length > 0 && selected !== '#B8623A') return selected;
    return DeepReadDesign.color('#C8402F', '#E67261', ACCENT);
  }
  static get done(): string { return DeepReadDesign.color('#3F6A4A', '#8DBB98', SUCCESS_FG); }
  static get warn(): string { return DeepReadDesign.color('#8F5A10', '#E2A75A', WARNING_FG); }
  static get danger(): string { return DeepReadDesign.color('#A8261F', '#FF7B6E', ERROR); }
}
