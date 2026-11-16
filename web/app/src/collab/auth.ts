import { create } from 'zustand';
import { isNativeYtmCollabMode, resolveCollabServerUrl, resolveGoogleClientId } from './config';
import type { CollabUser } from './types';

const SESSION_KEY = 'bitchord:collab-session:v1';
const PKCE_STATE_KEY = 'bitchord:collab-pkce:v1';
const GIS_SCRIPT_URL = 'https://accounts.google.com/gsi/client';

export interface StoredCollabSession {
  token: string;
  expiresAtMs: number;
  user: CollabUser;
  serverUrl: string;
}

export class CollabAuthError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status = 401,
  ) {
    super(message);
    this.name = 'CollabAuthError';
  }
}

function normalizeUser(raw: unknown): CollabUser | null {
  if (!raw || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const id = typeof r.id === 'string' ? r.id.trim() : '';
  const email = typeof r.email === 'string' ? r.email.trim() : '';
  const displayName = typeof r.displayName === 'string' ? r.displayName.trim() : '';
  if (!id || !displayName) return null;
  return {
    id,
    email,
    emailVerified: Boolean(r.emailVerified),
    displayName,
    avatarUrl: typeof r.avatarUrl === 'string' && r.avatarUrl.trim() ? r.avatarUrl.trim() : null,
    createdAtMs: typeof r.createdAtMs === 'number' ? r.createdAtMs : 0,
    updatedAtMs: typeof r.updatedAtMs === 'number' ? r.updatedAtMs : 0,
  };
}

function readStoredSession(): StoredCollabSession | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<StoredCollabSession>;
    const token = typeof parsed.token === 'string' ? parsed.token.trim() : '';
    const expiresAtMs = typeof parsed.expiresAtMs === 'number' ? parsed.expiresAtMs : 0;
    const user = normalizeUser(parsed.user);
    if (!token.startsWith('bcs_') || !user) return null;
    if (expiresAtMs > 0 && Date.now() >= expiresAtMs) {
      localStorage.removeItem(SESSION_KEY);
      return null;
    }
    return {
      token,
      expiresAtMs,
      user,
      serverUrl: typeof parsed.serverUrl === 'string' ? parsed.serverUrl : '',
    };
  } catch {
    return null;
  }
}

function writeStoredSession(session: StoredCollabSession | null): void {
  try {
    if (!session) localStorage.removeItem(SESSION_KEY);
    else localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  } catch {
    // Ignore storage failures.
  }
}

export interface CollabAuthState {
  token: string | null;
  expiresAtMs: number;
  user: CollabUser | null;
  serverUrl: string;
  verifying: boolean;
  signingIn: boolean;
  error: string | null;
  setSession: (session: StoredCollabSession) => void;
  clearSession: (error?: string | null) => void;
  clearError: () => void;
}

export const useCollabAuth = create<CollabAuthState>()((set) => {
  const initial = readStoredSession();
  return {
    token: initial?.token ?? null,
    expiresAtMs: initial?.expiresAtMs ?? 0,
    user: initial?.user ?? null,
    serverUrl: initial?.serverUrl ?? '',
    verifying: false,
    signingIn: false,
    error: null,
    setSession: (session) => {
      writeStoredSession(session);
      set({
        token: session.token,
        expiresAtMs: session.expiresAtMs,
        user: session.user,
        serverUrl: session.serverUrl,
        verifying: false,
        signingIn: false,
        error: null,
      });
    },
    clearSession: (error = null) => {
      writeStoredSession(null);
      set({
        token: null,
        expiresAtMs: 0,
        user: null,
        serverUrl: '',
        verifying: false,
        signingIn: false,
        error,
      });
    },
    clearError: () => set({ error: null }),
  };
});

export function useCollabSignedIn(): boolean {
  return useCollabAuth((s) => s.user !== null && Boolean(s.token));
}

