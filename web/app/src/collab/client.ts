import { useCollabAuth } from './auth';
import { isNativeYtmCollabMode, resolveCollabServerUrl } from './config';
import { parseCollabInviteLink } from './inviteLink';
import {
  YTM_COLLAB_PREFIX,
  ytmAcceptInvite,
  ytmAddCollabTracks,
  ytmConvertToCollabPlaylist,
  ytmCreateCollabPlaylist,
  ytmCreateInvite,
  ytmFetchPlaylistDetail,
  ytmListCollabPlaylists,
  ytmListInvites,
  ytmPreviewInvite,
  ytmRemoveCollabTrack,
  ytmStopSharing,
} from './ytmCollab';
import {
  deriveMemberAvatars,
  normalizeActivity,
  normalizeMember,
  normalizePlaylistDetail,
  normalizePlaylistSummary,
  type CollabActivity,
  type CollabInvitePreview,
  type CollabInviteView,
  type CollabMember,
  type CollabPermissionsPatch,
  type CollabPlaylistDetail,
  type CollabPlaylistSummary,
  type CollabRole,
  type CollabTrackEntry,
  type CollabTrackInput,
  type ConvertCollabPlaylistInput,
} from './types';

export class CollabApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = 'CollabApiError';
  }
}

// ---- Local Offline-Friendly Playlist Cache -----------------------------------------

const CACHE_KEY_PREFIX = 'bitchord:collab-cache:v1:';
const MAX_CACHED_DETAILS = 40;

interface CollabLocalCache {
  summaries: CollabPlaylistSummary[];
  detailsById: Record<string, CollabPlaylistDetail>;
  updatedAtMs: number;
}

function readLocalCache(userId: string | null | undefined): CollabLocalCache {
  if (!userId) return { summaries: [], detailsById: {}, updatedAtMs: 0 };
  try {
    const raw = localStorage.getItem(`${CACHE_KEY_PREFIX}${userId}`);
    if (!raw) return { summaries: [], detailsById: {}, updatedAtMs: 0 };
    const parsed = JSON.parse(raw) as Partial<CollabLocalCache>;
    return {
      summaries: Array.isArray(parsed.summaries) ? parsed.summaries : [],
      detailsById: parsed.detailsById && typeof parsed.detailsById === 'object' ? parsed.detailsById : {},
      updatedAtMs: typeof parsed.updatedAtMs === 'number' ? parsed.updatedAtMs : 0,
    };
  } catch {
    return { summaries: [], detailsById: {}, updatedAtMs: 0 };
  }
}

function writeLocalCache(userId: string | null | undefined, cache: CollabLocalCache): void {
  if (!userId) return;
  try {
    const entries = Object.entries(cache.detailsById)
      .sort((a, b) => (b[1]?.updatedAtMs ?? 0) - (a[1]?.updatedAtMs ?? 0))
      .slice(0, MAX_CACHED_DETAILS);
    const bounded: CollabLocalCache = {
      summaries: cache.summaries,
      detailsById: Object.fromEntries(entries),
      updatedAtMs: Date.now(),
    };
    localStorage.setItem(`${CACHE_KEY_PREFIX}${userId}`, JSON.stringify(bounded));
  } catch {
    // Ignore storage quota issues.
  }
}

export function getCachedCollabSummaries(userId?: string | null): CollabPlaylistSummary[] {
  const uid = userId ?? useCollabAuth.getState().user?.id;
  return readLocalCache(uid).summaries;
}

export function getCachedCollabDetail(playlistId: string, userId?: string | null): CollabPlaylistDetail | null {
  const uid = userId ?? useCollabAuth.getState().user?.id;
  return readLocalCache(uid).detailsById[playlistId] ?? null;
}

export function saveCachedCollabSummaries(summaries: CollabPlaylistSummary[], userId?: string | null): void {
  const uid = userId ?? useCollabAuth.getState().user?.id;
  if (!uid) return;
  const current = readLocalCache(uid);
  const enriched = summaries.map((sum) => {
    const cachedDetail = current.detailsById[sum.id];
    if (cachedDetail?.members?.length) {
      return {
        ...sum,
        memberAvatars: deriveMemberAvatars(sum.ownerUserId, sum.ownerName, sum.ownerAvatarUrl, cachedDetail.members),
      };
    }
    return sum;
  });
  writeLocalCache(uid, { ...current, summaries: enriched });
}

