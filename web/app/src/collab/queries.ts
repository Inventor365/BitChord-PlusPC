import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { queryClient } from '@/lib/queryClient';
import { useCollabAuth } from './auth';
import {
  acceptCollabInvite,
  addCollabTracks,
  convertToCollabPlaylist,
  createCollabInvite,
  createCollabPlaylist,
  deleteCollabPlaylist,
  getCachedCollabDetail,
  getCachedCollabSummaries,
  getCollabPlaylist,
  leaveCollabPlaylist,
  listCollabInvites,
  listCollabPlaylists,
  previewCollabInvite,
  removeCachedCollabPlaylist,
  removeCollabMember,
  removeCollabTrack,
  reorderCollabTracks,
  revokeCollabInvite,
  saveCachedCollabDetail,
  stopSharingCollabPlaylist,
  transferCollabOwnership,
  updateCollabMemberRole,
  updateCollabPlaylist,
} from './client';
import {
  applyCollabWsFrame,
  createOpId,
  type CollabPermissionsPatch,
  type CollabPlaylistDetail,
  type CollabPlaylistSummary,
  type CollabRole,
  type CollabTrackEntry,
  type CollabTrackInput,
  type ConvertCollabPlaylistInput,
} from './types';
import { collabSocket } from './ws';

export const collabKeys = {
  all: ['collab'] as const,
  playlists: (userId: string | null) => ['collab', 'playlists', userId ?? 'guest'] as const,
  detail: (playlistId: string, userId: string | null) =>
    ['collab', 'playlist', playlistId, userId ?? 'guest'] as const,
  invites: (playlistId: string) => ['collab', 'invites', playlistId] as const,
  preview: (token: string, server: string | null) => ['collab', 'invite-preview', token, server ?? 'default'] as const,
};

function syncSummaryInListCache(detail: CollabPlaylistDetail, userId: string | null): void {
  saveCachedCollabDetail(detail, userId);
  queryClient.setQueryData<CollabPlaylistDetail>(collabKeys.detail(detail.id, userId), detail);
  queryClient.setQueryData<CollabPlaylistSummary[]>(collabKeys.playlists(userId), (prev) => {
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
    if (!prev) return [summary];
    const idx = prev.findIndex((p) => p.id === detail.id);
    if (idx < 0) return [summary, ...prev];
    return prev.map((p, i) => (i === idx ? summary : p));
  });
}

function dropPlaylistFromCache(playlistId: string, userId: string | null): void {
  removeCachedCollabPlaylist(playlistId, userId);
  queryClient.removeQueries({ queryKey: collabKeys.detail(playlistId, userId) });
  queryClient.setQueryData<CollabPlaylistSummary[]>(collabKeys.playlists(userId), (prev) =>
    prev ? prev.filter((p) => p.id !== playlistId) : prev,
  );
}

/** Lists collaborative playlists for the signed-in Google user, seeded instantaneously from local cache. */
export function useCollabPlaylists(includeStopped = true) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  const token = useCollabAuth((s) => s.token);
  return useQuery({
    queryKey: collabKeys.playlists(userId),
    queryFn: () => listCollabPlaylists({ includeStopped }),
    enabled: Boolean(userId && token),
    initialData: () => {
      if (!userId) return undefined;
      const cached = getCachedCollabSummaries(userId);
      return cached.length > 0 ? cached : undefined;
    },
    staleTime: 30_000,
  });
}

