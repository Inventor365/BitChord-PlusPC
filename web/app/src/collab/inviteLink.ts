import { sanitizeCollabServerUrl } from './config';

export interface ParsedCollabInvite {
  token: string;
  serverOrigin: string | null;
  ytmPlaylistId?: string | null;
  ytmJoinToken?: string | null;
}

const TOKEN_PATTERN = /^bci_[A-Za-z0-9_-]{8,124}$/;

export function cleanCollabInviteToken(raw: string | null | undefined): string | null {
  const trimmed = raw?.trim() ?? '';
  return TOKEN_PATTERN.test(trimmed) ? trimmed : null;
}

/**
 * Parses collaborative playlist invite URLs and raw tokens:
 * - `https://host/collab/invite/bci_...` (optional `?server=...`)
 * - `/collab/invite/bci_...` (optional `?server=...`)
 * - `bitchord://collab/invite/bci_...?server=...`
 * - `intent://collab/invite/bci_...?server=...#Intent;scheme=bitchord;end`
 * - raw `bci_...` token
 */
export function parseCollabInviteLink(rawInput: string): ParsedCollabInvite | null {
  const trimmed = rawInput.trim();
  if (!trimmed) return null;
  const direct = parseSingleInviteCandidate(trimmed);
  if (direct) return direct;
  const matches = trimmed.match(/(?:https?:\/\/|bitchord:\/\/|intent:\/\/)\S+/gi) ?? [];
  for (const m of matches) {
    const cleaned = m.replace(/[.,)\]>'"]+$/, '');
    const parsed = parseSingleInviteCandidate(cleaned);
    if (parsed) return parsed;
  }
  return null;
}

function parseRawQueryParam(search: string, key: string): string | null {
  const q = search.startsWith('?') ? search.slice(1) : search;
  for (const pair of q.split('&')) {
    const eq = pair.indexOf('=');
    if (eq <= 0) continue;
    if (pair.slice(0, eq).toLowerCase() === key.toLowerCase()) {
      try {
        return decodeURIComponent(pair.slice(eq + 1));
      } catch {
        return pair.slice(eq + 1);
      }
    }
  }
  return null;
}

