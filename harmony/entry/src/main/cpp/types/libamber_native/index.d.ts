/** Counts UTF-8 text with o200k_base, cl100k_base, claude, or gemini. */
export const countBatch: (tokenizerIds: string[], texts: string[]) => Promise<number[]>;