// ---- Sign-In Prompt Modal Store (guest-safe, on-demand only) -----------------------

export interface CollabSignInPromptState {
  open: boolean;
  reason: string;
  onSignedIn: ((user: CollabUser) => void) | null;
  requestSignIn: (options?: { reason?: string; onSignedIn?: (user: CollabUser) => void }) => void;
  closeSignIn: () => void;
}

export const useCollabSignInPrompt = create<CollabSignInPromptState>()((set) => ({
  open: false,
  reason: 'Sign in with Google to create, convert, or join collaborative playlists.',
  onSignedIn: null,
  requestSignIn: (options) => {
    const existing = useCollabAuth.getState().user;
    if (existing && useCollabAuth.getState().token) {
      options?.onSignedIn?.(existing);
      return;
    }
    set({
      open: true,
      reason: options?.reason ?? 'Sign in with Google to create, convert, or join collaborative playlists.',
      onSignedIn: options?.onSignedIn ?? null,
    });
  },
  closeSignIn: () => set({ open: false, onSignedIn: null }),
}));

// ---- Backend Auth HTTP Calls -------------------------------------------------------

async function authFetchJson<T>(
  path: string,
  init: RequestInit,
  serverOverride?: string | null,
): Promise<{ data: T; baseUrl: string }> {
  const baseUrl = resolveCollabServerUrl(serverOverride);
  if (!baseUrl) {
    throw new CollabAuthError('missing_server', 'Configure a Collaboration Server URL in Settings first.', 400);
  }
  let res: Response;
  try {
    res = await fetch(`${baseUrl}${path}`, {
      ...init,
      headers: {
        Accept: 'application/json',
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...(init.headers ?? {}),
      },
    });
  } catch {
    throw new CollabAuthError('network_error', 'Could not reach the BitChord collaboration server.', 503);
  }
  let payload: Record<string, unknown> = {};
  try {
    payload = (await res.json()) as Record<string, unknown>;
  } catch {
    // Non-JSON response
  }
  if (!res.ok) {
    const code = typeof payload.error === 'string' ? payload.error : 'auth_failed';
    const msg =
      typeof payload.message === 'string' ? payload.message : `Authentication failed (HTTP ${res.status}).`;
    throw new CollabAuthError(code, msg, res.status);
  }
  return { data: payload as unknown as T, baseUrl };
}

interface GoogleAuthWireResponse {
  token: string;
  expiresAtMs: number;
  user: unknown;
}

export async function completeGoogleIdTokenSignIn(idToken: string, serverOverride?: string | null): Promise<CollabUser> {
  useCollabAuth.setState({ signingIn: true, error: null });
  try {
    const { data, baseUrl } = await authFetchJson<GoogleAuthWireResponse>(
      '/api/collab/auth/google',
      {
        method: 'POST',
        body: JSON.stringify({ idToken: idToken.trim() }),
      },
      serverOverride,
    );
    const user = normalizeUser(data.user);
    if (!data.token || !user) {
      throw new CollabAuthError('invalid_auth_response', 'Server returned an incomplete session response.', 502);
    }
    useCollabAuth.getState().setSession({
      token: data.token,
      expiresAtMs: data.expiresAtMs,
      user,
      serverUrl: baseUrl,
    });
    const cb = useCollabSignInPrompt.getState().onSignedIn;
    useCollabSignInPrompt.getState().closeSignIn();
    cb?.(user);
    return user;
  } catch (err) {
    const msg = err instanceof Error ? err.message : 'Google sign-in failed.';
    useCollabAuth.setState({ signingIn: false, error: msg });
    throw err;
  }
}

