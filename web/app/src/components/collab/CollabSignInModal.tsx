import { ShieldCheck, SlidersHorizontal, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  completeGoogleIdTokenSignIn,
  loadGoogleIdentityServices,
  startGooglePkceSignIn,
  useCollabAuth,
  useCollabSignInPrompt,
} from '@/collab/auth';
import {
  DEFAULT_COLLAB_SERVER_URL,
  DEFAULT_GOOGLE_CLIENT_ID,
  resolveCollabServerUrl,
  resolveGoogleClientId,
  useCollabConfig,
} from '@/collab/config';

/**
 * Global Google OIDC Sign-In modal host. Guests never see this during normal
 * browsing, search, playback, or Listen Together — only when explicitly
 * creating, converting, or joining a collaborative playlist.
 */
export function CollabSignInModalHost() {
  const open = useCollabSignInPrompt((s) => s.open);
  const reason = useCollabSignInPrompt((s) => s.reason);
  const closeSignIn = useCollabSignInPrompt((s) => s.closeSignIn);

  if (!open) return null;
  return <CollabSignInDialog reason={reason} onClose={closeSignIn} />;
}

function CollabSignInDialog({ reason, onClose }: { reason: string; onClose: () => void }) {
  const signingIn = useCollabAuth((s) => s.signingIn);
  const error = useCollabAuth((s) => s.error);
  const clearError = useCollabAuth((s) => s.clearError);
  const configState = useCollabConfig();

  const effectiveClientId = resolveGoogleClientId();
  const effectiveServer = resolveCollabServerUrl();
  const [showConfig, setShowConfig] = useState(!effectiveClientId);
  const [serverDraft, setServerDraft] = useState(configState.serverUrl);
  const [clientDraft, setClientDraft] = useState(configState.googleClientId);
  const gisContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    clearError();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [clearError, onClose]);

  useEffect(() => {
    const container = gisContainerRef.current;
    if (!container || !effectiveClientId) return;
    let cancelled = false;
    container.innerHTML = '';

    void loadGoogleIdentityServices().then((gis) => {
      if (cancelled || !gis || !gisContainerRef.current) return;
      try {
        gis.initialize({
          client_id: effectiveClientId,
          ux_mode: 'popup',
          callback: (resp) => {
            if (resp.credential) {
              void completeGoogleIdTokenSignIn(resp.credential);
            }
          },
        });
        gis.renderButton(gisContainerRef.current, {
          type: 'standard',
          theme: 'filled_black',
          size: 'large',
          text: 'continue_with',
          shape: 'pill',
          width: 280,
        });
      } catch {
        // Fallback PKCE button below remains active.
      }
    });

    return () => {
      cancelled = true;
    };
  }, [effectiveClientId]);

  const applyConfig = () => {
    configState.setServerUrl(serverDraft);
    configState.setGoogleClientId(clientDraft);
    if (clientDraft.trim() || DEFAULT_GOOGLE_CLIENT_ID) {
      setShowConfig(false);
    }
  };

  return createPortal(
    <div
      className="fixed inset-0 z-(--z-modal) grid animate-fade place-items-center bg-scrim p-4"
      onPointerDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <div
        className="flex max-h-[min(680px,calc(100vh-32px))] w-[min(440px,calc(100vw-32px))] animate-zoom flex-col overflow-hidden rounded-[14px] bg-page shadow-glass"
        role="dialog"
        aria-modal="true"
        aria-label="Sign in for Collaborative Playlists"
      >
        <header className="flex items-center justify-between px-5 pt-[18px] pb-2">
          <div className="flex items-center gap-2">
            <ShieldCheck size={18} className="text-key" />
            <h2 className="type-title-2-emphasized">Collaborative Playlists</h2>
          </div>
          <button
            type="button"
            className="grid size-[30px] place-items-center rounded-full bg-quaternary text-secondary hover:text-primary"
            aria-label="Close"
            onClick={onClose}
          >
            <X size={16} />
          </button>
        </header>

        <div className="overflow-y-auto px-5 pb-5">
          <p className="mt-1 type-body-tall text-secondary">{reason}</p>

          <div className="mt-3.5 rounded-[10px] bg-quinary px-3.5 py-3 type-callout text-secondary">
            BitChord verifies your Google account so every song added to a shared playlist shows its real contributor.
            Browsing, playback, and Listen Together never need an account.
          </div>

          {error && (
            <div className="mt-3 rounded-[10px] bg-red/12 px-3.5 py-2.5 type-callout text-red" role="alert">
              {error}
            </div>
          )}

          <div className="mt-5 flex flex-col items-center gap-2.5">
            {effectiveClientId && <div ref={gisContainerRef} className="min-h-[40px]" />}

            <button
              type="button"
              disabled={signingIn || !effectiveClientId}
              onClick={() => void startGooglePkceSignIn({ mode: 'popup' }).catch(() => {})}
              className="inline-flex h-10 w-full max-w-[280px] items-center justify-center gap-2 rounded-pill bg-key-bg px-5 type-body-emphasized text-white hover:opacity-95 disabled:opacity-45"
            >
              <GoogleGlyph />
              <span>{signingIn ? 'Signing in with Google…' : 'Continue with Google (OAuth PKCE)'}</span>
            </button>

            {effectiveClientId && (
              <button
                type="button"
                disabled={signingIn}
                onClick={() => void startGooglePkceSignIn({ mode: 'redirect' }).catch(() => {})}
                className="type-callout text-secondary hover:text-primary hover:underline"
              >
                Popup blocked? Sign in via full-page redirect
              </button>
            )}
          </div>

          <div className="mt-5 border-t-[0.5px] border-t-divider pt-3">
            <button
              type="button"
              onClick={() => setShowConfig((v) => !v)}
              className="inline-flex items-center gap-1.5 type-callout-emphasized text-secondary hover:text-primary"
            >
              <SlidersHorizontal size={13} />
              <span>{showConfig ? 'Hide server & OAuth setup' : 'Server & OAuth setup'}</span>
            </button>

            {showConfig && (
              <div className="mt-3 flex flex-col gap-2.5 rounded-[10px] bg-quinary p-3">
                <label className="flex flex-col gap-1">
                  <span className="type-subhead-emphasized text-secondary">Collaboration Server URL</span>
                  <input
                    className="rounded-[8px] border-0 bg-quaternary px-2.5 py-1.5 type-body text-primary"
                    placeholder={DEFAULT_COLLAB_SERVER_URL || effectiveServer || 'https://collab.example.com'}
                    value={serverDraft}
                    onChange={(e) => setServerDraft(e.currentTarget.value)}
                  />
                </label>
                <label className="flex flex-col gap-1">
                  <span className="type-subhead-emphasized text-secondary">Google OAuth Client ID</span>
                  <input
                    className="rounded-[8px] border-0 bg-quaternary px-2.5 py-1.5 type-body text-primary"
                    placeholder={DEFAULT_GOOGLE_CLIENT_ID || '1234567890-abc.apps.googleusercontent.com'}
                    value={clientDraft}
                    onChange={(e) => setClientDraft(e.currentTarget.value)}
                  />
                </label>
                <button
                  type="button"
                  onClick={applyConfig}
                  className="self-end rounded-pill bg-quaternary px-3.5 py-1 type-callout-emphasized text-primary hover:opacity-90"
                >
                  Save Setup
                </button>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
}

function GoogleGlyph() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <path
        fill="#fff"
        d="M21.35 11.1H12v2.92h5.36c-.23 1.38-1.02 2.55-2.18 3.33v2.76h3.53c2.07-1.9 3.26-4.7 3.26-8.04 0-.65-.06-1.28-.18-1.97z"
      />
      <path
        fill="#fff"
        fillOpacity="0.85"
        d="M12 21c2.95 0 5.43-.98 7.24-2.65l-3.53-2.76c-.98.66-2.23 1.06-3.71 1.06-2.85 0-5.27-1.92-6.13-4.51H2.22v2.85A10.99 10.99 0 0 0 12 21z"
      />
      <path
        fill="#fff"
        fillOpacity="0.7"
        d="M5.87 12.14a6.6 6.6 0 0 1 0-4.28V5.01H2.22a11 11 0 0 0 0 9.98l3.65-2.85z"
      />
      <path
        fill="#fff"
        fillOpacity="0.95"
        d="M12 5.35c1.6 0 3.04.55 4.18 1.63l3.13-3.13C17.42 2.09 14.95 1 12 1A10.99 10.99 0 0 0 2.22 5.01l3.65 2.85C6.73 5.27 9.15 5.35 12 5.35z"
      />
    </svg>
  );
}
