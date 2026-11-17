package collab

import (
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/auth"
	"github.com/KabirSinghBhatia/BitChord/backend/clock"
	"github.com/KabirSinghBhatia/BitChord/backend/config"
)

type rateLimiter struct {
	mu      sync.Mutex
	window  time.Duration
	limit   int
	maxKeys int
	entries map[string]rateEntry
	swept   time.Time
}

type rateEntry struct {
	started time.Time
	count   int
}

func newRateLimiter(window time.Duration, limit, maxKeys int) *rateLimiter {
	return &rateLimiter{
		window:  window,
		limit:   limit,
		maxKeys: maxKeys,
		entries: make(map[string]rateEntry),
	}
}

func (l *rateLimiter) Allow(key string) bool {
	if l == nil || l.limit <= 0 {
		return true
	}
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()

	if now.Sub(l.swept) >= time.Second || (l.maxKeys > 0 && len(l.entries) >= l.maxKeys) {
		for k, e := range l.entries {
			if now.Sub(e.started) >= l.window {
				delete(l.entries, k)
			}
		}
		l.swept = now
	}
	e := l.entries[key]
	if e.started.IsZero() || now.Sub(e.started) >= l.window {
		if e.started.IsZero() && l.maxKeys > 0 && len(l.entries) >= l.maxKeys {
			return false
		}
		l.entries[key] = rateEntry{started: now, count: 1}
		return true
	}
	if e.count >= l.limit {
		return false
	}
	e.count++
	l.entries[key] = e
	return true
}

// Handler serves all Collaborative Playlists HTTP, HTML invite landing, and WebSocket endpoints.
type Handler struct {
	Store          *Store
	Auth           *auth.Manager
	WS             *WSManager
	AuthLimiter    *rateLimiter
	PreviewLimiter *rateLimiter
	MutateLimiter  *rateLimiter
	NowMs          func() int64
}

func NewHandler(store *Store, authMgr *auth.Manager) *Handler {
	wsMgr := NewWSManager(store, authMgr)
	return &Handler{
		Store:          store,
		Auth:           authMgr,
		WS:             wsMgr,
		AuthLimiter:    newRateLimiter(time.Minute, config.CollabAuthRatePerMinute, config.RateLimitMaxEntries),
		PreviewLimiter: newRateLimiter(time.Minute, config.CollabPreviewRatePerMinute, config.RateLimitMaxEntries),
		MutateLimiter:  newRateLimiter(time.Minute, config.CollabMutateRatePerMinute, config.RateLimitMaxEntries),
		NowMs:          clock.NowMs,
	}
}

func (h *Handler) nowMs() int64 {
	if h.NowMs != nil {
		return h.NowMs()
	}
	return clock.NowMs()
}

