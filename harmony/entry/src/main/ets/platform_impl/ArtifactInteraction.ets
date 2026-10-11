// One focused canvas at a time; bounds and touch coordinates are in window vp.
interface ArtifactInteractionBounds {
  owner: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export const activateArtifactInteraction = (owner: string, area: Area): void => {
  const bounds: ArtifactInteractionBounds = {
    owner: owner, x: Number(area.globalPosition.x), y: Number(area.globalPosition.y),
    width: Number(area.width), height: Number(area.height),
  };
  if (![bounds.x, bounds.y, bounds.width, bounds.height].every((value: number): boolean =>
    Number.isFinite(value)) || bounds.width <= 0 || bounds.height <= 0) return;
  AppStorage.setOrCreate<ArtifactInteractionBounds>('artifactInteractionBounds', bounds);
  AppStorage.setOrCreate<string>('activeArtifactInteraction', owner);
};

export const clearArtifactInteraction = (owner: string): void => {
  if (AppStorage.get<string>('activeArtifactInteraction') === owner) {
    AppStorage.setOrCreate<string>('activeArtifactInteraction', '');
  }
};

export const clearArtifactInteractionOutside = (event: TouchEvent): void => {
  if (event.type !== TouchType.Down) return;
  const owner: string = AppStorage.get<string>('activeArtifactInteraction') ?? '';
  if (owner.length === 0) return;
  const bounds: ArtifactInteractionBounds | undefined =
    AppStorage.get<ArtifactInteractionBounds>('artifactInteractionBounds');
  const touch: TouchObject | undefined = event.touches[0] ?? event.changedTouches[0];
  if (bounds === undefined || bounds.owner !== owner || touch === undefined ||
    !Number.isFinite(touch.windowX) || !Number.isFinite(touch.windowY) ||
    touch.windowX < bounds.x || touch.windowX > bounds.x + bounds.width ||
    touch.windowY < bounds.y || touch.windowY > bounds.y + bounds.height) {
    clearArtifactInteraction(owner);
  }
};
