import { AlertCircle, CheckCircle2, Users } from 'lucide-react';
import { useEffect } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router';
import {
  completeGoogleCodeSignIn,
  consumePendingPkceSession,
  useCollabAuth,
  useCollabSignInPrompt,
} from '@/collab/auth';
import { sanitizeCollabServerUrl, useCollabConfig } from '@/collab/config';
import { cleanCollabInviteToken } from '@/collab/inviteLink';
import { useAcceptCollabInvite, useCollabInvitePreview } from '@/collab/queries';
import { CollabAvatar, CollabCover } from '@/components/collab/CollabAvatars';
import { useArtworkColor } from '@/lib/artworkColor';
import { usePageTint } from '@/lib/usePageTint';

/**
 * Public/authenticated Collaborative Playlist Invite page (`/collab/invite/:token`).
 * Guests can preview playlist title, cover, owner, member count, and song count
 * without signing in, then sign in with Google to accept and collaborate.
 */
export default function CollabInvitePage() {
  const rawToken = decodeURIComponent(useParams().token ?? '');
  const token = cleanCollabInviteToken(rawToken) ?? '';
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const serverOverride = sanitizeCollabServerUrl(searchParams.get('server'));
  const adoptServerOrigin = useCollabConfig((s) => s.adoptServerOrigin);
  const user = useCollabAuth((s) => s.user);
  const requestSignIn = useCollabSignInPrompt((s) => s.requestSignIn);

  useEffect(() => {
    if (serverOverride) {
      adoptServerOrigin(serverOverride);
    }
  }, [serverOverride, adoptServerOrigin]);

  const preview = useCollabInvitePreview(token, serverOverride);
  const accept = useAcceptCollabInvite();
  usePageTint(useArtworkColor(preview.data?.artworkUrl ?? null));

  const joinPlaylist = () => {
    if (!token) return;
    const executeJoin = () => {
      accept.mutate(
        { token, serverOverride },
        {
          onSuccess: ({ playlist }) => {
            navigate(`/collab/${encodeURIComponent(playlist.id)}`, { replace: true });
          },
        },
      );
    };

    if (!user) {
      requestSignIn({
        reason: preview.data
          ? `Sign in with Google to join “${preview.data.title}” as ${preview.data.role === 'EDITOR' ? 'an Editor' : 'a Viewer'}.`
          : 'Sign in with Google to accept this collaborative playlist invite.',
        onSignedIn: () => executeJoin(),
      });
      return;
    }
    executeJoin();
  };

  if (!token) {
    return (
      <div className="mx-auto flex min-h-[70vh] max-w-[460px] flex-col items-center justify-center px-6 text-center">
        <AlertCircle size={32} className="mb-3 text-red" />
        <h1 className="type-title-1-emphasized">Invalid Invite Link</h1>
        <p className="mt-2 type-body-tall text-secondary">
          This collaborative playlist invite link is malformed. Ask the playlist owner for a fresh invite link.
        </p>
      </div>
    );
  }

  const data = preview.data;
  const unavailable = data?.revoked || data?.expired;

  return (
    <div className="mx-auto flex min-h-[75vh] max-w-[480px] flex-col items-center justify-center px-6 py-12">
      <div className=" flex w-full flex-col items-center rounded-[20px] bg-quinary p-7 text-center shadow-glass">
        <span className="mb-4 inline-flex items-center gap-1.5 rounded-pill bg-quaternary px-3 py-1 type-subhead-emphasized text-primary">
          <Users size={13} className="text-key" />
          <span>
            Collaborative Playlist
            {data ? ` · ${data.role === 'EDITOR' ? 'Editor Invite' : 'Viewer Invite'}` : ''}
          </span>
        </span>

        {preview.isPending && <p className="py-8 type-body text-secondary">Loading playlist invite…</p>}

        {preview.isError && (
          <div className="py-4">
            <AlertCircle size={28} className="mx-auto mb-2 text-red" />
            <h1 className="type-title-2-emphasized">Invite Not Found</h1>
            <p className="mt-1.5 type-body-tall text-secondary">
              {preview.error instanceof Error ? preview.error.message : 'Could not load this collaborative invite.'}
            </p>
          </div>
        )}

        {data && (
          <>
            <div className="mb-5 w-[180px] rounded-artwork-lg shadow-[0_10px_24px_var(--radiosityShadowColor)]">
              <CollabCover
                artworkUrl={data.artworkUrl}
                title={data.title}
                displayWidth={180}
                radius="var(--radius-artwork-large)"
                eager
              />
            </div>

            <h1 className="type-title-1-emphasized">{data.title}</h1>
            {data.description && <p className="mt-1.5 line-clamp-2 type-body text-secondary">{data.description}</p>}

            <div className="mt-3 flex items-center justify-center gap-2 type-callout-emphasized text-secondary">
              <CollabAvatar src={data.ownerAvatarUrl} name={data.ownerName} size={22} />
              <span>
                Curated by {data.ownerName} · {data.trackCount} {data.trackCount === 1 ? 'song' : 'songs'} ·{' '}
                {data.memberCount} {data.memberCount === 1 ? 'collaborator' : 'collaborators'}
              </span>
            </div>

            {unavailable ? (
              <div className="mt-5 w-full rounded-[12px] bg-red/12 px-4 py-3 type-callout text-red">
                {data.revoked
                  ? 'This invite link was revoked by the playlist owner.'
                  : 'This invite link has expired.'}
              </div>
            ) : (
              <>
                {accept.isError && (
                  <p className="mt-3 rounded-[10px] bg-red/12 px-3 py-2 type-callout text-red">
                    {accept.error instanceof Error ? accept.error.message : 'Failed to join playlist.'}
                  </p>
                )}
                <button
                  type="button"
                  disabled={accept.isPending}
                  onClick={joinPlaylist}
                  className="mt-6 inline-flex h-11 w-full items-center justify-center gap-2 rounded-pill bg-key-bg px-6 type-title-3-emphasized text-white hover:opacity-95 disabled:opacity-45"
                >
                  <CheckCircle2 size={18} />
                  <span>
                    {accept.isPending
                      ? 'Joining Playlist…'
                      : user
                        ? 'Join Collaborative Playlist'
                        : 'Sign in with Google to Join'}
                  </span>
                </button>
              </>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/**
 * Handles Google OAuth 2.0 Authorization Code + PKCE S256 callback (`/collab/auth/callback`)
 * for both popup windows (`window.opener.postMessage`) and full-page redirects.
 */
export function CollabAuthCallbackPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  useEffect(() => {
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    const error = searchParams.get('error');

    if (window.opener && window.opener !== window) {
      try {
        window.opener.postMessage(
          { type: 'bitchord:collab-oauth-callback', code, state, error },
          window.location.origin,
        );
        window.close();
        return;
      } catch {
        // Fall through to redirect completion.
      }
    }

    const pending = consumePendingPkceSession(state);
    if (error || !code || !pending) {
      navigate('/library/playlists', { replace: true });
      return;
    }

    void completeGoogleCodeSignIn({
      code,
      codeVerifier: pending.codeVerifier,
      redirectUri: pending.redirectUri,
      clientId: pending.clientId,
    })
      .then(() => {
        navigate(pending.returnTo || '/library/playlists', { replace: true });
      })
      .catch(() => {
        navigate('/library/playlists', { replace: true });
      });
  }, [navigate, searchParams]);

  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <p className="type-body text-secondary">Completing Google sign-in…</p>
    </div>
  );
}
