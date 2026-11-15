import { ChevronLeft, ChevronRight, ListEnd, ListPlus, ListStart, MoreHorizontal, Star, Users } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { useCollabAuth, useCollabSignedIn } from '@/collab/auth';
import { addCollabTracks } from '@/collab/client';
import { collabKeys, useCollabPlaylists } from '@/collab/queries';
import { createOpId, evaluateCollabPermissions, trackToCollabInput, type CollabPlaylistSummary } from '@/collab/types';
import { collabSocket } from '@/collab/ws';
import { Menu, MenuHeader, MenuItem, MenuSeparator, type MenuPlacement } from '@/components/Menu';
import { queryClient } from '@/lib/queryClient';
import {
  useAddToPlaylist,
  useLikeStatus,
  usePlaylistOptions,
  useSetLiked,
  useSignedIn,
} from '@/data/music/queries';
import type { Track } from '@/data/music/types';
import { usePlayer } from '@/state/player';

function PlaylistChoices({ videoId, onDone, onBack }: { videoId: string; onDone: () => void; onBack: () => void }) {
  const options = usePlaylistOptions(videoId, true);
  const add = useAddToPlaylist();
  return (
    <>
      <MenuItem icon={<ChevronLeft size={14} />} onSelect={onBack}>
        Back
      </MenuItem>
      <MenuSeparator />
      <MenuHeader>Add to Playlist</MenuHeader>
      {options.isPending && (
        <MenuItem disabled onSelect={() => {}}>
          Loading…
        </MenuItem>
      )}
      {options.isError && (
        <MenuItem disabled onSelect={() => {}}>
          Couldn’t load your playlists
        </MenuItem>
      )}
      {options.data?.length === 0 && (
        <MenuItem disabled onSelect={() => {}}>
          No playlists yet
        </MenuItem>
      )}
      {options.data?.map((option) => (
        <MenuItem
          key={option.playlistId}
          onSelect={() => {
            add.mutate({ playlistId: option.playlistId, videoId });
            onDone();
          }}
        >
          {option.title}
        </MenuItem>
      ))}
    </>
  );
}

function CollabPlaylistChoices({
  track,
  playlists,
  onDone,
  onBack,
}: {
  track: Track;
  playlists: CollabPlaylistSummary[];
  onDone: () => void;
  onBack: () => void;
}) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return (
    <>
      <MenuItem icon={<ChevronLeft size={14} />} onSelect={onBack}>
        Back
      </MenuItem>
      <MenuSeparator />
      <MenuHeader>Add to Collaborative Playlist</MenuHeader>
      {playlists.map((pl) => (
        <MenuItem
          key={pl.id}
          icon={<Users size={14} />}
          onSelect={() => {
            const opId = createOpId('add');
            collabSocket.registerLocalOpId(pl.id, opId);
            void addCollabTracks(pl.id, {
              tracks: [trackToCollabInput(track, 'youtube')],
              opId,
            }).then(() => {
              void queryClient.invalidateQueries({ queryKey: collabKeys.playlists(userId) });
              void queryClient.invalidateQueries({ queryKey: collabKeys.detail(pl.id, userId) });
            });
            onDone();
          }}
        >
          {pl.title}
        </MenuItem>
      ))}
    </>
  );
}

/** Favorite and Add to Playlist: the account's actions, signed in only. */
function AccountItems({ track, onClose, onPlaylists }: { track: Track; onClose: () => void; onPlaylists: () => void }) {
  const status = useLikeStatus(track.videoId);
  const setLiked = useSetLiked();
  const liked = status.data === 'LIKE';
  return (
    <>
      <MenuSeparator />
      <MenuItem
        icon={<Star size={15} fill={liked ? 'currentColor' : 'none'} />}
        disabled={status.isPending}
        onSelect={() => {
          setLiked.mutate({ videoId: track.videoId, liked: !liked });
          onClose();
        }}
      >
        {liked ? 'Unfavorite' : 'Favorite'}
      </MenuItem>
      <MenuItem icon={<ListPlus size={15} />} trailing={<ChevronRight size={14} />} onSelect={onPlaylists}>
        Add to Playlist
      </MenuItem>
    </>
  );
}

function TrackMenuItems({ track, onClose }: { track: Track; onClose: () => void }) {
  const [view, setView] = useState<'main' | 'playlists' | 'collab-playlists'>('main');
  const signedIn = useSignedIn();
  const collabSignedIn = useCollabSignedIn();
  const collabUserId = useCollabAuth((s) => s.user?.id ?? null);
  const collabPlaylists = useCollabPlaylists(false);
  const editableCollab =
    collabSignedIn && collabPlaylists.data
      ? collabPlaylists.data.filter((pl) => evaluateCollabPermissions(pl, collabUserId).canAddTracks)
      : [];

  if (view === 'playlists') {
    return <PlaylistChoices videoId={track.videoId} onDone={onClose} onBack={() => setView('main')} />;
  }
  if (view === 'collab-playlists') {
    return (
      <CollabPlaylistChoices
        track={track}
        playlists={editableCollab}
        onDone={onClose}
        onBack={() => setView('main')}
      />
    );
  }
  const { playNext, addToQueue } = usePlayer.getState();
  return (
    <>
      <MenuItem icon={<ListStart size={15} />} onSelect={() => (onClose(), playNext([track]))}>
        Play Next
      </MenuItem>
      <MenuItem icon={<ListEnd size={15} />} onSelect={() => (onClose(), addToQueue([track]))}>
        Add to Queue
      </MenuItem>
      {editableCollab.length > 0 && (
        <>
          <MenuSeparator />
          <MenuItem
            icon={<Users size={15} />}
            trailing={<ChevronRight size={14} />}
            onSelect={() => setView('collab-playlists')}
          >
            Add to collaborative playlist
          </MenuItem>
        </>
      )}
      {signedIn && <AccountItems track={track} onClose={onClose} onPlaylists={() => setView('playlists')} />}
    </>
  );
}

/**
 * The ⋯ button at the end of a track row (report §14): Play Next and Add to
 * Queue for everyone; Favorite and Add to Playlist signed in.
 */
export function TrackMenu({
  track,
  className,
  placement,
  onOpenChange,
}: {
  track: Track;
  className?: string;
  /** Where the menu opens from the button; the player's sit at the bottom of the window. */
  placement?: MenuPlacement;
  /** Told when the menu opens and closes; most mounts don't need to know. */
  onOpenChange?: (open: boolean) => void;
}) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);
  useEffect(() => {
    onOpenChange?.(anchor !== null);
  }, [anchor, onOpenChange]);
  return (
    <>
      <button
        className={className}
        aria-label={`More options for ${track.title}`}
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        data-open={anchor !== null || undefined}
        onClick={(event) => {
          event.stopPropagation();
          setAnchor(anchor ? null : event.currentTarget);
        }}
        onDoubleClick={(event) => event.stopPropagation()}
      >
        <MoreHorizontal size={18} strokeWidth={2} />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement={placement} onClose={close} label={track.title}>
          <TrackMenuItems track={track} onClose={close} />
        </Menu>
      )}
    </>
  );
}