function parseSingleInviteCandidate(input: string): ParsedCollabInvite | null {
  const directToken = cleanCollabInviteToken(input);
  if (directToken) {
    return { token: directToken, serverOrigin: null };
  }
  if (input.startsWith('ytm:')) {
    const parts = input.split(':');
    if (parts.length >= 3 && parts[1] && parts.slice(2).join(':')) {
      const jct = parts.slice(2).join(':');
      return { token: input, serverOrigin: null, ytmPlaylistId: parts[1], ytmJoinToken: jct };
    }
  }
  if (input.startsWith('/')) {
    try {
      const parsed = new URL(input, 'https://bitchord.local');
      const match = parsed.pathname.match(/^\/collab\/invite\/([^/?#]+)\/?$/);
      const token = match ? cleanCollabInviteToken(decodeURIComponent(match[1]!)) : null;
      if (!token) return null;
      const serverOrigin = sanitizeCollabServerUrl(parsed.searchParams.get('server'));
      return { token, serverOrigin };
    } catch {
      return null;
    }
  }

  // Android intent URI: intent://collab/invite/bci_...?server=...#Intent;scheme=bitchord;end
  if (/^intent:\/\/collab\//i.test(input)) {
    const withoutFragment = input.split('#')[0] ?? input;
    const normalized = withoutFragment.replace(/^intent:\/\//i, 'bitchord://');
    return parseSingleInviteCandidate(normalized);
  }

  // Custom scheme: bitchord://collab/invite/bci_... or bitchord://collab/ytm/<playlistId>?jct=...
  if (/^bitchord:\/\//i.test(input)) {
    try {
      const parsed = new URL(input);
      const host = parsed.host.toLowerCase();
      let candidateToken: string | null = null;
      if (host === 'collab') {
        const ytmMatch = parsed.pathname.match(/^\/ytm\/([^/?#]+)\/?$/);
        const jct = parseRawQueryParam(parsed.search, 'jct')?.trim();
        if (ytmMatch?.[1] && jct) {
          const pid = decodeURIComponent(ytmMatch[1]).replace(/^VL/, '').trim();
          return { token: `ytm:${pid}:${jct}`, serverOrigin: null, ytmPlaylistId: pid, ytmJoinToken: jct };
        }
        const match = parsed.pathname.match(/^\/invite\/([^/?#]+)\/?$/);
        if (match) candidateToken = decodeURIComponent(match[1]!);
      } else if (!host && parsed.pathname.startsWith('/collab/invite/')) {
        const match = parsed.pathname.match(/^\/collab\/invite\/([^/?#]+)\/?$/);
        if (match) candidateToken = decodeURIComponent(match[1]!);
      }
      const token = cleanCollabInviteToken(candidateToken ?? parsed.searchParams.get('token'));
      if (!token) return null;
      const serverOrigin = sanitizeCollabServerUrl(parsed.searchParams.get('server'));
      return { token, serverOrigin };
    } catch {
      return null;
    }
  }

  // HTTP(S) invite URL
  try {
    const parsed = new URL(input);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    const host = parsed.hostname.toLowerCase();
    if (
      host === 'music.youtube.com' ||
      host === 'www.youtube.com' ||
      host === 'youtube.com' ||
      host === 'm.youtube.com'
    ) {
      const listId = parseRawQueryParam(parsed.search, 'list')?.replace(/^VL/, '').trim();
      const jct = parseRawQueryParam(parsed.search, 'jct')?.trim();
      if (listId && jct) {
        return {
          token: `ytm:${listId}:${jct}`,
          serverOrigin: null,
          ytmPlaylistId: listId,
          ytmJoinToken: jct,
        };
      }
    }
    const match = parsed.pathname.match(/\/collab\/invite\/([^/?#]+)\/?$/);
    const token = match ? cleanCollabInviteToken(decodeURIComponent(match[1]!)) : null;
    if (!token) return null;

    const explicitServer = sanitizeCollabServerUrl(parsed.searchParams.get('server'));
    const linkOrigin = sanitizeCollabServerUrl(parsed.origin);
    const currentOrigin =
      typeof window !== 'undefined' && /^https?:\/\//i.test(window.location?.origin ?? '')
        ? sanitizeCollabServerUrl(window.location.origin)
        : null;

    const serverOrigin = explicitServer ?? (linkOrigin && linkOrigin !== currentOrigin ? linkOrigin : linkOrigin);
    return { token, serverOrigin };
  } catch {
    return null;
  }
}

/** Formats a shareable web invite URL (`/collab/invite/bci_...`). */
export function formatCollabInviteUrl(token: string, serverOrigin?: string | null): string {
  const cleanToken = cleanCollabInviteToken(token) ?? token.trim();
  const cleanServer = sanitizeCollabServerUrl(serverOrigin);
  const webOrigin =
    typeof window !== 'undefined' && /^https?:\/\//i.test(window.location?.origin ?? '')
      ? sanitizeCollabServerUrl(window.location.origin)
      : null;

  const baseOrigin = webOrigin ?? cleanServer ?? 'https://bitchord.app';
  const query =
    cleanServer && webOrigin && cleanServer !== webOrigin ? `?server=${encodeURIComponent(cleanServer)}` : '';
  return `${baseOrigin}/collab/invite/${encodeURIComponent(cleanToken)}${query}`;
}

/** Formats a native app deep link (`bitchord://collab/invite/bci_...`). */
export function formatCollabDeepLink(token: string, serverOrigin?: string | null): string {
  const cleanToken = cleanCollabInviteToken(token) ?? token.trim();
  const cleanServer = sanitizeCollabServerUrl(serverOrigin);
  const query = cleanServer ? `?server=${encodeURIComponent(cleanServer)}` : '';
  return `bitchord://collab/invite/${encodeURIComponent(cleanToken)}${query}`;
}

function protoVarint(value: number): number[] {
  const out: number[] = [];
  let rest = value;
  while (rest >= 0x80) {
    out.push((rest & 0x7f) | 0x80);
    rest >>>= 7;
  }
  out.push(rest);
  return out;
}

function innertubeParams(bytes: number[]): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '%3D');
}

/**
 * `browse` params carrying a YouTube Music invite link's `jct`, byte for byte what
 * music.youtube.com's server builds for `/playlist?list=…&jct=…`: field 39 holds the token,
 * field 88 a fixed `{1: 600}`. The playlist page requested with these carries the invite
 * dialog and its join command.
 */
export function ytmInviteBrowseParams(jct: string): string {
  const token = Array.from(new TextEncoder().encode(jct));
  return innertubeParams([0xba, 0x02, ...protoVarint(token.length), ...token, 0xc2, 0x05, 0x03, 0x08, 0xd8, 0x04]);
}

/** `get_panel` params for a playlist's `PAplaylist_collaborate` panel: field 138 wrapping the playlist id. */
export function ytmCollaboratorsPanelParams(rawPlaylistId: string): string {
  const id = Array.from(new TextEncoder().encode(rawPlaylistId.replace(/^VL/, '')));
  const inner = [0x0a, ...protoVarint(id.length), ...id];
  return innertubeParams([0xd2, 0x08, ...protoVarint(inner.length), ...inner]);
}
