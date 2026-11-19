import { Check, Copy, Crown, Link2, Lock, ShieldAlert, Trash2, UserMinus, Users, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router';
import { useCollabAuth, useCollabSignInPrompt } from '@/collab/auth';
import { formatCollabInviteUrl, parseCollabInviteLink } from '@/collab/inviteLink';
import {
  useCollabInvites,
  useConvertCollabPlaylist,
  useCreateCollabInvite,
  useCreateCollabPlaylist,
  useDeleteCollabPlaylist,
  useLeaveCollabPlaylist,
  useRemoveCollabMember,
  useRevokeCollabInvite,
  useStopSharingCollabPlaylist,
  useTransferCollabOwnership,
  useUpdateCollabMemberRole,
  useUpdateCollabPlaylist,
} from '@/collab/queries';
import { useCollabConfig } from '@/collab/config';
import {
  buildConvertPayload,
  DEFAULT_COLLAB_PERMISSIONS,
  evaluateCollabPermissions,
  type CollabPermissions,
  type CollabPlaylistDetail,
  type CollabRole,
  type CollabSourceType,
} from '@/collab/types';
import { Select } from '@/components/Select';
import { Artwork } from '@/components/music/Artwork';
import type { Track } from '@/data/music/types';
import { settingsUi } from '@/pages/settingsStyles';
import { CollabAvatar } from './CollabAvatars';

function useEscapeClose(onClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
}

function ModalShell({
  title,
  onClose,
  maxWidth = '500px',
  children,
}: {
  title: string;
  onClose: () => void;
  maxWidth?: string;
  children: React.ReactNode;
}) {
  useEscapeClose(onClose);
  return createPortal(
    <div
      className="fixed inset-0 z-(--z-modal) grid animate-fade place-items-center bg-scrim p-4"
      onPointerDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <div
        className="flex max-h-[min(740px,calc(100vh-32px))] animate-zoom flex-col overflow-hidden rounded-[14px] bg-page shadow-glass"
        style={{ width: `min(${maxWidth}, calc(100vw - 32px))` }}
        role="dialog"
        aria-modal="true"
        aria-label={title}
      >
        <header className="flex items-center justify-between px-5 pt-[18px] pb-2">
          <h2 className="type-title-2-emphasized">{title}</h2>
          <button
            type="button"
            className="grid size-[30px] place-items-center rounded-full bg-quaternary text-secondary hover:text-primary"
            aria-label="Close"
            onClick={onClose}
          >
            <X size={16} />
          </button>
        </header>
        <div className="overflow-y-auto px-5 pb-5">{children}</div>
      </div>
    </div>,
    document.body,
  );
}

function PermissionsControls({
  permissions,
  onChange,
  disabled = false,
}: {
  permissions: CollabPermissions;
  onChange: (next: CollabPermissions) => void;
  disabled?: boolean;
}) {
  return (
    <div className="overflow-hidden rounded-[10px] bg-quinary">
      <label className="flex items-center justify-between gap-3 px-3.5 py-2.5 type-body">
        <span className="flex flex-col">
          <span className="type-body-emphasized">Editors can remove</span>
          <span className="type-callout text-secondary">Control whether editors can remove others’ songs.</span>
        </span>
        <Select<'any' | 'own'>
          label="Editors can remove"
          value={permissions.editorCanRemove}
          options={[
            { value: 'any', label: 'Any song' },
            { value: 'own', label: 'Only their own' },
          ]}
          onChange={(val) => !disabled && onChange({ ...permissions, editorCanRemove: val })}
        />
      </label>
      <label className="flex cursor-pointer items-center justify-between gap-3 border-t-[0.5px] border-t-divider px-3.5 py-2.5 type-body">
        <span className="flex flex-col">
          <span className="type-body-emphasized">Editors can reorder songs</span>
          <span className="type-callout text-secondary">Allow editors to arrange track order.</span>
        </span>
        <input
          type="checkbox"
          className={settingsUi.switch}
          checked={permissions.editorCanReorder}
          disabled={disabled}
          onChange={(e) => onChange({ ...permissions, editorCanReorder: e.currentTarget.checked })}
        />
      </label>
      <label className="flex cursor-pointer items-center justify-between gap-3 border-t-[0.5px] border-t-divider px-3.5 py-2.5 type-body">
        <span className="flex flex-col">
          <span className="type-body-emphasized">Editors can invite collaborators</span>
          <span className="type-callout text-secondary">Let editors generate shareable invite links.</span>
        </span>
        <input
          type="checkbox"
          className={settingsUi.switch}
          checked={permissions.editorCanInvite}
          disabled={disabled}
          onChange={(e) => onChange({ ...permissions, editorCanInvite: e.currentTarget.checked })}
        />
      </label>
    </div>
  );
}

// ---- 1. Create Collaborative Playlist Modal ----------------------------------------

export function CreateCollabPlaylistModal({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const user = useCollabAuth((s) => s.user);
  const requestSignIn = useCollabSignInPrompt((s) => s.requestSignIn);
  const create = useCreateCollabPlaylist();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [permissions, setPermissions] = useState<CollabPermissions>({ ...DEFAULT_COLLAB_PERMISSIONS });

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim()) return;
    const run = () => {
      create.mutate(
        { title: title.trim(), description: description.trim(), permissions },
        {
          onSuccess: (detail) => {
            onClose();
            navigate(`/collab/${encodeURIComponent(detail.id)}`);
          },
        },
      );
    };
    if (!user) {
      requestSignIn({
        reason: 'Sign in with Google to create and own a collaborative playlist.',
        onSignedIn: () => run(),
      });
      return;
    }
    run();
  };

  return (
    <ModalShell title="New Collaborative Playlist" onClose={onClose}>
      <form onSubmit={submit} className="mt-2 flex flex-col gap-4">
        <div className="flex flex-col gap-2.5">
          <input
            autoFocus
            className="w-full rounded-[10px] border-0 bg-quinary px-3.5 py-2.5 type-title-3 text-primary placeholder:text-tertiary"
            placeholder="Playlist Title"
            maxLength={200}
            value={title}
            onChange={(e) => setTitle(e.currentTarget.value)}
          />
          <textarea
            rows={2}
            className="w-full resize-none rounded-[10px] border-0 bg-quinary px-3.5 py-2 type-body text-primary placeholder:text-tertiary"
            placeholder="Description (optional)"
            maxLength={2000}
            value={description}
            onChange={(e) => setDescription(e.currentTarget.value)}
          />
        </div>

        <div>
          <h3 className="mx-1 mb-1.5 type-subhead-emphasized uppercase text-secondary">Collaborator Permissions</h3>
          <PermissionsControls permissions={permissions} onChange={setPermissions} />
        </div>

        {create.isError && (
          <p className="rounded-[10px] bg-red/12 px-3 py-2 type-callout text-red">
            {create.error instanceof Error ? create.error.message : 'Failed to create playlist.'}
          </p>
        )}

        <div className="mt-1 flex items-center justify-end gap-2.5">
          <button
            type="button"
            onClick={onClose}
            className="rounded-pill bg-quinary px-4 py-2 type-body-emphasized text-primary hover:bg-quaternary"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!title.trim() || create.isPending}
            className="rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white hover:opacity-95 disabled:opacity-45"
          >
            {create.isPending ? 'Creating…' : user ? 'Create Playlist' : 'Sign in & Create'}
          </button>
        </div>
      </form>
    </ModalShell>
  );
}

