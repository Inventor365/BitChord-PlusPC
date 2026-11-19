import type { Track } from '@/data/music/types';

export type CollabRole = 'OWNER' | 'EDITOR' | 'VIEWER';
export type CollabEditorRemovePolicy = 'any' | 'own';
export type CollabTrackProvider = 'youtube' | 'spotify' | 'local' | 'addon';
export type CollabSourceType = 'ytm' | 'local' | 'desktop' | 'spotify';

export interface CollabUser {
  id: string;
  email: string;
  emailVerified: boolean;
  displayName: string;
  avatarUrl: string | null;
  createdAtMs: number;
  updatedAtMs: number;
}

export interface CollabPermissions {
  editorCanRemove: CollabEditorRemovePolicy;
  editorCanReorder: boolean;
  editorCanInvite: boolean;
}

export interface CollabPermissionsPatch {
  editorCanRemove?: CollabEditorRemovePolicy;
  editorCanReorder?: boolean;
  editorCanInvite?: boolean;
}

export const DEFAULT_COLLAB_PERMISSIONS: CollabPermissions = {
  editorCanRemove: 'any',
  editorCanReorder: true,
  editorCanInvite: false,
};

export interface CollabMemberAvatar {
  userId: string;
  displayName: string;
  avatarUrl: string | null;
}

export interface CollabPlaylistSummary {
  id: string;
  title: string;
  description: string;
  artworkUrl: string | null;
  ownerUserId: string;
  ownerName: string;
  ownerAvatarUrl: string | null;
  myRole: CollabRole;
  sharingEnabled: boolean;
  permissions: CollabPermissions;
  sourceType: CollabSourceType | null;
  sourcePlaylistId: string | null;
  memberCount: number;
  memberAvatars: CollabMemberAvatar[];
  trackCount: number;
  revision: number;
  createdAtMs: number;
  updatedAtMs: number;
}

export interface CollabTrackEntry {
  entryId: string;
  playlistId: string;
  position: number;
  trackId: string;
  provider: CollabTrackProvider;
  title: string;
  artist: string;
  albumName: string | null;
  thumbnailUrl: string | null;
  durationMs: number | null;
  isExplicit: boolean;
  contributorId: string | null;
  contributorName: string | null;
  contributorAvatarUrl: string | null;
  addedAtMs: number;
}

export interface CollabTrackInput {
  trackId: string;
  videoId?: string;
  provider: CollabTrackProvider;
  title: string;
  artist: string;
  albumName?: string | null;
  thumbnailUrl?: string | null;
  durationMs?: number | null;
  isExplicit?: boolean;
}

export interface CollabMember {
  playlistId: string;
  userId: string;
  role: CollabRole;
  displayName: string;
  avatarUrl: string | null;
  joinedAtMs: number;
  addedById?: string | null;
}

export interface CollabActivity {
  id: string;
  playlistId: string;
  actorUserId: string;
  actorName: string;
  actorAvatarUrl: string | null;
  action: string;
  summary: string;
  createdAtMs: number;
}

export interface CollabInviteView {
  inviteId: string;
  playlistId: string;
  token: string;
  role: 'EDITOR' | 'VIEWER';
  expiresAtMs: number | null;
  maxUses: number;
  useCount: number;
  createdAtMs: number;
  inviteUrl: string;
}

export interface CollabInvitePreview {
  playlistId: string;
  title: string;
  description: string;
  artworkUrl: string | null;
  ownerName: string;
  ownerAvatarUrl: string | null;
  memberCount: number;
  trackCount: number;
  role: CollabRole;
  expired: boolean;
  revoked: boolean;
}

export interface CollabPlaylistDetail extends CollabPlaylistSummary {
  members: CollabMember[];
  tracks: CollabTrackEntry[];
  activities: CollabActivity[];
}

export interface ConvertCollabPlaylistInput {
  sourcePlaylistId: string;
  sourceType: CollabSourceType;
  title: string;
  description?: string;
  artworkUrl?: string | null;
  attributeExistingTracksToOwner: boolean;
  permissions?: CollabPermissionsPatch;
  tracks: CollabTrackInput[];
}

