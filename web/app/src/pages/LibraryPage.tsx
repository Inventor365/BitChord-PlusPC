import { Link2, Plus, Users } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { Navigate, useParams } from 'react-router';
import { useCollabAuth, useCollabSignInPrompt } from '@/collab/auth';
import { useCollabPlaylists } from '@/collab/queries';
import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';
import { CollabAccountPill } from '@/components/collab/CollabAvatars';
import { CreateCollabPlaylistModal, JoinCollabInviteModal } from '@/components/collab/CollabModals';
import { CircleLockup, SquareLockup, VideoLockup } from '@/components/music/Lockups';
import { CollabPlaylistLockup } from '@/components/music/PlaylistMenu';
import { EmptyState, LoadError, LoadMoreError, NeedsExtension } from '@/components/music/States';
import { TrackList } from '@/components/music/TrackList';
import { isLibrarySection, LIBRARY_SECTIONS } from '@/data/music/library';
import { useLibrary, useSignedIn, useYouTubeReady } from '@/data/music/queries';
import type { MusicItem } from '@/data/music/types';
import { useSpotifyPlaylists } from '@/data/spotify/queries';
import { useSpotifySignedIn } from '@/extension/useSpotifyAuth';
import { useYtAuth } from '@/extension/useYtAuth';
import { PlainSkeleton } from '@/skeletons/PageSkeletons';
import { seeAllGrid, seeAllTracks } from './pageStyles';

/** Asks for the next page when [target] scrolls into view. */
function useLoadMore(onVisible: (() => void) | null) {
  const target = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const node = target.current;
    if (!node || !onVisible) return;
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && onVisible(), {
      rootMargin: '600px 0px',
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [onVisible]);
  return target;
}

const sectionHeading = 'mx-(--body-gutter) mt-8 mb-1 flex items-center gap-2 type-title-1-emphasized';
const actionPill =
  'inline-flex h-8 items-center gap-1.5 rounded-pill border border-quaternary bg-quinary px-3.5 type-callout-emphasized text-primary hover:bg-quaternary';

