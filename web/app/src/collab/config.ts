import { create } from 'zustand';
import { config } from '@/config';

const COLLAB_CONFIG_KEY = 'bitchord:collab-config:v1';

export interface StoredCollabConfig {
  serverUrl: string;
  googleClientId: string;
}

export function sanitizeCollabServerUrl(raw: string | null | undefined): string | null {
  const trimmed = raw?.trim().replace(/\/+$/, '');
  if (!trimmed) return null;
  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:(?!\d+(?:\/|$))/.test(trimmed) && !/^https?:\/\//i.test(trimmed)) {
    return null;
  }
  const withScheme = /^https?:\/\//i.test(trimmed)
    ? trimmed
    : /^(localhost|127\.0\.0\.1)(:\d+)?(\/.*)?$/i.test(trimmed)
      ? `http://${trimmed}`
      : `https://${trimmed}`;
  try {
    const parsed = new URL(withScheme);
    if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || !parsed.host || parsed.username) {
      return null;
    }
    return `${parsed.protocol}//${parsed.host}${parsed.pathname === '/' ? '' : parsed.pathname.replace(/\/+$/, '')}`;
  } catch {
    return null;
  }
}

export const DEFAULT_COLLAB_SERVER_URL = sanitizeCollabServerUrl(config.collabServerUrl) ?? '';
export const DEFAULT_GOOGLE_CLIENT_ID = config.googleClientId.trim();

function readStoredConfig(): StoredCollabConfig {
  try {
    const raw = localStorage.getItem(COLLAB_CONFIG_KEY);
    if (!raw) return { serverUrl: '', googleClientId: '' };
    const parsed = JSON.parse(raw) as Partial<StoredCollabConfig>;
    return {
      serverUrl: typeof parsed.serverUrl === 'string' ? parsed.serverUrl.trim() : '',
      googleClientId: typeof parsed.googleClientId === 'string' ? parsed.googleClientId.trim() : '',
    };
  } catch {
    return { serverUrl: '', googleClientId: '' };
  }
}

function writeStoredConfig(next: StoredCollabConfig): void {
  try {
    localStorage.setItem(COLLAB_CONFIG_KEY, JSON.stringify(next));
  } catch {
    // Ignore storage quota / private mode failures; keep in-memory state.
  }
}

export interface CollabConfigState extends StoredCollabConfig {
  setServerUrl: (serverUrl: string) => void;
  setGoogleClientId: (googleClientId: string) => void;
  adoptServerOrigin: (origin: string | null | undefined) => string | null;
}

export const useCollabConfig = create<CollabConfigState>()((set, get) => {
  const initial = readStoredConfig();
  return {
    ...initial,
    setServerUrl: (serverUrl: string) => {
      const next = { ...get(), serverUrl: serverUrl.trim() };
      writeStoredConfig({ serverUrl: next.serverUrl, googleClientId: next.googleClientId });
      set({ serverUrl: next.serverUrl });
    },
    setGoogleClientId: (googleClientId: string) => {
      const next = { ...get(), googleClientId: googleClientId.trim() };
      writeStoredConfig({ serverUrl: next.serverUrl, googleClientId: next.googleClientId });
      set({ googleClientId: next.googleClientId });
    },
    adoptServerOrigin: (origin: string | null | undefined) => {
      const clean = sanitizeCollabServerUrl(origin);
      if (!clean) return null;
      const current = sanitizeCollabServerUrl(get().serverUrl) ?? DEFAULT_COLLAB_SERVER_URL;
      if (!current) {
        writeStoredConfig({ serverUrl: clean, googleClientId: get().googleClientId });
        set({ serverUrl: clean });
      }
      return clean;
    },
  };
});

/** Effective Collaboration Server URL (override -> user setting -> env default -> current window origin). */
export function resolveCollabServerUrl(overrideOrigin?: string | null): string {
  const override = sanitizeCollabServerUrl(overrideOrigin);
  if (override) return override;
  const custom = sanitizeCollabServerUrl(useCollabConfig.getState().serverUrl);
  if (custom) return custom;
  if (DEFAULT_COLLAB_SERVER_URL) return DEFAULT_COLLAB_SERVER_URL;
  if (typeof window !== 'undefined' && /^https?:\/\//i.test(window.location?.origin ?? '')) {
    return window.location.origin.replace(/\/+$/, '');
  }
  return '';
}

/** Converts an HTTP(S) collab server base URL into its `/ws/collab` WebSocket URL. */
export function resolveCollabWsUrl(overrideOrigin?: string | null): string | null {
  const base = resolveCollabServerUrl(overrideOrigin);
  if (!base) return null;
  try {
    const url = new URL(base);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    url.pathname = `${url.pathname.replace(/\/+$/, '')}/ws/collab`;
    url.search = '';
    url.hash = '';
    return url.toString();
  } catch {
    return null;
  }
}

/** Effective Google OAuth 2.0 Web Client ID (user setting -> VITE_GOOGLE_CLIENT_ID). */
export function resolveGoogleClientId(): string {
  return useCollabConfig.getState().googleClientId.trim() || DEFAULT_GOOGLE_CLIENT_ID;
}
