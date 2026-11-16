import { extractCreatedPlaylistIdFromData, fetchLibraryPlaylists } from '@/data/music/api';
import { getInnertube, getSignedInnertube } from '@/playback/youtube/innertube';
import { useCollabAuth } from './auth';
import { ytmCollaboratorsPanelParams, ytmInviteBrowseParams } from './inviteLink';
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

type SignedInnertube = Awaited<ReturnType<typeof getSignedInnertube>>;

/** The HTTP status a failed Innertube call was answered with, when the error says. */
export function ytmErrorStatus(err: unknown): number | null {
  if (!err || typeof err !== 'object') return null;
  const direct = (err as RawObj).status ?? ((err as RawObj).info as RawObj | undefined)?.status ?? ((err as RawObj).response as RawObj | undefined)?.status;
  if (typeof direct === 'number') return direct;
  const text = err instanceof Error ? err.message : '';
  const match = /\b(?:status(?: code)?|HTTP)\s*:?\s*(\d{3})\b/i.exec(text) ?? /"code"\s*:\s*(\d{3})/.exec(text);
  return match ? Number(match[1]) : null;
}

const MEMBER_ONLY_EDIT_ACTIONS = ['ACTION_REMOVE_VIDEO', 'ACTION_MOVE_VIDEO', 'ACTION_SET_PLAYLIST', 'ACTION_SET_CLOSED_TO_CONTRIBUTIONS', 'ACTION_LEAVE'];

function endpointJoins(endpoint: RawObj): boolean {
  const actions = Array.isArray(endpoint.actions) ? (endpoint.actions as RawObj[]) : [];
  return actions.some((a) => String(a?.action ?? '').includes('JOIN') || typeof a?.joinCollaborationToken === 'string');
}

/** Playlist ids the account can add to, from a `get_add_to_playlist` answer. A join offer is not write access. */
export function parseAddToPlaylistIds(data: unknown): Set<string> {
  const ids = new Set<string>();
  for (const opt of collectByKey(data, 'playlistAddToOptionRenderer')) {
    const id = findStringDeep(opt, 'playlistId');
    if (id) ids.add(id.replace(/^VL/, ''));
  }
  for (const ep of collectByKey(data, 'playlistEditEndpoint')) {
    if (!endpointJoins(ep) && typeof ep.playlistId === 'string') ids.add(ep.playlistId.replace(/^VL/, ''));
  }
  return ids;
}

/** Whether a playlist page hands the account commands that change the playlist — proof it may write to it. */
export function hasPlaylistEditAffordance(data: unknown, rawId: string): boolean {
  return collectByKey(data, 'playlistEditEndpoint').some((ep) => {
    if (typeof ep.playlistId !== 'string' || ep.playlistId.replace(/^VL/, '') !== rawId) return false;
    const actions = Array.isArray(ep.actions) ? (ep.actions as RawObj[]) : [];
    return actions.some((a) => MEMBER_ONLY_EDIT_ACTIONS.some((prefix) => String(a?.action ?? '').startsWith(prefix)));
  });
}

/** The join commands a playlist page's invite dialog carries (a `playlistEditEndpoint` whose actions join). */
export function parseCollabJoinEndpoints(data: unknown, rawId: string): RawObj[] {
  return collectByKey(data, 'playlistEditEndpoint')
    .filter((ep) => endpointJoins(ep) && (typeof ep.playlistId !== 'string' || ep.playlistId.replace(/^VL/, '') === rawId))
    .map((ep) => ({ ...ep, playlistId: rawId }));
}

/**
 * Whether the signed-in account can write to the playlist: it owns it, YouTube Music hands it
 * edit commands for it, or lists it in the "Save to playlist" dialog (asked the way the web
 * client asks, with the track being saved).
 */
async function ytmCanEditPlaylist(yt: SignedInnertube, rawId: string): Promise<boolean> {
  const page = await yt.actions.execute('/browse', { browseId: `VL${rawId}`, client: 'YTMUSIC' }).catch(() => null);
  if (page?.data) {
    if (collectByKey(page.data, 'musicEditablePlaylistDetailHeaderRenderer').length > 0) return true;
    if (hasPlaylistEditAffordance(page.data, rawId)) return true;
  }
  const probe = (page?.data && findStringDeep(collectByKey(page.data, 'playlistItemData')[0], 'videoId')) || 'dQw4w9WgXcQ';
  const byTrack = await yt.actions
    .execute('/playlist/get_add_to_playlist', { videoIds: [probe], excludeWatchLater: true, client: 'YTMUSIC' })
    .catch(() => null);
  let ids = parseAddToPlaylistIds(byTrack?.data);
  if (ids.size === 0) {
    const byList = await yt.actions
      .execute('/playlist/get_add_to_playlist', { playlistId: 'LM', excludeWatchLater: true, client: 'YTMUSIC' })
      .catch(() => null);
    ids = parseAddToPlaylistIds(byList?.data);
  }
  return ids.has(rawId);
}