// ---- 2. Join via Invite Modal ------------------------------------------------------

export function JoinCollabInviteModal({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const adoptServerOrigin = useCollabConfig((s) => s.adoptServerOrigin);
  const [input, setInput] = useState('');
  const parsed = parseCollabInviteLink(input);

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!parsed) return;
    if (parsed.serverOrigin) {
      adoptServerOrigin(parsed.serverOrigin);
    }
    onClose();
    const query = parsed.serverOrigin ? `?server=${encodeURIComponent(parsed.serverOrigin)}` : '';
    navigate(`/collab/invite/${encodeURIComponent(parsed.token)}${query}`);
  };

  return (
    <ModalShell title="Join Collaborative Playlist" onClose={onClose} maxWidth="440px">
      <form onSubmit={submit} className="mt-1 flex flex-col gap-3.5">
        <p className="type-body-tall text-secondary">
          Paste a collaborative invite link or token (<code className="text-primary">bci_…</code>) shared by a friend.
        </p>
        <input
          autoFocus
          className="w-full rounded-[10px] border-0 bg-quinary px-3.5 py-2.5 type-body text-primary placeholder:text-tertiary"
          placeholder="https://…/collab/invite/bci_… or bci_…"
          value={input}
          onChange={(e) => setInput(e.currentTarget.value)}
        />
        {input.trim().length > 0 && !parsed && (
          <p className="type-callout text-red">Enter a valid Sonara collaborative invite link or bci_ token.</p>
        )}
        <div className="mt-1 flex items-center justify-end gap-2.5">
          <button
            type="button"
            onClick={onClose}
            className="rounded-pill bg-quinary px-4 py-2 type-body-emphasized text-primary hover:bg-quaternary"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!parsed}
            className="rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white hover:opacity-95 disabled:opacity-45"
          >
            Preview Invite
          </button>
        </div>
      </form>
    </ModalShell>
  );
}

