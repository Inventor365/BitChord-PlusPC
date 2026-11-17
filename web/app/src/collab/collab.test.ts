import { describe, expect, it } from 'vitest';
import { derivePkceChallengeS256, syncYtExtensionToCollab, useCollabAuth } from './auth';
import { sanitizeCollabServerUrl } from './config';
import {
  cleanCollabInviteToken,
  formatCollabDeepLink,
  formatCollabInviteUrl,
  parseCollabInviteLink,
  ytmCollaboratorsPanelParams,
  ytmInviteBrowseParams,
} from './inviteLink';
import {
  hasPlaylistEditAffordance,
  parseAddToPlaylistIds,
  parseCollabJoinEndpoints,
  parseCollaboratorsPanel,
  ytmErrorStatus,
  ytmJoinFailureMessage,
} from './ytmCollab';
import {
  applyCollabWsFrame,
  buildConvertPayload,
  canRemoveCollabTrack,
  collabEntryToTrack,
  DEFAULT_COLLAB_PERMISSIONS,
  evaluateCollabPermissions,
  normalizePlaylistDetail,
  trackToCollabInput,
  type CollabPlaylistDetail,
  type CollabTrackEntry,
} from './types';
import { computeReconnectDelayMs } from './ws';
import type { Track } from '@/data/music/types';
import { extractCreatedPlaylistIdFromData } from '@/data/music/api';

const SAMPLE_TOKEN = 'bci_AbCdEfGhIjKlMnOpQrStUvWxYz012345';

function makeSampleDetail(overrides?: Partial<CollabPlaylistDetail>): CollabPlaylistDetail {
  return normalizePlaylistDetail(
    {
      id: 'cpl_1122334455',
      title: 'Late Night Synth',
      description: 'Roadtrip picks',
      artworkUrl: 'https://img.example.com/cover.jpg',
      ownerUserId: 'usr_owner',
      ownerName: 'Saksham',
      ownerAvatarUrl: 'https://img.example.com/saksham.jpg',
      myRole: 'OWNER',
      sharingEnabled: true,
      permissions: { ...DEFAULT_COLLAB_PERMISSIONS },
      sourceType: 'ytm',
      sourcePlaylistId: 'VLPL123',
      memberCount: 3,
      trackCount: 2,
      revision: 4,
      createdAtMs: 1_700_000_000_000,
      updatedAtMs: 1_700_000_005_000,
      members: [
        {
          playlistId: 'cpl_1122334455',
          userId: 'usr_owner',
          role: 'OWNER',
          displayName: 'Saksham',
          avatarUrl: 'https://img.example.com/saksham.jpg',
          joinedAtMs: 1_700_000_000_000,
        },
        {
          playlistId: 'cpl_1122334455',
          userId: 'usr_editor',
          role: 'EDITOR',
          displayName: 'Aarav',
          avatarUrl: 'https://img.example.com/aarav.jpg',
          joinedAtMs: 1_700_000_001_000,
        },
        {
          playlistId: 'cpl_1122334455',
          userId: 'usr_viewer',
          role: 'VIEWER',
          displayName: 'Meera',
          avatarUrl: null,
          joinedAtMs: 1_700_000_002_000,
        },
      ],
      tracks: [
        {
          entryId: 'cte_1',
          playlistId: 'cpl_1122334455',
          position: 0,
          trackId: 'fHI8X4OXluQ',
          provider: 'youtube',
          title: 'Blinding Lights',
          artist: 'The Weeknd',
          albumName: 'After Hours',
          thumbnailUrl: 'https://img.example.com/t1.jpg',
          durationMs: 200_000,
          isExplicit: false,
          contributorId: 'usr_owner',
          contributorName: 'Saksham',
          contributorAvatarUrl: 'https://img.example.com/saksham.jpg',
          addedAtMs: 1_700_000_000_500,
        },
        {
          entryId: 'cte_2',
          playlistId: 'cpl_1122334455',
          position: 1,
          trackId: '4NRXx6U8ABQ',
          provider: 'youtube',
          title: 'Midnight City',
          artist: 'M83',
          albumName: 'Hurry Up, We Are Dreaming',
          thumbnailUrl: 'https://img.example.com/t2.jpg',
          durationMs: 243_000,
          isExplicit: false,
          contributorId: 'usr_editor',
          contributorName: 'Aarav',
          contributorAvatarUrl: 'https://img.example.com/aarav.jpg',
          addedAtMs: 1_700_000_003_000,
        },
      ],
      activities: [
        {
          id: 'act_1',
          playlistId: 'cpl_1122334455',
          actorUserId: 'usr_editor',
          actorName: 'Aarav',
          actorAvatarUrl: 'https://img.example.com/aarav.jpg',
          action: 'tracks.added',
          summary: 'Aarav added "Midnight City".',
          createdAtMs: 1_700_000_003_000,
        },
      ],
      ...overrides,
    },
    'usr_owner',
  );
}