export async function completeGoogleCodeSignIn(params: {
  code: string;
  codeVerifier: string;
  redirectUri: string;
  clientId: string;
  serverOverride?: string | null;
}): Promise<CollabUser> {
  useCollabAuth.setState({ signingIn: true, error: null });
  try {
    const { data, baseUrl } = await authFetchJson<GoogleAuthWireResponse>(
      '/api/collab/auth/google',
      {
        method: 'POST',
        body: JSON.stringify({
          code: params.code,
          codeVerifier: params.codeVerifier,
          redirectUri: params.redirectUri,
          clientId: params.clientId,
        }),
      },
      params.serverOverride,
    );
    const user = normalizeUser(data.user);
    if (!data.token || !user) {
      throw new CollabAuthError('invalid_auth_response', 'Server returned an incomplete session response.', 502);
    }
    useCollabAuth.getState().setSession({
      token: data.token,
      expiresAtMs: data.expiresAtMs,
      user,
      serverUrl: baseUrl,
    });
    const cb = useCollabSignInPrompt.getState().onSignedIn;
    useCollabSignInPrompt.getState().closeSignIn();
    cb?.(user);
    return user;
  } catch (err) {
    const msg = err instanceof Error ? err.message : 'Google authorization code exchange failed.';
    useCollabAuth.setState({ signingIn: false, error: msg });
    throw err;
  }
}

let initializedOnce = false;

/** Verifies any stored Bearer session against `GET /api/collab/auth/me` on app startup. */
export async function verifyStoredCollabSession(): Promise<CollabUser | null> {
  const { token, serverUrl } = useCollabAuth.getState();
  if (!token || token === YTM_WEB_SESSION_TOKEN) return useCollabAuth.getState().user;
  useCollabAuth.setState({ verifying: true });
  try {
    const { data, baseUrl } = await authFetchJson<{ user: unknown; expiresAtMs: number }>(
      '/api/collab/auth/me',
      {
        method: 'GET',
        headers: { Authorization: `Bearer ${token}` },
      },
      serverUrl || null,
    );
    const user = normalizeUser(data.user);
    if (!user) {
      useCollabAuth.getState().clearSession();
      return null;
    }
    useCollabAuth.getState().setSession({
      token,
      expiresAtMs: typeof data.expiresAtMs === 'number' ? data.expiresAtMs : useCollabAuth.getState().expiresAtMs,
      user,
      serverUrl: baseUrl,
    });
    return user;
  } catch (err) {
    if (err instanceof CollabAuthError && (err.status === 401 || err.status === 403)) {
      useCollabAuth.getState().clearSession();
      return null;
    }
    // Keep cached session if offline / transient network failure.
    useCollabAuth.setState({ verifying: false });
    return useCollabAuth.getState().user;
  }
}

export function initCollabAuth(): void {
  if (initializedOnce) return;
  initializedOnce = true;
  if (useCollabAuth.getState().token) {
    void verifyStoredCollabSession();
  }
}

export const YTM_WEB_SESSION_TOKEN = 'ytm_native_web_session';

/** Syncs the browser extension's signed-in Google / YouTube Music account into Collaborative Playlists. */
export function syncYtExtensionToCollab(params: {
  signedIn: boolean;
  id?: string | null;
  name?: string | null;
  email?: string | null;
  avatarUrl?: string | null;
}): void {
  if (!isNativeYtmCollabMode()) return;
  const current = useCollabAuth.getState();
  if (!params.signedIn || !params.name) {
    if (current.token === YTM_WEB_SESSION_TOKEN) {
      useCollabAuth.setState({ token: null, expiresAtMs: 0, user: null, serverUrl: '' });
    }
    return;
  }
  const nextUser: CollabUser = {
    id: `ytm:${params.id || params.name}`,
    email: params.email ?? '',
    emailVerified: true,
    displayName: params.name,
    avatarUrl: params.avatarUrl ?? null,
    createdAtMs: 0,
    updatedAtMs: Date.now(),
  };
  if (current.token !== YTM_WEB_SESSION_TOKEN || current.user?.id !== nextUser.id) {
    useCollabAuth.setState({
      token: YTM_WEB_SESSION_TOKEN,
      expiresAtMs: 0,
      user: nextUser,
      serverUrl: '',
      verifying: false,
      signingIn: false,
      error: null,
    });
  }
}

