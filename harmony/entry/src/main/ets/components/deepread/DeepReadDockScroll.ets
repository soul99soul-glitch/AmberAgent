// Lists keep navigation expanded. Existing scroll hooks also clear a compact
// value left by an older page, without changing scroll position or selection.
export class DeepReadDockScroll {
  reset(): void {
    AppStorage.setOrCreate<boolean>('deepreadDockCollapsed', false);
  }

  update(_delta: number, _offset: number, _userScroll: boolean): void {
    if (AppStorage.get<boolean>('deepreadDockCollapsed') === true) this.reset();
  }
}