/** Fetches a collaborative playlist detail and subscribes to live `/ws/collab` updates with `sinceRevision`. */
export function useCollabPlaylistDetail(playlistId: string) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  const token = useCollabAuth((s) => s.token);
  const [liveNotice, setLiveNotice] = useState<string | null>(null);

  const query = useQuery({
    queryKey: collabKeys.detail(playlistId, userId),
    queryFn: () => getCollabPlaylist(playlistId),
    enabled: Boolean(playlistId && userId && token),
    initialData: () => (userId && playlistId ? (getCachedCollabDetail(playlistId, userId) ?? undefined) : undefined),
    staleTime: 15_000,
  });

  useEffect(() => {
    setLiveNotice(null);
    if (!playlistId || !userId || !token) return;

    const getRevision = () =>
      queryClient.getQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId))?.revision;

    return collabSocket.subscribe(playlistId, getRevision, (frame) => {
      const current =
        queryClient.getQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId)) ?? null;
      const result = applyCollabWsFrame(current, frame, {
        currentUserId: userId,
        seenOpIds: collabSocket.seenOpIdsFor(playlistId),
      });

      if (result.removedReason) {
        setLiveNotice(result.removedReason);
        dropPlaylistFromCache(playlistId, userId);
        return;
      }
      if (result.stoppedMessage && !result.detail) {
        setLiveNotice(result.stoppedMessage);
        dropPlaylistFromCache(playlistId, userId);
        return;
      }
      if (result.stoppedMessage) {
        setLiveNotice(result.stoppedMessage);
      }
      if (result.changed && result.detail) {
        syncSummaryInListCache(result.detail, userId);
      }
    });
  }, [playlistId, userId, token]);

  return { ...query, liveNotice };
}

export function useCreateCollabPlaylist() {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: createCollabPlaylist,
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useConvertCollabPlaylist() {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (input: ConvertCollabPlaylistInput) => convertToCollabPlaylist(input),
    onSuccess: ({ playlist }) => syncSummaryInListCache(playlist, userId),
  });
}

export function useUpdateCollabPlaylist(playlistId: string) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (patch: {
      title?: string;
      description?: string;
      artworkUrl?: string | null;
      permissions?: CollabPermissionsPatch;
    }) => {
      const opId = createOpId('meta');
      collabSocket.registerLocalOpId(playlistId, opId);
      return updateCollabPlaylist(playlistId, { ...patch, opId });
    },
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useAddCollabTracks(playlistId: string) {
  const user = useCollabAuth((s) => s.user);
  const userId = user?.id ?? null;

  return useMutation({
    mutationFn: (params: { tracks: CollabTrackInput[]; position?: number }) => {
      const opId = createOpId('add');
      collabSocket.registerLocalOpId(playlistId, opId);
      return addCollabTracks(playlistId, { ...params, opId });
    },
    onMutate: async ({ tracks }) => {
      if (!user) return { previous: undefined };
      await queryClient.cancelQueries({ queryKey: collabKeys.detail(playlistId, userId) });
      const previous = queryClient.getQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId));
      if (previous) {
        const now = Date.now();
        const optimisticEntries: CollabTrackEntry[] = tracks.map((t, idx) => ({
          entryId: `temp_${now}_${idx}`,
          playlistId,
          position: previous.tracks.length + idx,
          trackId: t.trackId,
          provider: t.provider,
          title: t.title,
          artist: t.artist,
          albumName: t.albumName ?? null,
          thumbnailUrl: t.thumbnailUrl ?? null,
          durationMs: t.durationMs ?? null,
          isExplicit: Boolean(t.isExplicit),
          contributorId: user.id,
          contributorName: user.displayName,
          contributorAvatarUrl: user.avatarUrl,
          addedAtMs: now,
        }));
        const nextTracks = [...previous.tracks, ...optimisticEntries];
        queryClient.setQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId), {
          ...previous,
          tracks: nextTracks,
          trackCount: nextTracks.length,
        });
      }
      return { previous };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.previous) {
        queryClient.setQueryData(collabKeys.detail(playlistId, userId), ctx.previous);
      }
    },
    onSuccess: ({ playlist }) => syncSummaryInListCache(playlist, userId),
  });
}