/** Collaborator rows of a `get_panel` (`PAplaylist_collaborate`) answer. */
export function parseCollaboratorsPanel(data: unknown): { name: string; avatarUrl: string | null; channelId: string | null }[] {
  const out: { name: string; avatarUrl: string | null; channelId: string | null }[] = [];
  for (const vm of collectByKey(data, 'playlistCollaborationViewModel')) {
    const rows = Array.isArray(vm.playlistCollaborators) ? (vm.playlistCollaborators as RawObj[]) : [];
    for (const item of rows) {
      const row = (item?.contentListItemViewModel as RawObj | undefined) ?? item;
      const name = findStringDeep(row?.title, 'content') ?? runsText(row?.title);
      if (!name) continue;
      const sources = ((((row?.avatar as RawObj | undefined)?.avatarViewModel as RawObj | undefined)?.image as RawObj | undefined)?.sources ?? []) as RawObj[];
      const last = sources[sources.length - 1];
      out.push({
        name: name.trim(),
        avatarUrl: typeof last?.url === 'string' ? last.url : null,
        channelId: findStringDeep(row?.rendererContext, 'browseId'),
      });
    }
  }
  return out;
}

export interface YtmAddResult {
  acceptedIds: string[];
  rejectedIds: string[];
}

/**
 * Adds tracks and accounts for every one. A playlist created a moment ago answers 409 (or 5xx)
 * until it settles, so that is waited out; any other refusal is narrowed by halving the batch,
 * so one track YouTube Music will not take costs that track and not the fifty sent with it.
 */
