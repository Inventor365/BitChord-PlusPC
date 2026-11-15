import {
  Activity,
  ArrowDown,
  ArrowUp,
  ChevronDown,
  ChevronRight,
  GripVertical,
  Link2,
  Lock,
  Plus,
  Search,
  Shuffle,
  SlidersHorizontal,
  Trash2,
  Users,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useCollabAuth, useCollabSignInPrompt } from '@/collab/auth';
import {
  useAddCollabTracks,
  useCollabPlaylistDetail,
  useRemoveCollabTrack,
  useReorderCollabTracks,
} from '@/collab/queries';
import {
  canRemoveCollabTrack,
  collabEntryToTrack,
  evaluateCollabPermissions,
  trackToCollabInput,
  type CollabTrackEntry,
  type CollabTrackInput,
} from '@/collab/types';
import { useCollabSocketState } from '@/collab/ws';
import { PlayIcon, PauseIcon } from '@/components/icons/Transport';
import { CollabAvatar, CollabAvatarStack, CollabCover } from '@/components/collab/CollabAvatars';
import { CollabInviteModal, CollabManageModal, StopSharingModal } from '@/components/collab/CollabModals';
import { Artwork } from '@/components/music/Artwork';
import { detailButton } from '@/components/music/DetailHeader';
import { ExplicitBadge } from '@/components/music/ExplicitBadge';
import { formatDuration } from '@/components/music/links';
import { playList, shuffleList, toTrack } from '@/components/music/play';
import { EmptyState, LoadError } from '@/components/music/States';
import { TrackMenu } from '@/components/music/TrackMenu';
import { useSearch, useYouTubeReady } from '@/data/music/queries';
import { useArtworkColor } from '@/lib/artworkColor';
import { usePageTint } from '@/lib/usePageTint';
import { PlaylistSkeleton } from '@/skeletons/PageSkeletons';
import { usePlayer } from '@/state/player';
import { detailFooter } from './pageStyles';

function formatRelativeTime(timestampMs: number): string {
  if (!timestampMs) return '';
  const diffSec = Math.max(0, Math.round((Date.now() - timestampMs) / 1000));
  if (diffSec < 60) return 'just now';
  const mins = Math.floor(diffSec / 60);
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  const days = Math.floor(hrs / 24);
  if (days < 30) return `${days}d ago`;
  return new Date(timestampMs).toLocaleDateString();
}

function parseYouTubeVideoId(raw: string): string | null {
  const trimmed = raw.trim();
  if (/^[A-Za-z0-9_-]{11}$/.test(trimmed)) return trimmed;
  try {
    const u = new URL(trimmed);
    if (u.hostname.includes('youtu.be')) {
      const id = u.pathname.replace(/^\/+/, '').split('/')[0];
      return id && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
    }
    const v = u.searchParams.get('v');
    return v && /^[A-Za-z0-9_-]{11}$/.test(v) ? v : null;
  } catch {
    return null;
  }
}