describe('collaborative invite links', () => {
  it('parses web URLs, relative paths, bitchord:// deep links, intent URIs, and raw tokens', () => {
    expect(parseCollabInviteLink(SAMPLE_TOKEN)).toEqual({
      token: SAMPLE_TOKEN,
      serverOrigin: null,
    });

    expect(parseCollabInviteLink(`/collab/invite/${SAMPLE_TOKEN}`)).toEqual({
      token: SAMPLE_TOKEN,
      serverOrigin: null,
    });

    expect(
      parseCollabInviteLink(`/collab/invite/${SAMPLE_TOKEN}?server=https%3A%2F%2Fcollab.bitchord.org%2F`),
    ).toEqual({
      token: SAMPLE_TOKEN,
      serverOrigin: 'https://collab.bitchord.org',
    });

    expect(parseCollabInviteLink(`https://collab.bitchord.org/collab/invite/${SAMPLE_TOKEN}`)).toEqual({
      token: SAMPLE_TOKEN,
      serverOrigin: 'https://collab.bitchord.org',
    });

    expect(
      parseCollabInviteLink(
        `https://app.bitchord.org/collab/invite/${SAMPLE_TOKEN}?server=https%3A%2F%2Fapi.bitchord.org`,
      ),
    ).toEqual({
      token: SAMPLE_TOKEN,
      serverOrigin: 'https://api.bitchord.org',
    });

    expect(
      parseCollabInviteLink(`bitchord://collab/invite/${SAMPLE_TOKEN}?server=https%3A%2F%2Fcollab.bitchord.org`),
    ).toEqual({
      token: SAMPLE_TOKEN,
      serverOrigin: 'https://collab.bitchord.org',
    });

    expect(
      parseCollabInviteLink(
        `intent://collab/invite/${SAMPLE_TOKEN}?server=https%3A%2F%2Fcollab.bitchord.org#Intent;scheme=bitchord;end`,
      ),
    ).toEqual({
      token: SAMPLE_TOKEN,
      serverOrigin: 'https://collab.bitchord.org',
    });
  });

  it('rejects malformed tokens, party codes, and non-http origins', () => {
    expect(cleanCollabInviteToken('abc123')).toBeNull();
    expect(cleanCollabInviteToken('bci_short')).toBeNull();
    expect(parseCollabInviteLink('https://bitchord.org/invite/AB12CD')).toBeNull();
    expect(parseCollabInviteLink('javascript:alert(1)')).toBeNull();
    expect(sanitizeCollabServerUrl('ftp://bad.example.com')).toBeNull();
    expect(sanitizeCollabServerUrl('javascript:alert(1)')).toBeNull();
    expect(sanitizeCollabServerUrl('localhost:8080/')).toBe('http://localhost:8080');
    expect(sanitizeCollabServerUrl('collab.bitchord.org:8443/')).toBe('https://collab.bitchord.org:8443');
  });

  it('formats shareable web and deep-link URLs cleanly', () => {
    expect(formatCollabDeepLink(SAMPLE_TOKEN, 'https://collab.bitchord.org/')).toBe(
      `bitchord://collab/invite/${SAMPLE_TOKEN}?server=${encodeURIComponent('https://collab.bitchord.org')}`,
    );
    expect(formatCollabInviteUrl(SAMPLE_TOKEN, 'https://collab.bitchord.org')).toContain(
      `/collab/invite/${SAMPLE_TOKEN}`,
    );
  });
});