export async function ytmAddVideosReliably(
  yt: SignedInnertube,
  rawId: string,
  videoIds: string[],
  options: { settleDelayMs?: number; settleWaits?: number } = {},
): Promise<YtmAddResult> {
  const settleDelayMs = options.settleDelayMs ?? 2500;
  const settleWaits = options.settleWaits ?? 10;
  const acceptedIds: string[] = [];
  const rejectedIds: string[] = [];
  const send = async (batch: string[]) => {
    const resp = await yt.actions.execute('/browse/edit_playlist', {
      playlistId: rawId,
      actions: batch.map((vid) => ({ action: 'ACTION_ADD_VIDEO', addedVideoId: vid, dedupeOption: 'DEDUPE_OPTION_SKIP' })),
      client: 'YTMUSIC',
    });
    const status = typeof (resp.data as RawObj | undefined)?.status === 'string' ? ((resp.data as RawObj).status as string) : null;
    if (status && !status.includes('SUCCEEDED')) throw new Error(`YouTube Music refused the edit (${status})`);
  };
  const addBatch = async (batch: string[], waitForSettling: boolean): Promise<void> => {
    if (batch.length === 0) return;
    let lastErr: unknown = null;
    for (let wait = 0; wait <= (waitForSettling ? settleWaits : 0); wait++) {
      try {
        await send(batch);
        acceptedIds.push(...batch);
        return;
      } catch (err) {
        lastErr = err;
        const status = ytmErrorStatus(err);
        const settling = status === 409 || (status !== null && status >= 500);
        if (!settling) break;
        if (wait < settleWaits) await sleepMs(settleDelayMs);
      }
    }
    // Signed out: no smaller batch will fare better.
    if (ytmErrorStatus(lastErr) === 401) throw lastErr;
    if (batch.length === 1) {
      rejectedIds.push(batch[0] as string);
      return;
    }
    const middle = Math.floor(batch.length / 2);
    await addBatch(batch.slice(0, middle), false);
    await addBatch(batch.slice(middle), false);
  };
  for (let i = 0; i < videoIds.length; i += 50) {
    await addBatch(videoIds.slice(i, i + 50), true);
  }
  return { acceptedIds, rejectedIds };
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

  // The roster behind the header's avatar stack. The panel takes params the header carries,
  // which are nothing more than the playlist id wrapped up — so they can be built when absent.
  if (facepile && options?.signedIn !== false) {
    const showPanel = collectByKey(facepile, 'showEngagementPanelEndpoint')[0];
    const headerParams = findStringDeep(showPanel?.globalConfiguration, 'params');
    const panelResp = await yt.actions
      .execute('/get_panel', {
        panelId: 'PAplaylist_collaborate',
        params: headerParams ?? ytmCollaboratorsPanelParams(rawId),
        client: 'YTMUSIC',
      })
      .catch(() => null);
    parseCollaboratorsPanel(panelResp?.data).forEach((row, idx) => {
      const isMe = Boolean(me && row.name.toLowerCase() === me.displayName.toLowerCase());
      const known = members.find((m) => m.displayName.toLowerCase() === row.name.toLowerCase() || (isMe && m.userId === me?.id));
      if (known) {
        if (!known.avatarUrl && row.avatarUrl) known.avatarUrl = row.avatarUrl;
        return;
      }
      if (idx === 0 && !owned && members[0]) {
        // First row is the owner; the header only gave a label for them.
        members[0].displayName = row.name;
        members[0].avatarUrl = row.avatarUrl ?? members[0].avatarUrl;
        return;
      }
      members.push({
        playlistId: cplId,
        userId: isMe && me ? me.id : (row.channelId ?? `ytm:${row.name.toLowerCase()}`),
        role: 'EDITOR',
        displayName: row.name,
        avatarUrl: row.avatarUrl,
        joinedAtMs: 0,
      });
    });
  }
  // Collaborators the header counts but the panel did not name still count as members.
  for (let i = members.length; i < facepileAvatars.length; i++) {
    members.push({
      playlistId: cplId,
      userId: `ytm:facepile:${rawId}_${i}`,
      role: 'EDITOR',
      displayName: `Collaborator ${i}`,
      avatarUrl: facepileAvatars[i] ?? null,
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
  const videoIds = Array.from(
    new Set(
      (params.tracks ?? [])
        .map((t) => (t.trackId || t.videoId || '').trim())
        .filter((v) => /^[A-Za-z0-9_-]{11}$/.test(v)),
    ),
  );
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
  // The rest follow once the playlist exists — confirmed batch by batch, never dropped quietly.
  const migration = await ytmAddVideosReliably(yt, rawId, videoIds.slice(50));
  const rejected = new Set(migration.rejectedIds);
  const inviteUrl = await enableYtmCollabOnId(rawId, createdAsPrivate);

  const state = readYtmState();
  if (!state.trackedIds.includes(rawId)) state.trackedIds.push(rawId);
  state.stoppedIds = state.stoppedIds.filter((x) => x !== rawId);
  state.inviteLinks[rawId] = inviteUrl;
  if (me) {
    const now = Date.now();
    for (const vid of videoIds) {
      if (rejected.has(vid)) continue;
      state.stamps[`${rawId}:${vid}`] = { userId: me.id, displayName: me.displayName, avatarUrl: me.avatarUrl, addedAtMs: now };
    }
  }
  const carried = videoIds.length - rejected.size;
  recordActivity(
    state,
    rawId,
    'playlist.create',
    rejected.size > 0
      ? `${me?.displayName ?? 'You'} created collaborative playlist "${cleanTitle}" (${carried} of ${videoIds.length} songs — YouTube Music would not take the rest)`
      : `${me?.displayName ?? 'You'} created collaborative playlist "${cleanTitle}"`,
  );
  writeYtmState(state);
  return ytmFetchPlaylistDetail(rawId, {
    fallbackTitle: cleanTitle,
    fallbackDescription: cleanDesc,
    fallbackTracks: (params.tracks ?? []).filter((t) => !rejected.has((t.trackId || t.videoId || '').trim())),
  });
}

export async function ytmConvertToCollabPlaylist(
  input: ConvertCollabPlaylistInput,
): Promise<{ playlist: CollabPlaylistDetail; alreadyConverted: boolean }> {
  const state = readYtmState();
  const key = `${input.sourceType}:${input.sourcePlaylistId}`;
  const existing = state.convertedSources[key];
  if (existing && !state.stoppedIds.includes(existing)) {
    try {
      let detail = await ytmFetchPlaylistDetail(existing);
      // An earlier conversion may have stopped short, or the source may have grown since.
      const present = new Set(detail.tracks.map((t) => t.trackId));
      const missing = Array.from(
        new Set(
          input.tracks
            .map((t) => (t.trackId || t.videoId || '').trim())
            .filter((v) => /^[A-Za-z0-9_-]{11}$/.test(v) && !present.has(v)),
        ),
      );
      if (missing.length > 0) {
        const yt = await getSignedInnertube();
        const topUp = await ytmAddVideosReliably(yt, existing, missing);
        if (topUp.acceptedIds.length > 0) detail = await ytmFetchPlaylistDetail(existing);
      }
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
  // A collaborator's first add can be refused for a few seconds after joining while the
  // membership propagates, which the settle wait covers.
  const outcome = await ytmAddVideosReliably(yt, rawId, videoIds, { settleWaits: 3 });
  if (outcome.acceptedIds.length === 0 && videoIds.length > 0) {
    throw new Error("YouTube Music didn't add the song. Check that you still have access to this playlist and try again.");
  }
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

/** What a refused join should say on screen — never the response body. */
export function ytmJoinFailureMessage(err: unknown): string {
  const status = ytmErrorStatus(err);
  if (status === 404) return "This playlist can't be found on YouTube Music. It may have been deleted or made private.";
  if (status === null || status === 400 || status === 401 || status === 403) {
    return (
      "YouTube Music didn't accept this invite for the signed-in account. The link may have been replaced by a newer one, " +
      'or sharing was turned off — ask the owner to create a fresh invite link and send it again. If it keeps happening, ' +
      "check that you're signed in with the Google account you want to join with and that it has a YouTube channel."
    );
  }
  return `YouTube Music couldn't complete the join right now (HTTP ${status}). Try again in a moment.`;
}

/**
 * Joins a YouTube Music collaborative playlist via its `jct`.
 *
 * `ACTION_JOIN_COLLABORATION` is answered 401 "You are not authorized to edit this playlist"
 * both when the account is already a collaborator and while a freshly minted token has not
 * propagated (up to ~20s). So a refusal is never taken at its word: write access is checked
 * after it, the join is retried across that window, and on alternate rounds it is made the way
 * music.youtube.com makes it — through the join command on the invite page itself.
 */
export async function ytmAcceptInvite(
  rawId: string,
  jct: string,
  options: { retryDelayMs?: number; attempts?: number } = {},
): Promise<{ playlist: CollabPlaylistDetail; alreadyMember: boolean }> {
  const yt = await getSignedInnertube();
  const state = readYtmState();
  const wasAlreadyTracked = state.trackedIds.includes(rawId);
  const retryDelayMs = options.retryDelayMs ?? 2500;
  const attempts = options.attempts ?? 7;
  const succeeded = (data: unknown) => {
    const status = typeof (data as RawObj | undefined)?.status === 'string' ? ((data as RawObj).status as string) : null;
    return !status || status.includes('SUCCEEDED');
  };
  let joined = false;
  let alreadyCollaborator = false;
  let lastErr: unknown = null;
  for (let attempt = 0; attempt < attempts && !joined; attempt++) {
    if (attempt > 0) await sleepMs(retryDelayMs);
    try {
      const resp = await yt.actions.execute('/browse/edit_playlist', {
        playlistId: rawId,
        actions: [{ action: 'ACTION_JOIN_COLLABORATION', joinCollaborationToken: jct }],
        client: 'YTMUSIC',
      });
      if (succeeded(resp.data)) {
        joined = true;
        break;
      }
      lastErr = new Error('YouTube Music refused the collaboration join');
    } catch (err) {
      lastErr = err;
    }
    if (await ytmCanEditPlaylist(yt, rawId)) {
      joined = true;
      alreadyCollaborator = attempt === 0;
      break;
    }
    if (attempt % 2 === 0) {
      const page = await yt.actions
        .execute('/browse', { browseId: `VL${rawId}`, params: ytmInviteBrowseParams(jct), client: 'YTMUSIC' })
        .catch(() => null);
      for (const endpoint of parseCollabJoinEndpoints(page?.data, rawId)) {
        const served = await yt.actions.execute('/browse/edit_playlist', { ...endpoint, client: 'YTMUSIC' }).catch(() => null);
        if (served && succeeded(served.data)) {
          joined = true;
          break;
        }
      }
      if (!joined && (await ytmCanEditPlaylist(yt, rawId))) joined = true;
    }
    const status = ytmErrorStatus(lastErr);
    if (status !== null && ![400, 401, 403, 404, 409, 429, 500, 502, 503].includes(status)) break;
  }
  if (!joined) {
    throw new Error(ytmJoinFailureMessage(lastErr));
  }
  await yt.actions.execute('/like/like', { target: { playlistId: rawId }, client: 'YTMUSIC' }).catch(() => {});
  if (!state.trackedIds.includes(rawId)) state.trackedIds.push(rawId);
  state.inviteLinks[rawId] = `https://music.youtube.com/playlist?list=${rawId}&jct=${jct}`;
  writeYtmState(state);
  const playlist = await ytmFetchPlaylistDetail(rawId);
  return { playlist, alreadyMember: wasAlreadyTracked || alreadyCollaborator };
}