// RegisterRoutes mounts all collaborative playlist REST, invite landing, and WebSocket routes on mux.
func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	// Verified Google OIDC & Session endpoints
	mux.HandleFunc("POST /api/collab/auth/google", h.handleGoogleAuth)
	mux.HandleFunc("POST /api/collab/auth/google/code", h.handleGoogleAuthCode)
	mux.HandleFunc("GET /api/collab/auth/me", h.handleAuthMe)
	mux.HandleFunc("POST /api/collab/auth/logout", h.handleAuthLogout)

	// Collaborative Playlist lifecycle & conversion
	mux.HandleFunc("GET /api/collab/playlists", h.handleListPlaylists)
	mux.HandleFunc("POST /api/collab/playlists", h.handleCreatePlaylist)
	mux.HandleFunc("POST /api/collab/playlists/convert", h.handleConvertPlaylist)
	mux.HandleFunc("GET /api/collab/playlists/{id}", h.handleGetPlaylist)
	mux.HandleFunc("PATCH /api/collab/playlists/{id}", h.handleUpdatePlaylist)
	mux.HandleFunc("DELETE /api/collab/playlists/{id}", h.handleDeletePlaylist)
	mux.HandleFunc("POST /api/collab/playlists/{id}/stop-sharing", h.handleStopSharing)

	// Tracks with server-enforced contributor attribution
	mux.HandleFunc("POST /api/collab/playlists/{id}/tracks", h.handleAddTracks)
	mux.HandleFunc("DELETE /api/collab/playlists/{id}/tracks/{entryId}", h.handleRemoveTrack)
	mux.HandleFunc("PUT /api/collab/playlists/{id}/tracks/order", h.handleReorderTracks)

	// Members, roles, ownership transfer & activity
	mux.HandleFunc("GET /api/collab/playlists/{id}/members", h.handleListMembers)
	mux.HandleFunc("PATCH /api/collab/playlists/{id}/members/{userId}", h.handleUpdateMemberRole)
	mux.HandleFunc("POST /api/collab/playlists/{id}/transfer-ownership", h.handleTransferOwnership)
	mux.HandleFunc("DELETE /api/collab/playlists/{id}/members/{userId}", h.handleRemoveMember)
	mux.HandleFunc("POST /api/collab/playlists/{id}/leave", h.handleLeavePlaylist)
	mux.HandleFunc("GET /api/collab/playlists/{id}/activity", h.handleListActivity)

	// Shareable invitations & public preview
	mux.HandleFunc("POST /api/collab/playlists/{id}/invites", h.handleCreateInvite)
	mux.HandleFunc("GET /api/collab/playlists/{id}/invites", h.handleListInvites)
	mux.HandleFunc("DELETE /api/collab/playlists/{id}/invites/{inviteId}", h.handleRevokeInvite)
	mux.HandleFunc("GET /api/collab/invites/{token}/preview", h.handlePreviewInvite)
	mux.HandleFunc("POST /api/collab/invites/{token}/accept", h.handleAcceptInvite)

	// HTML deep-link landing page
	mux.HandleFunc("GET /collab/invite/{token}", h.handleInviteLanding)

	// Realtime WebSocket endpoint
	mux.HandleFunc("GET /ws/collab", h.WS.HandleWebSocket)
}

func collabClientIP(r *http.Request) string {
	if config.TrustProxy {
		if fwd := strings.TrimSpace(strings.Split(r.Header.Get("X-Forwarded-For"), ",")[0]); fwd != "" {
			return fwd
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err == nil {
		return host
	}
	return r.RemoteAddr
}

func collabRequestOrigin(r *http.Request) string {
	if config.PublicOrigin != "" {
		return config.PublicOrigin
	}
	proto := "http"
	if r.TLS != nil {
		proto = "https"
	}
	if config.TrustProxy {
		if fwdProto := r.Header.Get("X-Forwarded-Proto"); fwdProto != "" {
			proto = strings.TrimSpace(strings.Split(fwdProto, ",")[0])
		}
	}
	host := r.Host
	if host == "" {
		host = "localhost"
	}
	return fmt.Sprintf("%s://%s", proto, host)
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

func writeError(w http.ResponseWriter, err error) {
	var ae *auth.AuthError
	if errors.As(err, &ae) {
		writeJSON(w, ae.Status, map[string]string{"error": ae.Code, "message": ae.Message})
		return
	}
	var ce *CollabError
	if errors.As(err, &ce) {
		writeJSON(w, ce.Status, map[string]string{"error": ce.Code, "message": ce.Message})
		return
	}
	writeJSON(w, http.StatusInternalServerError, map[string]string{
		"error":   "server_error",
		"message": "An unexpected server error occurred.",
	})
}

func decodeBody(w http.ResponseWriter, r *http.Request, maxBytes int64, dst interface{}) bool {
	if maxBytes <= 0 {
		maxBytes = config.CollabRequestMaxBytes
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxBytes)
	defer r.Body.Close()

	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(dst); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeJSON(w, http.StatusRequestEntityTooLarge, map[string]string{
				"error":   "request_too_large",
				"message": "Request body exceeds maximum allowed size.",
			})
		} else {
			writeJSON(w, http.StatusUnprocessableEntity, map[string]string{
				"error":   "invalid_json",
				"message": "Malformed JSON request body.",
			})
		}
		return false
	}
	if err := dec.Decode(&struct{}{}); err != io.EOF {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{
			"error":   "invalid_json",
			"message": "Request body must contain a single JSON object.",
		})
		return false
	}
	return true
}

func (h *Handler) requireUser(w http.ResponseWriter, r *http.Request) (*auth.User, *auth.SessionRecord, string, bool) {
	rawTok := ParseBearerToken(r)
	user, ses, err := h.Auth.Authenticate(rawTok)
	if err != nil {
		writeError(w, err)
		return nil, nil, "", false
	}
	return user, ses, rawTok, true
}