export async function signOutCollab(): Promise<void> {
  const { token, serverUrl } = useCollabAuth.getState();
  useCollabAuth.getState().clearSession();
  if (!token) return;
  try {
    await authFetchJson(
      '/api/collab/auth/logout',
      {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
      },
      serverUrl || null,
    );
  } catch {
    // Local session is already cleared.
  }
}

// ---- Google Identity Services (GIS) Loader -----------------------------------------

interface GisCredentialResponse {
  credential?: string;
}

interface GisAccountsId {
  initialize: (config: {
    client_id: string;
    callback: (response: GisCredentialResponse) => void;
    ux_mode?: 'popup' | 'redirect';
    auto_select?: boolean;
    cancel_on_tap_outside?: boolean;
  }) => void;
  renderButton: (
    parent: HTMLElement,
    options: {
      type?: 'standard' | 'icon';
      theme?: 'outline' | 'filled_blue' | 'filled_black';
      size?: 'large' | 'medium' | 'small';
      text?: 'signin_with' | 'signup_with' | 'continue_with' | 'signin';
      shape?: 'pill' | 'rectangular' | 'circle' | 'square';
      width?: number;
    },
  ) => void;
  prompt: () => void;
}

declare global {
  interface Window {
    google?: {
      accounts?: {
        id?: GisAccountsId;
      };
    };
  }
}

let gisLoadPromise: Promise<GisAccountsId | null> | null = null;

export function loadGoogleIdentityServices(): Promise<GisAccountsId | null> {
  if (typeof window === 'undefined' || typeof document === 'undefined') return Promise.resolve(null);
  if (window.google?.accounts?.id) return Promise.resolve(window.google.accounts.id);
  if (gisLoadPromise) return gisLoadPromise;

  gisLoadPromise = new Promise<GisAccountsId | null>((resolve) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GIS_SCRIPT_URL}"]`);
    if (existing) {
      existing.addEventListener('load', () => resolve(window.google?.accounts?.id ?? null), { once: true });
      existing.addEventListener('error', () => resolve(null), { once: true });
      return;
    }
    const script = document.createElement('script');
    script.src = GIS_SCRIPT_URL;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve(window.google?.accounts?.id ?? null);
    script.onerror = () => {
      gisLoadPromise = null;
      resolve(null);
    };
    document.head.appendChild(script);
  });
  return gisLoadPromise;
}

// ---- OAuth 2.0 Authorization Code + PKCE S256 Flow ---------------------------------