// ---- WebSocket Wire Frames ---------------------------------------------------------

export type CollabWsClientFrame =
  | { type: 'subscribe'; playlistId: string; sinceRevision?: number }
  | { type: 'unsubscribe'; playlistId: string }
  | { type: 'ping'; clientMs: number };

export type CollabWsServerFrame =
  | {
      type: 'collab.snapshot';
      playlistId: string;
      revision: number;
      playlist: unknown;
      serverMs: number;
    }
  | {
      type: 'collab.subscribed';
      playlistId: string;
      revision: number;
      upToDate: boolean;
      serverMs: number;
    }
  | {
      type: 'collab.unsubscribed';
      playlistId: string;
      serverMs: number;
    }
  | {
      type: 'collab.tracks_changed';
      playlistId: string;
      revision: number;
      opId?: string;
      tracks: CollabTrackEntry[];
      latestActivity?: CollabActivity | null;
      serverMs: number;
    }
  | {
      type: 'collab.metadata_changed';
      playlistId: string;
      revision: number;
      opId?: string;
      playlist: unknown;
      latestActivity?: CollabActivity | null;
      serverMs: number;
    }
  | {
      type: 'collab.member_joined';
      playlistId: string;
      revision: number;
      member: CollabMember;
      members: CollabMember[];
      latestActivity?: CollabActivity | null;
      serverMs: number;
    }
  | {
      type: 'collab.members_changed';
      playlistId: string;
      revision: number;
      members: CollabMember[];
      latestActivity?: CollabActivity | null;
      serverMs: number;
    }
  | {
      type: 'collab.member_removed';
      playlistId: string;
      userId: string;
      reason: string;
      message: string;
      serverMs: number;
    }
  | {
      type: 'collab.stopped';
      playlistId: string;
      message: string;
      serverMs: number;
    }
  | {
      type: 'collab.error';
      playlistId?: string;
      error: string;
      message: string;
      serverMs: number;
    }
  | {
      type: 'pong';
      clientMs?: number;
      serverMs: number;
    };

// ---- Normalization -----------------------------------------------------------------

function asRole(value: unknown, fallback: CollabRole = 'VIEWER'): CollabRole {
  if (value === 'OWNER' || value === 'EDITOR' || value === 'VIEWER') return value;
  return fallback;
}

function asPermissions(raw: unknown): CollabPermissions {
  if (!raw || typeof raw !== 'object') return { ...DEFAULT_COLLAB_PERMISSIONS };
  const rec = raw as Record<string, unknown>;
  return {
    editorCanRemove: rec.editorCanRemove === 'own' ? 'own' : 'any',
    editorCanReorder: typeof rec.editorCanReorder === 'boolean' ? rec.editorCanReorder : true,
    editorCanInvite: typeof rec.editorCanInvite === 'boolean' ? rec.editorCanInvite : false,
  };
}

function asSourceType(value: unknown): CollabSourceType | null {
  if (value === 'ytm' || value === 'local' || value === 'desktop' || value === 'spotify') return value;
  return null;
}

function asProvider(value: unknown): CollabTrackProvider {
  if (value === 'youtube' || value === 'spotify' || value === 'local' || value === 'addon') return value;
  return 'youtube';
}

export function deriveMemberAvatars(
  ownerUserId: string,
  ownerName: string,
  ownerAvatarUrl: string | null,
  members?: readonly CollabMember[] | null,
  rawAvatars?: unknown,
): CollabMemberAvatar[] {
  if (members && members.length > 0) {
    return members.slice(0, 6).map((m) => ({
      userId: m.userId,
      displayName: m.displayName || 'Collaborator',
      avatarUrl: m.avatarUrl ?? null,
    }));
  }
  if (Array.isArray(rawAvatars) && rawAvatars.length > 0) {
    return rawAvatars
      .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
      .slice(0, 6)
      .map((item) => ({
        userId: String(item.userId ?? ''),
        displayName: String(item.displayName ?? 'Collaborator'),
        avatarUrl: typeof item.avatarUrl === 'string' && item.avatarUrl ? item.avatarUrl : null,
      }));
  }
  if (ownerUserId || ownerName) {
    return [{ userId: ownerUserId, displayName: ownerName || 'Owner', avatarUrl: ownerAvatarUrl }];
  }
  return [];
}