export function saveCachedCollabDetail(detail: CollabPlaylistDetail, userId?: string | null): void {
  const uid = userId ?? useCollabAuth.getState().user?.id;
  if (!uid) return;
  const current = readLocalCache(uid);
  const summary: CollabPlaylistSummary = {
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
  };
  const existingIdx = current.summaries.findIndex((s) => s.id === detail.id);
  const summaries =
    existingIdx >= 0
      ? current.summaries.map((s, idx) => (idx === existingIdx ? summary : s))
      : [summary, ...current.summaries];
  writeLocalCache(uid, {
    summaries,
    detailsById: { ...current.detailsById, [detail.id]: detail },
    updatedAtMs: Date.now(),
  });
}

export function removeCachedCollabPlaylist(playlistId: string, userId?: string | null): void {
  const uid = userId ?? useCollabAuth.getState().user?.id;
  if (!uid) return;
  const current = readLocalCache(uid);
  const nextDetails = { ...current.detailsById };
  delete nextDetails[playlistId];
  writeLocalCache(uid, {
    summaries: current.summaries.filter((s) => s.id !== playlistId),
    detailsById: nextDetails,
    updatedAtMs: Date.now(),
  });
}

// ---- Core Authenticated / Public Request Helper ------------------------------------

async function collabRequest<T>(
  path: string,
  init: RequestInit = {},
  options?: { requireAuth?: boolean; serverOverride?: string | null },
): Promise<T> {
  const requireAuth = options?.requireAuth ?? true;
  const auth = useCollabAuth.getState();
  const baseUrl = resolveCollabServerUrl(options?.serverOverride ?? (auth.serverUrl || null));
  if (!baseUrl) {
    throw new CollabApiError(400, 'missing_server', 'Collaboration server URL is not configured.');
  }
  if (requireAuth && !auth.token) {
    throw new CollabApiError(401, 'auth_required', 'Sign in with Google to access collaborative playlists.');
  }

  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(init.body ? { 'Content-Type': 'application/json' } : {}),
  };
  if (auth.token && requireAuth) {
    headers.Authorization = `Bearer ${auth.token}`;
  }

  let res: Response;
  try {
    res = await fetch(`${baseUrl}${path}`, {
      ...init,
      headers: { ...headers, ...(init.headers as Record<string, string> | undefined) },
    });
  } catch {
    throw new CollabApiError(503, 'network_error', 'Could not reach the collaboration server.');
  }

  let body: Record<string, unknown> = {};
  try {
    body = (await res.json()) as Record<string, unknown>;
  } catch {
    // Non-JSON error body
  }

  if (!res.ok) {
    const code = typeof body.error === 'string' ? body.error : 'request_failed';
    const message =
      typeof body.message === 'string' ? body.message : `Collaboration request failed (HTTP ${res.status}).`;
    if (res.status === 401 && requireAuth) {
      useCollabAuth.getState().clearSession('Your Google session expired. Please sign in again.');
    }
    throw new CollabApiError(res.status, code, message);
  }

  return body as unknown as T;
}

// ---- Playlist Lifecycle & Conversion Endpoints -------------------------------------

