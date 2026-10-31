import type { JsonObject } from '../json.ts';
import type { SSHProfile, SSHRouteHopSummary } from './models.ts';
import { TerminalError } from './profile_store.ts';

export const MAX_JUMP_HOSTS: number = 3;

const CIRCLED_NUMBERS: string[] = ['①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧'];

export const isProfileHostTrusted = (p: SSHProfile): boolean =>
  p.knownHostSHA256 !== null &&
  p.knownHostHost === p.host &&
  p.knownHostPort === p.port &&
  (p.knownHostViaJump ?? null) === (p.jumpProfileId ?? null);

/**
 * Resolves a target profile into an ordered hop array `[hop1, ..., target]`.
 * Validates:
 *   - target existence (`profile_missing`)
 *   - jump target existence (`jump_profile_not_found` with `referenced_by`)
 *   - cycle detection (`jump_cycle` with `cycle_path`)
 *   - max 3 jump hosts / 4 total hops (`jump_chain_too_long`)
 */
export const resolveSSHRoute = (targetId: string, allProfiles: SSHProfile[]): SSHProfile[] => {
  const byId: Map<string, SSHProfile> = new Map();
  for (const profile of allProfiles) {
    byId.set(profile.id, profile);
  }
  const target: SSHProfile | undefined = byId.get(targetId);
  if (target === undefined) {
    throw new TerminalError('profile_missing', `No saved SSH profile with id '${targetId}'.`);
  }

  const reverseChain: SSHProfile[] = [target];
  const visitedIds: string[] = [target.id];
  let current: SSHProfile = target;

  while (current.jumpProfileId !== undefined && current.jumpProfileId !== null && current.jumpProfileId.length > 0) {
    const nextId: string = current.jumpProfileId;
    const cycleIdx: number = visitedIds.indexOf(nextId);
    if (cycleIdx >= 0) {
      const cycleIds: string[] = [...visitedIds, nextId];
      const cycleNames: string = cycleIds.map((id: string): string => byId.get(id)?.name ?? id).join(' → ');
      throw new TerminalError(
        'jump_cycle',
        `SSH jump route forms a cycle: ${cycleNames}`,
        { cycle_path: cycleIds, referenced_by: current.id },
      );
    }

    const nextProfile: SSHProfile | undefined = byId.get(nextId);
    if (nextProfile === undefined) {
      throw new TerminalError(
        'jump_profile_not_found',
        `Jump profile '${nextId}' referenced by '${current.id}' does not exist.`,
        { missing_profile_id: nextId, referenced_by: current.id },
      );
    }

    reverseChain.push(nextProfile);
    visitedIds.push(nextId);

    const jumpCount: number = reverseChain.length - 1;
    if (jumpCount > MAX_JUMP_HOSTS) {
      throw new TerminalError(
        'jump_chain_too_long',
        `SSH jump chain exceeds maximum of ${MAX_JUMP_HOSTS} jump hosts (${reverseChain.length} total hops).`,
        { max_jump_hosts: MAX_JUMP_HOSTS, hop_count: reverseChain.length },
      );
    }

    current = nextProfile;
  }

  return reverseChain.reverse();
};

export const buildRouteHopSummaries = (route: SSHProfile[]): SSHRouteHopSummary[] =>
  route.map((p: SSHProfile, idx: number): SSHRouteHopSummary => {
    const trusted: boolean = isProfileHostTrusted(p);
    return {
      hop: idx + 1,
      profile_id: p.id,
      name: p.name,
      endpoint: `${p.username}@${p.host}:${p.port}`,
      host: p.host,
      port: p.port,
      username: p.username,
      host_key_trusted: trusted,
      fingerprint: trusted ? p.knownHostSHA256 : null,
    };
  });

export const buildRouteHopJsonList = (route: SSHProfile[]): JsonObject[] =>
  buildRouteHopSummaries(route).map((h: SSHRouteHopSummary): JsonObject => ({
    hop: h.hop,
    profile_id: h.profile_id,
    name: h.name,
    endpoint: h.endpoint,
    host: h.host,
    port: h.port,
    username: h.username,
    host_key_trusted: h.host_key_trusted,
    fingerprint: h.fingerprint,
  }));

export const formatApprovalRouteText = (hops: SSHRouteHopSummary[]): string => {
  if (hops.length === 0) return '';
  const segments: string[] = hops.map((h: SSHRouteHopSummary, index: number): string => {
    const badge: string = CIRCLED_NUMBERS[index] ?? `(${h.hop})`;
    const trustMark: string = h.host_key_trusted ? '✓' : '⚠ 首次连接';
    return `${badge} ${h.name} (${h.endpoint}) ${trustMark}`;
  });
  return `本机 → ${segments.join(' → ')}`;
};

/**
 * Returns ids of profiles that directly reference `targetId` as their `jumpProfileId`.
 */
export const dependentJumpProfileIds = (targetId: string, allProfiles: SSHProfile[]): string[] => {
  const out: string[] = [];
  for (const profile of allProfiles) {
    if (profile.id !== targetId && (profile.jumpProfileId ?? null) === targetId) {
      out.push(profile.id);
    }
  }
  return out;
};

/**
 * Validates that writing `candidate` into `existingProfiles` keeps BOTH `candidate`'s own
 * route and every reverse-dependent profile's route valid (no cycles, no dangling refs,
 * and no route exceeding 3 jump hosts).
 */
export const validateProfileRouteCandidate = (candidate: SSHProfile, existingProfiles: SSHProfile[]): void => {
  const merged: SSHProfile[] = existingProfiles.filter((p: SSHProfile): boolean => p.id !== candidate.id);
  merged.push(candidate);

  // 1. Validate candidate's own route first.
  resolveSSHRoute(candidate.id, merged);

  // 2. Validate every other profile that directly or transitively depends on candidate.id.
  for (const other of merged) {
    if (other.id === candidate.id) continue;
    if (other.jumpProfileId === undefined || other.jumpProfileId === null) continue;
    // Only check profiles whose jump chain touches candidate.id so unrelated broken legacy profiles don't block edits.
    let cursor: SSHProfile | undefined = other;
    const seen: Set<string> = new Set();
    let touchesCandidate: boolean = false;
    while (cursor !== undefined && cursor.jumpProfileId !== undefined && cursor.jumpProfileId !== null) {
      if (cursor.jumpProfileId === candidate.id) {
        touchesCandidate = true;
        break;
      }
      if (seen.has(cursor.id)) break;
      seen.add(cursor.id);
      const nextId: string = cursor.jumpProfileId;
      cursor = merged.find((p: SSHProfile): boolean => p.id === nextId);
    }
    if (touchesCandidate) {
      resolveSSHRoute(other.id, merged);
    }
  }
};