export function useRemoveCollabTrack(playlistId: string) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (entryId: string) => {
      const opId = createOpId('rm');
      collabSocket.registerLocalOpId(playlistId, opId);
      return removeCollabTrack(playlistId, entryId, opId);
    },
    onMutate: async (entryId) => {
      await queryClient.cancelQueries({ queryKey: collabKeys.detail(playlistId, userId) });
      const previous = queryClient.getQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId));
      if (previous) {
        const nextTracks = previous.tracks
          .filter((t) => t.entryId !== entryId)
          .map((t, idx) => ({ ...t, position: idx }));
        queryClient.setQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId), {
          ...previous,
          tracks: nextTracks,
          trackCount: nextTracks.length,
        });
      }
      return { previous };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.previous) {
        queryClient.setQueryData(collabKeys.detail(playlistId, userId), ctx.previous);
      }
    },
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useReorderCollabTracks(playlistId: string) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (entryIds: string[]) => {
      const opId = createOpId('ord');
      collabSocket.registerLocalOpId(playlistId, opId);
      return reorderCollabTracks(playlistId, { entryIds, opId });
    },
    onMutate: async (entryIds) => {
      await queryClient.cancelQueries({ queryKey: collabKeys.detail(playlistId, userId) });
      const previous = queryClient.getQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId));
      if (previous && entryIds.length === previous.tracks.length) {
        const byId = new Map(previous.tracks.map((t) => [t.entryId, t]));
        const reordered = entryIds
          .map((id, idx) => {
            const found = byId.get(id);
            return found ? { ...found, position: idx } : null;
          })
          .filter((t): t is CollabTrackEntry => t !== null);
        if (reordered.length === previous.tracks.length) {
          queryClient.setQueryData<CollabPlaylistDetail>(collabKeys.detail(playlistId, userId), {
            ...previous,
            tracks: reordered,
          });
        }
      }
      return { previous };
    },
    onError: (_err, _vars, ctx) => {
      if (ctx?.previous) {
        queryClient.setQueryData(collabKeys.detail(playlistId, userId), ctx.previous);
      }
    },
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useStopSharingCollabPlaylist() {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (playlistId: string) => stopSharingCollabPlaylist(playlistId),
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useDeleteCollabPlaylist() {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (playlistId: string) => deleteCollabPlaylist(playlistId),
    onSuccess: (_res, playlistId) => dropPlaylistFromCache(playlistId, userId),
  });
}

export function useLeaveCollabPlaylist() {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (playlistId: string) => leaveCollabPlaylist(playlistId),
    onSuccess: (_res, playlistId) => dropPlaylistFromCache(playlistId, userId),
  });
}

export function useCollabInvites(playlistId: string, enabled: boolean) {
  return useQuery({
    queryKey: collabKeys.invites(playlistId),
    queryFn: () => listCollabInvites(playlistId),
    enabled: Boolean(playlistId && enabled),
    staleTime: 10_000,
  });
}

export function useCreateCollabInvite(playlistId: string) {
  return useMutation({
    mutationFn: (params: { role: 'EDITOR' | 'VIEWER'; expiresInSec?: number; maxUses?: number }) =>
      createCollabInvite(playlistId, params),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: collabKeys.invites(playlistId) }),
  });
}

export function useRevokeCollabInvite(playlistId: string) {
  return useMutation({
    mutationFn: (inviteId: string) => revokeCollabInvite(playlistId, inviteId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: collabKeys.invites(playlistId) }),
  });
}

export function useUpdateCollabMemberRole(playlistId: string) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: ({ targetUserId, role }: { targetUserId: string; role: Exclude<CollabRole, 'OWNER'> }) =>
      updateCollabMemberRole(playlistId, targetUserId, role),
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useTransferCollabOwnership(playlistId: string) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (newOwnerUserId: string) => transferCollabOwnership(playlistId, newOwnerUserId),
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useRemoveCollabMember(playlistId: string) {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: (targetUserId: string) => removeCollabMember(playlistId, targetUserId),
    onSuccess: (detail) => syncSummaryInListCache(detail, userId),
  });
}

export function useCollabInvitePreview(token: string, serverOverride?: string | null) {
  return useQuery({
    queryKey: collabKeys.preview(token, serverOverride ?? null),
    queryFn: () => previewCollabInvite(token, serverOverride),
    enabled: Boolean(token),
    retry: false,
  });
}

export function useAcceptCollabInvite() {
  const userId = useCollabAuth((s) => s.user?.id ?? null);
  return useMutation({
    mutationFn: ({ token, serverOverride }: { token: string; serverOverride?: string | null }) =>
      acceptCollabInvite(token, serverOverride),
    onSuccess: ({ playlist }) => syncSummaryInListCache(playlist, userId),
  });
}