// --- Auth Handlers ---

func (h *Handler) handleGoogleAuth(w http.ResponseWriter, r *http.Request) {
	if !h.AuthLimiter.Allow(collabClientIP(r)) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{
			"error":   "rate_limited",
			"message": "Too many sign-in attempts from this address. Please try again shortly.",
		})
		return
	}
	var req struct {
		IDToken      string `json:"idToken"`
		Code         string `json:"code"`
		CodeVerifier string `json:"codeVerifier"`
		RedirectURI  string `json:"redirectUri"`
		ClientID     string `json:"clientId"`
		ClientSecret string `json:"clientSecret"`
	}
	if !decodeBody(w, r, 64*1024, &req) {
		return
	}

	var (
		user        *auth.User
		sessionTok  string
		expiresAtMs int64
		err         error
	)
	if strings.TrimSpace(req.IDToken) != "" {
		user, sessionTok, expiresAtMs, err = h.Auth.SignInWithIDToken(r.Context(), req.IDToken)
	} else if strings.TrimSpace(req.Code) != "" {
		user, sessionTok, expiresAtMs, err = h.Auth.SignInWithCode(r.Context(), auth.CodeExchangeRequest{
			Code:         req.Code,
			CodeVerifier: req.CodeVerifier,
			RedirectURI:  req.RedirectURI,
			ClientID:     req.ClientID,
			ClientSecret: req.ClientSecret,
		})
	} else {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]string{
			"error":   "validation_error",
			"message": "Provide either idToken or {code, codeVerifier, redirectUri, clientId}.",
		})
		return
	}
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"token":       sessionTok,
		"expiresAtMs": expiresAtMs,
		"user":        user.ToWire(),
	})
}

func (h *Handler) handleGoogleAuthCode(w http.ResponseWriter, r *http.Request) {
	if !h.AuthLimiter.Allow(collabClientIP(r)) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{
			"error":   "rate_limited",
			"message": "Too many sign-in attempts from this address. Please try again shortly.",
		})
		return
	}
	var req auth.CodeExchangeRequest
	if !decodeBody(w, r, 64*1024, &req) {
		return
	}
	user, sessionTok, expiresAtMs, err := h.Auth.SignInWithCode(r.Context(), req)
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"token":       sessionTok,
		"expiresAtMs": expiresAtMs,
		"user":        user.ToWire(),
	})
}

func (h *Handler) handleAuthMe(w http.ResponseWriter, r *http.Request) {
	user, ses, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"user":        user.ToWire(),
		"expiresAtMs": ses.ExpiresAtMs,
	})
}

func (h *Handler) handleAuthLogout(w http.ResponseWriter, r *http.Request) {
	_, _, rawTok, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	if err := h.Auth.SignOut(rawTok); err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// --- Playlist Lifecycle & Conversion Handlers ---

func (h *Handler) handleListPlaylists(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	includeStopped := strings.EqualFold(r.URL.Query().Get("includeStopped"), "true")
	list := h.Store.ListPlaylists(user.ID, includeStopped)
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlists": list,
	})
}

func (h *Handler) handleCreatePlaylist(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	if !h.MutateLimiter.Allow(collabClientIP(r)) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "rate_limited", "message": "Too many requests. Please slow down."})
		return
	}

	var req struct {
		Title       string            `json:"title"`
		Description string            `json:"description"`
		ArtworkURL  *string           `json:"artworkUrl"`
		Permissions *PermissionsPatch `json:"permissions"`
		Tracks      []TrackInput      `json:"tracks"`
	}
	if !decodeBody(w, r, config.CollabRequestMaxBytes, &req) {
		return
	}

	detail, err := h.Store.CreatePlaylist(user, CreatePlaylistParams{
		Title:       req.Title,
		Description: req.Description,
		ArtworkURL:  req.ArtworkURL,
		Permissions: req.Permissions,
		Tracks:      req.Tracks,
	}, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"playlist": detail,
	})
}

