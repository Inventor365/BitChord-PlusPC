import { ExternalLink, Link2, Lock, LogOut, MoreHorizontal, Shuffle, Trash2, Users } from 'lucide-react';
import { useCallback, useState } from 'react';
import { useNavigate } from 'react-router';
import { useCollabAuth } from '@/collab/auth';
import { getCachedCollabDetail, getCollabPlaylist } from '@/collab/client';
import { useDeleteCollabPlaylist, useLeaveCollabPlaylist } from '@/collab/queries';
import {
  collabEntryToTrack,
  evaluateCollabPermissions,
  type CollabPlaylistSummary,
  type CollabSourceType,
} from '@/collab/types';
import { Menu, MenuItem, MenuSeparator, type MenuPlacement } from '@/components/Menu';
import { PrefetchLink } from '@/components/PrefetchLink';
import {
  CollabInviteModal,
  MakeCollaborativeModal,
  StopSharingModal,
  type MakeCollaborativeSource,
} from '@/components/collab/CollabModals';
import { CollabAvatarStack, CollabCover } from '@/components/collab/CollabAvatars';
import { PlayIcon } from '@/components/icons/Transport';
import { fetchPlaylist } from '@/data/music/api';
import type { Track } from '@/data/music/types';
import { fetchSpotifyTracks, pendingTrack } from '@/data/spotify/api';
import { playList, shuffleList } from './play';

async function loadAllYtmPlaylistTracks(playlistId: string): Promise<Track[]> {
  const first = await fetchPlaylist(playlistId);
  const all = [...first.page.tracks];
  let next = first.more;
  let pages = 0;
  while (next && pages < 15 && all.length < 2000) {
    const chunk = await next();
    all.push(...chunk.data);
    next = chunk.next;
    pages++;
  }
  return all;
}

async function loadSpotifyPlaylistAsTracks(playlistId: string): Promise<Track[]> {
  const tracks = await fetchSpotifyTracks(playlistId, () => {});
  return tracks.map((t, idx) => ({ ...pendingTrack(t, 'missing'), index: idx + 1 }));
}

/**
 * Contextual `⋯` menu for personal YouTube Music & Spotify playlists,
 * including the first-party "Make Collaborative" conversion flow.
 */
export function PlaylistMenu({
  playlistId,
  sourceType = 'ytm',
  title,
  description,
  artworkUrl,
  tracks,
  className,
  placement,
  onPlay,
  onShuffle,
}: {
  playlistId: string;
  sourceType?: CollabSourceType;
  title: string;
  description?: string | null;
  artworkUrl?: string | null;
  tracks?: readonly Track[];
  className?: string;
  placement?: MenuPlacement;
  onPlay?: () => void;
  onShuffle?: () => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [converting, setConverting] = useState<MakeCollaborativeSource | null>(null);
  const close = useCallback(() => setAnchor(null), []);

  const openMakeCollab = () => {
    close();
    setConverting({
      sourcePlaylistId: playlistId,
      sourceType,
      title,
      description,
      artworkUrl,
      tracks,
      loadTracks: tracks
        ? undefined
        : () =>
            sourceType === 'spotify'
              ? loadSpotifyPlaylistAsTracks(playlistId)
              : loadAllYtmPlaylistTracks(playlistId),
    });
  };

  return (
    <>
      <button
        type="button"
        className={className}
        aria-label={`More options for ${title}`}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        data-open={anchor !== null || undefined}
        onClick={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setAnchor(anchor ? null : e.currentTarget);
        }}
      >
        <MoreHorizontal size={18} strokeWidth={2} />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement={placement} onClose={close} label={title}>
          {onPlay && (
            <MenuItem
              icon={<PlayIcon size={14} />}
              onSelect={() => {
                close();
                onPlay();
              }}
            >
              Play
            </MenuItem>
          )}
          {onShuffle && (
            <MenuItem
              icon={<Shuffle size={14} />}
              onSelect={() => {
                close();
                onShuffle();
              }}
            >
              Shuffle
            </MenuItem>
          )}
          {(onPlay || onShuffle) && <MenuSeparator />}
          <MenuItem icon={<Users size={15} />} onSelect={openMakeCollab}>
            Make Collaborative…
          </MenuItem>
        </Menu>
      )}
      {converting && <MakeCollaborativeModal source={converting} onClose={() => setConverting(null)} />}
    </>
  );
}

/**
 * First-party Collaborative Playlist lockup card matching SquareLockup:
 * artwork (or mosaic/gradient), subtle collaborative indicator, overlapping
 * circular member avatar stack, Play platter, and contextual menu.
 */
