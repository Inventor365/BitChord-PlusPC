package collab

import (
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/KabirSinghBhatia/BitChord/backend/auth"
	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
	"github.com/KabirSinghBhatia/BitChord/backend/hub"
)

const (
	CollabSocketProtocol    = "bitchord.collab.v1"
	CollabSocketTokenPrefix = "bitchord.token."

	FramePing        = "ping"
	FramePong        = "pong"
	FrameSubscribe   = "subscribe"
	FrameUnsubscribe = "unsubscribe"

	EventSubscribed      = "collab.subscribed"
	EventUnsubscribed    = "collab.unsubscribed"
	EventSnapshot        = "collab.snapshot"
	EventTracksChanged   = "collab.tracks_changed"
	EventMetadataChanged = "collab.metadata_changed"
	EventMembersChanged  = "collab.members_changed"
	EventMemberJoined    = "collab.member_joined"
	EventMemberRemoved   = "collab.member_removed"
	EventStopped         = "collab.stopped"
	EventError           = "collab.error"

	clientSendQueueSize = 64
)

// ParseBearerToken extracts a Bearer token from the Authorization header.
func ParseBearerToken(r *http.Request) string {
	hdr := r.Header.Get("Authorization")
	parts := strings.SplitN(hdr, " ", 2)
	if len(parts) == 2 && strings.EqualFold(parts[0], "Bearer") {
		return strings.TrimSpace(parts[1])
	}
	return ""
}

// ParseCollabSocketToken reads the session token from Authorization: Bearer <token>
// or browser Sec-WebSocket-Protocol: bitchord.collab.v1, bitchord.token.<token>.
func ParseCollabSocketToken(r *http.Request) string {
	if tok := ParseBearerToken(r); tok != "" {
		return tok
	}
	for _, proto := range websocket.Subprotocols(r) {
		if strings.HasPrefix(proto, CollabSocketTokenPrefix) {
			return strings.TrimSpace(strings.TrimPrefix(proto, CollabSocketTokenPrefix))
		}
	}
	return ""
}

type collabClient struct {
	safeConn *hub.SafeConn
	userID   string
	rawToken string
	out      chan interface{}
	closeCh  chan struct{}
	once     sync.Once

	rateMu     sync.Mutex
	tokens     float64
	lastRefill int64
}

func newCollabClient(conn *websocket.Conn, userID, rawToken string) *collabClient {
	c := &collabClient{
		safeConn:   hub.NewSafeConn(conn),
		userID:     userID,
		rawToken:   rawToken,
		out:        make(chan interface{}, clientSendQueueSize),
		closeCh:    make(chan struct{}),
		tokens:     config.FrameRatePerSecond,
		lastRefill: clock.NowMs(),
	}
	go c.writePump()
	return c
}

func (c *collabClient) writePump() {
	for {
		select {
		case <-c.closeCh:
			return
		case msg, ok := <-c.out:
			if !ok {
				return
			}
			// SafeConn enforces a 5s write deadline under per-socket mutex.
			if err := c.safeConn.WriteJSON(msg); err != nil {
				c.Close()
				return
			}
		}
	}
}

// Send queues a JSON frame non-blockingly; slow/stalled clients whose buffer fills are closed.
func (c *collabClient) Send(payload interface{}) {
	select {
	case <-c.closeCh:
		return
	default:
	}
	select {
	case c.out <- payload:
	case <-c.closeCh:
	default:
		c.Close()
	}
}

func (c *collabClient) Close() {
	c.once.Do(func() {
		close(c.closeCh)
		_ = c.safeConn.Close()
	})
}

func (c *collabClient) spendFrameBudget() bool {
	rate := config.FrameRatePerSecond
	if rate <= 0 {
		return true
	}
	c.rateMu.Lock()
	defer c.rateMu.Unlock()

	now := clock.NowMs()
	elapsedSec := float64(now-c.lastRefill) / 1000.0
	if elapsedSec > 0 {
		c.tokens += elapsedSec * rate
		if c.tokens > rate {
			c.tokens = rate
		}
		c.lastRefill = now
	}
	if c.tokens < 1.0 {
		return false
	}
	c.tokens -= 1.0
	return true
}

// WSManager coordinates /ws/collab WebSocket connections and per-playlist subscriptions.
type WSManager struct {
	store    *Store
	authMgr  *auth.Manager
	upgrader websocket.Upgrader

	mu      sync.RWMutex
	subs    map[string]map[*collabClient]struct{} // playlistID -> subscribed clients
	clients map[*collabClient]map[string]struct{} // client -> subscribed playlistIDs
}