func (h *Handler) handleConvertPlaylist(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	if !h.MutateLimiter.Allow(collabClientIP(r)) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "rate_limited", "message": "Too many requests. Please slow down."})
		return
	}

	var req struct {
		SourcePlaylistID               string            `json:"sourcePlaylistId"`
		SourceType                     string            `json:"sourceType"`
		Title                          string            `json:"title"`
		Description                    string            `json:"description"`
		ArtworkURL                     *string           `json:"artworkUrl"`
		AttributeExistingTracksToOwner bool              `json:"attributeExistingTracksToOwner"`
		Permissions                    *PermissionsPatch `json:"permissions"`
		Tracks                         []TrackInput      `json:"tracks"`
	}
	if !decodeBody(w, r, config.CollabRequestMaxBytes, &req) {
		return
	}

	detail, alreadyConverted, err := h.Store.ConvertPersonalPlaylist(user, ConvertPlaylistParams{
		SourcePlaylistID:               req.SourcePlaylistID,
		SourceType:                     req.SourceType,
		Title:                          req.Title,
		Description:                    req.Description,
		ArtworkURL:                     req.ArtworkURL,
		AttributeExistingTracksToOwner: req.AttributeExistingTracksToOwner,
		Permissions:                    req.Permissions,
		Tracks:                         req.Tracks,
	}, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	status := http.StatusCreated
	if alreadyConverted {
		status = http.StatusOK
	}
	writeJSON(w, status, map[string]interface{}{
		"playlist":         detail,
		"alreadyConverted": alreadyConverted,
	})
}

func (h *Handler) handleGetPlaylist(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	detail, err := h.Store.GetPlaylistDetail(user.ID, r.PathValue("id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist": detail,
	})
}

func (h *Handler) handleUpdatePlaylist(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")

	var rawFields map[string]json.RawMessage
	if !decodeBody(w, r, 64*1024, &rawFields) {
		return
	}

	var params UpdatePlaylistParams
	if v, has := rawFields["title"]; has {
		var s string
		if err := json.Unmarshal(v, &s); err != nil {
			writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "validation_error", "message": "title must be a string."})
			return
		}
		params.Title = &s
	}
	if v, has := rawFields["description"]; has {
		var s string
		if err := json.Unmarshal(v, &s); err != nil {
			writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "validation_error", "message": "description must be a string."})
			return
		}
		params.Description = &s
	}
	if v, has := rawFields["artworkUrl"]; has {
		params.HasArtwork = true
		if string(v) != "null" {
			var s string
			if err := json.Unmarshal(v, &s); err != nil {
				writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "validation_error", "message": "artworkUrl must be a string or null."})
				return
			}
			params.ArtworkURL = &s
		}
	}
	if v, has := rawFields["permissions"]; has && string(v) != "null" {
		var patch PermissionsPatch
		if err := json.Unmarshal(v, &patch); err != nil {
			writeJSON(w, http.StatusUnprocessableEntity, map[string]string{"error": "validation_error", "message": "Invalid permissions object."})
			return
		}
		params.Permissions = &patch
	}
	if v, has := rawFields["opId"]; has && string(v) != "null" {
		_ = json.Unmarshal(v, &params.OpID)
	}

	detail, latestAct, duplicate, err := h.Store.UpdatePlaylistMetadata(user, playlistID, params, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	if !duplicate {
		h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
			"type":           EventMetadataChanged,
			"playlistId":     playlistID,
			"revision":       detail.Revision,
			"opId":           params.OpID,
			"playlist":       detail.PlaylistSummary,
			"latestActivity": latestAct,
			"serverMs":       h.nowMs(),
		})
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist": detail,
	})
}

func (h *Handler) handleDeletePlaylist(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")
	if _, err := h.Store.DeletePlaylist(user, playlistID, h.nowMs()); err != nil {
		writeError(w, err)
		return
	}
	h.WS.NotifyStoppedAndDropNonOwners(playlistID, user.ID, "The owner deleted this playlist.", true)
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (h *Handler) handleStopSharing(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")

	snapshot, _, err := h.Store.StopSharing(user, playlistID, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	// Notify all connected non-owner collaborator WebSockets and drop their subscriptions.
	h.WS.NotifyStoppedAndDropNonOwners(playlistID, user.ID, "The owner stopped sharing this playlist.", false)

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":       true,
		"playlist": snapshot,
	})
}

// --- Track Mutation Handlers ---