export interface PkcePendingSession {
  state: string;
  codeVerifier: string;
  redirectUri: string;
  clientId: string;
  returnTo: string;
  createdAtMs: number;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]!);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function generatePkceVerifier(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

export async function derivePkceChallengeS256(codeVerifier: string): Promise<string> {
  const data = new TextEncoder().encode(codeVerifier);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return base64UrlEncode(new Uint8Array(digest));
}

export function getGoogleRedirectUri(): string {
  if (typeof window === 'undefined') return 'http://localhost:5173/collab/auth/callback';
  return `${window.location.origin}/collab/auth/callback`;
}

export async function startGooglePkceSignIn(options?: {
  mode?: 'popup' | 'redirect';
  returnTo?: string;
}): Promise<CollabUser | null> {
  const clientId = resolveGoogleClientId();
  if (!clientId) {
    const err = new CollabAuthError(
      'missing_client_id',
      'Configure your Google OAuth Client ID in Settings first.',
      400,
    );
    useCollabAuth.setState({ error: err.message });
    throw err;
  }

  const codeVerifier = generatePkceVerifier();
  const codeChallenge = await derivePkceChallengeS256(codeVerifier);
  const stateBytes = new Uint8Array(16);
  crypto.getRandomValues(stateBytes);
  const state = base64UrlEncode(stateBytes);
  const redirectUri = getGoogleRedirectUri();
  const returnTo =
    options?.returnTo ??
    (typeof window !== 'undefined' ? `${window.location.pathname}${window.location.search}` : '/library/playlists');

  const pending: PkcePendingSession = {
    state,
    codeVerifier,
    redirectUri,
    clientId,
    returnTo,
    createdAtMs: Date.now(),
  };
  sessionStorage.setItem(PKCE_STATE_KEY, JSON.stringify(pending));

  const authUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  authUrl.searchParams.set('client_id', clientId);
  authUrl.searchParams.set('redirect_uri', redirectUri);
  authUrl.searchParams.set('response_type', 'code');
  authUrl.searchParams.set('scope', 'openid email profile');
  authUrl.searchParams.set('code_challenge', codeChallenge);
  authUrl.searchParams.set('code_challenge_method', 'S256');
  authUrl.searchParams.set('state', state);
  authUrl.searchParams.set('prompt', 'select_account');

  if (options?.mode === 'redirect') {
    window.location.assign(authUrl.toString());
    return null;
  }

  const width = 500;
  const height = 620;
  const left = Math.max(0, Math.round(window.screenX + (window.outerWidth - width) / 2));
  const top = Math.max(0, Math.round(window.screenY + (window.outerHeight - height) / 2));
  const popup = window.open(
    authUrl.toString(),
    'bitchord-google-oauth',
    `popup=yes,width=${width},height=${height},left=${left},top=${top}`,
  );

  // Fallback to redirect flow if the browser blocked the popup.
  if (!popup) {
    window.location.assign(authUrl.toString());
    return null;
  }

  useCollabAuth.setState({ signingIn: true, error: null });

  return new Promise<CollabUser | null>((resolve, reject) => {
    let settled = false;
    const closePoll = window.setInterval(() => {
      if (popup.closed && !settled) {
        settled = true;
        cleanup();
        useCollabAuth.setState({ signingIn: false });
        resolve(null);
      }
    }, 400);

    const onMessage = (event: MessageEvent) => {
      if (event.origin !== window.location.origin) return;
      const data = event.data as
        | { type?: string; code?: string; state?: string; error?: string }
        | undefined;
      if (!data || data.type !== 'bitchord:collab-oauth-callback') return;
      settled = true;
      cleanup();
      if (data.error) {
        const err = new CollabAuthError('oauth_cancelled', `Google sign-in failed: ${data.error}`, 401);
        useCollabAuth.setState({ signingIn: false, error: err.message });
        reject(err);
        return;
      }
      if (!data.code || data.state !== pending.state) {
        const err = new CollabAuthError('oauth_state_mismatch', 'OAuth state verification failed. Try again.', 401);
        useCollabAuth.setState({ signingIn: false, error: err.message });
        reject(err);
        return;
      }
      sessionStorage.removeItem(PKCE_STATE_KEY);
      void completeGoogleCodeSignIn({
        code: data.code,
        codeVerifier: pending.codeVerifier,
        redirectUri: pending.redirectUri,
        clientId: pending.clientId,
      }).then(resolve, reject);
    };

    function cleanup() {
      window.clearInterval(closePoll);
      window.removeEventListener('message', onMessage);
    }

    window.addEventListener('message', onMessage);
  });
}

export function consumePendingPkceSession(stateParam: string | null): PkcePendingSession | null {
  try {
    const raw = sessionStorage.getItem(PKCE_STATE_KEY);
    if (!raw) return null;
    sessionStorage.removeItem(PKCE_STATE_KEY);
    const parsed = JSON.parse(raw) as PkcePendingSession;
    if (!stateParam || parsed.state !== stateParam) return null;
    if (Date.now() - parsed.createdAtMs > 15 * 60_000) return null;
    return parsed;
  } catch {
    return null;
  }
}