// ---- 3. Make Collaborative Confirmation Modal --------------------------------------

export interface MakeCollaborativeSource {
  sourcePlaylistId: string;
  sourceType: CollabSourceType;
  title: string;
  description?: string | null;
  artworkUrl?: string | null;
  tracks?: readonly Track[];
  loadTracks?: () => Promise<Track[]>;
}

export function MakeCollaborativeModal({
  source,
  onClose,
}: {
  source: MakeCollaborativeSource;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const user = useCollabAuth((s) => s.user);
  const requestSignIn = useCollabSignInPrompt((s) => s.requestSignIn);
  const convert = useConvertCollabPlaylist();

  const [attributeToOwner, setAttributeToOwner] = useState(true);
  const [permissions, setPermissions] = useState<CollabPermissions>({ ...DEFAULT_COLLAB_PERMISSIONS });
  const [loadedTracks, setLoadedTracks] = useState<readonly Track[] | null>(source.tracks ?? null);
  const [loadingTracks, setLoadingTracks] = useState(!source.tracks && Boolean(source.loadTracks));
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    if (source.tracks || !source.loadTracks) return;
    let cancelled = false;
    setLoadingTracks(true);
    setLoadError(null);
    source
      .loadTracks()
      .then((tracks) => {
        if (!cancelled) {
          setLoadedTracks(tracks);
          setLoadingTracks(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setLoadError(err instanceof Error ? err.message : 'Could not load playlist songs.');
          setLoadingTracks(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [source]);

  const tracks = loadedTracks ?? [];
  const sourceLabel =
    source.sourceType === 'spotify'
      ? 'Spotify Playlist'
      : source.sourceType === 'ytm'
        ? 'YouTube Music Playlist'
        : 'Personal Playlist';

  const handleConvert = () => {
    const run = () => {
      const payload = buildConvertPayload({
        sourcePlaylistId: source.sourcePlaylistId,
        sourceType: source.sourceType,
        title: source.title,
        description: source.description,
        artworkUrl: source.artworkUrl,
        attributeExistingTracksToOwner: attributeToOwner,
        permissions,
        tracks,
      });
      convert.mutate(payload, {
        onSuccess: ({ playlist }) => {
          onClose();
          navigate(`/collab/${encodeURIComponent(playlist.id)}`);
        },
      });
    };

    if (!user) {
      requestSignIn({
        reason: `Sign in with Google to share "${source.title}" as a collaborative playlist.`,
        onSignedIn: () => run(),
      });
      return;
    }
    run();
  };

  return (
    <ModalShell title="Make Collaborative" onClose={onClose} maxWidth="520px">
      <div className="mt-1 flex flex-col gap-4">
        <div className="flex items-center gap-3.5 rounded-[12px] bg-quinary p-3">
          <div className="w-14 flex-none">
            <Artwork src={source.artworkUrl ?? null} displayWidth={56} radius="var(--radius-artwork-small)" />
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate type-title-3-emphasized text-primary">{source.title}</p>
            <p className="truncate type-callout text-secondary">
              {sourceLabel} · {loadingTracks ? 'Loading songs…' : `${tracks.length} ${tracks.length === 1 ? 'song' : 'songs'}`}
            </p>
          </div>
        </div>

        <div className="rounded-[10px] bg-quinary px-3.5 py-3 type-callout text-secondary">
          <p className="type-body-emphasized text-primary">What sharing does</p>
          <ul className="mt-1.5 list-disc space-y-1 ps-4">
            <li>Your original {sourceLabel.toLowerCase()} stays untouched and private.</li>
            <li>Sonara creates a live shared copy where invited friends can add songs with Google-verified attribution.</li>
            <li>You stay the owner and can stop sharing or revoke invite links anytime.</li>
          </ul>
        </div>

        <label className="flex cursor-pointer items-center justify-between gap-3 rounded-[10px] bg-quinary px-3.5 py-2.5">
          <span className="flex flex-col">
            <span className="type-body-emphasized">Attribute existing songs to you</span>
            <span className="type-callout text-secondary">Shows your profile picture beside the initial songs.</span>
          </span>
          <input
            type="checkbox"
            className={settingsUi.switch}
            checked={attributeToOwner}
            onChange={(e) => setAttributeToOwner(e.currentTarget.checked)}
          />
        </label>

        <div>
          <h3 className="mx-1 mb-1.5 type-subhead-emphasized uppercase text-secondary">Initial Editor Permissions</h3>
          <PermissionsControls permissions={permissions} onChange={setPermissions} />
        </div>

        {(loadError || convert.isError) && (
          <p className="rounded-[10px] bg-red/12 px-3 py-2 type-callout text-red">
            {loadError ?? (convert.error instanceof Error ? convert.error.message : 'Conversion failed.')}
          </p>
        )}

        <div className="mt-1 flex items-center justify-end gap-2.5">
          <button
            type="button"
            onClick={onClose}
            className="rounded-pill bg-quinary px-4 py-2 type-body-emphasized text-primary hover:bg-quaternary"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={loadingTracks || Boolean(loadError) || convert.isPending}
            onClick={handleConvert}
            className="inline-flex items-center gap-1.5 rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white hover:opacity-95 disabled:opacity-45"
          >
            <Users size={15} />
            <span>
              {convert.isPending
                ? 'Making Collaborative…'
                : user
                  ? 'Make Collaborative'
                  : 'Sign in & Make Collaborative'}
            </span>
          </button>
        </div>
      </div>
    </ModalShell>
  );
}

// ---- 4. Stop Sharing Reversal Confirmation Modal -----------------------------------

export function StopSharingModal({
  playlistId,
  playlistTitle,
  onClose,
}: {
  playlistId: string;
  playlistTitle: string;
  onClose: () => void;
}) {
  const stopSharing = useStopSharingCollabPlaylist();

  return (
    <ModalShell title="Stop Sharing Playlist?" onClose={onClose} maxWidth="460px">
      <div className="mt-1 flex flex-col gap-4">
        <div className="flex items-start gap-3 rounded-[10px] bg-quinary p-3.5">
          <Lock size={18} className="mt-0.5 flex-none text-key" />
          <div className="type-callout text-secondary">
            <p className="type-body-emphasized text-primary">Reversing collaboration on “{playlistTitle}”</p>
            <ul className="mt-1.5 list-disc space-y-1 ps-4">
              <li>Collaborators immediately lose access to this shared playlist.</li>
              <li>All active invite links are permanently revoked.</li>
              <li>Your playlist and historical contributor attribution stay preserved for you.</li>
            </ul>
          </div>
        </div>

        {stopSharing.isError && (
          <p className="rounded-[10px] bg-red/12 px-3 py-2 type-callout text-red">
            {stopSharing.error instanceof Error ? stopSharing.error.message : 'Failed to stop sharing.'}
          </p>
        )}

        <div className="flex items-center justify-end gap-2.5">
          <button
            type="button"
            onClick={onClose}
            className="rounded-pill bg-quinary px-4 py-2 type-body-emphasized text-primary hover:bg-quaternary"
          >
            Keep Sharing
          </button>
          <button
            type="button"
            disabled={stopSharing.isPending}
            onClick={() => stopSharing.mutate(playlistId, { onSuccess: () => onClose() })}
            className="rounded-pill bg-red px-5 py-2 type-body-emphasized text-white hover:opacity-95 disabled:opacity-45"
          >
            {stopSharing.isPending ? 'Stopping…' : 'Stop Sharing'}
          </button>
        </div>
      </div>
    </ModalShell>
  );
}

// ---- 5. Shareable Invite Links Modal -----------------------------------------------

export function CollabInviteModal({
  playlist,
  onClose,
}: {
  playlist: Pick<CollabPlaylistDetail, 'id' | 'title' | 'ownerUserId' | 'myRole' | 'sharingEnabled' | 'permissions'>;
  onClose: () => void;
}) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  const serverUrl = useCollabAuth((s) => s.serverUrl);
  const perms = evaluateCollabPermissions(playlist, userId);
  const invites = useCollabInvites(playlist.id, perms.isOwner);
  const createInvite = useCreateCollabInvite(playlist.id);
  const revokeInvite = useRevokeCollabInvite(playlist.id);

  const [role, setRole] = useState<'EDITOR' | 'VIEWER'>('EDITOR');
  const [expiresInSec, setExpiresInSec] = useState<number>(0);
  const [maxUses, setMaxUses] = useState<number>(0);
  const [latestLink, setLatestLink] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const copyLink = (id: string, token: string, fallbackUrl?: string) => {
    const shareUrl = formatCollabInviteUrl(token, serverUrl || null) || fallbackUrl || '';
    void navigator.clipboard?.writeText(shareUrl);
    setCopiedId(id);
    window.setTimeout(() => setCopiedId((prev) => (prev === id ? null : prev)), 1800);
  };

  return (
    <ModalShell title={`Invite to “${playlist.title}”`} onClose={onClose} maxWidth="520px">
      <div className="mt-1 flex flex-col gap-4">
        <div className="overflow-hidden rounded-[10px] bg-quinary">
          <label className="flex items-center justify-between gap-3 px-3.5 py-2.5 type-body">
            <span className="type-body-emphasized">Collaborator Role</span>
            <Select<'EDITOR' | 'VIEWER'>
              label="Collaborator Role"
              value={role}
              options={[
                { value: 'EDITOR', label: 'Editor (add songs)' },
                { value: 'VIEWER', label: 'Viewer (listen only)' },
              ]}
              onChange={setRole}
            />
          </label>
          <label className="flex items-center justify-between gap-3 border-t-[0.5px] border-t-divider px-3.5 py-2.5 type-body">
            <span className="type-body-emphasized">Link Expiration</span>
            <Select<number>
              label="Link Expiration"
              value={expiresInSec}
              options={[
                { value: 0, label: 'Never expires' },
                { value: 86_400, label: '24 hours' },
                { value: 604_800, label: '7 days' },
                { value: 2_592_000, label: '30 days' },
              ]}
              onChange={setExpiresInSec}
            />
          </label>
          <label className="flex items-center justify-between gap-3 border-t-[0.5px] border-t-divider px-3.5 py-2.5 type-body">
            <span className="type-body-emphasized">Max Joins</span>
            <Select<number>
              label="Max Joins"
              value={maxUses}
              options={[
                { value: 0, label: 'Unlimited' },
                { value: 1, label: '1 person' },
                { value: 5, label: '5 people' },
                { value: 25, label: '25 people' },
              ]}
              onChange={setMaxUses}
            />
          </label>
        </div>

        <button
          type="button"
          disabled={createInvite.isPending || !perms.canInvite}
          onClick={() =>
            createInvite.mutate(
              {
                role,
                ...(expiresInSec > 0 ? { expiresInSec } : {}),
                ...(maxUses > 0 ? { maxUses } : {}),
              },
              {
                onSuccess: (inv) => {
                  const link = formatCollabInviteUrl(inv.token, serverUrl || null);
                  setLatestLink(link);
                  copyLink(inv.inviteId, inv.token, inv.inviteUrl);
                },
              },
            )
          }
          className="inline-flex h-9 items-center justify-center gap-2 rounded-pill bg-key-bg px-4 type-body-emphasized text-white hover:opacity-95 disabled:opacity-45"
        >
          <Link2 size={15} />
          <span>{createInvite.isPending ? 'Generating Link…' : 'Create & Copy Invite Link'}</span>
        </button>

        {latestLink && (
          <div className="flex items-center gap-2 rounded-[10px] bg-quinary px-3 py-2">
            <input
              readOnly
              value={latestLink}
              className="min-w-0 flex-1 bg-transparent type-callout text-primary outline-none"
            />
            <button
              type="button"
              onClick={() => {
                void navigator.clipboard?.writeText(latestLink);
                setCopiedId('latest');
                window.setTimeout(() => setCopiedId(null), 1800);
              }}
              className="inline-flex items-center gap-1 rounded-pill bg-quaternary px-2.5 py-1 type-callout-emphasized text-primary"
            >
              {copiedId === 'latest' ? <Check size={13} className="text-green" /> : <Copy size={13} />}
              <span>{copiedId === 'latest' ? 'Copied' : 'Copy'}</span>
            </button>
          </div>
        )}

        {perms.isOwner && (
          <div>
            <h3 className="mx-1 mb-1.5 type-subhead-emphasized uppercase text-secondary">Active Invite Links</h3>
            {invites.isPending ? (
              <p className="px-1 type-callout text-secondary">Loading active invites…</p>
            ) : !invites.data?.length ? (
              <p className="px-1 type-callout text-secondary">No active invite links yet.</p>
            ) : (
              <ul className="overflow-hidden rounded-[10px] bg-quinary">
                {invites.data.map((inv) => (
                  <li
                    key={inv.inviteId}
                    className="flex items-center justify-between gap-2 px-3.5 py-2.5 [&+&]:border-t-[0.5px] [&+&]:border-t-divider"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate type-body-emphasized text-primary">
                        {inv.role === 'EDITOR' ? 'Editor Invite' : 'Viewer Invite'} · {inv.token.slice(0, 12)}…
                      </p>
                      <p className="type-callout text-secondary">
                        {inv.useCount} {inv.useCount === 1 ? 'join' : 'joins'}
                        {inv.maxUses > 0 ? ` of ${inv.maxUses}` : ''}
                        {inv.expiresAtMs ? ` · Expires ${new Date(inv.expiresAtMs).toLocaleDateString()}` : ''}
                      </p>
                    </div>
                    <div className="flex flex-none items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => copyLink(inv.inviteId, inv.token, inv.inviteUrl)}
                        className="inline-flex items-center gap-1 rounded-pill bg-quaternary px-2.5 py-1 type-callout-emphasized text-primary"
                      >
                        {copiedId === inv.inviteId ? <Check size={13} className="text-green" /> : <Copy size={13} />}
                        <span>{copiedId === inv.inviteId ? 'Copied' : 'Copy'}</span>
                      </button>
                      <button
                        type="button"
                        aria-label="Revoke invite"
                        title="Revoke invite"
                        disabled={revokeInvite.isPending}
                        onClick={() => revokeInvite.mutate(inv.inviteId)}
                        className="grid size-7 place-items-center rounded-full text-secondary hover:bg-quaternary hover:text-red"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </ModalShell>
  );
}

// ---- 6. Manage Playlist, Members, Permissions & Ownership Modal --------------------

export function CollabManageModal({
  playlist,
  onClose,
  onOpenStopSharing,
}: {
  playlist: CollabPlaylistDetail;
  onClose: () => void;
  onOpenStopSharing: () => void;
}) {
  const navigate = useNavigate();
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  const perms = evaluateCollabPermissions(playlist, userId);

  const updateMeta = useUpdateCollabPlaylist(playlist.id);
  const updateRole = useUpdateCollabMemberRole(playlist.id);
  const transferOwner = useTransferCollabOwnership(playlist.id);
  const removeMember = useRemoveCollabMember(playlist.id);
  const leavePlaylist = useLeaveCollabPlaylist();
  const deletePlaylist = useDeleteCollabPlaylist();

  const [title, setTitle] = useState(playlist.title);
  const [description, setDescription] = useState(playlist.description);
  const [permissions, setPermissions] = useState<CollabPermissions>(playlist.permissions);
  const [confirmTransferTo, setConfirmTransferTo] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const dirty =
    title.trim() !== playlist.title ||
    description.trim() !== playlist.description ||
    permissions.editorCanRemove !== playlist.permissions.editorCanRemove ||
    permissions.editorCanReorder !== playlist.permissions.editorCanReorder ||
    permissions.editorCanInvite !== playlist.permissions.editorCanInvite;

  return (
    <ModalShell title="Manage Collaborative Playlist" onClose={onClose} maxWidth="540px">
      <div className="mt-1 flex flex-col gap-4">
        {perms.canEditMetadata && (
          <div className="flex flex-col gap-2.5">
            <h3 className="mx-1 type-subhead-emphasized uppercase text-secondary">Details & Permissions</h3>
            <input
              className="w-full rounded-[10px] border-0 bg-quinary px-3.5 py-2 type-body-emphasized text-primary"
              value={title}
              maxLength={200}
              onChange={(e) => setTitle(e.currentTarget.value)}
            />
            <textarea
              rows={2}
              className="w-full resize-none rounded-[10px] border-0 bg-quinary px-3.5 py-2 type-body text-primary placeholder:text-tertiary"
              placeholder="Playlist description"
              value={description}
              maxLength={2000}
              onChange={(e) => setDescription(e.currentTarget.value)}
            />
            <PermissionsControls permissions={permissions} onChange={setPermissions} />
            {dirty && (
              <button
                type="button"
                disabled={!title.trim() || updateMeta.isPending}
                onClick={() =>
                  updateMeta.mutate({
                    title: title.trim(),
                    description: description.trim(),
                    permissions,
                  })
                }
                className="self-end rounded-pill bg-key-bg px-4 py-1.5 type-callout-emphasized text-white"
              >
                {updateMeta.isPending ? 'Saving…' : 'Save Changes'}
              </button>
            )}
          </div>
        )}

        <div>
          <h3 className="mx-1 mb-1.5 type-subhead-emphasized uppercase text-secondary">
            Collaborators ({playlist.members.length})
          </h3>
          <ul className="overflow-hidden rounded-[10px] bg-quinary">
            {playlist.members.map((member) => {
              const isMe = member.userId === userId;
              const memberIsOwner = member.role === 'OWNER';
              return (
                <li
                  key={member.userId}
                  className="flex items-center justify-between gap-2.5 px-3.5 py-2.5 [&+&]:border-t-[0.5px] [&+&]:border-t-divider"
                >
                  <div className="flex min-w-0 items-center gap-2.5">
                    <CollabAvatar src={member.avatarUrl} name={member.displayName} seed={member.userId} size={30} />
                    <div className="min-w-0">
                      <p className="flex items-center gap-1.5 truncate type-body-emphasized text-primary">
                        <span className="truncate">{member.displayName}</span>
                        {isMe && <span className="type-callout text-secondary">(you)</span>}
                        {memberIsOwner && <Crown size={13} className="flex-none text-[#ff9f0a]" />}
                      </p>
                      <p className="type-callout text-secondary">
                        {member.role === 'OWNER' ? 'Owner' : member.role === 'EDITOR' ? 'Editor' : 'Viewer'}
                      </p>
                    </div>
                  </div>

                  {perms.canManageMembers && !memberIsOwner && (
                    <div className="flex flex-none items-center gap-1.5">
                      <Select<Exclude<CollabRole, 'OWNER'>>
                        label={`Role for ${member.displayName}`}
                        value={member.role === 'EDITOR' ? 'EDITOR' : 'VIEWER'}
                        options={[
                          { value: 'EDITOR', label: 'Editor' },
                          { value: 'VIEWER', label: 'Viewer' },
                        ]}
                        onChange={(nextRole) => updateRole.mutate({ targetUserId: member.userId, role: nextRole })}
                      />
                      {confirmTransferTo === member.userId ? (
                        <button
                          type="button"
                          disabled={transferOwner.isPending}
                          onClick={() =>
                            transferOwner.mutate(member.userId, {
                              onSuccess: () => setConfirmTransferTo(null),
                            })
                          }
                          className="rounded-pill bg-key-bg px-2.5 py-1 type-callout-emphasized text-white"
                        >
                          Confirm Owner
                        </button>
                      ) : (
                        <button
                          type="button"
                          title="Transfer ownership"
                          aria-label={`Transfer ownership to ${member.displayName}`}
                          onClick={() => setConfirmTransferTo(member.userId)}
                          className="grid size-7 place-items-center rounded-full text-secondary hover:bg-quaternary hover:text-primary"
                        >
                          <Crown size={14} />
                        </button>
                      )}
                      <button
                        type="button"
                        title={`Remove ${member.displayName}`}
                        aria-label={`Remove ${member.displayName}`}
                        disabled={removeMember.isPending}
                        onClick={() => removeMember.mutate(member.userId)}
                        className="grid size-7 place-items-center rounded-full text-secondary hover:bg-quaternary hover:text-red"
                      >
                        <UserMinus size={14} />
                      </button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 border-t-[0.5px] border-t-divider pt-3">
          {perms.canStopSharing && (
            <button
              type="button"
              onClick={() => {
                onClose();
                onOpenStopSharing();
              }}
              className="inline-flex items-center gap-1.5 rounded-pill bg-quinary px-3.5 py-1.5 type-callout-emphasized text-primary hover:bg-quaternary"
            >
              <Lock size={14} />
              <span>Stop Sharing…</span>
            </button>
          )}

          {perms.canLeavePlaylist && (
            <button
              type="button"
              disabled={leavePlaylist.isPending}
              onClick={() =>
                leavePlaylist.mutate(playlist.id, {
                  onSuccess: () => {
                    onClose();
                    navigate('/library/playlists');
                  },
                })
              }
              className="inline-flex items-center gap-1.5 rounded-pill bg-red/14 px-3.5 py-1.5 type-callout-emphasized text-red hover:bg-red/22"
            >
              <span>{leavePlaylist.isPending ? 'Leaving…' : 'Leave Playlist'}</span>
            </button>
          )}

          {perms.canDeletePlaylist &&
            (confirmDelete ? (
              <div className="flex items-center gap-2">
                <span className="inline-flex items-center gap-1 type-callout text-red">
                  <ShieldAlert size={14} /> Permanently delete?
                </span>
                <button
                  type="button"
                  disabled={deletePlaylist.isPending}
                  onClick={() =>
                    deletePlaylist.mutate(playlist.id, {
                      onSuccess: () => {
                        onClose();
                        navigate('/library/playlists');
                      },
                    })
                  }
                  className="rounded-pill bg-red px-3.5 py-1 type-callout-emphasized text-white"
                >
                  {deletePlaylist.isPending ? 'Deleting…' : 'Delete Forever'}
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmDelete(true)}
                className="inline-flex items-center gap-1.5 rounded-pill bg-red/14 px-3.5 py-1.5 type-callout-emphasized text-red hover:bg-red/22"
              >
                <Trash2 size={14} />
                <span>Delete Playlist</span>
              </button>
            ))}
        </div>
      </div>
    </ModalShell>
  );
}