function PlaylistsSection() {
  const { missing } = useYouTubeReady();
  const ytAuth = useYtAuth();
  const ytSignedIn = useSignedIn();
  const spotifySignedIn = useSpotifySignedIn();
  const collabUser = useCollabAuth((s) => s.user);
  const requestSignIn = useCollabSignInPrompt((s) => s.requestSignIn);

  const collabPlaylists = useCollabPlaylists(true);
  const ytLibrary = useLibrary('playlists');
  const spotifyPlaylists = useSpotifyPlaylists();

  const [creatingCollab, setCreatingCollab] = useState(false);
  const [joiningInvite, setJoiningInvite] = useState(false);

  const loadMore = useLoadMore(
    ytSignedIn && !missing && ytLibrary.hasNextPage && !ytLibrary.isFetchingNextPage && !ytLibrary.isFetchNextPageError
      ? () => void ytLibrary.fetchNextPage()
      : null,
  );

  const collabList = collabUser ? (collabPlaylists.data ?? []) : [];
  const ownedCollab = collabList.filter((p) => p.myRole === 'OWNER' || p.ownerUserId === collabUser?.id);
  const sharedWithMe = collabList.filter((p) => p.myRole !== 'OWNER' && p.ownerUserId !== collabUser?.id);
  const hasCollab = ownedCollab.length > 0 || sharedWithMe.length > 0;

  const ytItems: MusicItem[] =
    !missing && ytSignedIn && ytLibrary.data ? ytLibrary.data.pages.flatMap((page) => page.data.grid) : [];
  const spotifyItems: MusicItem[] =
    !missing && spotifySignedIn && spotifyPlaylists.data
      ? spotifyPlaylists.data.map((p) => ({
          kind: 'spotify-playlist',
          id: p.id,
          title: p.name,
          subtitle: `Spotify · ${p.owner ?? 'Spotify'}`,
          artists: [],
          album: null,
          thumbnail: p.imageUrl,
          wide: false,
          durationSec: null,
          explicit: false,
        }))
      : [];
  const personalPlaylists = [...ytItems, ...spotifyItems];
  const loadingPersonal =
    (!missing && ytSignedIn && !ytLibrary.data && !ytLibrary.isError) ||
    (!missing && spotifySignedIn && !spotifyPlaylists.data && !spotifyPlaylists.isError);

  return (
    <>
      <div className="mx-(--body-gutter) flex flex-wrap items-center justify-between gap-3 pt-8">
        <h1 className="type-header-emphasized">{LIBRARY_SECTIONS.playlists.title}</h1>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() =>
              collabUser
                ? setCreatingCollab(true)
                : requestSignIn({
                    reason: 'Sign in with Google to create a new collaborative playlist.',
                    onSignedIn: () => setCreatingCollab(true),
                  })
            }
            className="inline-flex h-8 items-center gap-1.5 rounded-pill bg-key-bg px-3.5 type-callout-emphasized text-white hover:opacity-95"
          >
            <Plus size={14} strokeWidth={2.5} />
            <span>New Collaborative Playlist</span>
          </button>
          <button type="button" onClick={() => setJoiningInvite(true)} className={actionPill}>
            <Link2 size={14} />
            <span>Join via Invite</span>
          </button>
          <CollabAccountPill />
        </div>
      </div>

      {/* Collaborative Playlists (Owned) */}
      {ownedCollab.length > 0 && (
        <section aria-label="Collaborative Playlists">
          <h2 className={sectionHeading}>
            <Users size={19} className="text-key" />
            <span>Collaborative Playlists</span>
          </h2>
          <ul className={seeAllGrid}>
            {ownedCollab.map((playlist) => (
              <li key={playlist.id}>
                <CollabPlaylistLockup playlist={playlist} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* Shared with Me */}
      {sharedWithMe.length > 0 && (
        <section aria-label="Shared with Me">
          <h2 className={sectionHeading}>Shared with Me</h2>
          <ul className={seeAllGrid}>
            {sharedWithMe.map((playlist) => (
              <li key={playlist.id}>
                <CollabPlaylistLockup playlist={playlist} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* My Playlists (Personal YouTube Music & Spotify) */}
      {personalPlaylists.length > 0 && (
        <section aria-label="My Playlists">
          {hasCollab && <h2 className={sectionHeading}>My Playlists</h2>}
          <ul className={seeAllGrid}>
            {personalPlaylists.map((item, i) => (
              <li key={`${item.kind}-${item.id}-${i}`}>
                <SquareLockup item={item} />
              </li>
            ))}
          </ul>
          <div ref={loadMore} aria-hidden="true" />
          {ytLibrary.isFetchNextPageError && <LoadMoreError onRetry={() => void ytLibrary.fetchNextPage()} />}
        </section>
      )}

      {loadingPersonal && !hasCollab && <PlainSkeleton />}

      {!loadingPersonal && !hasCollab && personalPlaylists.length === 0 && (
        <EmptyState
          title={collabUser ? 'No playlists yet' : 'Collaborate on playlists or connect your library'}
          detail={
            missing
              ? 'Collaborative playlists work right here with Google sign-in. Install the Sonara Extension anytime to also browse your YouTube Music and Spotify playlists.'
              : ytAuth.state === 'ready' && !ytSignedIn
                ? 'Start a Collaborative Playlist above, or sign in on music.youtube.com to see your personal YouTube Music playlists.'
                : 'Create a Collaborative Playlist above, join a friend’s invite link, or save playlists on YouTube Music.'
          }
          action={
            missing ? (
              <PrefetchLink
                to="/extension"
                className="inline-flex h-8 items-center rounded-pill bg-quinary px-4 type-callout-emphasized text-primary hover:bg-quaternary"
              >
                Sonara Extension
              </PrefetchLink>
            ) : undefined
          }
        />
      )}

      {creatingCollab && <CreateCollabPlaylistModal onClose={() => setCreatingCollab(false)} />}
      {joiningInvite && <JoinCollabInviteModal onClose={() => setJoiningInvite(false)} />}
    </>
  );
}

/** A library section (prompt §5.3): Recently Played, Artists, Albums, Songs, Made for You, All Playlists. */
export default function LibraryPage() {
  const section = useParams().section ?? '';
  const { missing } = useYouTubeReady();
  const auth = useYtAuth();
  const signedIn = useSignedIn();
  const isPlaylists = section === 'playlists';
  const library = useLibrary(isLibrarySection(section) && !isPlaylists ? section : 'recent');
  const loadMore = useLoadMore(
    !isPlaylists && library.hasNextPage && !library.isFetchingNextPage && !library.isFetchNextPageError
      ? () => void library.fetchNextPage()
      : null,
  );

  if (!isLibrarySection(section)) return <Navigate to="/home" replace />;
  if (isPlaylists) return <PlaylistsSection />;

  const title = LIBRARY_SECTIONS[section].title;
  if (missing) return <NeedsExtension what="your library" />;
  if (auth.state === 'ready' && !signedIn) {
    return (
      <>
        <PageHeader title={title} />
        <EmptyState
          title="Sign in to see your library"
          detail="Sonara uses the YouTube Music account this browser is signed in to. Sign in on music.youtube.com, then come back."
        />
      </>
    );
  }
  if (library.isError && !library.data) return <LoadError error={library.error} onRetry={() => void library.refetch()} />;
  if (!library.data) return <PlainSkeleton />;

  const grid = library.data.pages.flatMap((page) => page.data.grid);
  const tracks = library.data.pages.flatMap((page) => page.data.tracks);
  return (
    <>
      <PageHeader title={title} />
      {!grid.length && !tracks.length && <EmptyState title="Nothing here yet" />}
      {grid.length > 0 && (
        <ul className={seeAllGrid} data-wide={grid.every((i) => i.wide) || undefined}>
          {grid.map((item, i) => (
            <li key={`${item.id}-${i}`}>
              {item.kind === 'artist' ? (
                <CircleLockup item={item} />
              ) : item.wide ? (
                <VideoLockup item={item} />
              ) : (
                <SquareLockup item={item} />
              )}
            </li>
          ))}
        </ul>
      )}
      {tracks.length > 0 && (
        <div className={seeAllTracks}>
          <TrackList
            tracks={tracks}
            variant="playlist"
            source={{ title, type: 'library', id: section }}
            onNearEnd={
              library.hasNextPage && !library.isFetchingNextPage && !library.isFetchNextPageError
                ? () => void library.fetchNextPage()
                : undefined
            }
          />
        </div>
      )}
      {grid.length > 0 && <div ref={loadMore} aria-hidden="true" />}
      {library.isFetchNextPageError && <LoadMoreError onRetry={() => void library.fetchNextPage()} />}
    </>
  );
}