describe('collaborative permissions evaluation', () => {
  it('grants full control to OWNER and read-only access to VIEWER', () => {
    const playlist = makeSampleDetail();

    const ownerPerms = evaluateCollabPermissions(playlist, 'usr_owner');
    expect(ownerPerms).toMatchObject({
      role: 'OWNER',
      isMember: true,
      isOwner: true,
      canAddTracks: true,
      canReorderTracks: true,
      canInvite: true,
      canEditMetadata: true,
      canManageMembers: true,
      canStopSharing: true,
      canDeletePlaylist: true,
      canLeavePlaylist: false,
    });
    expect(canRemoveCollabTrack(playlist, playlist.tracks[0]!, 'usr_owner')).toBe(true);
    expect(canRemoveCollabTrack(playlist, playlist.tracks[1]!, 'usr_owner')).toBe(true);

    const viewerPerms = evaluateCollabPermissions(playlist, 'usr_viewer');
    expect(viewerPerms).toMatchObject({
      role: 'VIEWER',
      isMember: true,
      isOwner: false,
      canAddTracks: false,
      canReorderTracks: false,
      canInvite: false,
      canEditMetadata: false,
      canManageMembers: false,
      canStopSharing: false,
      canDeletePlaylist: false,
      canLeavePlaylist: true,
    });
    expect(canRemoveCollabTrack(playlist, playlist.tracks[0]!, 'usr_viewer')).toBe(false);
    expect(canRemoveCollabTrack(playlist, playlist.tracks[1]!, 'usr_viewer')).toBe(false);
  });

  it('enforces editorCanRemove (any vs own), editorCanReorder, and editorCanInvite for EDITOR', () => {
    const defaultPlaylist = makeSampleDetail({
      permissions: { editorCanRemove: 'any', editorCanReorder: true, editorCanInvite: false },
    });
    const editorDefault = evaluateCollabPermissions(defaultPlaylist, 'usr_editor');
    expect(editorDefault.canAddTracks).toBe(true);
    expect(editorDefault.canReorderTracks).toBe(true);
    expect(editorDefault.canInvite).toBe(false);
    expect(editorDefault.canLeavePlaylist).toBe(true);
    expect(canRemoveCollabTrack(defaultPlaylist, defaultPlaylist.tracks[0]!, 'usr_editor')).toBe(true);
    expect(canRemoveCollabTrack(defaultPlaylist, defaultPlaylist.tracks[1]!, 'usr_editor')).toBe(true);

    const restrictedPlaylist = makeSampleDetail({
      permissions: { editorCanRemove: 'own', editorCanReorder: false, editorCanInvite: true },
    });
    const editorRestricted = evaluateCollabPermissions(restrictedPlaylist, 'usr_editor');
    expect(editorRestricted.canAddTracks).toBe(true);
    expect(editorRestricted.canReorderTracks).toBe(false);
    expect(editorRestricted.canInvite).toBe(true);

    // Track 0 was added by usr_owner; Track 1 was added by usr_editor.
    expect(canRemoveCollabTrack(restrictedPlaylist, restrictedPlaylist.tracks[0]!, 'usr_editor')).toBe(false);
    expect(canRemoveCollabTrack(restrictedPlaylist, restrictedPlaylist.tracks[1]!, 'usr_editor')).toBe(true);
    // OWNER can still remove any track even when editorCanRemove === 'own'.
    expect(canRemoveCollabTrack(restrictedPlaylist, restrictedPlaylist.tracks[1]!, 'usr_owner')).toBe(true);
  });

  it('disables live collaborative mutations when sharingEnabled is false while preserving owner delete', () => {
    const stopped = makeSampleDetail({ sharingEnabled: false });
    const ownerStopped = evaluateCollabPermissions(stopped, 'usr_owner');
    expect(ownerStopped.canAddTracks).toBe(false);
    expect(ownerStopped.canReorderTracks).toBe(false);
    expect(ownerStopped.canInvite).toBe(false);
    expect(ownerStopped.canStopSharing).toBe(false);
    expect(ownerStopped.canDeletePlaylist).toBe(true);
    expect(canRemoveCollabTrack(stopped, stopped.tracks[0]!, 'usr_owner')).toBe(false);
  });
});

