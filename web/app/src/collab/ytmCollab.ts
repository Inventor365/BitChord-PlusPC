import { extractCreatedPlaylistIdFromData, fetchLibraryPlaylists } from '@/data/music/api';
import { getInnertube, getSignedInnertube } from '@/playback/youtube/innertube';
import { useCollabAuth } from './auth';
import {
  DEFAULT_COLLAB_PERMISSIONS,
  deriveMemberAvatars,
  type CollabActivity,
  type CollabInvitePreview,
  type CollabInviteView,
  type CollabMember,
  type CollabPermissionsPatch,
  type CollabPlaylistDetail,
  type CollabPlaylistSummary,
  type CollabTrackEntry,
  type CollabTrackInput,
  type ConvertCollabPlaylistInput,
} from './types';

export const YTM_COLLAB_PREFIX = 'ytm:';
const YTM_STATE_KEY = 'bitchord:ytm-collab-state:v1';

interface LocalStamp {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
  addedAtMs: number;
}

interface StoredYtmCollabState {
  trackedIds: string[];
  inviteLinks: Record<string, string>;
  convertedSources: Record<string, string>;
  stoppedIds: string[];
  stamps: Record<string, LocalStamp>;
  activities: Record<string, CollabActivity[]>;
}

function readYtmState(): StoredYtmCollabState {
  try {
    const raw = localStorage.getItem(YTM_STATE_KEY);
    if (!raw) {
      return { trackedIds: [], inviteLinks: {}, convertedSources: {}, stoppedIds: [], stamps: {}, activities: {} };
    }
    const p = JSON.parse(raw) as Partial<StoredYtmCollabState>;
    return {
      trackedIds: Array.isArray(p.trackedIds) ? p.trackedIds : [],
      inviteLinks: p.inviteLinks && typeof p.inviteLinks === 'object' ? p.inviteLinks : {},
      convertedSources: p.convertedSources && typeof p.convertedSources === 'object' ? p.convertedSources : {},
      stoppedIds: Array.isArray(p.stoppedIds) ? p.stoppedIds : [],
      stamps: p.stamps && typeof p.stamps === 'object' ? p.stamps : {},
      activities: p.activities && typeof p.activities === 'object' ? p.activities : {},
    };
  } catch {
    return { trackedIds: [], inviteLinks: {}, convertedSources: {}, stoppedIds: [], stamps: {}, activities: {} };
  }
}

function writeYtmState(state: StoredYtmCollabState): void {
  try {
    localStorage.setItem(YTM_STATE_KEY, JSON.stringify(state));
  } catch {
    // Ignore quota errors
  }
}

function recordActivity(state: StoredYtmCollabState, rawId: string, action: string, summary: string): void {
  const me = useCollabAuth.getState().user;
  if (!me) return;
  const act: CollabActivity = {
    id: `act_${Date.now()}_${Math.floor(Math.random() * 1000)}`,
    playlistId: `${YTM_COLLAB_PREFIX}${rawId}`,
    actorUserId: me.id,
    actorName: me.displayName,
    actorAvatarUrl: me.avatarUrl,
    action,
    summary,
    createdAtMs: Date.now(),
  };
  const existing = state.activities[rawId] ?? [];
  state.activities[rawId] = [act, ...existing].slice(0, 50);
}

type RawObj = Record<string, unknown>;

function collectByKey(root: unknown, key: string): RawObj[] {
  const found: RawObj[] = [];
  const walk = (node: unknown) => {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      for (const item of node) walk(item);
      return;
    }
    const obj = node as RawObj;
    if (key in obj && obj[key] && typeof obj[key] === 'object') {
      found.push(obj[key] as RawObj);
    }
    for (const v of Object.values(obj)) walk(v);
  };
  walk(root);
  return found;
}

function findStringDeep(root: unknown, key: string): string | null {
  if (!root || typeof root !== 'object') return null;
  if (Array.isArray(root)) {
    for (const item of root) {
      const hit = findStringDeep(item, key);
      if (hit) return hit;
    }
    return null;
  }
  const obj = root as RawObj;
  if (typeof obj[key] === 'string' && (obj[key] as string).trim()) {
    return (obj[key] as string).trim();
  }
  for (const v of Object.values(obj)) {
    const hit = findStringDeep(v, key);
    if (hit) return hit;
  }
  return null;
}