export function normalizePlaylistSummary(
  raw: unknown,
  currentUserId?: string | null,
  membersHint?: readonly CollabMember[] | null,
): CollabPlaylistSummary {
  const rec = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const ownerUserId = String(rec.ownerUserId ?? rec.ownerId ?? '');
  const ownerName = String(rec.ownerName ?? 'Sonara Listener');
  const ownerAvatarUrl = typeof rec.ownerAvatarUrl === 'string' && rec.ownerAvatarUrl ? rec.ownerAvatarUrl : null;

  let inferredRole: CollabRole = asRole(rec.myRole ?? rec.role, ownerUserId && ownerUserId === currentUserId ? 'OWNER' : 'VIEWER');
  if (currentUserId) {
    if (ownerUserId === currentUserId) {
      inferredRole = 'OWNER';
    } else if (membersHint?.length) {
      const found = membersHint.find((m) => m.userId === currentUserId);
      if (found) inferredRole = found.role;
    }
  }

  return {
    id: String(rec.id ?? ''),
    title: String(rec.title ?? 'Collaborative Playlist'),
    description: typeof rec.description === 'string' ? rec.description : '',
    artworkUrl: typeof rec.artworkUrl === 'string' && rec.artworkUrl ? rec.artworkUrl : null,
    ownerUserId,
    ownerName,
    ownerAvatarUrl,
    myRole: inferredRole,
    sharingEnabled: rec.sharingEnabled !== false,
    permissions: asPermissions(rec.permissions),
    sourceType: asSourceType(rec.sourceType),
    sourcePlaylistId: typeof rec.sourcePlaylistId === 'string' && rec.sourcePlaylistId ? rec.sourcePlaylistId : null,
    memberCount: typeof rec.memberCount === 'number' ? rec.memberCount : (membersHint?.length ?? 1),
    memberAvatars: deriveMemberAvatars(ownerUserId, ownerName, ownerAvatarUrl, membersHint, rec.memberAvatars),
    trackCount: typeof rec.trackCount === 'number' ? rec.trackCount : 0,
    revision: typeof rec.revision === 'number' ? rec.revision : 1,
    createdAtMs: typeof rec.createdAtMs === 'number' ? rec.createdAtMs : 0,
    updatedAtMs: typeof rec.updatedAtMs === 'number' ? rec.updatedAtMs : 0,
  };
}

export function normalizeTrackEntry(raw: unknown, index = 0): CollabTrackEntry {
  const rec = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  return {
    entryId: String(rec.entryId ?? ''),
    playlistId: String(rec.playlistId ?? ''),
    position: typeof rec.position === 'number' ? rec.position : index,
    trackId: String(rec.trackId ?? ''),
    provider: asProvider(rec.provider),
    title: String(rec.title ?? 'Untitled'),
    artist: String(rec.artist ?? 'Unknown Artist'),
    albumName: typeof rec.albumName === 'string' && rec.albumName ? rec.albumName : null,
    thumbnailUrl: typeof rec.thumbnailUrl === 'string' && rec.thumbnailUrl ? rec.thumbnailUrl : null,
    durationMs: typeof rec.durationMs === 'number' ? rec.durationMs : null,
    isExplicit: Boolean(rec.isExplicit),
    contributorId: typeof rec.contributorId === 'string' && rec.contributorId ? rec.contributorId : null,
    contributorName: typeof rec.contributorName === 'string' && rec.contributorName ? rec.contributorName : null,
    contributorAvatarUrl:
      typeof rec.contributorAvatarUrl === 'string' && rec.contributorAvatarUrl ? rec.contributorAvatarUrl : null,
    addedAtMs: typeof rec.addedAtMs === 'number' ? rec.addedAtMs : 0,
  };
}