describe('WebSocket event reducer and opId deduplication', () => {
  it('reconciles tracks_changed, deduplicates local opId echoes, and ignores stale revisions', () => {
    const base = makeSampleDetail();
    const seenOpIds = new Set<string>(['op_local_1']);

    const newTrack: CollabTrackEntry = {
      entryId: 'cte_3',
      playlistId: base.id,
      position: 2,
      trackId: 'dQw4w9WgXcQ',
      provider: 'youtube',
      title: 'Starboy',
      artist: 'The Weeknd',
      albumName: 'Starboy',
      thumbnailUrl: null,
      durationMs: 230_000,
      isExplicit: true,
      contributorId: 'usr_owner',
      contributorName: 'Saksham',
      contributorAvatarUrl: null,
      addedAtMs: 1_700_000_010_000,
    };

    const echoed = applyCollabWsFrame(
      base,
      {
        type: 'collab.tracks_changed',
        playlistId: base.id,
        revision: 5,
        opId: 'op_local_1',
        tracks: [...base.tracks, newTrack],
        latestActivity: {
          id: 'act_2',
          playlistId: base.id,
          actorUserId: 'usr_owner',
          actorName: 'Saksham',
          actorAvatarUrl: null,
          action: 'tracks.added',
          summary: 'Saksham added "Starboy".',
          createdAtMs: 1_700_000_010_000,
        },
        serverMs: 1_700_000_010_000,
      },
      { currentUserId: 'usr_owner', seenOpIds },
    );

    expect(echoed.changed).toBe(true);
    expect(echoed.skippedEcho).toBe(true);
    expect(seenOpIds.has('op_local_1')).toBe(false);
    expect(echoed.detail?.revision).toBe(5);
    expect(echoed.detail?.tracks).toHaveLength(3);
    expect(echoed.detail?.activities).toHaveLength(2);

    // Duplicate / older revision frame is dropped cleanly.
    const stale = applyCollabWsFrame(
      echoed.detail,
      {
        type: 'collab.tracks_changed',
        playlistId: base.id,
        revision: 4,
        tracks: base.tracks,
        serverMs: 1_700_000_009_000,
      },
      { currentUserId: 'usr_owner', seenOpIds },
    );
    expect(stale.changed).toBe(false);
    expect(stale.detail?.revision).toBe(5);
    expect(stale.detail?.tracks).toHaveLength(3);
  });

  it('preserves non-owner subscriber myRole on metadata_changed broadcasts from OWNER', () => {
    const editorView = normalizePlaylistDetail(makeSampleDetail(), 'usr_editor');
    expect(editorView.myRole).toBe('EDITOR');

    const res = applyCollabWsFrame(
      editorView,
      {
        type: 'collab.metadata_changed',
        playlistId: editorView.id,
        revision: 6,
        playlist: {
          ...editorView,
          title: 'Renamed by Owner',
          myRole: 'OWNER', // Broadcaster's summary had OWNER!
          permissions: { editorCanRemove: 'own', editorCanReorder: false, editorCanInvite: true },
        },
        serverMs: 1_700_000_020_000,
      },
      { currentUserId: 'usr_editor' },
    );

    expect(res.changed).toBe(true);
    expect(res.detail?.title).toBe('Renamed by Owner');
    expect(res.detail?.myRole).toBe('EDITOR');
    expect(res.detail?.permissions.editorCanRemove).toBe('own');
  });

  it('handles ownership transfer, member_removed, and stopped sharing per role', () => {
    const editorView = normalizePlaylistDetail(makeSampleDetail(), 'usr_editor');

    // Ownership transferred to usr_editor
    const transferred = applyCollabWsFrame(
      editorView,
      {
        type: 'collab.members_changed',
        playlistId: editorView.id,
        revision: 7,
        members: editorView.members.map((m) =>
          m.userId === 'usr_editor'
            ? { ...m, role: 'OWNER' }
            : m.userId === 'usr_owner'
              ? { ...m, role: 'EDITOR' }
              : m,
        ),
        serverMs: 1_700_000_030_000,
      },
      { currentUserId: 'usr_editor' },
    );
    expect(transferred.detail?.myRole).toBe('OWNER');
    expect(transferred.detail?.ownerUserId).toBe('usr_editor');

    // Stopped sharing drops playlist for non-owners, preserves for owner
    const nonOwnerStopped = applyCollabWsFrame(
      editorView,
      {
        type: 'collab.stopped',
        playlistId: editorView.id,
        message: 'The owner stopped sharing this playlist.',
        serverMs: 1_700_000_040_000,
      },
      { currentUserId: 'usr_editor' },
    );
    expect(nonOwnerStopped.detail).toBeNull();
    expect(nonOwnerStopped.stoppedMessage).toContain('stopped sharing');

    const ownerStopped = applyCollabWsFrame(
      makeSampleDetail(),
      {
        type: 'collab.stopped',
        playlistId: editorView.id,
        message: 'Sharing stopped.',
        serverMs: 1_700_000_040_000,
      },
      { currentUserId: 'usr_owner' },
    );
    expect(ownerStopped.detail?.sharingEnabled).toBe(false);
    expect(ownerStopped.detail?.tracks).toHaveLength(2);

    // Kicked member receives null detail + reason
    const removed = applyCollabWsFrame(
      editorView,
      {
        type: 'collab.member_removed',
        playlistId: editorView.id,
        userId: 'usr_editor',
        reason: 'removed',
        message: 'Removed by owner',
        serverMs: 1_700_000_050_000,
      },
      { currentUserId: 'usr_editor' },
    );
    expect(removed.detail).toBeNull();
    expect(removed.removedReason).toBe('Removed by owner');
  });
});