export function CollabPlaylistLockup({
  playlist,
  width = 220,
}: {
  playlist: CollabPlaylistSummary;
  width?: number;
}) {
  const navigate = useNavigate();
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  const perms = evaluateCollabPermissions(playlist, userId);
  const cachedDetail = getCachedCollabDetail(playlist.id, userId);

  const leave = useLeaveCollabPlaylist();
  const del = useDeleteCollabPlaylist();

  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [showInvite, setShowInvite] = useState(false);
  const [showStopSharing, setShowStopSharing] = useState(false);
  const close = useCallback(() => setAnchor(null), []);

  const href = `/collab/${encodeURIComponent(playlist.id)}`;

  const playFromCard = async (shuffle = false) => {
    const detail = cachedDetail ?? (await getCollabPlaylist(playlist.id).catch(() => null));
    if (!detail) return;
    const playable = detail.tracks.map(collabEntryToTrack).filter((t) => !t.unavailable);
    if (!playable.length) {
      navigate(href);
      return;
    }
    const ctx = {
      source: { title: detail.title, type: 'playlist' as const, id: `collab:${detail.id}` },
      fallbackArtwork: detail.artworkUrl,
    };
    if (shuffle) shuffleList(playable, ctx);
    else playList(playable, 0, ctx);
  };

  const ownerLabel = userId && playlist.ownerUserId === userId ? 'You' : playlist.ownerName;

  return (
    <>
      <div className="flex min-w-0 flex-col">
        <div className="group/art relative rounded-artwork-md">
          <CollabCover
            artworkUrl={playlist.artworkUrl}
            tracks={cachedDetail?.tracks}
            title={playlist.title}
            displayWidth={width}
            radius="var(--radius-artwork-medium)"
          />
          <PrefetchLink to={href} className="absolute inset-0 z-1 rounded-[inherit]" aria-label={playlist.title} />

          {/* Subtle collaborative badge at top-left */}
          <span className="pointer-events-none absolute top-2 left-2 z-2 inline-flex items-center gap-1 rounded-pill bg-thin-material px-2 py-0.5 type-caption-2 text-white [backdrop-filter:saturate(180%)_blur(24px)]">
            {playlist.sharingEnabled ? <Users size={11} /> : <Lock size={11} />}
            <span>{playlist.sharingEnabled ? 'Shared' : 'Stopped'}</span>
          </span>

          {/* Overlapping circular member avatar stack at bottom-right */}
          <div className="pointer-events-none absolute right-2 bottom-2 z-2">
            <CollabAvatarStack
              members={playlist.memberAvatars}
              totalCount={playlist.memberCount}
              size={22}
              max={3}
              ringClass="ring-[1.5px] ring-black/45"
            />
          </div>

          {/* Hover scrim + Play platter */}
          <div className="pointer-events-none absolute inset-0 z-2 rounded-[inherit] bg-lockup-hover opacity-0 transition-opacity duration-100 ease-in group-focus-visible/art:opacity-100 group-hover/art:opacity-100">
            {playlist.trackCount > 0 && (
              <button
                type="button"
                className="pointer-events-auto absolute bottom-2.5 left-2.5 grid size-[30px] place-items-center rounded-full bg-thin-material text-white [backdrop-filter:saturate(180%)_blur(60px)] hover:bg-key-bg"
                aria-label={`Play ${playlist.title}`}
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  void playFromCard(false);
                }}
              >
                <PlayIcon size={16} />
              </button>
            )}
          </div>

          {/* Contextual ⋯ menu */}
          <button
            type="button"
            aria-label={`More options for ${playlist.title}`}
            aria-haspopup="menu"
            aria-expanded={anchor !== null}
            data-open={anchor !== null || undefined}
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
              setAnchor(anchor ? null : e.currentTarget);
            }}
            className="absolute top-2 right-2 z-3 grid size-7 place-items-center rounded-full bg-thin-material text-white opacity-0 transition-opacity duration-100 ease-in [backdrop-filter:saturate(180%)_blur(60px)] group-hover/art:opacity-100 focus-visible:opacity-100 hover:bg-key-bg data-open:opacity-100"
          >
            <MoreHorizontal size={18} strokeWidth={2} />
          </button>
        </div>

        <div className="mt-2 flex min-w-0 flex-col gap-px">
          <div className="flex min-w-0 items-center gap-1 type-callout [&>:first-child]:truncate">
            <PrefetchLink to={href} className="hover:underline">
              {playlist.title}
            </PrefetchLink>
          </div>
          <div className="truncate type-callout text-secondary">
            {ownerLabel} · {playlist.trackCount} {playlist.trackCount === 1 ? 'song' : 'songs'}
          </div>
        </div>
      </div>

      {anchor && (
        <Menu anchor={anchor} onClose={close} label={playlist.title}>
          <MenuItem
            icon={<ExternalLink size={14} />}
            onSelect={() => {
              close();
              navigate(href);
            }}
          >
            Open
          </MenuItem>
          {playlist.trackCount > 0 && (
            <MenuItem
              icon={<PlayIcon size={14} />}
              onSelect={() => {
                close();
                void playFromCard(false);
              }}
            >
              Play
            </MenuItem>
          )}
          {perms.canInvite && (
            <MenuItem
              icon={<Link2 size={14} />}
              onSelect={() => {
                close();
                setShowInvite(true);
              }}
            >
              Invite collaborators…
            </MenuItem>
          )}
          <MenuSeparator />
          {perms.canStopSharing && (
            <MenuItem
              icon={<Lock size={14} />}
              onSelect={() => {
                close();
                setShowStopSharing(true);
              }}
            >
              Stop sharing…
            </MenuItem>
          )}
          {perms.canLeavePlaylist && (
            <MenuItem
              destructive
              icon={<LogOut size={14} />}
              onSelect={() => {
                close();
                leave.mutate(playlist.id);
              }}
            >
              Leave playlist
            </MenuItem>
          )}
          {perms.canDeletePlaylist && (
            <MenuItem
              destructive
              icon={<Trash2 size={14} />}
              onSelect={() => {
                close();
                del.mutate(playlist.id);
              }}
            >
              Delete
            </MenuItem>
          )}
        </Menu>
      )}

      {showInvite && <CollabInviteModal playlist={playlist} onClose={() => setShowInvite(false)} />}
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