export function normalizeMember(raw: unknown): CollabMember {
  const rec = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  return {
    playlistId: String(rec.playlistId ?? ''),
    userId: String(rec.userId ?? ''),
    role: asRole(rec.role, 'VIEWER'),
    displayName: String(rec.displayName ?? 'Collaborator'),
    avatarUrl: typeof rec.avatarUrl === 'string' && rec.avatarUrl ? rec.avatarUrl : null,
    joinedAtMs: typeof rec.joinedAtMs === 'number' ? rec.joinedAtMs : 0,
    addedById: typeof rec.addedById === 'string' && rec.addedById ? rec.addedById : null,
  };
}

export function normalizeActivity(raw: unknown): CollabActivity {
  const rec = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  return {
    id: String(rec.id ?? ''),
    playlistId: String(rec.playlistId ?? ''),
    actorUserId: String(rec.actorUserId ?? ''),
    actorName: String(rec.actorName ?? 'Collaborator'),
    actorAvatarUrl: typeof rec.actorAvatarUrl === 'string' && rec.actorAvatarUrl ? rec.actorAvatarUrl : null,
    action: String(rec.action ?? ''),
    summary: String(rec.summary ?? ''),
    createdAtMs: typeof rec.createdAtMs === 'number' ? rec.createdAtMs : 0,
  };
}

export function normalizePlaylistDetail(raw: unknown, currentUserId?: string | null): CollabPlaylistDetail {
  const rec = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const members = Array.isArray(rec.members) ? rec.members.map(normalizeMember) : [];
  const tracks = Array.isArray(rec.tracks)
    ? rec.tracks.map((t, i) => normalizeTrackEntry(t, i)).sort((a, b) => a.position - b.position)
    : [];
  const activities = Array.isArray(rec.activities) ? rec.activities.map(normalizeActivity) : [];
  const summary = normalizePlaylistSummary(rec, currentUserId, members);
  return {
    ...summary,
    memberCount: members.length || summary.memberCount,
    trackCount: tracks.length,
    members,
    tracks,
    activities,
  };
}

// ---- Permissions Evaluation --------------------------------------------------------

export interface EvaluatedCollabPermissions {
  role: CollabRole | null;
  isMember: boolean;
  isOwner: boolean;
  canAddTracks: boolean;
  canReorderTracks: boolean;
  canInvite: boolean;
  canEditMetadata: boolean;
  canManageMembers: boolean;
  canStopSharing: boolean;
  canDeletePlaylist: boolean;
  canLeavePlaylist: boolean;
}

export function resolveUserRole(
  playlist: Pick<CollabPlaylistSummary, 'ownerUserId' | 'myRole'> & { members?: readonly CollabMember[] },
  userId?: string | null,
): CollabRole | null {
  if (!userId) return null;
  if (playlist.ownerUserId === userId) return 'OWNER';
  if (playlist.members && playlist.members.length > 0) {
    const member = playlist.members.find((m) => m.userId === userId);
    return member ? member.role : null;
  }
  return playlist.myRole ?? null;
}

export function evaluateCollabPermissions(
  playlist: Pick<CollabPlaylistSummary, 'ownerUserId' | 'myRole' | 'sharingEnabled' | 'permissions'> & {
    members?: readonly CollabMember[];
  },
  userId?: string | null,
): EvaluatedCollabPermissions {
  const role = resolveUserRole(playlist, userId);
  const active = playlist.sharingEnabled;
  const isOwner = role === 'OWNER';
  const isEditor = role === 'EDITOR';
  const isMember = role !== null;

  return {
    role,
    isMember,
    isOwner,
    canAddTracks: active && (isOwner || isEditor),
    canReorderTracks: active && (isOwner || (isEditor && playlist.permissions.editorCanReorder)),
    canInvite: active && (isOwner || (isEditor && playlist.permissions.editorCanInvite)),
    canEditMetadata: active && isOwner,
    canManageMembers: active && isOwner,
    canStopSharing: active && isOwner,
    canDeletePlaylist: isOwner,
    canLeavePlaylist: isMember && !isOwner,
  };
}