describe('playlist conversion mapping, PKCE S256, and socket backoff', () => {
  it('maps YouTube Music and Spotify tracks into valid ConvertCollabPlaylistInput payloads', () => {
    const tracks: Track[] = [
      {
        videoId: 'fHI8X4OXluQ',
        title: 'Blinding Lights',
        artists: [{ name: 'The Weeknd', id: 'UC123' }],
        album: { name: 'After Hours', id: 'MPREb_1' },
        durationSec: 200,
        explicit: true,
        thumbnail: 'https://lh3.googleusercontent.com/art1',
        index: 1,
        plays: null,
      },
      {
        videoId: 'spotify:6DCZcSspjsKoFjzjrWoCdn',
        title: 'Gods Plan',
        artists: [{ name: 'Drake', id: null }],
        album: { name: 'Scorpion', id: null },
        durationSec: 199,
        explicit: true,
        thumbnail: 'https://i.scdn.co/image/art2',
        index: 2,
        plays: null,
        unavailable: 'missing',
      },
    ];

    const payload = buildConvertPayload({
      sourcePlaylistId: '37i9dQZF1DXcBWIGoYBM5M',
      sourceType: 'spotify',
      title: '  My Mixed Playlist  ',
      description: '  Converted from Spotify  ',
      artworkUrl: 'https://i.scdn.co/image/cover',
      attributeExistingTracksToOwner: true,
      permissions: { editorCanRemove: 'own', editorCanReorder: true, editorCanInvite: false },
      tracks,
    });

    expect(payload).toEqual({
      sourcePlaylistId: '37i9dQZF1DXcBWIGoYBM5M',
      sourceType: 'spotify',
      title: 'My Mixed Playlist',
      description: 'Converted from Spotify',
      artworkUrl: 'https://i.scdn.co/image/cover',
      attributeExistingTracksToOwner: true,
      permissions: { editorCanRemove: 'own', editorCanReorder: true, editorCanInvite: false },
      tracks: [
        {
          trackId: 'fHI8X4OXluQ',
          videoId: 'fHI8X4OXluQ',
          provider: 'youtube',
          title: 'Blinding Lights',
          artist: 'The Weeknd',
          albumName: 'After Hours',
          thumbnailUrl: 'https://lh3.googleusercontent.com/art1',
          durationMs: 200_000,
          isExplicit: true,
        },
        {
          trackId: '6DCZcSspjsKoFjzjrWoCdn',
          provider: 'spotify',
          title: 'Gods Plan',
          artist: 'Drake',
          albumName: 'Scorpion',
          thumbnailUrl: 'https://i.scdn.co/image/art2',
          durationMs: 199_000,
          isExplicit: true,
        },
      ],
    });

    // Round-trip CollabTrackEntry -> player Track
    const sampleDetail = makeSampleDetail();
    const ytTrack = collabEntryToTrack(sampleDetail.tracks[0]!);
    expect(ytTrack.videoId).toBe('fHI8X4OXluQ');
    expect(ytTrack.unavailable).toBeUndefined();
    expect(trackToCollabInput(ytTrack).provider).toBe('youtube');

    const spTrack = collabEntryToTrack({
      ...sampleDetail.tracks[0]!,
      provider: 'spotify',
      trackId: '6DCZcSspjsKoFjzjrWoCdn',
    });
    expect(spTrack.unavailable).toBe('missing');
  });

  it('computes RFC 7636 S256 PKCE challenges and bounded exponential backoff delays', async () => {
    // RFC 7636 Appendix B test vector
    const challenge = await derivePkceChallengeS256('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk');
    expect(challenge).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');

    expect(computeReconnectDelayMs(0, () => 0)).toBe(500);
    expect(computeReconnectDelayMs(1, () => 0)).toBe(1000);
    expect(computeReconnectDelayMs(2, () => 0)).toBe(2000);
    expect(computeReconnectDelayMs(20, () => 1)).toBe(30_000);
  });

  it('parses YouTube Music ?list=...&jct=... invite links and syncs extension Google sign-in automatically', () => {
    const ytm = parseCollabInviteLink('https://music.youtube.com/playlist?list=PLQwVIlKxHM6qv-o99&jct=kM9wXdRj2p8v_qL3sHBkTz');
    expect(ytm).not.toBeNull();
    expect(ytm?.ytmPlaylistId).toBe('PLQwVIlKxHM6qv-o99');
    expect(ytm?.ytmJoinToken).toBe('kM9wXdRj2p8v_qL3sHBkTz');

    // Plain non-collaborative YTM playlist URL without jct= is not parsed as an invite
    expect(parseCollabInviteLink('https://music.youtube.com/playlist?list=PLQwVIlKxHM6qv-o99')).toBeNull();

    syncYtExtensionToCollab({
      signedIn: true,
      id: 'UC_pranav_123',
      name: 'Pranav',
      email: 'pranav@example.com',
      avatarUrl: 'https://yt3.ggpht.com/avatar',
    });
    expect(useCollabAuth.getState().user?.displayName).toBe('Pranav');
    expect(useCollabAuth.getState().user?.email).toBe('pranav@example.com');
  });

  it('extracts created playlist IDs across top-level playlistId, toast browseEndpoint.browseId, command, and URLs', () => {
    expect(extractCreatedPlaylistIdFromData({ playlistId: 'VLPL_top_12345' })).toBe('PL_top_12345');
    expect(
      extractCreatedPlaylistIdFromData({
        responseContext: { visitorData: 'Cgvisitor' },
        actions: [
          {
            addToToastAction: {
              item: {
                notificationTextRenderer: {
                  navigationEndpoint: { browseEndpoint: { browseId: 'VLPL_toast_98765' } },
                },
              },
            },
          },
        ],
      }),
    ).toBe('PL_toast_98765');
    expect(
      extractCreatedPlaylistIdFromData({
        command: { browseEndpoint: { browseId: 'VLPL_cmd_55555' } },
      }),
    ).toBe('PL_cmd_55555');
    expect(
      extractCreatedPlaylistIdFromData({
        shareUrl: 'https://music.youtube.com/playlist?list=PL_url_77777&jct=tok',
      }),
    ).toBe('PL_url_77777');
    expect(
      extractCreatedPlaylistIdFromData({
        actions: [{ navigationEndpoint: { browseEndpoint: { browseId: 'FEmusic_liked_playlists' } } }],
      }),
    ).toBeNull();
  });
});