func NewWSManager(store *Store, authMgr *auth.Manager) *WSManager {
	return &WSManager{
		store:   store,
		authMgr: authMgr,
		upgrader: websocket.Upgrader{
			Subprotocols: []string{CollabSocketProtocol},
			CheckOrigin: func(r *http.Request) bool {
				origin := r.Header.Get("Origin")
				return origin == "" || config.IsAllowedOrigin(origin)
			},
		},
		subs:    make(map[string]map[*collabClient]struct{}),
		clients: make(map[*collabClient]map[string]struct{}),
	}
}

func (m *WSManager) registerClient(c *collabClient) {
	m.mu.Lock()
	m.clients[c] = make(map[string]struct{})
	m.mu.Unlock()
}

func (m *WSManager) unregisterClient(c *collabClient) {
	m.mu.Lock()
	pids := m.clients[c]
	delete(m.clients, c)
	for pid := range pids {
		if room := m.subs[pid]; room != nil {
			delete(room, c)
			if len(room) == 0 {
				delete(m.subs, pid)
			}
		}
	}
	m.mu.Unlock()
	c.Close()
}

func (m *WSManager) addSubscription(c *collabClient, playlistID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	pids, ok := m.clients[c]
	if !ok {
		return
	}
	pids[playlistID] = struct{}{}
	room, ok := m.subs[playlistID]
	if !ok {
		room = make(map[*collabClient]struct{})
		m.subs[playlistID] = room
	}
	room[c] = struct{}{}
}

func (m *WSManager) removeSubscription(c *collabClient, playlistID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if pids, ok := m.clients[c]; ok {
		delete(pids, playlistID)
	}
	if room, ok := m.subs[playlistID]; ok {
		delete(room, c)
		if len(room) == 0 {
			delete(m.subs, playlistID)
		}
	}
}

// BroadcastPlaylist sends payload non-blockingly to all subscribed sockets for playlistID.
// Must be called OUTSIDE the Store lock.
func (m *WSManager) BroadcastPlaylist(playlistID string, payload interface{}) {
	m.mu.RLock()
	room := m.subs[playlistID]
	if len(room) == 0 {
		m.mu.RUnlock()
		return
	}
	targets := make([]*collabClient, 0, len(room))
	for c := range room {
		targets = append(targets, c)
	}
	m.mu.RUnlock()

	for _, c := range targets {
		c.Send(payload)
	}
}

// RemoveUserSubscription sends collab.member_removed to all subscribed sockets belonging to userID
// for playlistID and immediately drops their subscription so no further playlist events reach them.
func (m *WSManager) RemoveUserSubscription(playlistID, userID, reason, message string) {
	m.mu.Lock()
	room := m.subs[playlistID]
	var removedClients []*collabClient
	for c := range room {
		if c.userID == userID {
			removedClients = append(removedClients, c)
			delete(room, c)
			if pids, ok := m.clients[c]; ok {
				delete(pids, playlistID)
			}
		}
	}
	if len(room) == 0 {
		delete(m.subs, playlistID)
	}
	m.mu.Unlock()

	if len(removedClients) == 0 {
		return
	}
	frame := map[string]interface{}{
		"type":       EventMemberRemoved,
		"playlistId": playlistID,
		"userId":     userID,
		"reason":     reason,
		"message":    message,
		"serverMs":   clock.NowMs(),
	}
	for _, c := range removedClients {
		c.Send(frame)
	}
}

// NotifyStoppedAndDropNonOwners notifies all subscribed non-owner collaborators with collab.stopped
// and drops all non-owner subscriptions (or all subscriptions when dropAll == true).
func (m *WSManager) NotifyStoppedAndDropNonOwners(playlistID, ownerUserID, message string, dropAll bool) {
	m.mu.Lock()
	room := m.subs[playlistID]
	var targets []*collabClient
	for c := range room {
		if dropAll || c.userID != ownerUserID {
			if c.userID != ownerUserID {
				targets = append(targets, c)
			}
			delete(room, c)
			if pids, ok := m.clients[c]; ok {
				delete(pids, playlistID)
			}
		}
	}
	if len(room) == 0 {
		delete(m.subs, playlistID)
	}
	m.mu.Unlock()

	if len(targets) == 0 {
		return
	}
	frame := map[string]interface{}{
		"type":       EventStopped,
		"playlistId": playlistID,
		"message":    message,
		"serverMs":   clock.NowMs(),
	}
	for _, c := range targets {
		c.Send(frame)
	}
}

