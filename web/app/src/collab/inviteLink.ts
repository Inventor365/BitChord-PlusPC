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
  const input = rawInput.trim();
  if (!input) return null;

  const directToken = cleanCollabInviteToken(input);
  if (directToken) {
    return { token: directToken, serverOrigin: null };
  }
  if (input.startsWith('ytm:')) {
    const parts = input.split(':');
    if (parts.length === 3 && parts[1] && parts[2]) {
      return { token: input, serverOrigin: null, ytmPlaylistId: parts[1], ytmJoinToken: parts[2] };
    }
  }

  // Relative path: /collab/invite/bci_...
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
  if (/^intent:\/\/collab\/invite\//i.test(input)) {
    const withoutFragment = input.split('#')[0] ?? input;
    const normalized = withoutFragment.replace(/^intent:\/\//i, 'bitchord://');
    return parseCollabInviteLink(normalized);
  }

  // Custom scheme: bitchord://collab/invite/bci_...?server=...
  if (/^bitchord:\/\//i.test(input)) {
    try {
      const parsed = new URL(input);
      const host = parsed.host.toLowerCase();
      let candidateToken: string | null = null;
      if (host === 'collab') {
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
      const listId = parsed.searchParams.get('list')?.replace(/^VL/, '').trim();
      const jct = parsed.searchParams.get('jct')?.trim();
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