describe('YouTube Music collaboration protocol', () => {
  it('folds an invite jct into browse params exactly as music.youtube.com does', () => {
    // Expected values are what the real server put in the playlist page's own browse request.
    expect(ytmInviteBrowseParams('AbCdEfGhIjKlMnOpQrStUv')).toBe('ugIWQWJDZEVmR2hJaktsTW5PcFFyU3RVdsIFAwjYBA%3D%3D');
    expect(ytmInviteBrowseParams('L1_yAKqFkzTabc-XYZ_0123456789')).toBe('ugIdTDFfeUFLcUZrelRhYmMtWFlaXzAxMjM0NTY3ODnCBQMI2AQ%3D');
    expect(ytmInviteBrowseParams('ab~ab~ab~ab~ab~ab~ab~a.')).toBe('ugIXYWJ-YWJ-YWJ-YWJ-YWJ-YWJ-YWJ-YS7CBQMI2AQ%3D');
  });

  it('builds the collaborators panel params a real playlist header carries', () => {
    expect(ytmCollaboratorsPanelParams('PLxyTaDz8f5PBc-8kE36gvB-eflhODG2dw')).toBe('0ggkCiJQTHh5VGFEejhmNVBCYy04a0UzNmd2Qi1lZmxoT0RHMmR3');
    expect(ytmCollaboratorsPanelParams('VLPLxyTaDz8f5PBc-8kE36gvB-eflhODG2dw')).toBe('0ggkCiJQTHh5VGFEejhmNVBCYy04a0UzNmd2Qi1lZmxoT0RHMmR3');
  });

  it('treats edit commands as write access and a join offer as the opposite', () => {
    const member = {
      menu: { serviceEndpoint: { playlistEditEndpoint: { playlistId: 'PLabc', actions: [{ action: 'ACTION_REMOVE_VIDEO', setVideoId: 's1' }] } } },
    };
    const invited = {
      button: { command: { playlistEditEndpoint: { playlistId: 'PLabc', params: 'served', actions: [{ action: 'ACTION_JOIN_COLLABORATION', joinCollaborationToken: 'tok' }] } } },
    };
    expect(hasPlaylistEditAffordance(member, 'PLabc')).toBe(true);
    expect(hasPlaylistEditAffordance(member, 'PLother')).toBe(false);
    expect(hasPlaylistEditAffordance(invited, 'PLabc')).toBe(false);
    expect([...parseAddToPlaylistIds(invited)]).toEqual([]);
    expect([...parseAddToPlaylistIds({ playlists: [{ playlistAddToOptionRenderer: { playlistId: 'VLPLmine' } }] })]).toEqual(['PLmine']);
    expect(parseCollabJoinEndpoints(invited, 'PLabc')).toEqual([
      { playlistId: 'PLabc', params: 'served', actions: [{ action: 'ACTION_JOIN_COLLABORATION', joinCollaborationToken: 'tok' }] },
    ]);
    expect(parseCollabJoinEndpoints(invited, 'PLother')).toEqual([]);
    expect(parseCollabJoinEndpoints(member, 'PLabc')).toEqual([]);
  });

  it('reads the roster from the collaborate panel', () => {
    const panel = {
      content: {
        playlistCollaborationViewModel: {
          playlistCollaborators: [
            { contentListItemViewModel: { title: { content: 'Pranav' }, avatar: { avatarViewModel: { image: { sources: [{ url: 'https://yt3.ggpht.com/p' }] } } } } },
            { contentListItemViewModel: { title: { content: 'Saksham' }, rendererContext: { commandContext: { onTap: { innertubeCommand: { browseEndpoint: { browseId: 'UCsaksham' } } } } } } },
          ],
        },
      },
    };
    expect(parseCollaboratorsPanel(panel)).toEqual([
      { name: 'Pranav', avatarUrl: 'https://yt3.ggpht.com/p', channelId: null },
      { name: 'Saksham', avatarUrl: null, channelId: 'UCsaksham' },
    ]);
    expect(parseCollaboratorsPanel({ contents: [{ contentListItemViewModel: { title: { content: 'A setting' } } }] })).toEqual([]);
  });

  it('explains a refused join in words, never with the response body', () => {
    const raw = new Error('Request to https://music.youtube.com/youtubei/v1/browse/edit_playlist failed with status code 401 {"error":{"code":401,"message":"You are not authorized to edit this playlist."}}');
    expect(ytmErrorStatus(raw)).toBe(401);
    expect(ytmErrorStatus({ info: { status: 409 } })).toBe(409);
    const shown = ytmJoinFailureMessage(raw);
    expect(shown).not.toMatch(/[{}]|youtubei/);
    expect(shown).toMatch(/invite/i);
    expect(ytmJoinFailureMessage({ status: 404 })).toMatch(/can't be found/);
    expect(ytmJoinFailureMessage({ status: 503 })).toMatch(/HTTP 503/);
  });
});

describe('readWithBackoff', () => {
  it('retries 429 and 5xx, then succeeds', async () => {
    const { readWithBackoff } = await import('./ytmCollab');
    let calls = 0;
    const value = await readWithBackoff(async () => {
      calls++;
      if (calls === 1) throw Object.assign(new Error('rate'), { status: 429 });
      if (calls === 2) throw new Error('Request failed with status 503');
      return 'ok';
    }, 3, 1);
    expect(value).toBe('ok');
    expect(calls).toBe(3);
  });

  it('does not retry other client errors', async () => {
    const { readWithBackoff } = await import('./ytmCollab');
    let calls = 0;
    await expect(
      readWithBackoff(async () => {
        calls++;
        throw Object.assign(new Error('nope'), { status: 404 });
      }, 3, 1),
    ).rejects.toThrow('nope');
    expect(calls).toBe(1);
  });
});
