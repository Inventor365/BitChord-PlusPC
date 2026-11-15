import { create } from 'zustand';
import { useCollabAuth } from './auth';
import { resolveCollabWsUrl } from './config';
import type { CollabWsClientFrame, CollabWsServerFrame } from './types';

export type CollabSocketStatus = 'offline' | 'connecting' | 'live';

export interface CollabSocketState {
  status: CollabSocketStatus;
}

export const useCollabSocketState = create<CollabSocketState>()(() => ({
  status: 'offline',
}));

const PROTOCOL_NAME = 'bitchord.collab.v1';
const TOKEN_PROTOCOL_PREFIX = 'bitchord.token.';
const PING_INTERVAL_MS = 20_000;
const BASE_BACKOFF_MS = 500;
const MAX_BACKOFF_MS = 30_000;
const MAX_TRACKED_OP_IDS = 128;

export function computeReconnectDelayMs(attempt: number, random = Math.random): number {
  const exp = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * Math.pow(2, Math.max(0, attempt)));
  const jitter = Math.round(exp * 0.2 * random());
  return Math.min(MAX_BACKOFF_MS, exp + jitter);
}

type FrameListener = (frame: CollabWsServerFrame) => void;

interface PlaylistSubscription {
  getRevision: () => number | undefined;
  listeners: Set<FrameListener>;
}

/**
 * Multiplexed WebSocket manager for `/ws/collab` using
 * `Sec-WebSocket-Protocol: ['bitchord.collab.v1', 'bitchord.token.<sessionToken>']`
 * with exponential backoff reconnect, `sinceRevision` delta/snapshot reconciliation,
 * and bounded `opId` optimistic-echo tracking.
 */
class CollabSocketManager {
  private ws: WebSocket | null = null;
  private activeToken: string | null = null;
  private activeUrl: string | null = null;
  private reconnectTimer: number | null = null;
  private pingTimer: number | null = null;
  private attempt = 0;
  private subscriptions = new Map<string, PlaylistSubscription>();
  private seenOpIds = new Map<string, Set<string>>();
  private listenersBound = false;

  constructor() {
    this.bindEnvironmentListeners();
  }

  private bindEnvironmentListeners(): void {
    if (this.listenersBound || typeof window === 'undefined') return;
    this.listenersBound = true;
    window.addEventListener('online', () => {
      if (this.subscriptions.size > 0 && (!this.ws || this.ws.readyState !== WebSocket.OPEN)) {
        this.attempt = 0;
        this.connectNow();
      }
    });
    document.addEventListener('visibilitychange', () => {
      if (
        document.visibilityState === 'visible' &&
        this.subscriptions.size > 0 &&
        (!this.ws || this.ws.readyState !== WebSocket.OPEN)
      ) {
        this.attempt = 0;
        this.connectNow();
      }
    });
    useCollabAuth.subscribe((state, prev) => {
      if (state.token !== prev.token || state.serverUrl !== prev.serverUrl) {
        this.disconnect();
        if (state.token && this.subscriptions.size > 0) {
          this.attempt = 0;
          this.connectNow();
        }
      }
    });
  }

  registerLocalOpId(playlistId: string, opId: string): void {
    if (!opId) return;
    let set = this.seenOpIds.get(playlistId);
    if (!set) {
      set = new Set<string>();
      this.seenOpIds.set(playlistId, set);
    }
    set.add(opId);
    if (set.size > MAX_TRACKED_OP_IDS) {
      const oldest = set.values().next().value;
      if (oldest) set.delete(oldest);
    }
  }

  seenOpIdsFor(playlistId: string): Set<string> {
    let set = this.seenOpIds.get(playlistId);
    if (!set) {
      set = new Set<string>();
      this.seenOpIds.set(playlistId, set);
    }
    return set;
  }

  subscribe(playlistId: string, getRevision: () => number | undefined, listener: FrameListener): () => void {
    let sub = this.subscriptions.get(playlistId);
    const isFirstForPlaylist = !sub;
    if (!sub) {
      sub = { getRevision, listeners: new Set() };
      this.subscriptions.set(playlistId, sub);
    } else {
      sub.getRevision = getRevision;
    }
    sub.listeners.add(listener);

    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      if (isFirstForPlaylist) {
        this.sendSubscribe(playlistId, getRevision());
      }
    } else {
      this.connectNow();
    }