function runsText(node: unknown): string {
  if (!node || typeof node !== 'object') return '';
  const runs = (node as RawObj).runs;
  if (!Array.isArray(runs)) return typeof (node as RawObj).simpleText === 'string' ? ((node as RawObj).simpleText as string) : '';
  return runs.map((r) => (r && typeof r === 'object' && typeof (r as RawObj).text === 'string' ? (r as RawObj).text : '')).join('');
}

function bestThumb(node: unknown): string | null {
  const thumbs = collectByKey(node, 'musicThumbnailRenderer')[0]?.thumbnail as RawObj | undefined;
  const list = Array.isArray(thumbs?.thumbnails) ? (thumbs.thumbnails as RawObj[]) : [];
  const last = list[list.length - 1];
  return typeof last?.url === 'string' ? last.url : null;
}

function parseDurationMs(text: string): number | null {
  const parts = text.trim().split(':').map((x) => Number(x));
  if (parts.some((n) => Number.isNaN(n))) return null;
  if (parts.length === 2) return ((parts[0] ?? 0) * 60 + (parts[1] ?? 0)) * 1000;
  if (parts.length === 3) return ((parts[0] ?? 0) * 3600 + (parts[1] ?? 0) * 60 + (parts[2] ?? 0)) * 1000;
  return null;
}