export function canRemoveCollabTrack(
  playlist: Pick<CollabPlaylistSummary, 'ownerUserId' | 'myRole' | 'sharingEnabled' | 'permissions'> & {
    members?: readonly CollabMember[];
  },
  entry: Pick<CollabTrackEntry, 'contributorId'>,
  userId?: string | null,
): boolean {
  if (!userId || !playlist.sharingEnabled) return false;
  const role = resolveUserRole(playlist, userId);
  if (role === 'OWNER') return true;
  if (role !== 'EDITOR') return false;
  if (playlist.permissions.editorCanRemove === 'any') return true;
  return Boolean(entry.contributorId && entry.contributorId === userId);
}

// ---- Track & Conversion Mappers ----------------------------------------------------

function sanitizeHttpThumbnail(url: string | null | undefined): string | null {
  if (!url) return null;
  const trimmed = url.trim();
  if (!trimmed || trimmed.length > 1000) return null;
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  return null;
}

export function trackToCollabInput(track: Track, defaultProvider: CollabTrackProvider = 'youtube'): CollabTrackInput {
  const artist = track.artists.map((a) => a.name.trim()).filter(Boolean).join(', ') || 'Unknown Artist';
  const durationMs =
    track.durationSec != null && Number.isFinite(track.durationSec) && track.durationSec >= 0
      ? Math.round(track.durationSec * 1000)
      : null;
  const provider: CollabTrackProvider = track.unavailable === 'missing' || track.unavailable === 'matching' ? 'spotify' : defaultProvider;
  const cleanId = track.videoId.replace(/^spotify:/i, '').trim();

  return {
    trackId: cleanId,
    ...(provider === 'youtube' ? { videoId: cleanId } : {}),
    provider,
    title: track.title.trim() || 'Untitled',
    artist,
    albumName: track.album?.name?.trim() || null,
    thumbnailUrl: sanitizeHttpThumbnail(track.thumbnail),
    durationMs,
    isExplicit: Boolean(track.explicit),
  };
}

export function buildConvertPayload(params: {
  sourcePlaylistId: string;
  sourceType: CollabSourceType;
  title: string;
  description?: string | null;
  artworkUrl?: string | null;
  attributeExistingTracksToOwner?: boolean;
  permissions?: CollabPermissionsPatch;
  tracks: readonly Track[];
}): ConvertCollabPlaylistInput {
  const defaultProvider: CollabTrackProvider = params.sourceType === 'spotify' ? 'youtube' : 'youtube';
  const mappedTracks = params.tracks
    .filter((t) => Boolean(t.videoId?.trim()) && Boolean(t.title?.trim()))
    .slice(0, 2000)
    .map((t) => trackToCollabInput(t, defaultProvider));

  return {
    sourcePlaylistId: params.sourcePlaylistId.trim(),
    sourceType: params.sourceType,
    title: params.title.trim().slice(0, 200) || 'Collaborative Playlist',
    description: (params.description ?? '').trim().slice(0, 2000),
    artworkUrl: sanitizeHttpThumbnail(params.artworkUrl),
    attributeExistingTracksToOwner: params.attributeExistingTracksToOwner ?? true,
    ...(params.permissions ? { permissions: params.permissions } : {}),
    tracks: mappedTracks,
  };
}

export function collabEntryToTrack(entry: CollabTrackEntry): Track {
  const playableOnYt = entry.provider === 'youtube' && Boolean(entry.trackId);
  return {
    videoId: entry.trackId,
    title: entry.title,
    artists: [{ name: entry.artist || 'Unknown Artist', id: null }],
    album: entry.albumName ? { name: entry.albumName, id: null } : null,
    durationSec: entry.durationMs != null ? Math.round(entry.durationMs / 1000) : null,
    explicit: entry.isExplicit,
    thumbnail: entry.thumbnailUrl,
    index: entry.position + 1,
    plays: null,
    ...(playableOnYt ? {} : { unavailable: 'missing' as const }),
  };
}