    return () => {
      const current = this.subscriptions.get(playlistId);
      if (!current) return;
      current.listeners.delete(listener);
      if (current.listeners.size === 0) {
        this.subscriptions.delete(playlistId);
        this.sendFrame({ type: 'unsubscribe', playlistId });
        if (this.subscriptions.size === 0) {
          this.disconnect();
        }
      }
    };
  }

  private sendSubscribe(playlistId: string, revision: number | undefined): void {
    const frame: CollabWsClientFrame =
      typeof revision === 'number' && revision >= 0
        ? { type: 'subscribe', playlistId, sinceRevision: revision }
        : { type: 'subscribe', playlistId };
    this.sendFrame(frame);
  }

  private sendFrame(frame: CollabWsClientFrame): void {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    try {
      this.ws.send(JSON.stringify(frame));
    } catch {
      // Socket closing; reconnect loop will reconcile.
    }
  }

  private connectNow(): void {
    if (typeof WebSocket === 'undefined') return;
    this.clearReconnectTimer();

    if (this.subscriptions.size === 0) {
      this.disconnect();
      return;
    }

    const auth = useCollabAuth.getState();
    if (!auth.token) {
      useCollabSocketState.setState({ status: 'offline' });
      return;
    }

    const wsUrl = resolveCollabWsUrl(auth.serverUrl || null);
    if (!wsUrl) {
      useCollabSocketState.setState({ status: 'offline' });
      return;
    }

    if (
      this.ws &&
      (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING) &&
      this.activeToken === auth.token &&
      this.activeUrl === wsUrl
    ) {
      return;
    }

    this.closeSocketOnly();
    useCollabSocketState.setState({ status: 'connecting' });
    this.activeToken = auth.token;
    this.activeUrl = wsUrl;

    let socket: WebSocket;
    try {
      socket = new WebSocket(wsUrl, [PROTOCOL_NAME, `${TOKEN_PROTOCOL_PREFIX}${auth.token}`]);
    } catch {
      this.scheduleReconnect();
      return;
    }

    this.ws = socket;

    socket.onopen = () => {
      if (this.ws !== socket) return;
      this.attempt = 0;
      useCollabSocketState.setState({ status: 'live' });
      for (const [playlistId, sub] of this.subscriptions.entries()) {
        this.sendSubscribe(playlistId, sub.getRevision());
      }
      this.startPing();
    };

    socket.onmessage = (event) => {
      if (this.ws !== socket || typeof event.data !== 'string') return;
      let frame: CollabWsServerFrame;
      try {
        frame = JSON.parse(event.data) as CollabWsServerFrame;
      } catch {
        return;
      }
      if (frame.type === 'collab.error' && frame.error === 'auth_required') {
        useCollabAuth.getState().clearSession('Your Google session expired. Please sign in again.');
        this.disconnect();
        return;
      }
      if ('playlistId' in frame && frame.playlistId) {
        const sub = this.subscriptions.get(frame.playlistId);
        if (sub) {
          for (const listener of sub.listeners) {
            listener(frame);
          }
        }
      }
    };

    socket.onerror = () => {
      // onclose handles backoff reconnect.
    };

    socket.onclose = () => {
      if (this.ws !== socket) return;
      this.ws = null;
      this.stopPing();
      useCollabSocketState.setState({ status: 'offline' });
      if (this.subscriptions.size > 0 && useCollabAuth.getState().token) {
        this.scheduleReconnect();
      }
    };
  }

  private scheduleReconnect(): void {
    this.clearReconnectTimer();
    const delay = computeReconnectDelayMs(this.attempt++);
    useCollabSocketState.setState({ status: 'connecting' });
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connectNow();
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null && typeof window !== 'undefined') {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = window.setInterval(() => {
      this.sendFrame({ type: 'ping', clientMs: Date.now() });
    }, PING_INTERVAL_MS);
  }

  private stopPing(): void {
    if (this.pingTimer !== null && typeof window !== 'undefined') {
      window.clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private closeSocketOnly(): void {
    this.stopPing();
    if (this.ws) {
      const s = this.ws;
      this.ws = null;
      s.onopen = null;
      s.onmessage = null;
      s.onerror = null;
      s.onclose = null;
      try {
        s.close();
      } catch {
        // Ignore close error.
      }
    }
  }

  disconnect(): void {
    this.clearReconnectTimer();
    this.closeSocketOnly();
    this.activeToken = null;
    this.activeUrl = null;
    useCollabSocketState.setState({ status: 'offline' });
  }
}

export const collabSocket = new CollabSocketManager();