function sleepMs(ms: number): Promise<void> {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function enableYtmCollabOnId(rawId: string, ensureUnlisted = true): Promise<string> {
  const yt = await getSignedInnertube();
  if (ensureUnlisted) {
    await yt.actions.execute('/browse/edit_playlist', {
      playlistId: rawId,
      actions: [{ action: 'ACTION_SET_PLAYLIST_PRIVACY', playlistPrivacy: 'UNLISTED' }],
      client: 'YTMUSIC',
    }).catch(() => {});
  }
  for (let attempt = 0; attempt < 12; attempt++) {
    if (attempt > 0) {
      await sleepMs(2000);
    }
    try {
      const resp = await yt.actions.execute('/browse/edit_playlist', {
        playlistId: rawId,
        actions: [{ action: 'ACTION_CREATE_COLLABORATION_INVITE_LINK' }],
        client: 'YTMUSIC',
      });
      const link = findStringDeep(resp.data, 'collaborationInviteLink');
      if (link) {
        return link.startsWith('http')
          ? link.replace('https://www.youtube.com/', 'https://music.youtube.com/')
          : `https://music.youtube.com/playlist?list=${rawId}&jct=${link}`;
      }
      const jct = findStringDeep(resp.data, 'joinCollaborationToken');
      if (jct) {
        return `https://music.youtube.com/playlist?list=${rawId}&jct=${jct}`;
      }
    } catch {
      // Retry while YouTube Music propagates UNLISTED / settles playlist
    }
  }
  throw new Error('YouTube Music is still preparing collaboration on this playlist. Please try creating the invite link again in a few seconds.');
}

export async function ytmFetchPlaylistDetail(
  rawOrPrefixedId: string,
  options?: { signedIn?: boolean; fallbackTitle?: string; fallbackDescription?: string; fallbackTracks?: CollabTrackInput[] },
): Promise<CollabPlaylistDetail> {
  const rawId = rawOrPrefixedId.replace(/^ytm:/, '').replace(/^VL/, '');
  const cplId = `${YTM_COLLAB_PREFIX}${rawId}`;
  const state = readYtmState();
  const me = useCollabAuth.getState().user;
  const yt = options?.signedIn === false ? await getInnertube() : await getSignedInnertube();
  const browseResp = await yt.actions.execute('/browse', { browseId: `VL${rawId}`, client: 'YTMUSIC' }).catch(() => ({ data: {} }));
  const root = (browseResp.data ?? {}) as RawObj;
  const editable = collectByKey(root, 'musicEditablePlaylistDetailHeaderRenderer')[0];
  const owned = Boolean(editable);
  const header =
    ((editable?.header as RawObj | undefined)?.musicResponsiveHeaderRenderer as RawObj | undefined) ??
    collectByKey(root, 'musicResponsiveHeaderRenderer')[0] ??
    {};

  const title = runsText(header.title) || options?.fallbackTitle || 'Collaborative Playlist';
  const descShelf = collectByKey(root, 'musicDescriptionShelfRenderer')[0];
  const description = runsText(descShelf?.description) || runsText(header.description) || options?.fallbackDescription || '';
  const artworkUrl = bestThumb(header);

  const facepile = (header.facepile as RawObj | undefined)?.avatarStackViewModel as RawObj | undefined;
  const avatarsRaw = Array.isArray(facepile?.avatars) ? (facepile.avatars as RawObj[]) : [];
  const facepileAvatars = avatarsRaw
    .map((a) => {
      const sources = (((a.avatarViewModel as RawObj | undefined)?.image as RawObj | undefined)?.sources ?? []) as RawObj[];
      const last = sources[sources.length - 1];
      return typeof last?.url === 'string' ? last.url : null;
    })
    .filter((u): u is string => Boolean(u));

  const authorText =
    findStringDeep(facepile?.text, 'content') ??
    (runsText(header.straplineTextOne) || 'YouTube Music');
  const ownerUserId = owned ? (me?.id ?? 'ytm:self') : `ytm:owner:${rawId}`;
  const ownerName = owned ? (me?.displayName ?? authorText) : authorText;
  const ownerAvatarUrl = owned ? (me?.avatarUrl ?? facepileAvatars[0] ?? null) : (facepileAvatars[0] ?? null);

  const members: CollabMember[] = [
    {
      playlistId: cplId,
      userId: ownerUserId,
      role: 'OWNER',
      displayName: ownerName,
      avatarUrl: ownerAvatarUrl,
      joinedAtMs: 0,
    },
  ];
  if (!owned && me) {
    members.push({
      playlistId: cplId,
      userId: me.id,
      role: 'EDITOR',
      displayName: me.displayName,
      avatarUrl: me.avatarUrl,
      joinedAtMs: 0,
    });
  }

  const shelf = collectByKey(root, 'musicPlaylistShelfRenderer')[0];
  const rows = collectByKey(shelf ?? {}, 'musicResponsiveListItemRenderer');
  const tracks: CollabTrackEntry[] = [];

  rows.forEach((row, idx) => {
    const itemData = (row.playlistItemData as RawObj | undefined) ?? {};
    const videoId =
      (typeof itemData.videoId === 'string' ? itemData.videoId : null) ??
      findStringDeep(row.overlay, 'videoId');
    if (!videoId) return;
    const setVideoId = typeof itemData.playlistSetVideoId === 'string' ? itemData.playlistSetVideoId : videoId;
    const cols = Array.isArray(row.flexColumns) ? (row.flexColumns as RawObj[]) : [];
    const rowTitle = runsText((cols[0]?.musicResponsiveListItemFlexColumnRenderer as RawObj | undefined)?.text);
    if (!rowTitle) return;
    const subText = runsText((cols[1]?.musicResponsiveListItemFlexColumnRenderer as RawObj | undefined)?.text);
    const artist = subText.split(' • ')[0]?.trim() || 'Unknown Artist';
    const fixedCols = Array.isArray(row.fixedColumns) ? (row.fixedColumns as RawObj[]) : [];
    const durText = runsText((fixedCols[0]?.musicResponsiveListItemFixedColumnRenderer as RawObj | undefined)?.text);

    const contribStack = ((row.contributorsAvatars as RawObj | undefined)?.avatarStackViewModel as RawObj | undefined);
    const rowAvatarVm =
      (((Array.isArray(contribStack?.avatars) ? contribStack?.avatars[0] : undefined) as RawObj | undefined)?.avatarViewModel as RawObj | undefined) ??
      collectByKey(row, 'avatarViewModel')[0];
    const rowAvatarSources = (((rowAvatarVm?.image as RawObj | undefined)?.sources ?? []) as RawObj[]);
    const ytmContribAvatar = typeof rowAvatarSources[rowAvatarSources.length - 1]?.url === 'string'
      ? (rowAvatarSources[rowAvatarSources.length - 1]!.url as string)
      : null;
    const rawLabel =
      findStringDeep(rowAvatarVm, 'accessibilityText') ??
      findStringDeep(rowAvatarVm, 'label') ??
      null;
    const ytmContribLabel = rawLabel?.replace(/^(Added by|by)\s+/i, '').trim() || null;

    const stamp = state.stamps[`${rawId}:${setVideoId}`] ?? state.stamps[`${rawId}:${videoId}`];
    const contribName = ytmContribLabel ?? stamp?.displayName ?? ownerName;
    const contribAvatar = ytmContribAvatar ?? stamp?.avatarUrl ?? ownerAvatarUrl;
    const contribId =
      stamp?.userId ??
      (me && contribName.toLowerCase() === me.displayName.toLowerCase() ? me.id : ownerUserId);

    tracks.push({
      entryId: setVideoId,
      playlistId: cplId,
      position: idx,
      trackId: videoId,
      provider: 'youtube',
      title: rowTitle,
      artist,
      albumName: null,
      thumbnailUrl: bestThumb(row),
      durationMs: parseDurationMs(durText),
      isExplicit: false,
      contributorId: contribId,
      contributorName: contribName,
      contributorAvatarUrl: contribAvatar,
      addedAtMs: stamp?.addedAtMs ?? 0,
    });
  });
  if (tracks.length === 0 && options?.fallbackTracks?.length) {
    options.fallbackTracks.forEach((t, idx) => {
      const vid = (t.trackId || t.videoId || '').trim();
      if (!vid) return;
      tracks.push({
        entryId: vid,
        playlistId: cplId,
        position: idx,
        trackId: vid,
        provider: 'youtube',
        title: t.title || 'Track',
        artist: t.artist || 'Unknown Artist',
        albumName: t.albumName ?? null,
        thumbnailUrl: t.thumbnailUrl ?? null,
        durationMs: t.durationMs ?? null,
        isExplicit: Boolean(t.isExplicit),
        contributorId: ownerUserId,
        contributorName: ownerName,
        contributorAvatarUrl: ownerAvatarUrl,
        addedAtMs: Date.now(),
      });
    });
  }

  const stopped = state.stoppedIds.includes(rawId);
  return {
    id: cplId,
    title,
    description,
    artworkUrl: artworkUrl ?? tracks[0]?.thumbnailUrl ?? null,
    ownerUserId,
    ownerName,
    ownerAvatarUrl,
    myRole: owned ? 'OWNER' : 'EDITOR',
    sharingEnabled: !stopped,
    permissions: { ...DEFAULT_COLLAB_PERMISSIONS, editorCanInvite: true },
    sourceType: null,
    sourcePlaylistId: null,
    memberCount: Math.max(members.length, facepileAvatars.length || 1),
    memberAvatars: deriveMemberAvatars(ownerUserId, ownerName, ownerAvatarUrl, members),
    trackCount: tracks.length,
    revision: Date.now(),
    createdAtMs: 0,
    updatedAtMs: Date.now(),
    members,
    tracks,
    activities: state.activities[rawId] ?? [],
  };
}

export async function ytmListCollabPlaylists(): Promise<CollabPlaylistSummary[]> {
  const state = readYtmState();
  const libItems = await fetchLibraryPlaylists().catch(() => []);
  const candidateIds = new Set<string>(state.trackedIds);
  for (const item of libItems.slice(0, 24)) {
    const rawId = item.id.replace(/^VL/, '');
    if (rawId && rawId !== 'LM' && rawId !== 'SE' && !rawId.startsWith('RD')) {
      candidateIds.add(rawId);
    }
  }

  const summaries: CollabPlaylistSummary[] = [];
  const yt = await getSignedInnertube();
  for (const rawId of candidateIds) {
    try {
      const resp = await yt.actions.execute('/browse', { browseId: `VL${rawId}`, client: 'YTMUSIC' });
      const isCollab =
        state.trackedIds.includes(rawId) ||
        findStringDeep(resp.data, 'tag') === 'PAplaylist_collaborate' ||
        findStringDeep(resp.data, 'panelIdentifier') === 'PAplaylist_collaborate';
      if (!isCollab) continue;
      if (!state.trackedIds.includes(rawId)) state.trackedIds.push(rawId);
      const detail = await ytmFetchPlaylistDetail(rawId);
      summaries.push({
        id: detail.id,
        title: detail.title,
        description: detail.description,
        artworkUrl: detail.artworkUrl,
        ownerUserId: detail.ownerUserId,
        ownerName: detail.ownerName,
        ownerAvatarUrl: detail.ownerAvatarUrl,
        myRole: detail.myRole,
        sharingEnabled: detail.sharingEnabled,
        permissions: detail.permissions,
        sourceType: detail.sourceType,
        sourcePlaylistId: detail.sourcePlaylistId,
        memberCount: detail.memberCount,
        memberAvatars: detail.memberAvatars,
        trackCount: detail.trackCount,
        revision: detail.revision,
        createdAtMs: detail.createdAtMs,
        updatedAtMs: detail.updatedAtMs,
      });
    } catch {
      // Skip inaccessible playlist
    }
  }
  writeYtmState(state);
  return summaries;
}

export async function ytmCreateCollabPlaylist(params: {
  title: string;
  description?: string;
  tracks?: CollabTrackInput[];
}): Promise<CollabPlaylistDetail> {
  const yt = await getSignedInnertube();
  const me = useCollabAuth.getState().user;
  const cleanTitle = params.title.trim().replace(/</g, '‹').replace(/>/g, '›') || 'Collaborative Playlist';
  const cleanDesc = (params.description ?? '').replace(/<[^>]+>/g, '').replace(/</g, '‹').replace(/>/g, '›').trim();
  const videoIds = (params.tracks ?? [])
    .map((t) => (t.trackId || t.videoId || '').trim())
    .filter((v) => /^[A-Za-z0-9_-]{11}$/.test(v));
  const createPayload: Record<string, unknown> = {
    title: cleanTitle,
    privacyStatus: 'UNLISTED',
    client: 'YTMUSIC',
  };
  if (cleanDesc) createPayload.description = cleanDesc;
  if (videoIds.length > 0) createPayload.videoIds = videoIds.slice(0, 50);
  let createResp = await yt.actions.execute('/playlist/create', createPayload);
  let rawId = extractCreatedPlaylistIdFromData(createResp.data);
  let createdAsPrivate = false;
  if (!rawId) {
    createResp = await yt.actions.execute('/playlist/create', { ...createPayload, privacyStatus: 'PRIVATE' });
    rawId = extractCreatedPlaylistIdFromData(createResp.data);
    createdAsPrivate = Boolean(rawId);
  }
  if (!rawId) {
    const panelTag = findStringDeep(createResp.data, 'tag') ?? findStringDeep(createResp.data, 'panelIdentifier');
    if (panelTag === 'PAfeature_enablement') {
      throw new Error('YouTube Music temporarily gated playlist creation on this account (PAfeature_enablement).');
    }
    if (collectByKey(createResp.data, 'channelCreationDialogRenderer').length > 0 || collectByKey(createResp.data, 'createCoreIdentityModalRenderer').length > 0) {
      throw new Error('Your Google account needs a YouTube channel before YouTube Music allows creating playlists. Open music.youtube.com once to finish channel setup.');
    }
    throw new Error('YouTube Music did not return a playlist ID. Check that your Google account has an active YouTube Music profile.');
  }
  for (let i = 50; i < videoIds.length; i += 100) {
    const chunk = videoIds.slice(i, i + 100);
    await yt.actions.execute('/browse/edit_playlist', {
      playlistId: rawId,
      actions: chunk.map((vid) => ({ action: 'ACTION_ADD_VIDEO', addedVideoId: vid, dedupeOption: 'DEDUPE_OPTION_SKIP' })),
      client: 'YTMUSIC',
    }).catch(() => {});
  }
  const inviteUrl = await enableYtmCollabOnId(rawId, createdAsPrivate);

  const state = readYtmState();
  if (!state.trackedIds.includes(rawId)) state.trackedIds.push(rawId);
  state.stoppedIds = state.stoppedIds.filter((x) => x !== rawId);
  state.inviteLinks[rawId] = inviteUrl;
  if (me) {
    const now = Date.now();
    for (const vid of videoIds) {
      state.stamps[`${rawId}:${vid}`] = { userId: me.id, displayName: me.displayName, avatarUrl: me.avatarUrl, addedAtMs: now };
    }
  }
  recordActivity(state, rawId, 'playlist.create', `${me?.displayName ?? 'You'} created collaborative playlist "${cleanTitle}"`);
  writeYtmState(state);
  return ytmFetchPlaylistDetail(rawId, { fallbackTitle: cleanTitle, fallbackDescription: cleanDesc, fallbackTracks: params.tracks });
}

export async function ytmConvertToCollabPlaylist(
  input: ConvertCollabPlaylistInput,
): Promise<{ playlist: CollabPlaylistDetail; alreadyConverted: boolean }> {
  const state = readYtmState();
  const key = `${input.sourceType}:${input.sourcePlaylistId}`;
  const existing = state.convertedSources[key];
  if (existing && !state.stoppedIds.includes(existing)) {
    try {
      const detail = await ytmFetchPlaylistDetail(existing);
      return { playlist: detail, alreadyConverted: true };
    } catch {
      // Recreate if deleted remotely
    }
  }
  const detail = await ytmCreateCollabPlaylist({
    title: input.title,
    description: input.description,
    tracks: input.tracks,
  });
  const rawId = detail.id.replace(/^ytm:/, '');
  const updated = readYtmState();
  updated.convertedSources[key] = rawId;
  writeYtmState(updated);
  return { playlist: { ...detail, sourceType: input.sourceType, sourcePlaylistId: input.sourcePlaylistId }, alreadyConverted: false };
}

export async function ytmAddCollabTracks(
  playlistId: string,
  tracks: CollabTrackInput[],
): Promise<{ playlist: CollabPlaylistDetail; added: CollabTrackEntry[]; tracks: CollabTrackEntry[] }> {
  const rawId = playlistId.replace(/^ytm:/, '').replace(/^VL/, '');
  const yt = await getSignedInnertube();
  const me = useCollabAuth.getState().user;
  const videoIds = tracks.map((t) => t.trackId || t.videoId || '').filter(Boolean);
  await yt.actions.execute('/browse/edit_playlist', {
    playlistId: rawId,
    actions: videoIds.map((vid) => ({ action: 'ACTION_ADD_VIDEO', addedVideoId: vid, dedupeOption: 'DEDUPE_OPTION_SKIP' })),
    client: 'YTMUSIC',
  });
  const state = readYtmState();
  if (me) {
    const now = Date.now();
    for (const t of tracks) {
      const vid = t.trackId || t.videoId;
      if (vid) {
        state.stamps[`${rawId}:${vid}`] = { userId: me.id, displayName: me.displayName, avatarUrl: me.avatarUrl, addedAtMs: now };
        recordActivity(state, rawId, 'track.add', `${me.displayName} added ${t.title}`);
      }
    }
  }
  writeYtmState(state);
  const playlist = await ytmFetchPlaylistDetail(rawId);
  return { playlist, added: [], tracks: playlist.tracks };
}

export async function ytmRemoveCollabTrack(playlistId: string, entryId: string, videoId?: string): Promise<CollabPlaylistDetail> {
  const rawId = playlistId.replace(/^ytm:/, '').replace(/^VL/, '');
  const yt = await getSignedInnertube();
  await yt.actions.execute('/browse/edit_playlist', {
    playlistId: rawId,
    actions: [{ action: 'ACTION_REMOVE_VIDEO', setVideoId: entryId, ...(videoId ? { removedVideoId: videoId } : {}) }],
    client: 'YTMUSIC',
  });
  return ytmFetchPlaylistDetail(rawId);
}

export async function ytmCreateInvite(playlistId: string): Promise<CollabInviteView> {
  const rawId = playlistId.replace(/^ytm:/, '').replace(/^VL/, '');
  const inviteUrl = await enableYtmCollabOnId(rawId);
  const state = readYtmState();
  state.inviteLinks[rawId] = inviteUrl;
  state.stoppedIds = state.stoppedIds.filter((x) => x !== rawId);
  writeYtmState(state);
  const jct = new URL(inviteUrl).searchParams.get('jct') ?? rawId;
  return {
    inviteId: `ytm_inv_${rawId}`,
    playlistId,
    token: jct,
    role: 'EDITOR',
    expiresAtMs: null,
    maxUses: 0,
    useCount: 0,
    createdAtMs: Date.now(),
    inviteUrl,
  };
}

export function ytmListInvites(playlistId: string): CollabInviteView[] {
  const rawId = playlistId.replace(/^ytm:/, '').replace(/^VL/, '');
  const state = readYtmState();
  const url = state.inviteLinks[rawId];
  if (!url) return [];
  return [
    {
      inviteId: `ytm_inv_${rawId}`,
      playlistId,
      token: rawId,
      role: 'EDITOR',
      expiresAtMs: null,
      maxUses: 0,
      useCount: 0,
      createdAtMs: Date.now(),
      inviteUrl: url,
    },
  ];
}

export async function ytmStopSharing(playlistId: string): Promise<CollabPlaylistDetail> {
  const rawId = playlistId.replace(/^ytm:/, '').replace(/^VL/, '');
  const yt = await getSignedInnertube();
  await yt.actions.execute('/browse/edit_playlist', {
    playlistId: rawId,
    actions: [
      { action: 'ACTION_SET_CLOSED_TO_CONTRIBUTIONS', closedToContributions: true },
      { action: 'ACTION_SET_PLAYLIST_PRIVACY', playlistPrivacy: 'PRIVATE' },
    ],
    client: 'YTMUSIC',
  });
  const state = readYtmState();
  if (!state.stoppedIds.includes(rawId)) state.stoppedIds.push(rawId);
  delete state.inviteLinks[rawId];
  recordActivity(state, rawId, 'playlist.stop_sharing', 'Stopped sharing and made playlist private');
  writeYtmState(state);
  return ytmFetchPlaylistDetail(rawId);
}

export async function ytmPreviewInvite(rawId: string): Promise<CollabInvitePreview> {
  const detail = await ytmFetchPlaylistDetail(rawId, { signedIn: false });
  return {
    playlistId: detail.id,
    title: detail.title,
    description: detail.description,
    artworkUrl: detail.artworkUrl,
    ownerName: detail.ownerName,
    ownerAvatarUrl: detail.ownerAvatarUrl,
    memberCount: detail.memberCount,
    trackCount: detail.trackCount,
    role: 'EDITOR',
    expired: false,
    revoked: false,
  };
}

export async function ytmAcceptInvite(rawId: string, jct: string): Promise<{ playlist: CollabPlaylistDetail; alreadyMember: boolean }> {
  const yt = await getSignedInnertube();
  const state = readYtmState();
  const wasAlreadyTracked = state.trackedIds.includes(rawId);
  let joined = false;
  let lastErr: unknown = null;
  for (let attempt = 0; attempt < 8; attempt++) {
    if (attempt > 0) {
      await sleepMs(2000);
    }
    try {
      const resp = await yt.actions.execute('/browse/edit_playlist', {
        playlistId: rawId,
        actions: [{ action: 'ACTION_JOIN_COLLABORATION', joinCollaborationToken: jct }],
        client: 'YTMUSIC',
      });
      const status = findStringDeep(resp.data, 'status');
      if (!status || status.includes('SUCCEEDED')) {
        joined = true;
        break;
      }
      lastErr = new Error(`YouTube Music refused collaboration join (${status})`);
    } catch (err) {
      lastErr = err;
      const addOpts = await yt.actions.execute('/playlist/get_add_to_playlist', { playlistId: 'LM', client: 'YTMUSIC' }).catch(() => ({ data: {} }));
      const addJson = JSON.stringify(addOpts.data ?? {});
      if (addJson.includes(`"${rawId}"`)) {
        joined = true;
        break;
      }
    }
  }
  if (!joined) {
    throw lastErr instanceof Error ? lastErr : new Error('YouTube Music refused the collaboration invite. Ask the owner for a fresh invite link.');
  }
  await yt.actions.execute('/like/like', { target: { playlistId: rawId }, client: 'YTMUSIC' }).catch(() => {});
  if (!state.trackedIds.includes(rawId)) state.trackedIds.push(rawId);
  state.inviteLinks[rawId] = `https://music.youtube.com/playlist?list=${rawId}&jct=${jct}`;
  writeYtmState(state);
  const playlist = await ytmFetchPlaylistDetail(rawId);
  return { playlist, alreadyMember: wasAlreadyTracked };
}