// ---- Optimistic OpId & Realtime WebSocket Reducer ----------------------------------

let opCounter = 0;

export function createOpId(prefix = 'op'): string {
  const rand =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID().replace(/-/g, '').slice(0, 16)
      : `${Math.random().toString(36).slice(2, 10)}${(++opCounter).toString(36)}`;
  return `${prefix}_${Date.now().toString(36)}_${rand}`.slice(0, 64);
}

function appendUniqueActivity(
  existing: readonly CollabActivity[],
  latest: CollabActivity | null | undefined,
): CollabActivity[] {
  if (!latest || !latest.id) return [...existing];
  if (existing.some((a) => a.id === latest.id)) return [...existing];
  const next = [...existing, normalizeActivity(latest)];
  return next.length > 200 ? next.slice(next.length - 200) : next;
}

export interface CollabWsReduceResult {
  detail: CollabPlaylistDetail | null;
  changed: boolean;
  skippedEcho: boolean;
  removedReason: string | null;
  stoppedMessage: string | null;
}

export function applyCollabWsFrame(
  current: CollabPlaylistDetail | null,
  frame: CollabWsServerFrame,
  options?: {
    currentUserId?: string | null;
    seenOpIds?: Set<string>;
  },
): CollabWsReduceResult {
  const currentUserId = options?.currentUserId ?? null;
  const seenOpIds = options?.seenOpIds;

  if (frame.type === 'collab.snapshot') {
    const next = normalizePlaylistDetail(frame.playlist, currentUserId);
    return {
      detail: next,
      changed: true,
      skippedEcho: false,
      removedReason: null,
      stoppedMessage: null,
    };
  }

  if (!current || ('playlistId' in frame && frame.playlistId && frame.playlistId !== current.id)) {
    return { detail: current, changed: false, skippedEcho: false, removedReason: null, stoppedMessage: null };
  }

  switch (frame.type) {
    case 'collab.subscribed':
    case 'collab.unsubscribed':
    case 'pong':
    case 'collab.error':
      return { detail: current, changed: false, skippedEcho: false, removedReason: null, stoppedMessage: null };

    case 'collab.tracks_changed': {
      if (frame.revision <= current.revision) {
        const isEcho = Boolean(frame.opId && seenOpIds?.has(frame.opId));
        if (isEcho && frame.opId) seenOpIds?.delete(frame.opId);
        return { detail: current, changed: false, skippedEcho: isEcho, removedReason: null, stoppedMessage: null };
      }
      const isEcho = Boolean(frame.opId && seenOpIds?.has(frame.opId));
      if (isEcho && frame.opId) {
        seenOpIds?.delete(frame.opId);
        // Reconcile authoritative server entry IDs, revision, and activity without visual duplicate insertion.
      }
      const normalizedTracks = Array.isArray(frame.tracks)
        ? frame.tracks.map((t, i) => normalizeTrackEntry(t, i)).sort((a, b) => a.position - b.position)
        : current.tracks;
      const activities = appendUniqueActivity(current.activities, frame.latestActivity);
      return {
        detail: {
          ...current,
          tracks: normalizedTracks,
          trackCount: normalizedTracks.length,
          revision: frame.revision,
          updatedAtMs: Math.max(current.updatedAtMs, frame.serverMs || 0),
          activities,
        },
        changed: true,
        skippedEcho: isEcho,
        removedReason: null,
        stoppedMessage: null,
      };
    }

    case 'collab.metadata_changed': {
      if (frame.revision < current.revision) {
        return { detail: current, changed: false, skippedEcho: false, removedReason: null, stoppedMessage: null };
      }
      const isEcho = Boolean(frame.opId && seenOpIds?.has(frame.opId));
      if (isEcho && frame.opId) seenOpIds?.delete(frame.opId);

      // Preserve the local viewer/editor's role rather than adopting the broadcaster's myRole.
      const incoming = normalizePlaylistSummary(frame.playlist, currentUserId, current.members);
      const localRole =
        currentUserId && incoming.ownerUserId === currentUserId
          ? 'OWNER'
          : (current.members.find((m) => m.userId === currentUserId)?.role ?? current.myRole);

      const activities = appendUniqueActivity(current.activities, frame.latestActivity);
      return {
        detail: {
          ...current,
          title: incoming.title,
          description: incoming.description,
          artworkUrl: incoming.artworkUrl,
          ownerUserId: incoming.ownerUserId,
          ownerName: incoming.ownerName,
          ownerAvatarUrl: incoming.ownerAvatarUrl,
          myRole: localRole,
          sharingEnabled: incoming.sharingEnabled,
          permissions: incoming.permissions,
          memberCount: incoming.memberCount || current.members.length,
          trackCount: incoming.trackCount || current.tracks.length,
          revision: frame.revision,
          updatedAtMs: Math.max(current.updatedAtMs, incoming.updatedAtMs, frame.serverMs || 0),
          activities,
        },
        changed: true,
        skippedEcho: isEcho,
        removedReason: null,
        stoppedMessage: null,
      };
    }

    case 'collab.member_joined':
    case 'collab.members_changed': {
      if (frame.revision < current.revision) {
        return { detail: current, changed: false, skippedEcho: false, removedReason: null, stoppedMessage: null };
      }
      const members = Array.isArray(frame.members) ? frame.members.map(normalizeMember) : current.members;
      const owner = members.find((m) => m.role === 'OWNER');
      const ownerUserId = owner?.userId ?? current.ownerUserId;
      const ownerName = owner?.displayName ?? current.ownerName;
      const ownerAvatarUrl = owner ? owner.avatarUrl : current.ownerAvatarUrl;
      const myRole = currentUserId
        ? (members.find((m) => m.userId === currentUserId)?.role ?? current.myRole)
        : current.myRole;
      const activities = appendUniqueActivity(current.activities, frame.latestActivity);
      return {
        detail: {
          ...current,
          ownerUserId,
          ownerName,
          ownerAvatarUrl,
          myRole,
          members,
          memberCount: members.length,
          memberAvatars: deriveMemberAvatars(ownerUserId, ownerName, ownerAvatarUrl, members),
          revision: frame.revision,
          updatedAtMs: Math.max(current.updatedAtMs, frame.serverMs || 0),
          activities,
        },
        changed: true,
        skippedEcho: false,
        removedReason: null,
        stoppedMessage: null,
      };
    }

    case 'collab.member_removed': {
      if (currentUserId && frame.userId === currentUserId) {
        return {
          detail: null,
          changed: true,
          skippedEcho: false,
          removedReason: frame.message || frame.reason || 'Removed from playlist',
          stoppedMessage: null,
        };
      }
      const members = current.members.filter((m) => m.userId !== frame.userId);
      return {
        detail: {
          ...current,
          members,
          memberCount: members.length,
          memberAvatars: deriveMemberAvatars(current.ownerUserId, current.ownerName, current.ownerAvatarUrl, members),
        },
        changed: members.length !== current.members.length,
        skippedEcho: false,
        removedReason: null,
        stoppedMessage: null,
      };
    }

    case 'collab.stopped': {
      const isOwner = Boolean(currentUserId && current.ownerUserId === currentUserId);
      if (!isOwner) {
        return {
          detail: null,
          changed: true,
          skippedEcho: false,
          removedReason: null,
          stoppedMessage: frame.message || 'The owner stopped sharing this playlist.',
        };
      }
      const ownerOnly = current.members.filter((m) => m.userId === current.ownerUserId);
      return {
        detail: {
          ...current,
          sharingEnabled: false,
          members: ownerOnly,
          memberCount: ownerOnly.length || 1,
          memberAvatars: deriveMemberAvatars(current.ownerUserId, current.ownerName, current.ownerAvatarUrl, ownerOnly),
        },
        changed: true,
        skippedEcho: false,
        removedReason: null,
        stoppedMessage: frame.message || 'Sharing stopped.',
      };
    }
  }
}
