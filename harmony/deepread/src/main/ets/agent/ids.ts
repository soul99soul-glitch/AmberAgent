// Pure ArkTS-compatible local identifiers and ISO timestamps.

const randomUuidV4 = (): string =>
  'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c: string): string => {
    const r: number = Math.floor(Math.random() * 16);
    const v: number = c === 'x' ? r : (r % 4) + 8;
    return v.toString(16);
  });

export const newId = (): string => randomUuidV4();

export const nowIso = (): string => new Date().toISOString();