export default function CollabPlaylistPage() {
  const id = decodeURIComponent(useParams().id ?? '');
  const navigate = useNavigate();
  const user = useCollabAuth((s) => s.user);
  const requestSignIn = useCollabSignInPrompt((s) => s.requestSignIn);
  const socketStatus = useCollabSocketState((s) => s.status);

  const detailQuery = useCollabPlaylistDetail(id);
  const playlist = detailQuery.data ?? null;

  const tintCover = playlist?.artworkUrl ?? playlist?.tracks.find((t) => t.thumbnailUrl)?.thumbnailUrl ?? null;
  usePageTint(useArtworkColor(tintCover));

  const addTracks = useAddCollabTracks(id);
  const removeTrack = useRemoveCollabTrack(id);
  const reorderTracks = useReorderCollabTracks(id);

  const [expandedDesc, setExpandedDesc] = useState(false);
  const [activityOpen, setActivityOpen] = useState(false);
  const [contributorFilter, setContributorFilter] = useState<string | null>(null);
  const [showInvite, setShowInvite] = useState(false);
  const [showManage, setShowManage] = useState(false);
  const [showStopSharing, setShowStopSharing] = useState(false);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

  const contributors = useMemo(() => {
    if (!playlist) return [];
    const map = new Map<string, { id: string; name: string; avatarUrl: string | null; count: number }>();
    for (const tr of playlist.tracks) {
      const cid = tr.contributorId ?? '__unattributed__';
      const cname =
        tr.contributorId && user && tr.contributorId === user.id
          ? 'You'
          : (tr.contributorName ?? 'Original');
      const existing = map.get(cid);
      if (existing) {
        existing.count += 1;
      } else {
        map.set(cid, {
          id: cid,
          name: cname,
          avatarUrl: tr.contributorAvatarUrl,
          count: 1,
        });
      }
    }
    return [...map.values()];
  }, [playlist, user]);

  useEffect(() => {
    if (contributorFilter && !contributors.some((c) => c.id === contributorFilter)) {
      setContributorFilter(null);
    }
  }, [contributorFilter, contributors]);

  if (!user) {
    return (
      <EmptyState
        title="Sign in to view this collaborative playlist"
        detail="Collaborative playlists verify contributors with Google so every song shows who added it."
        action={
          <button
            type="button"
            onClick={() =>
              requestSignIn({
                reason: 'Sign in with Google to open and collaborate on this shared playlist.',
              })
            }
            className="rounded-pill bg-key-bg px-5 py-2 type-body-emphasized text-white hover:opacity-95"
          >
            Sign in with Google
          </button>
        }
      />
    );
  }

  if (detailQuery.liveNotice && !playlist) {
    return (
      <EmptyState
        title="Playlist no longer shared with you"
        detail={detailQuery.liveNotice}
        action={
          <button
            type="button"
            onClick={() => navigate('/library/playlists')}
            className="rounded-pill bg-quinary px-5 py-2 type-body-emphasized text-primary hover:bg-quaternary"
          >
            Back to Playlists
          </button>
        }
      />
    );
  }

  if (detailQuery.isError && !playlist) {
    return <LoadError error={detailQuery.error} onRetry={() => void detailQuery.refetch()} />;
  }
  if (!playlist) return <PlaylistSkeleton />;

  const perms = evaluateCollabPermissions(playlist, user.id);
  const visibleEntries = contributorFilter
    ? playlist.tracks.filter((t) => (t.contributorId ?? '__unattributed__') === contributorFilter)
    : playlist.tracks;

  const playableTracks = visibleEntries.map(collabEntryToTrack).filter((t) => !t.unavailable);
  const queueContext = {
    source: { title: playlist.title, type: 'playlist' as const, id: `collab:${playlist.id}` },
    fallbackArtwork: playlist.artworkUrl,
  };

  const canReorderNow = perms.canReorderTracks && contributorFilter === null && playlist.tracks.length > 1;

  const moveTrack = (fromIdx: number, toIdx: number) => {
    if (fromIdx === toIdx || fromIdx < 0 || toIdx < 0 || toIdx >= playlist.tracks.length) return;
    const ids = playlist.tracks.map((t) => t.entryId);
    const [moved] = ids.splice(fromIdx, 1);
    if (!moved) return;
    ids.splice(toIdx, 0, moved);
    reorderTracks.mutate(ids);
  };

  const recentActivities = [...playlist.activities].reverse().slice(0, 15);
  const ownerDisplay = playlist.ownerUserId === user.id ? 'You' : playlist.ownerName;

  return (
    <>
      {/* DetailHeader-aligned Collaborative Playlist Header */}
      <div className="mx-(--body-gutter) mb-8 grid grid-cols-[auto_minmax(0,1fr)] grid-rows-[auto_1fr_auto] gap-x-[34px] pt-11 [grid-template-areas:'artwork_headings'_'artwork_description'_'artwork_actions'] max-md:grid-cols-[1fr] max-md:grid-rows-[auto] max-md:justify-items-center max-md:pt-4 max-md:text-center max-md:[grid-template-areas:'artwork'_'headings'_'actions'_'description']">
        <div className="relative w-[270px] self-start rounded-artwork-lg shadow-[0_10px_20px_0_var(--radiosityShadowColor)] [grid-area:artwork] max-lg:w-[220px] max-md:w-[min(270px,70vw)]">
          {tintCover && (
            <div className="absolute inset-0 -z-1 scale-88 opacity-30 blur-[20px] saturate-200" aria-hidden="true">
              <Artwork src={tintCover} displayWidth={80} radius="var(--radius-artwork-large)" />
            </div>
          )}
          <CollabCover
            artworkUrl={playlist.artworkUrl}
            tracks={playlist.tracks}
            title={playlist.title}
            displayWidth={270}
            radius="var(--radius-artwork-large)"
            eager
          />
        </div>

        <div className="flex flex-col pt-[28px] [grid-area:headings] max-md:items-center max-md:pt-5">
          <div className="mb-1.5 flex flex-wrap items-center gap-2 max-md:justify-center">
            <span className="inline-flex items-center gap-1.5 type-subhead-emphasized uppercase tracking-wide text-secondary">
              <Users size={13} className="text-key" />
              <span>Collaborative Playlist</span>
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-pill bg-quinary px-2.5 py-0.5 type-caption-2 text-secondary">
              <span
                className={`size-1.5 rounded-full ${
                  !playlist.sharingEnabled
                    ? 'bg-gray'
                    : socketStatus === 'live'
                      ? 'bg-green'
                      : socketStatus === 'connecting'
                        ? 'bg-[#ff9f0a]'
                        : 'bg-gray'
                }`}
              />
              <span>
                {!playlist.sharingEnabled
                  ? 'Stopped sharing'
                  : socketStatus === 'live'
                    ? 'Live sync'
                    : socketStatus === 'connecting'
                      ? 'Syncing…'
                      : 'Cached'}
              </span>
            </span>
          </div>

          <h1 className="type-large-title-emphasized leading-[32px]">{playlist.title}</h1>

          <div className="mt-2 flex flex-wrap items-center gap-2.5 max-md:justify-center">
            <CollabAvatarStack
              members={
                playlist.members.length
                  ? playlist.members.map((m) => ({
                      userId: m.userId,
                      displayName: m.displayName,
                      avatarUrl: m.avatarUrl,
                    }))
                  : playlist.memberAvatars
              }
              totalCount={playlist.memberCount}
              size={26}
              max={5}
            />
            <span className="type-callout-emphasized text-secondary">
              Curated by {ownerDisplay} · {playlist.memberCount}{' '}
              {playlist.memberCount === 1 ? 'collaborator' : 'collaborators'} · {playlist.trackCount}{' '}
              {playlist.trackCount === 1 ? 'song' : 'songs'}
            </span>
          </div>
        </div>

        {playlist.description && (
          <div className="mt-5 flex max-w-[480px] items-end type-body-tall text-secondary [grid-area:description] max-md:mt-4">
            <p className={expandedDesc ? 'min-w-0' : 'min-w-0 line-clamp-3'}>{playlist.description}</p>
            {!expandedDesc && playlist.description.length > 180 && (
              <button
                type="button"
                className="shrink-0 ps-[5px] type-subhead-emphasized text-primary"
                onClick={() => setExpandedDesc(true)}
              >
                MORE
              </button>
            )}
          </div>
        )}

        <div className="mt-4 flex flex-wrap items-center gap-2.5 self-end [grid-area:actions] max-md:justify-center">
          <button
            type="button"
            className={detailButton.circle}
            aria-label="Shuffle"
            disabled={!playableTracks.length}
            onClick={() => shuffleList(playableTracks, queueContext)}
          >
            <Shuffle size={18} strokeWidth={2.2} />
          </button>
          <button
            type="button"
            className={detailButton.pill}
            disabled={!playableTracks.length}
            onClick={() => playList(playableTracks, 0, queueContext)}
          >
            <PlayIcon size={18} />
            <span>Play</span>
          </button>

          {perms.canInvite && (
            <button
              type="button"
              onClick={() => setShowInvite(true)}
              className="inline-flex h-9 items-center gap-1.5 rounded-pill border-[0.75px] border-quaternary bg-quinary px-3.5 type-body-emphasized text-primary hover:bg-quaternary"
            >
              <Link2 size={15} />
              <span>Invite</span>
            </button>
          )}

          <button
            type="button"
            onClick={() => setShowManage(true)}
            className="inline-flex h-9 items-center gap-1.5 rounded-pill border-[0.75px] border-quaternary bg-quinary px-3.5 type-body-emphasized text-primary hover:bg-quaternary"
          >
            <SlidersHorizontal size={15} />
            <span>Manage</span>
          </button>
        </div>
      </div>

      {/* Stopped sharing banner */}
      {!playlist.sharingEnabled && (
        <div className="mx-(--body-gutter) mb-5 flex flex-wrap items-center justify-between gap-3 rounded-[12px] bg-quinary px-4 py-3">
          <div className="flex items-center gap-2.5 type-body text-secondary">
            <Lock size={16} className="flex-none text-key" />
            <span>
              Sharing is stopped. Collaborators no longer have access and invite links are revoked, while your songs and
              historical contributor attribution stay preserved here.
            </span>
          </div>
        </div>
      )}

      {/* Collapsible Recent Activity Strip & Contributor Filter Pills */}
      <div className="mx-(--body-gutter) mb-5 flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          {recentActivities.length > 0 && (
            <button
              type="button"
              onClick={() => setActivityOpen((v) => !v)}
              className="inline-flex items-center gap-1.5 rounded-pill bg-quinary px-3 py-1.5 type-callout-emphasized text-secondary hover:bg-quaternary hover:text-primary"
            >
              <Activity size={13} className="text-key" />
              <span className="max-w-[340px] truncate">{recentActivities[0]!.summary}</span>
              <span className="text-tertiary">· {formatRelativeTime(recentActivities[0]!.createdAtMs)}</span>
              {activityOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            </button>
          )}

          {contributors.length > 1 && (
            <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Filter by contributor">
              <button
                type="button"
                onClick={() => setContributorFilter(null)}
                className={`inline-flex h-7 items-center rounded-pill px-2.5 type-callout-emphasized ${
                  contributorFilter === null
                    ? 'bg-key-bg text-white'
                    : 'bg-quinary text-secondary hover:bg-quaternary hover:text-primary'
                }`}
              >
                All ({playlist.tracks.length})
              </button>
              {contributors.map((c) => {
                const active = contributorFilter === c.id;
                return (
                  <button
                    key={c.id}
                    type="button"
                    onClick={() => setContributorFilter(active ? null : c.id)}
                    className={`inline-flex h-7 items-center gap-1.5 rounded-pill pr-2.5 pl-1 type-callout-emphasized ${
                      active
                        ? 'bg-key-bg text-white'
                        : 'bg-quinary text-secondary hover:bg-quaternary hover:text-primary'
                    }`}
                  >
                    <CollabAvatar src={c.avatarUrl} name={c.name} seed={c.id} size={18} />
                    <span>
                      {c.name} ({c.count})
                    </span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {activityOpen && recentActivities.length > 0 && (
          <ul className="max-h-[220px] overflow-y-auto rounded-[12px] bg-quinary px-3.5 py-2">
            {recentActivities.map((act) => (
              <li
                key={act.id}
                className="flex items-center justify-between gap-3 py-1.5 type-callout [&+&]:border-t-[0.5px] [&+&]:border-t-divider"
              >
                <div className="flex min-w-0 items-center gap-2">
                  <CollabAvatar
                    src={act.actorAvatarUrl}
                    name={act.actorName}
                    seed={act.actorUserId}
                    size={20}
                  />
                  <span className="truncate text-primary">{act.summary}</span>
                </div>
                <span className="flex-none text-tertiary">{formatRelativeTime(act.createdAtMs)}</span>
              </li>
            ))}
          </ul>
        )}
      </div>

      {/* Add Song Search Bar (Editors & Owner) */}
      {perms.canAddTracks && (
        <AddSongsPanel
          onAdd={(trackInput) => addTracks.mutate({ tracks: [trackInput] })}
          isAdding={addTracks.isPending}
        />
      )}

      {/* Collaborative Track List with Verified Contributor Attribution */}
      {visibleEntries.length === 0 ? (
        <EmptyState
          title={contributorFilter ? 'No songs from this contributor' : 'No songs in this playlist yet'}
          detail={
            perms.canAddTracks
              ? 'Search above or use any song’s ⋯ menu throughout BitChord to add tracks.'
              : 'Waiting for collaborators to add songs.'
          }
        />
      ) : (
        <div className="mx-(--body-gutter) type-callout text-secondary" role="table" aria-label="Collaborative songs">
          <div
            className="grid h-8 grid-cols-[minmax(0,38fr)_minmax(0,22fr)_minmax(0,18fr)_minmax(0,14fr)_minmax(0,8fr)] items-center type-callout-emphasized text-secondary max-md:hidden"
            aria-hidden="true"
          >
            <div className="flex items-center gap-3 ps-[7px]">Song</div>
            <div className="pe-2.5">Artist</div>
            <div className="pe-2.5">Added By</div>
            <div className="pe-2.5">Album</div>
            <div className=" flex justify-end pe-[18px]">Time</div>
          </div>

          <div>
            {visibleEntries.map((entry, idx) => (
              <CollabTrackRow
                key={entry.entryId}
                entry={entry}
                currentUserId={user.id}
                fallbackArtwork={playlist.artworkUrl}
                canRemove={canRemoveCollabTrack(playlist, entry, user.id)}
                canReorder={canReorderNow}
                isFirst={idx === 0}
                isLast={idx === visibleEntries.length - 1}
                isDragOver={dragOverIndex === idx && dragIndex !== null && dragIndex !== idx}
                onPlayRow={() => {
                  const track = collabEntryToTrack(entry);
                  if (track.unavailable) return;
                  const startIdx = Math.max(
                    0,
                    playableTracks.findIndex((t) => t.videoId === track.videoId && t.index === track.index),
                  );
                  playList(playableTracks, startIdx, queueContext);
                }}
                onRemove={() => removeTrack.mutate(entry.entryId)}
                onMoveUp={() => moveTrack(idx, idx - 1)}
                onMoveDown={() => moveTrack(idx, idx + 1)}
                onDragStart={() => setDragIndex(idx)}
                onDragOver={() => setDragOverIndex(idx)}
                onDragEnd={() => {
                  setDragIndex(null);
                  setDragOverIndex(null);
                }}
                onDrop={() => {
                  if (dragIndex !== null && dragIndex !== idx) {
                    moveTrack(dragIndex, idx);
                  }
                  setDragIndex(null);
                  setDragOverIndex(null);
                }}
              />
            ))}
          </div>
        </div>
      )}

      <footer className={detailFooter}>
        <p>
          {playlist.trackCount} {playlist.trackCount === 1 ? 'song' : 'songs'} · Revision {playlist.revision} ·{' '}
          {playlist.permissions.editorCanRemove === 'own'
            ? 'Editors can remove their own songs'
            : 'Editors can remove any song'}
        </p>
      </footer>

      {showInvite && <CollabInviteModal playlist={playlist} onClose={() => setShowInvite(false)} />}
      {showManage && (
        <CollabManageModal
          playlist={playlist}
          onClose={() => setShowManage(false)}
          onOpenStopSharing={() => setShowStopSharing(true)}
        />
      )}
      {showStopSharing && (
        <StopSharingModal
          playlistId={playlist.id}
          playlistTitle={playlist.title}
          onClose={() => setShowStopSharing(false)}
        />
      )}
    </>
  );
}

function CollabTrackRow({
  entry,
  currentUserId,
  fallbackArtwork,
  canRemove,
  canReorder,
  isFirst,
  isLast,
  isDragOver,
  onPlayRow,
  onRemove,
  onMoveUp,
  onMoveDown,
  onDragStart,
  onDragOver,
  onDragEnd,
  onDrop,
}: {
  entry: CollabTrackEntry;
  currentUserId: string;
  fallbackArtwork: string | null;
  canRemove: boolean;
  canReorder: boolean;
  isFirst: boolean;
  isLast: boolean;
  isDragOver: boolean;
  onPlayRow: () => void;
  onRemove: () => void;
  onMoveUp: () => void;
  onMoveDown: () => void;
  onDragStart: () => void;
  onDragOver: () => void;
  onDragEnd: () => void;
  onDrop: () => void;
}) {
  const track = useMemo(() => collabEntryToTrack(entry), [entry]);
  const current = usePlayer((s) => s.nowPlaying?.videoId === track.videoId && !track.unavailable);
  const playing = usePlayer(
    (s) => s.nowPlaying?.videoId === track.videoId && !track.unavailable && (s.status === 'playing' || s.status === 'buffering'),
  );
  const togglePlay = usePlayer((s) => s.togglePlay);

  const addedByMe = Boolean(entry.contributorId && entry.contributorId === currentUserId);
  const contributorName = addedByMe ? 'you' : (entry.contributorName ?? 'Owner');
  const contributorDisplay = addedByMe ? 'You' : (entry.contributorName ?? 'Owner');
  const addedTooltip = entry.addedAtMs
    ? `Added by ${contributorName} · ${new Date(entry.addedAtMs).toLocaleDateString()}`
    : `Added by ${contributorName}`;

  const handlePlay = () => {
    if (track.unavailable) return;
    if (current) togglePlay();
    else onPlayRow();
  };

  return (
    <div
      draggable={canReorder}
      onDragStart={canReorder ? onDragStart : undefined}
      onDragOver={
        canReorder
          ? (e) => {
              e.preventDefault();
              onDragOver();
            }
          : undefined
      }
      onDragEnd={canReorder ? onDragEnd : undefined}
      onDrop={
        canReorder
          ? (e) => {
              e.preventDefault();
              onDrop();
            }
          : undefined
      }
      onDoubleClick={handlePlay}
      data-current={current || undefined}
      className={`group/row relative grid h-[56px] grid-cols-[minmax(0,38fr)_minmax(0,22fr)_minmax(0,18fr)_minmax(0,14fr)_minmax(0,8fr)] items-center rounded-[12px] bg-transparent hover:bg-tracklist-hover data-current:bg-[color-mix(in_srgb,var(--systemPrimary)_12%,transparent)] before:pointer-events-none before:absolute before:inset-x-0 before:top-0 before:border-t-[0.5px] before:border-t-divider hover:before:border-t-transparent max-md:grid-cols-[minmax(0,1fr)_auto] ${
        isDragOver ? 'ring-1 ring-key' : ''
      }`}
    >
      {/* Song + Artwork + Mobile Attribution */}
      <div className="flex h-full min-w-0 items-center gap-2.5 ps-[7px]">
        {canReorder && (
          <span
            className="cursor-grab text-tertiary opacity-0 group-hover/row:opacity-100 max-md:hidden"
            title="Drag to reorder"
          >
            <GripVertical size={14} />
          </span>
        )}
        <div className="relative grid w-10 shrink-0 place-items-center">
          <Artwork
            src={entry.thumbnailUrl ?? fallbackArtwork}
            displayWidth={40}
            radius="var(--radius-artwork-small)"
          />
          {current && (
            <span
              className="absolute inset-0 grid place-items-center rounded-artwork-sm bg-[rgb(0_0_0/0.45)] text-white group-hover/row:invisible"
              aria-label="Now playing"
            >
              <PlayIcon size={18} />
            </span>
          )}
          {!track.unavailable && (
            <button
              type="button"
              className="absolute inset-0 grid place-items-center rounded-artwork-sm bg-lockup-hover text-white opacity-0 group-hover/row:opacity-100 focus-visible:opacity-100"
              aria-label={playing ? `Pause ${entry.title}` : `Play ${entry.title}`}
              onClick={handlePlay}
            >
              {playing ? <PauseIcon size={18} /> : <PlayIcon size={18} />}
            </button>
          )}
        </div>

        <div className="flex min-w-0 flex-1 flex-col">
          <div className="flex min-w-0 items-center">
            <span className="truncate type-body text-primary">{entry.title}</span>
            {entry.isExplicit && <ExplicitBadge />}
          </div>
          {/* Mobile subtitle: artist + circular contributor avatar */}
          <div className="hidden min-w-0 items-center gap-1.5 truncate type-callout text-secondary max-md:flex">
            <span className="truncate">{entry.artist}</span>
            <span>·</span>
            <CollabAvatar
              src={entry.contributorAvatarUrl}
              name={contributorDisplay}
              seed={entry.contributorId}
              size={15}
              title={addedTooltip}
            />
            <span className="truncate" title={addedTooltip}>
              Added by {contributorName}
            </span>
          </div>
        </div>
      </div>

      {/* Artist */}
      <div className="flex h-full min-w-0 items-center pe-2.5 max-md:hidden">
        <span className="truncate type-body">{entry.artist}</span>
      </div>

      {/* Contributor Attribution Column (Desktop) */}
      <div className="flex h-full min-w-0 items-center gap-2 pe-2.5 max-md:hidden" title={addedTooltip}>
        <CollabAvatar
          src={entry.contributorAvatarUrl}
          name={contributorDisplay}
          seed={entry.contributorId}
          size={22}
          title={addedTooltip}
        />
        <span className="truncate type-callout text-secondary">Added by {contributorName}</span>
      </div>

      {/* Album */}
      <div className="flex h-full min-w-0 items-center pe-2.5 max-md:hidden">
        <span className="truncate type-body">{entry.albumName ?? ''}</span>
      </div>

      {/* Duration & Hover Controls (Reorder Up/Down, Remove, TrackMenu) */}
      <div className="relative flex h-full items-center justify-end pe-[14px] type-body tabular-nums">
        <span className="group-focus-within/row:invisible group-hover/row:invisible group-has-[[data-open]]/row:invisible">
          {formatDuration(track.durationSec)}
        </span>

        <div className="absolute end-2 hidden items-center gap-0.5 group-focus-within/row:flex group-hover/row:flex group-has-[[data-open]]/row:flex">
          {canReorder && (
            <>
              <button
                type="button"
                aria-label={`Move ${entry.title} up`}
                title="Move up"
                disabled={isFirst}
                onClick={onMoveUp}
                className="grid size-6 place-items-center rounded-full text-secondary enabled:hover:bg-quinary enabled:hover:text-primary disabled:opacity-25"
              >
                <ArrowUp size={13} />
              </button>
              <button
                type="button"
                aria-label={`Move ${entry.title} down`}
                title="Move down"
                disabled={isLast}
                onClick={onMoveDown}
                className="grid size-6 place-items-center rounded-full text-secondary enabled:hover:bg-quinary enabled:hover:text-primary disabled:opacity-25"
              >
                <ArrowDown size={13} />
              </button>
            </>
          )}
          {canRemove && (
            <button
              type="button"
              aria-label={`Remove ${entry.title}`}
              title="Remove from playlist"
              onClick={onRemove}
              className="grid size-7 place-items-center rounded-full text-secondary hover:bg-quinary hover:text-red"
            >
              <Trash2 size={14} />
            </button>
          )}
          {!track.unavailable && (
            <TrackMenu
              track={track}
              className="grid size-7 place-items-center rounded-full text-key hover:bg-quinary data-open:grid"
            />
          )}
        </div>
      </div>
    </div>
  );
}

function AddSongsPanel({
  onAdd,
  isAdding,
}: {
  onAdd: (track: CollabTrackInput) => void;
  isAdding: boolean;
}) {
  const { ready: ytReady } = useYouTubeReady();
  const [query, setQuery] = useState('');
  const [debounced, setDebounced] = useState('');
  const [manualTitle, setManualTitle] = useState('');
  const [manualArtist, setManualArtist] = useState('');

  useEffect(() => {
    const t = window.setTimeout(() => setDebounced(query.trim()), 240);
    return () => window.clearTimeout(t);
  }, [query]);

  const search = useSearch(ytReady ? debounced : '');
  const results = search.data?.songs.slice(0, 6) ?? [];
  const directVideoId = parseYouTubeVideoId(query);

  return (
    <div className="mx-(--body-gutter) mb-6">
      <div className="relative max-w-[520px]">
        <Search size={15} className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-secondary" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.currentTarget.value)}
          placeholder={
            ytReady
              ? 'Add songs to this collaborative playlist…'
              : 'Paste a YouTube link or 11-char video ID to add a song…'
          }
          className="h-9 w-full rounded-pill border border-search-border bg-quinary pr-8 pl-9 type-body text-primary placeholder:text-tertiary [&::-webkit-search-cancel-button]:hidden"
        />
        {query && (
          <button
            type="button"
            aria-label="Clear search"
            onClick={() => setQuery('')}
            className="absolute top-1/2 right-2.5 grid size-4 -translate-y-1/2 place-items-center rounded-full bg-tertiary text-page"
          >
            <X size={11} strokeWidth={3} />
          </button>
        )}
      </div>

      {ytReady && debounced.length > 0 && (
        <div className="mt-2.5 max-w-[640px] overflow-hidden rounded-[12px] bg-quinary p-1.5">
          {search.isPending ? (
            <p className="px-3 py-2 type-callout text-secondary">Searching YouTube Music…</p>
          ) : results.length === 0 ? (
            <p className="px-3 py-2 type-callout text-secondary">No songs found for “{debounced}”.</p>
          ) : (
            <ul>
              {results.map((item) => (
                <li
                  key={item.id}
                  className="flex items-center justify-between gap-3 rounded-[8px] px-2 py-1.5 hover:bg-quaternary"
                >
                  <div className="flex min-w-0 items-center gap-2.5">
                    <div className="w-9 flex-none">
                      <Artwork src={item.thumbnail} displayWidth={36} radius="var(--radius-artwork-small)" />
                    </div>
                    <div className="min-w-0">
                      <p className="truncate type-body text-primary">{item.title}</p>
                      <p className="truncate type-callout text-secondary">{item.subtitle}</p>
                    </div>
                  </div>
                  <button
                    type="button"
                    disabled={isAdding}
                    onClick={() => onAdd(trackToCollabInput(toTrack(item), 'youtube'))}
                    className="inline-flex h-7 flex-none items-center gap-1 rounded-pill bg-key-bg px-3 type-callout-emphasized text-white hover:opacity-95 disabled:opacity-45"
                  >
                    <Plus size={13} strokeWidth={2.5} />
                    <span>Add</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {!ytReady && directVideoId && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (!manualTitle.trim()) return;
            onAdd({
              trackId: directVideoId,
              videoId: directVideoId,
              provider: 'youtube',
              title: manualTitle.trim(),
              artist: manualArtist.trim() || 'Unknown Artist',
              thumbnailUrl: `https://i.ytimg.com/vi/${directVideoId}/hqdefault.jpg`,
            });
            setQuery('');
            setManualTitle('');
            setManualArtist('');
          }}
          className="mt-2.5 flex max-w-[640px] flex-wrap items-center gap-2 rounded-[12px] bg-quinary p-3"
        >
          <input
            required
            placeholder="Song title"
            value={manualTitle}
            onChange={(e) => setManualTitle(e.currentTarget.value)}
            className="min-w-[160px] flex-1 rounded-[8px] border-0 bg-quaternary px-2.5 py-1.5 type-body text-primary"
          />
          <input
            placeholder="Artist"
            value={manualArtist}
            onChange={(e) => setManualArtist(e.currentTarget.value)}
            className="min-w-[140px] flex-1 rounded-[8px] border-0 bg-quaternary px-2.5 py-1.5 type-body text-primary"
          />
          <button
            type="submit"
            disabled={!manualTitle.trim() || isAdding}
            className="inline-flex h-8 items-center gap-1 rounded-pill bg-key-bg px-3.5 type-callout-emphasized text-white disabled:opacity-45"
          >
            <Plus size={14} />
            <span>Add Song</span>
          </button>
        </form>
      )}
    </div>
  );
}