func (h *Handler) handleAddTracks(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")

	var req struct {
		Track    *TrackInput  `json:"track"`
		Tracks   []TrackInput `json:"tracks"`
		Position *int         `json:"position"`
		OpID     string       `json:"opId"`
	}
	if !decodeBody(w, r, config.CollabRequestMaxBytes, &req) {
		return
	}

	tracks := req.Tracks
	if len(tracks) == 0 && req.Track != nil {
		tracks = []TrackInput{*req.Track}
	}

	detail, added, latestAct, duplicate, err := h.Store.AddTracks(user, playlistID, AddTracksParams{
		Tracks:   tracks,
		Position: req.Position,
		OpID:     req.OpID,
	}, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	if !duplicate {
		h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
			"type":           EventTracksChanged,
			"playlistId":     playlistID,
			"revision":       detail.Revision,
			"opId":           req.OpID,
			"tracks":         detail.Tracks,
			"latestActivity": latestAct,
			"serverMs":       h.nowMs(),
		})
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist": detail,
		"added":    added,
		"tracks":   detail.Tracks,
	})
}

func (h *Handler) handleRemoveTrack(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")
	entryID := r.PathValue("entryId")

	opID := strings.TrimSpace(r.URL.Query().Get("opId"))
	if opID == "" && r.Body != nil && r.ContentLength != 0 {
		r.Body = http.MaxBytesReader(w, r.Body, 4096)
		defer r.Body.Close()
		var body struct {
			OpID string `json:"opId"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err == nil {
			opID = body.OpID
		}
	}

	detail, latestAct, duplicate, err := h.Store.RemoveTrack(user, playlistID, entryID, opID, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	if !duplicate {
		h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
			"type":           EventTracksChanged,
			"playlistId":     playlistID,
			"revision":       detail.Revision,
			"opId":           opID,
			"tracks":         detail.Tracks,
			"latestActivity": latestAct,
			"serverMs":       h.nowMs(),
		})
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist": detail,
		"tracks":   detail.Tracks,
	})
}

func (h *Handler) handleReorderTracks(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")

	var req struct {
		EntryIDs  []string `json:"entryIds"`
		FromIndex *int     `json:"fromIndex"`
		ToIndex   *int     `json:"toIndex"`
		EntryID   string   `json:"entryId"`
		OpID      string   `json:"opId"`
	}
	if !decodeBody(w, r, 256*1024, &req) {
		return
	}

	detail, latestAct, duplicate, err := h.Store.ReorderTracks(user, playlistID, ReorderTracksParams{
		EntryIDs:  req.EntryIDs,
		FromIndex: req.FromIndex,
		ToIndex:   req.ToIndex,
		EntryID:   req.EntryID,
		OpID:      req.OpID,
	}, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	if !duplicate {
		h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
			"type":           EventTracksChanged,
			"playlistId":     playlistID,
			"revision":       detail.Revision,
			"opId":           req.OpID,
			"tracks":         detail.Tracks,
			"latestActivity": latestAct,
			"serverMs":       h.nowMs(),
		})
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist": detail,
		"tracks":   detail.Tracks,
	})
}

// --- Membership, Roles, Ownership & Activity Handlers ---

func (h *Handler) handleListMembers(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	members, err := h.Store.ListMembers(user.ID, r.PathValue("id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"members": members,
	})
}

func (h *Handler) handleUpdateMemberRole(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")
	targetUserID := r.PathValue("userId")

	var req struct {
		Role string `json:"role"`
	}
	if !decodeBody(w, r, 16*1024, &req) {
		return
	}

	detail, latestAct, ownerChanged, err := h.Store.UpdateMemberRole(user, playlistID, targetUserID, req.Role, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	now := h.nowMs()
	h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
		"type":           EventMembersChanged,
		"playlistId":     playlistID,
		"revision":       detail.Revision,
		"members":        detail.Members,
		"latestActivity": latestAct,
		"serverMs":       now,
	})
	if ownerChanged {
		h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
			"type":           EventMetadataChanged,
			"playlistId":     playlistID,
			"revision":       detail.Revision,
			"playlist":       detail.PlaylistSummary,
			"latestActivity": latestAct,
			"serverMs":       now,
		})
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist": detail,
		"members":  detail.Members,
	})
}

func (h *Handler) handleTransferOwnership(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")

	var req struct {
		NewOwnerUserID string `json:"newOwnerUserId"`
	}
	if !decodeBody(w, r, 16*1024, &req) {
		return
	}

	detail, latestAct, err := h.Store.TransferOwnership(user, playlistID, req.NewOwnerUserID, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	now := h.nowMs()
	h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
		"type":           EventMembersChanged,
		"playlistId":     playlistID,
		"revision":       detail.Revision,
		"members":        detail.Members,
		"latestActivity": latestAct,
		"serverMs":       now,
	})
	h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
		"type":           EventMetadataChanged,
		"playlistId":     playlistID,
		"revision":       detail.Revision,
		"playlist":       detail.PlaylistSummary,
		"latestActivity": latestAct,
		"serverMs":       now,
	})

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist": detail,
		"members":  detail.Members,
	})
}

func (h *Handler) handleRemoveMember(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")
	targetUserID := r.PathValue("userId")

	detail, latestAct, selfLeft, err := h.Store.RemoveOrLeaveMember(user, playlistID, targetUserID, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	reason := "removed"
	msg := "The playlist owner removed you from this collaborative playlist."
	if selfLeft {
		reason = "left"
		msg = "You left this collaborative playlist."
	}
	h.WS.RemoveUserSubscription(playlistID, targetUserID, reason, msg)
	h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
		"type":           EventMembersChanged,
		"playlistId":     playlistID,
		"revision":       detail.Revision,
		"members":        detail.Members,
		"latestActivity": latestAct,
		"serverMs":       h.nowMs(),
	})

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"ok":       true,
		"playlist": detail,
		"members":  detail.Members,
	})
}

func (h *Handler) handleLeavePlaylist(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")

	detail, latestAct, _, err := h.Store.RemoveOrLeaveMember(user, playlistID, user.ID, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	h.WS.RemoveUserSubscription(playlistID, user.ID, "left", "You left this collaborative playlist.")
	h.WS.BroadcastPlaylist(playlistID, map[string]interface{}{
		"type":           EventMembersChanged,
		"playlistId":     playlistID,
		"revision":       detail.Revision,
		"members":        detail.Members,
		"latestActivity": latestAct,
		"serverMs":       h.nowMs(),
	})

	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (h *Handler) handleListActivity(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	acts, err := h.Store.ListActivities(user.ID, r.PathValue("id"))
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"activities": acts,
	})
}

// --- Invitations & Landing Page ---

func (h *Handler) handleCreateInvite(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	playlistID := r.PathValue("id")

	var req struct {
		Role         string `json:"role"`
		ExpiresInSec *int64 `json:"expiresInSec"`
		MaxUses      int    `json:"maxUses"`
	}
	if r.Body != nil && r.ContentLength != 0 {
		if !decodeBody(w, r, 16*1024, &req) {
			return
		}
	}

	origin := collabRequestOrigin(r)
	view, _, err := h.Store.CreateInvite(user, playlistID, CreateInviteParams{
		Role:         req.Role,
		ExpiresInSec: req.ExpiresInSec,
		MaxUses:      req.MaxUses,
	}, origin, h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, view)
}

func (h *Handler) handleListInvites(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	views, err := h.Store.ListInvites(user, r.PathValue("id"), collabRequestOrigin(r), h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"invites": views,
	})
}

func (h *Handler) handleRevokeInvite(w http.ResponseWriter, r *http.Request) {
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}
	if err := h.Store.RevokeInvite(user, r.PathValue("id"), r.PathValue("inviteId"), h.nowMs()); err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

func (h *Handler) handlePreviewInvite(w http.ResponseWriter, r *http.Request) {
	if !h.PreviewLimiter.Allow(collabClientIP(r)) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{
			"error":   "preview_rate_limited",
			"message": "Too many invite previews from this address. Please try again shortly.",
		})
		return
	}
	preview, err := h.Store.PreviewInvite(r.PathValue("token"), h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, preview)
}

func (h *Handler) handleAcceptInvite(w http.ResponseWriter, r *http.Request) {
	if !h.MutateLimiter.Allow(collabClientIP(r)) {
		writeJSON(w, http.StatusTooManyRequests, map[string]string{
			"error":   "rate_limited",
			"message": "Too many invite attempts from this address. Please try again shortly.",
		})
		return
	}
	// Strictly require valid Bearer session token (401 {"error":"auth_required"} when logged out).
	user, _, _, ok := h.requireUser(w, r)
	if !ok {
		return
	}

	detail, joinedMember, latestAct, alreadyMember, err := h.Store.AcceptInvite(user, r.PathValue("token"), h.nowMs())
	if err != nil {
		writeError(w, err)
		return
	}

	if !alreadyMember {
		now := h.nowMs()
		h.WS.BroadcastPlaylist(detail.ID, map[string]interface{}{
			"type":           EventMemberJoined,
			"playlistId":     detail.ID,
			"revision":       detail.Revision,
			"member":         joinedMember,
			"members":        detail.Members,
			"latestActivity": latestAct,
			"serverMs":       now,
		})
		h.WS.BroadcastPlaylist(detail.ID, map[string]interface{}{
			"type":           EventMembersChanged,
			"playlistId":     detail.ID,
			"revision":       detail.Revision,
			"members":        detail.Members,
			"latestActivity": latestAct,
			"serverMs":       now,
		})
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"playlist":      detail,
		"alreadyMember": alreadyMember,
	})
}

type collabLandingData struct {
	IsActive     bool
	Title        string
	Description  string
	OwnerName    string
	MemberCount  int
	TrackCount   int
	RoleLabel    string
	IntentURI    template.URL
	SafeDeepLink template.URL
	StatusReason string
}

var collabInviteTemplate = template.Must(template.New("collab_invite").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>BitChord Collaborative Playlist{{if .Title}} - {{.Title}}{{end}}</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: #0b0b0e;
            color: #f3f3f7;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 24px;
        }
        .container {
            background: rgba(22, 22, 30, 0.85);
            backdrop-filter: blur(24px);
            -webkit-backdrop-filter: blur(24px);
            border: 1px solid rgba(255, 255, 255, 0.08);
            border-radius: 28px;
            max-width: 460px;
            width: 100%;
            padding: 36px 28px;
            text-align: center;
            box-shadow: 0 20px 50px rgba(0, 0, 0, 0.5), 0 0 40px rgba(124, 77, 255, 0.12);
        }
        .badge {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            background: rgba(124, 77, 255, 0.15);
            color: #b388ff;
            font-size: 13px;
            font-weight: 600;
            padding: 6px 14px;
            border-radius: 9999px;
            margin-bottom: 20px;
            letter-spacing: 0.4px;
        }
        .badge-dot {
            width: 8px;
            height: 8px;
            background: #00e676;
            border-radius: 50%;
            box-shadow: 0 0 8px #00e676;
        }
        .badge-dot.offline {
            background: #ff5252;
            box-shadow: 0 0 8px #ff5252;
        }
        h1 {
            font-size: 24px;
            font-weight: 700;
            margin-bottom: 8px;
            letter-spacing: -0.5px;
        }
        .subtitle {
            color: #9e9ea7;
            font-size: 15px;
            line-height: 1.5;
            margin-bottom: 24px;
        }
        .playlist-box {
            background: rgba(255, 255, 255, 0.04);
            border: 1px solid rgba(255, 255, 255, 0.1);
            border-radius: 18px;
            padding: 18px 20px;
            margin-bottom: 20px;
        }
        .playlist-title {
            font-size: 20px;
            font-weight: 700;
            color: #ffffff;
            margin-bottom: 6px;
        }
        .playlist-meta {
            font-size: 14px;
            color: #b8b8c2;
            line-height: 1.5;
        }
        .auth-notice {
            background: rgba(61, 90, 254, 0.12);
            border: 1px solid rgba(179, 136, 255, 0.22);
            border-radius: 14px;
            padding: 12px 16px;
            font-size: 13px;
            color: #d8c8ff;
            margin-bottom: 24px;
            line-height: 1.45;
        }
        .btn {
            display: block;
            width: 100%;
            padding: 16px 24px;
            background: linear-gradient(135deg, #7c4dff 0%, #3d5afe 100%);
            color: #ffffff;
            font-size: 16px;
            font-weight: 600;
            text-decoration: none;
            border-radius: 16px;
            border: none;
            cursor: pointer;
            transition: transform 0.15s ease, opacity 0.15s ease;
            box-shadow: 0 8px 24px rgba(124, 77, 255, 0.35);
        }
        .btn:hover { transform: translateY(-1px); opacity: 0.95; }
        .btn:active { transform: scale(0.98); }
        .footer-note {
            margin-top: 22px;
            font-size: 13px;
            color: #71717a;
            line-height: 1.5;
        }
        .footer-note a { color: #b388ff; text-decoration: none; }
        .footer-note a:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div class="container">
        {{if .IsActive}}
            <div class="badge">
                <span class="badge-dot"></span>
                <span>Collaborative Playlist • {{.RoleLabel}} Invite</span>
            </div>
            <h1>Collaborate in BitChord</h1>
            <p class="subtitle">{{.OwnerName}} invited you to collaborate on this playlist.</p>
            <div class="playlist-box">
                <div class="playlist-title">{{.Title}}</div>
                <div class="playlist-meta">
                    Curated by {{.OwnerName}} • {{.TrackCount}} songs • {{.MemberCount}} collaborators
                </div>
            </div>
            <div class="auth-notice">
                Google sign-in is required in BitChord to join and contribute to collaborative playlists.
            </div>
            <a id="joinBtn" href="{{.IntentURI}}" class="btn">Open Playlist in BitChord</a>
            <p class="footer-note">
                Didn’t open automatically? Tap the button above.<br>
                Don't have BitChord yet? <a href="https://github.com/Inventor365/Sonara/releases" target="_blank" rel="noopener">Download it here</a>.
            </p>
            <script>
                var intentUri = {{.IntentURI}};
                var deepLink = {{.SafeDeepLink}};
                function launch() {
                    if (/Android/i.test(navigator.userAgent)) {
                        window.location.href = intentUri;
                    } else {
                        window.location.href = deepLink;
                    }
                }
                setTimeout(launch, 100);
            </script>
        {{else}}
            <div class="badge">
                <span class="badge-dot offline"></span>
                <span>Invite Unavailable</span>
            </div>
            <h1>Invitation Not Available</h1>
            <p class="subtitle">{{.StatusReason}}</p>
            <p class="footer-note">
                Ask the playlist owner for a fresh collaborative invite link.<br>
                <a href="https://github.com/Inventor365/Sonara/releases" target="_blank" rel="noopener">Download BitChord PlusPC</a>
            </p>
        {{end}}
    </div>
</body>
</html>`))

func (h *Handler) handleInviteLanding(w http.ResponseWriter, r *http.Request) {
	if !h.PreviewLimiter.Allow(collabClientIP(r)) {
		http.Error(w, "too many requests", http.StatusTooManyRequests)
		return
	}
	token := strings.TrimSpace(r.PathValue("token"))
	origin := collabRequestOrigin(r)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")

	preview, err := h.Store.PreviewInvite(token, h.nowMs())
	if err != nil {
		w.WriteHeader(http.StatusNotFound)
		_ = collabInviteTemplate.Execute(w, collabLandingData{
			IsActive:     false,
			StatusReason: "This collaborative playlist invite link does not exist on this server.",
		})
		return
	}

	if preview.Revoked || preview.Expired {
		reason := "This collaborative playlist invite link has expired."
		if preview.Revoked {
			reason = "This collaborative playlist invite link has been revoked by the owner."
		}
		w.WriteHeader(http.StatusGone)
		_ = collabInviteTemplate.Execute(w, collabLandingData{
			IsActive:     false,
			Title:        preview.Title,
			StatusReason: reason,
		})
		return
	}

	escapedTok := url.PathEscape(token)
	escapedOrigin := url.QueryEscape(origin)
	deepLink := fmt.Sprintf("bitchord://collab/invite/%s?server=%s", escapedTok, escapedOrigin)
	intentURI := fmt.Sprintf("intent://collab/invite/%s?server=%s#Intent;scheme=bitchord;end", escapedTok, escapedOrigin)

	roleLabel := "Viewer"
	if preview.Role == RoleEditor {
		roleLabel = "Editor"
	}

	w.WriteHeader(http.StatusOK)
	_ = collabInviteTemplate.Execute(w, collabLandingData{
		IsActive:     true,
		Title:        preview.Title,
		Description:  preview.Description,
		OwnerName:    preview.OwnerName,
		MemberCount:  preview.MemberCount,
		TrackCount:   preview.TrackCount,
		RoleLabel:    roleLabel,
		IntentURI:    template.URL(intentURI),
		SafeDeepLink: template.URL(deepLink),
	})
}