export async function listCollabPlaylists(options?: { includeStopped?: boolean }): Promise<CollabPlaylistSummary[]> {
  if (isNativeYtmCollabMode()) {
    const list = await ytmListCollabPlaylists();
    saveCachedCollabSummaries(list);
    return list;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const query = options?.includeStopped !== false ? '?includeStopped=true' : '';
  const data = await collabRequest<{ playlists: unknown[] }>(`/api/collab/playlists${query}`);
  const cached = readLocalCache(userId);
  const summaries = (data.playlists ?? []).map((raw) => {
    const rec = raw as Record<string, unknown>;
    const pid = String(rec?.id ?? '');
    return normalizePlaylistSummary(raw, userId, cached.detailsById[pid]?.members);
  });
  saveCachedCollabSummaries(summaries, userId);
  return summaries;
}

export async function createCollabPlaylist(params: {
  title: string;
  description?: string;
  artworkUrl?: string | null;
  permissions?: CollabPermissionsPatch;
  tracks?: CollabTrackInput[];
}): Promise<CollabPlaylistDetail> {
  if (isNativeYtmCollabMode()) {
    const detail = await ytmCreateCollabPlaylist(params);
    saveCachedCollabDetail(detail);
    return detail;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown }>('/api/collab/playlists', {
    method: 'POST',
    body: JSON.stringify({
      title: params.title.trim(),
      description: params.description?.trim() ?? '',
      artworkUrl: params.artworkUrl ?? null,
      permissions: params.permissions,
      tracks: params.tracks ?? [],
    }),
  });
  const detail = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(detail, userId);
  return detail;
}

export async function convertToCollabPlaylist(
  input: ConvertCollabPlaylistInput,
): Promise<{ playlist: CollabPlaylistDetail; alreadyConverted: boolean }> {
  if (isNativeYtmCollabMode()) {
    const res = await ytmConvertToCollabPlaylist(input);
    saveCachedCollabDetail(res.playlist);
    return res;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown; alreadyConverted?: boolean }>(
    '/api/collab/playlists/convert',
    {
      method: 'POST',
      body: JSON.stringify(input),
    },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return { playlist, alreadyConverted: Boolean(data.alreadyConverted) };
}

export async function getCollabPlaylist(playlistId: string): Promise<CollabPlaylistDetail> {
  if (isNativeYtmCollabMode() || playlistId.startsWith(YTM_COLLAB_PREFIX)) {
    const detail = await ytmFetchPlaylistDetail(playlistId);
    saveCachedCollabDetail(detail);
    return detail;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}`,
  );
  const detail = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(detail, userId);
  return detail;
}

export async function updateCollabPlaylist(
  playlistId: string,
  patch: {
    title?: string;
    description?: string;
    artworkUrl?: string | null;
    permissions?: CollabPermissionsPatch;
    opId?: string;
  },
): Promise<CollabPlaylistDetail> {
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}`,
    {
      method: 'PATCH',
      body: JSON.stringify(patch),
    },
  );
  const detail = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(detail, userId);
  return detail;
}

export async function stopSharingCollabPlaylist(playlistId: string): Promise<CollabPlaylistDetail> {
  if (isNativeYtmCollabMode() || playlistId.startsWith(YTM_COLLAB_PREFIX)) {
    const detail = await ytmStopSharing(playlistId);
    saveCachedCollabDetail(detail);
    return detail;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ ok: boolean; playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/stop-sharing`,
    { method: 'POST', body: '{}' },
  );
  const detail = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(detail, userId);
  return detail;
}

export async function deleteCollabPlaylist(playlistId: string): Promise<void> {
  const userId = useCollabAuth.getState().user?.id ?? null;
  await collabRequest<{ ok: boolean }>(`/api/collab/playlists/${encodeURIComponent(playlistId)}`, {
    method: 'DELETE',
  });
  removeCachedCollabPlaylist(playlistId, userId);
}

// ---- Track Mutations ---------------------------------------------------------------

export async function addCollabTracks(
  playlistId: string,
  params: { tracks: CollabTrackInput[]; position?: number; opId?: string },
): Promise<{ playlist: CollabPlaylistDetail; added: CollabTrackEntry[]; tracks: CollabTrackEntry[] }> {
  if (isNativeYtmCollabMode() || playlistId.startsWith(YTM_COLLAB_PREFIX)) {
    const res = await ytmAddCollabTracks(playlistId, params.tracks);
    saveCachedCollabDetail(res.playlist);
    return res;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown; added: CollabTrackEntry[] }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/tracks`,
    {
      method: 'POST',
      body: JSON.stringify(params),
    },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return { playlist, added: data.added ?? [], tracks: playlist.tracks };
}

export async function removeCollabTrack(
  playlistId: string,
  entryId: string,
  opId?: string,
): Promise<CollabPlaylistDetail> {
  if (isNativeYtmCollabMode() || playlistId.startsWith(YTM_COLLAB_PREFIX)) {
    const cur = getCachedCollabDetail(playlistId);
    const vid = cur?.tracks.find((t) => t.entryId === entryId)?.trackId;
    const detail = await ytmRemoveCollabTrack(playlistId, entryId, vid);
    saveCachedCollabDetail(detail);
    return detail;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const query = opId ? `?opId=${encodeURIComponent(opId)}` : '';
  const data = await collabRequest<{ playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/tracks/${encodeURIComponent(entryId)}${query}`,
    { method: 'DELETE' },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return playlist;
}

export async function reorderCollabTracks(
  playlistId: string,
  params:
    | { entryIds: string[]; opId?: string }
    | { fromIndex: number; toIndex: number; entryId?: string; opId?: string },
): Promise<CollabPlaylistDetail> {
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/tracks/order`,
    {
      method: 'PUT',
      body: JSON.stringify(params),
    },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return playlist;
}

// ---- Members, Roles, Ownership & Activity ------------------------------------------

export async function listCollabMembers(playlistId: string): Promise<CollabMember[]> {
  const data = await collabRequest<{ members: unknown[] }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/members`,
  );
  return (data.members ?? []).map(normalizeMember);
}

export async function updateCollabMemberRole(
  playlistId: string,
  targetUserId: string,
  role: Exclude<CollabRole, 'OWNER'>,
): Promise<CollabPlaylistDetail> {
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/members/${encodeURIComponent(targetUserId)}`,
    {
      method: 'PATCH',
      body: JSON.stringify({ role }),
    },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return playlist;
}

export async function transferCollabOwnership(
  playlistId: string,
  newOwnerUserId: string,
): Promise<CollabPlaylistDetail> {
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/transfer-ownership`,
    {
      method: 'POST',
      body: JSON.stringify({ newOwnerUserId }),
    },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return playlist;
}

export async function removeCollabMember(playlistId: string, targetUserId: string): Promise<CollabPlaylistDetail> {
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/members/${encodeURIComponent(targetUserId)}`,
    { method: 'DELETE' },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return playlist;
}

export async function leaveCollabPlaylist(playlistId: string): Promise<void> {
  const userId = useCollabAuth.getState().user?.id ?? null;
  await collabRequest<{ ok: boolean }>(`/api/collab/playlists/${encodeURIComponent(playlistId)}/leave`, {
    method: 'POST',
    body: '{}',
  });
  removeCachedCollabPlaylist(playlistId, userId);
}

export async function listCollabActivity(playlistId: string): Promise<CollabActivity[]> {
  const data = await collabRequest<{ activities: unknown[] }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/activity`,
  );
  return (data.activities ?? []).map(normalizeActivity);
}

// ---- Invites & Public Preview ------------------------------------------------------

export async function createCollabInvite(
  playlistId: string,
  params: { role: 'EDITOR' | 'VIEWER'; expiresInSec?: number; maxUses?: number },
): Promise<CollabInviteView> {
  if (isNativeYtmCollabMode() || playlistId.startsWith(YTM_COLLAB_PREFIX)) {
    return ytmCreateInvite(playlistId);
  }
  return collabRequest<CollabInviteView>(`/api/collab/playlists/${encodeURIComponent(playlistId)}/invites`, {
    method: 'POST',
    body: JSON.stringify(params),
  });
}

export async function listCollabInvites(playlistId: string): Promise<CollabInviteView[]> {
  if (isNativeYtmCollabMode() || playlistId.startsWith(YTM_COLLAB_PREFIX)) {
    return ytmListInvites(playlistId);
  }
  const data = await collabRequest<{ invites: CollabInviteView[] }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/invites`,
  );
  return data.invites ?? [];
}

export async function revokeCollabInvite(playlistId: string, inviteId: string): Promise<void> {
  await collabRequest<{ ok: boolean }>(
    `/api/collab/playlists/${encodeURIComponent(playlistId)}/invites/${encodeURIComponent(inviteId)}`,
    { method: 'DELETE' },
  );
}

export async function previewCollabInvite(
  token: string,
  serverOverride?: string | null,
): Promise<CollabInvitePreview> {
  const parsedPreview = parseCollabInviteLink(token);
  if (parsedPreview?.ytmPlaylistId) {
    return ytmPreviewInvite(parsedPreview.ytmPlaylistId);
  }
  return collabRequest<CollabInvitePreview>(
    `/api/collab/invites/${encodeURIComponent(token)}/preview`,
    { method: 'GET' },
    { requireAuth: false, serverOverride },
  );
}

export async function acceptCollabInvite(
  token: string,
  serverOverride?: string | null,
): Promise<{ playlist: CollabPlaylistDetail; alreadyMember: boolean }> {
  const parsedAccept = parseCollabInviteLink(token);
  if (parsedAccept?.ytmPlaylistId && parsedAccept?.ytmJoinToken) {
    const res = await ytmAcceptInvite(parsedAccept.ytmPlaylistId, parsedAccept.ytmJoinToken);
    saveCachedCollabDetail(res.playlist);
    return res;
  }
  const userId = useCollabAuth.getState().user?.id ?? null;
  const data = await collabRequest<{ playlist: unknown; alreadyMember: boolean }>(
    `/api/collab/invites/${encodeURIComponent(token)}/accept`,
    { method: 'POST', body: '{}' },
    { requireAuth: true, serverOverride },
  );
  const playlist = normalizePlaylistDetail(data.playlist, userId);
  saveCachedCollabDetail(playlist, userId);
  return { playlist, alreadyMember: Boolean(data.alreadyMember) };
}
