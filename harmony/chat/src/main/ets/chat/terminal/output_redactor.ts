import type { SSHConnectionOptions } from './models.ts';

export const connectionSecrets = (connection: SSHConnectionOptions): string[] => {
  const values: string[] = [];
  const add = (value: string | null): void => {
    if (!value) return;
    values.push(value);
    // Servers may echo private-key bodies a line at a time with altered line endings.
    for (const line of value.split(/\r?\n/)) {
      if (line.length >= 16 && !line.startsWith('-----')) values.push(line);
    }
  };
  add(connection.secret); add(connection.passphrase);
  for (const hop of connection.jumpHops ?? []) { add(hop.secret); add(hop.passphrase); }
  return [...new Set(values)].sort((a: string, b: string): number => b.length - a.length);
};

// Retain only a possible secret prefix between chunks. Nothing reaches logs/UI first.
export class SSHOutputRedactor {
  private pending: string = '';
  constructor(private secrets: string[]) {}
  push(text: string, final: boolean = false): string {
    const input: string = this.pending + text;
    this.pending = '';
    let out: string = '';
    let i: number = 0;
    while (i < input.length) {
      let matched: string | null = null;
      let partial: boolean = false;
      for (const secret of this.secrets) {
        if (input.startsWith(secret, i)) { matched = secret; break; }
        if (!final && secret.startsWith(input.slice(i))) partial = true;
      }
      if (matched !== null) { out += '***'; i += matched.length; }
      else if (partial) { this.pending = input.slice(i); break; }
      else { out += input[i]; i++; }
    }
    return out;
  }
  clear(): void { this.pending = ''; this.secrets = []; }
}
