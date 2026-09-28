import { BG, SURFACE, SURFACE2, INK, INK2, INK3, LINE, ERROR, ERROR_BG, WARNING_BG, WARNING_FG, ACCENT_INK,
  SUCCESS_FG, SUCCESS_BG } from '../design/tokens.ets';

export class SSHUIColors {
  background: string = BG;
  surface: string = SURFACE;
  inset: string = SURFACE2;
  ink: string = INK;
  secondary: string = INK2;
  muted: string = INK3;
  line: string = LINE;
  error: string = ERROR;
  errorBackground: string = ERROR_BG;
  warning: string = WARNING_FG;
  warningBackground: string = WARNING_BG;
  success: string = SUCCESS_FG;
  successBackground: string = SUCCESS_BG;
  accentInk: string = ACCENT_INK;
}