type wsClientFrame struct {
	Type          string      `json:"type"`
	ClientMs      interface{} `json:"clientMs,omitempty"`
	PlaylistID    string      `json:"playlistId,omitempty"`
	SinceRevision *int64      `json:"sinceRevision,omitempty"`
}

// HandleWebSocket upgrades and serves GET /ws/collab.
func (m *WSManager) HandleWebSocket(w http.ResponseWriter, r *http.Request) {
	rawToken := ParseCollabSocketToken(r)
	user, _, err := m.authMgr.Authenticate(rawToken)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"auth_required","message":"Valid Google session token is required."}`))
		return
	}

	conn, err := m.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	conn.SetReadLimit(config.WebSocketMaxBytes)
	_ = conn.SetReadDeadline(time.Now().Add(time.Duration(config.ConnectionIdleMs) * time.Millisecond))

	client := newCollabClient(conn, user.ID, rawToken)
	m.registerClient(client)
	defer m.unregisterClient(client)

	for {
		var frame wsClientFrame
		if err := conn.ReadJSON(&frame); err != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(time.Duration(config.ConnectionIdleMs) * time.Millisecond))

		if !client.spendFrameBudget() {
			client.Send(map[string]interface{}{
				"type":     EventError,
				"error":    "rate_limited",
				"message":  "Too many WebSocket messages at once.",
				"serverMs": clock.NowMs(),
			})
			continue
		}

		// Re-check session validity so signed-out/expired sessions cannot act on existing sockets.
		currentUser, _, authErr := m.authMgr.Authenticate(client.rawToken)
		if authErr != nil {
			client.Send(map[string]interface{}{
				"type":     EventError,
				"error":    "auth_required",
				"message":  "Your session has expired or been signed out.",
				"serverMs": clock.NowMs(),
			})
			break
		}

		switch frame.Type {
		case FramePing:
			client.Send(map[string]interface{}{
				"type":     FramePong,
				"clientMs": frame.ClientMs,
				"serverMs": clock.NowMs(),
			})

		case FrameSubscribe:
			pid := strings.TrimSpace(frame.PlaylistID)
			if pid == "" {
				client.Send(map[string]interface{}{
					"type":     EventError,
					"error":    "validation_error",
					"message":  "playlistId is required to subscribe.",
					"serverMs": clock.NowMs(),
				})
				continue
			}

			detail, err := m.store.CheckSubscriptionAccess(currentUser.ID, pid)
			if err != nil {
				code := "forbidden"
				msg := "Cannot subscribe to playlist."
				var ce *CollabError
				if errors.As(err, &ce) {
					code = ce.Code
					msg = ce.Message
				}
				client.Send(map[string]interface{}{
					"type":       EventError,
					"playlistId": pid,
					"error":      code,
					"message":    msg,
					"serverMs":   clock.NowMs(),
				})
				continue
			}

			m.addSubscription(client, pid)

			var sinceRev int64 = -1
			if frame.SinceRevision != nil {
				sinceRev = *frame.SinceRevision
			}
			if sinceRev < detail.Revision {
				client.Send(map[string]interface{}{
					"type":       EventSnapshot,
					"playlistId": pid,
					"revision":   detail.Revision,
					"playlist":   detail,
					"serverMs":   clock.NowMs(),
				})
			} else {
				client.Send(map[string]interface{}{
					"type":       EventSubscribed,
					"playlistId": pid,
					"revision":   detail.Revision,
					"upToDate":   true,
					"serverMs":   clock.NowMs(),
				})
			}

		case FrameUnsubscribe:
			pid := strings.TrimSpace(frame.PlaylistID)
			if pid != "" {
				m.removeSubscription(client, pid)
				client.Send(map[string]interface{}{
					"type":       EventUnsubscribed,
					"playlistId": pid,
					"serverMs":   clock.NowMs(),
				})
			}

		default:
			client.Send(map[string]interface{}{
				"type":     EventError,
				"error":    "unknown_frame",
				"message":  "Unsupported WebSocket frame type.",
				"serverMs": clock.NowMs(),
			})
		}
	}
}
