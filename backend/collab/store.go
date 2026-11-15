package collab

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/KabirSinghBhatia/BitChord/backend/auth"
)

const CurrentSchemaVersion = 1

// StoreLimits configures capacity bounds enforced by Store.
type StoreLimits struct {
	MaxPlaylistsPerUser   int
	MaxTracksPerPlaylist  int
	MaxMembersPerPlaylist int
}

func DefaultStoreLimits() StoreLimits {
	return StoreLimits{
		MaxPlaylistsPerUser:   200,
		MaxTracksPerPlaylist:  2000,
		MaxMembersPerPlaylist: 50,
	}
}

type playlistRecord struct {
	Playlist   *CollabPlaylist     `json:"playlist"`
	Members    []*CollabMember     `json:"members"`
	Tracks     []*CollabTrackEntry `json:"tracks"`
	Invites    []*CollabInvite     `json:"invites"`
	Activities []*CollabActivity   `json:"activities"`
	AppliedOps []AppliedOp         `json:"appliedOps"`
}

type snapshotFile struct {
	Version   int                   `json:"version"`
	SavedAtMs int64                 `json:"savedAtMs"`
	Users     []*auth.User          `json:"users"`
	Sessions  []*auth.SessionRecord `json:"sessions"`
	Playlists []*playlistRecord     `json:"playlists"`
}

// MigrationHook transforms older on-disk snapshot schemas to CurrentSchemaVersion.
type MigrationHook func(version int, raw []byte) (int, []byte, error)

// DefaultMigrationHook upgrades schema v0 (missing version) to v1 and rejects future versions.
func DefaultMigrationHook(version int, raw []byte) (int, []byte, error) {
	if version == 0 {
		version = 1
	}
	if version != CurrentSchemaVersion {
		return version, nil, fmt.Errorf("unsupported collab snapshot schema version %d (supported: %d)", version, CurrentSchemaVersion)
	}
	return version, raw, nil
}

// Store is a thread-safe (sync.RWMutex) ACID state store for users, hashed sessions,
// and collaborative playlists, backed by atomic temp-file + fsync + os.Rename JSON snapshots.
type Store struct {
	mu       sync.RWMutex
	dataPath string
	limits   StoreLimits
	migrate  MigrationHook

	users     map[string]*auth.User          // userID -> User
	userBySub map[string]string              // googleSub -> userID
	sessions  map[string]*auth.SessionRecord // tokenHash -> SessionRecord
	playlists map[string]*playlistRecord     // playlistID -> record
}

// NewStore opens or initializes a collaborative state store at dataPath.
// Pass dataPath == "" or ":memory:" for pure in-memory operation.
func NewStore(dataPath string, limits StoreLimits) (*Store, error) {
	return NewStoreWithMigration(dataPath, limits, DefaultMigrationHook)
}

func NewStoreWithMigration(dataPath string, limits StoreLimits, migrate MigrationHook) (*Store, error) {
	if limits.MaxPlaylistsPerUser <= 0 {
		limits.MaxPlaylistsPerUser = 200
	}
	if limits.MaxTracksPerPlaylist <= 0 {
		limits.MaxTracksPerPlaylist = 2000
	}
	if limits.MaxMembersPerPlaylist <= 0 {
		limits.MaxMembersPerPlaylist = 50
	}
	if migrate == nil {
		migrate = DefaultMigrationHook
	}
	trimmedPath := strings.TrimSpace(dataPath)
	if trimmedPath == ":memory:" {
		trimmedPath = ""
	}

	s := &Store{
		dataPath:  trimmedPath,
		limits:    limits,
		migrate:   migrate,
		users:     make(map[string]*auth.User),
		userBySub: make(map[string]string),
		sessions:  make(map[string]*auth.SessionRecord),
		playlists: make(map[string]*playlistRecord),
	}

	if trimmedPath != "" {
		if err := s.loadFromDisk(); err != nil {
			return nil, err
		}
	}
	return s, nil
}

func (s *Store) loadFromDisk() error {
	raw, err := os.ReadFile(s.dataPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return fmt.Errorf("read collab store %q: %w", s.dataPath, err)
	}
	if len(strings.TrimSpace(string(raw))) == 0 {
		return nil
	}

	var probe struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(raw, &probe); err != nil {
		return fmt.Errorf("parse collab store header %q: %w", s.dataPath, err)
	}

	ver, migratedRaw, err := s.migrate(probe.Version, raw)
	if err != nil {
		return err
	}
	if ver != CurrentSchemaVersion {
		return fmt.Errorf("migration produced schema version %d, expected %d", ver, CurrentSchemaVersion)
	}

	var snap snapshotFile
	if err := json.Unmarshal(migratedRaw, &snap); err != nil {
		return fmt.Errorf("decode collab store %q: %w", s.dataPath, err)
	}

	for _, u := range snap.Users {
		if u != nil && u.ID != "" && u.GoogleSub != "" {
			cp := u.Clone()
			s.users[cp.ID] = cp
			s.userBySub[cp.GoogleSub] = cp.ID
		}
	}
	for _, ses := range snap.Sessions {
		if ses != nil && ses.TokenHash != "" && ses.UserID != "" {
			cp := ses.Clone()
			s.sessions[cp.TokenHash] = cp
		}
	}
	for _, rec := range snap.Playlists {
		if rec != nil && rec.Playlist != nil && rec.Playlist.ID != "" {
			if rec.Members == nil {
				rec.Members = []*CollabMember{}
			}
			if rec.Tracks == nil {
				rec.Tracks = []*CollabTrackEntry{}
			}
			if rec.Invites == nil {
				rec.Invites = []*CollabInvite{}
			}
			if rec.Activities == nil {
				rec.Activities = []*CollabActivity{}
			}
			normalizeTrackPositions(rec.Tracks)
			s.playlists[rec.Playlist.ID] = rec
		}
	}
	return nil
}

// persistLocked writes a complete atomic snapshot to disk via temp-file + fsync + os.Rename.
// Caller MUST hold s.mu.Lock().
func (s *Store) persistLocked(nowMs int64) error {
	if s.dataPath == "" {
		return nil
	}

	users := make([]*auth.User, 0, len(s.users))
	for _, u := range s.users {
		users = append(users, u)
	}
	sort.Slice(users, func(i, j int) bool { return users[i].ID < users[j].ID })

	sessions := make([]*auth.SessionRecord, 0, len(s.sessions))
	for _, ses := range s.sessions {
		// Prune long-expired sessions (>7 days past expiry) during compaction to bound snapshot size.
		if nowMs > 0 && ses.ExpiresAtMs > 0 && nowMs-ses.ExpiresAtMs > 7*24*60*60*1000 {
			continue
		}
		sessions = append(sessions, ses)
	}
	sort.Slice(sessions, func(i, j int) bool { return sessions[i].ID < sessions[j].ID })

	playlists := make([]*playlistRecord, 0, len(s.playlists))
	for _, rec := range s.playlists {
		playlists = append(playlists, rec)
	}
	sort.Slice(playlists, func(i, j int) bool { return playlists[i].Playlist.ID < playlists[j].Playlist.ID })

	snap := snapshotFile{
		Version:   CurrentSchemaVersion,
		SavedAtMs: nowMs,
		Users:     users,
		Sessions:  sessions,
		Playlists: playlists,
	}

	encoded, err := json.MarshalIndent(snap, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal collab snapshot: %w", err)
	}

	dir := filepath.Dir(s.dataPath)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("mkdir %q: %w", dir, err)
	}

	tmpFile, err := os.CreateTemp(dir, ".collab_tmp_*.json")
	if err != nil {
		return fmt.Errorf("create temp snapshot: %w", err)
	}
	tmpName := tmpFile.Name()
	cleanupTmp := true
	defer func() {
		if cleanupTmp {
			_ = os.Remove(tmpName)
		}
	}()

	_ = tmpFile.Chmod(0o600)
	if _, err := tmpFile.Write(encoded); err != nil {
		_ = tmpFile.Close()
		return fmt.Errorf("write temp snapshot: %w", err)
	}
	if err := tmpFile.Sync(); err != nil {
		_ = tmpFile.Close()
		return fmt.Errorf("fsync temp snapshot: %w", err)
	}
	if err := tmpFile.Close(); err != nil {
		return fmt.Errorf("close temp snapshot: %w", err)
	}
	if err := os.Rename(tmpName, s.dataPath); err != nil {
		return fmt.Errorf("rename snapshot to %q: %w", s.dataPath, err)
	}
	cleanupTmp = false

	// Best-effort fsync of parent directory so directory entry rename is durable.
	if dirFD, err := os.Open(dir); err == nil {
		_ = dirFD.Sync()
		_ = dirFD.Close()
	}
	return nil
}

// --- auth.SessionStore implementation ---

func (s *Store) UpsertGoogleUserAndCreateSession(identity auth.VerifiedIdentity, tokenHash string, nowMs int64, ttlMs int64) (*auth.User, *auth.SessionRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var user *auth.User
	if uid, exists := s.userBySub[identity.Sub]; exists {
		user = s.users[uid]
		user.Email = identity.Email
		user.EmailVerified = identity.EmailVerified
		user.DisplayName = identity.DisplayName
		user.AvatarURL = cloneStrPtr(identity.AvatarURL)
		user.UpdatedAtMs = nowMs

		// Keep member display names/avatars fresh across playlists this user belongs to.
		for _, rec := range s.playlists {
			for _, m := range rec.Members {
				if m.UserID == user.ID {
					m.DisplayName = user.DisplayName
					m.AvatarURL = cloneStrPtr(user.AvatarURL)
				}
			}
		}
	} else {
		uid, err := auth.NewUserID()
		if err != nil {
			return nil, nil, err
		}
		user = &auth.User{
			ID:            uid,
			GoogleSub:     identity.Sub,
			Email:         identity.Email,
			EmailVerified: identity.EmailVerified,
			DisplayName:   identity.DisplayName,
			AvatarURL:     cloneStrPtr(identity.AvatarURL),
			CreatedAtMs:   nowMs,
			UpdatedAtMs:   nowMs,
		}
		s.users[uid] = user
		s.userBySub[identity.Sub] = uid
	}

	sid, err := auth.NewSessionID()
	if err != nil {
		return nil, nil, err
	}
	session := &auth.SessionRecord{
		ID:           sid,
		TokenHash:    tokenHash,
		UserID:       user.ID,
		CreatedAtMs:  nowMs,
		ExpiresAtMs:  nowMs + ttlMs,
		LastSeenAtMs: nowMs,
	}
	s.sessions[tokenHash] = session

	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, err
	}
	return user.Clone(), session.Clone(), nil
}

func (s *Store) AuthenticateSession(tokenHash string, nowMs int64) (*auth.User, *auth.SessionRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.sessions[tokenHash]
	if !ok || !auth.ConstantTimeHashEqual(rec.TokenHash, tokenHash) {
		return nil, nil, auth.NewAuthError(http.StatusUnauthorized, "auth_required", "Invalid or unknown session token.")
	}
	if rec.RevokedAtMs != nil {
		return nil, nil, auth.NewAuthError(http.StatusUnauthorized, "session_revoked", "Session has been signed out.")
	}
	if nowMs >= rec.ExpiresAtMs {
		return nil, nil, auth.NewAuthError(http.StatusUnauthorized, "session_expired", "Session has expired. Please sign in with Google again.")
	}
	user := s.users[rec.UserID]
	if user == nil {
		return nil, nil, auth.NewAuthError(http.StatusUnauthorized, "auth_required", "Account not found.")
	}
	rec.LastSeenAtMs = nowMs
	return user.Clone(), rec.Clone(), nil
}

func (s *Store) RevokeSession(tokenHash string, nowMs int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.sessions[tokenHash]
	if !ok || !auth.ConstantTimeHashEqual(rec.TokenHash, tokenHash) {
		return auth.NewAuthError(http.StatusUnauthorized, "auth_required", "Invalid or unknown session token.")
	}
	if rec.RevokedAtMs == nil {
		rev := nowMs
		rec.RevokedAtMs = &rev
		if err := s.persistLocked(nowMs); err != nil {
			return err
		}
	}
	return nil
}

// --- Internal helpers (caller holds s.mu) ---

func normalizeTrackPositions(tracks []*CollabTrackEntry) {
	for i, tr := range tracks {
		tr.Position = i
	}
}

func findMemberLocked(rec *playlistRecord, userID string) *CollabMember {
	for _, m := range rec.Members {
		if m.UserID == userID {
			return m
		}
	}
	return nil
}

func hasAppliedOpLocked(rec *playlistRecord, userID, opID string) bool {
	if opID == "" {
		return false
	}
	for _, op := range rec.AppliedOps {
		if op.UserID == userID && op.OpID == opID {
			return true
		}
	}
	return false
}

func recordAppliedOpLocked(rec *playlistRecord, userID, opID string, nowMs int64) {
	if opID == "" {
		return
	}
	rec.AppliedOps = append(rec.AppliedOps, AppliedOp{
		UserID:    userID,
		OpID:      opID,
		AppliedMs: nowMs,
	})
	if len(rec.AppliedOps) > MaxOpHistoryPerPlaylist {
		excess := len(rec.AppliedOps) - MaxOpHistoryPerPlaylist
		trimmed := make([]AppliedOp, MaxOpHistoryPerPlaylist)
		copy(trimmed, rec.AppliedOps[excess:])
		rec.AppliedOps = trimmed
	}
}

func appendActivityLocked(rec *playlistRecord, actor *auth.User, action, summary string, nowMs int64) *CollabActivity {
	actID, _ := NewActivityID()
	if actID == "" {
		actID = fmt.Sprintf("act_%d", nowMs)
	}
	act := &CollabActivity{
		ID:          actID,
		PlaylistID:  rec.Playlist.ID,
		ActorUserID: actor.ID,
		ActorName:   actor.DisplayName,
		ActorAvatar: cloneStrPtr(actor.AvatarURL),
		Action:      action,
		Summary:     summary,
		CreatedAtMs: nowMs,
	}
	rec.Activities = append(rec.Activities, act)
	if len(rec.Activities) > MaxActivitiesPerPlaylist {
		excess := len(rec.Activities) - MaxActivitiesPerPlaylist
		trimmed := make([]*CollabActivity, MaxActivitiesPerPlaylist)
		copy(trimmed, rec.Activities[excess:])
		rec.Activities = trimmed
	}
	return act.Clone()
}

func latestActivityCloneLocked(rec *playlistRecord) *CollabActivity {
	if len(rec.Activities) == 0 {
		return nil
	}
	return rec.Activities[len(rec.Activities)-1].Clone()
}

func (s *Store) buildSummaryLocked(rec *playlistRecord, callerID string) PlaylistSummary {
	ownerName := "BitChord Listener"
	var ownerAvatar *string
	if owner, ok := s.users[rec.Playlist.OwnerUserID]; ok {
		ownerName = owner.DisplayName
		ownerAvatar = cloneStrPtr(owner.AvatarURL)
	} else {
		for _, m := range rec.Members {
			if m.Role == RoleOwner {
				ownerName = m.DisplayName
				ownerAvatar = cloneStrPtr(m.AvatarURL)
				break
			}
		}
	}
	myRole := ""
	if callerID != "" {
		if m := findMemberLocked(rec, callerID); m != nil {
			myRole = m.Role
		}
	}
	return PlaylistSummary{
		ID:               rec.Playlist.ID,
		Title:            rec.Playlist.Title,
		Description:      rec.Playlist.Description,
		ArtworkURL:       cloneStrPtr(rec.Playlist.ArtworkURL),
		OwnerUserID:      rec.Playlist.OwnerUserID,
		OwnerName:        ownerName,
		OwnerAvatarURL:   ownerAvatar,
		MyRole:           myRole,
		SharingEnabled:   rec.Playlist.SharingEnabled,
		Permissions:      rec.Playlist.Permissions,
		SourceType:       cloneStrPtr(rec.Playlist.SourceType),
		SourcePlaylistID: cloneStrPtr(rec.Playlist.SourcePlaylistID),
		MemberCount:      len(rec.Members),
		TrackCount:       len(rec.Tracks),
		Revision:         rec.Playlist.Revision,
		CreatedAtMs:      rec.Playlist.CreatedAtMs,
		UpdatedAtMs:      rec.Playlist.UpdatedAtMs,
	}
}

func (s *Store) buildDetailLocked(rec *playlistRecord, callerID string) *PlaylistDetail {
	members := make([]*CollabMember, len(rec.Members))
	for i, m := range rec.Members {
		members[i] = m.Clone()
	}
	tracks := make([]*CollabTrackEntry, len(rec.Tracks))
	for i, t := range rec.Tracks {
		tracks[i] = t.Clone()
	}
	activities := make([]*CollabActivity, len(rec.Activities))
	for i, a := range rec.Activities {
		activities[i] = a.Clone()
	}
	return &PlaylistDetail{
		PlaylistSummary: s.buildSummaryLocked(rec, callerID),
		Members:         members,
		Tracks:          tracks,
		Activities:      activities,
	}
}

func (s *Store) countOwnedPlaylistsLocked(userID string) int {
	count := 0
	for _, rec := range s.playlists {
		if rec.Playlist.OwnerUserID == userID && rec.Playlist.SharingEnabled {
			count++
		}
	}
	return count
}

// Constant-time lookup across all stored invite tokens using SHA-256 digest comparison.
func (s *Store) findInviteByTokenLocked(token string) (*playlistRecord, *CollabInvite) {
	if !strings.HasPrefix(token, InviteTokenPrefix) || len(token) > 128 {
		return nil, nil
	}
	targetSum := sha256.Sum256([]byte(token))
	var matchedRec *playlistRecord
	var matchedInv *CollabInvite

	for _, rec := range s.playlists {
		for _, inv := range rec.Invites {
			candSum := sha256.Sum256([]byte(inv.Token))
			if subtle.ConstantTimeCompare(candSum[:], targetSum[:]) == 1 {
				matchedRec = rec
				matchedInv = inv
			}
		}
	}
	return matchedRec, matchedInv
}

// --- Public Collaborative Playlist Domain Methods ---

// ListPlaylists returns summaries of collaborative playlists where callerID is a member.
func (s *Store) ListPlaylists(callerID string, includeStopped bool) []*PlaylistSummary {
	s.mu.RLock()
	defer s.mu.RUnlock()

	out := make([]*PlaylistSummary, 0)
	for _, rec := range s.playlists {
		m := findMemberLocked(rec, callerID)
		if m == nil {
			continue
		}
		if !rec.Playlist.SharingEnabled && (!includeStopped || rec.Playlist.OwnerUserID != callerID) {
			continue
		}
		sum := s.buildSummaryLocked(rec, callerID)
		out = append(out, &sum)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].UpdatedAtMs != out[j].UpdatedAtMs {
			return out[i].UpdatedAtMs > out[j].UpdatedAtMs
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// GetPlaylistDetail returns full playlist detail if callerID is an authorized member.
func (s *Store) GetPlaylistDetail(callerID, playlistID string) (*PlaylistDetail, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, callerID)
	if member == nil || (!rec.Playlist.SharingEnabled && rec.Playlist.OwnerUserID != callerID) {
		return nil, NewError(http.StatusForbidden, "forbidden", "You do not have access to this collaborative playlist.")
	}
	return s.buildDetailLocked(rec, callerID), nil
}

// CheckSubscriptionAccess verifies WebSocket subscription eligibility and returns the current snapshot.
func (s *Store) CheckSubscriptionAccess(userID, playlistID string) (*PlaylistDetail, error) {
	return s.GetPlaylistDetail(userID, playlistID)
}

type CreatePlaylistParams struct {
	Title       string
	Description string
	ArtworkURL  *string
	Permissions *PermissionsPatch
	Tracks      []TrackInput
}

func (s *Store) CreatePlaylist(caller *auth.User, req CreatePlaylistParams, nowMs int64) (*PlaylistDetail, error) {
	title, err := ValidateTitle(req.Title)
	if err != nil {
		return nil, err
	}
	desc, err := ValidateDescription(req.Description)
	if err != nil {
		return nil, err
	}
	artworkURL, err := sanitizeOptionalHTTPURL(req.ArtworkURL)
	if err != nil {
		return nil, err
	}
	perms, err := ApplyPermissionsPatch(DefaultPermissions(), req.Permissions)
	if err != nil {
		return nil, err
	}
	if len(req.Tracks) > s.limits.MaxTracksPerPlaylist {
		return nil, NewError(http.StatusUnprocessableEntity, "playlist_too_large", fmt.Sprintf("A collaborative playlist can hold at most %d tracks.", s.limits.MaxTracksPerPlaylist))
	}

	normalizedTracks := make([]TrackInput, len(req.Tracks))
	for i, t := range req.Tracks {
		nt, err := t.ValidateAndNormalize()
		if err != nil {
			return nil, err
		}
		normalizedTracks[i] = nt
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if s.countOwnedPlaylistsLocked(caller.ID) >= s.limits.MaxPlaylistsPerUser {
		return nil, NewError(http.StatusConflict, "max_playlists_reached", fmt.Sprintf("You can own at most %d active collaborative playlists.", s.limits.MaxPlaylistsPerUser))
	}

	pid, err := NewPlaylistID()
	if err != nil {
		return nil, NewError(http.StatusInternalServerError, "server_error", "Failed to generate playlist ID.")
	}

	entries := make([]*CollabTrackEntry, len(normalizedTracks))
	for i, nt := range normalizedTracks {
		eid, err := NewEntryID()
		if err != nil {
			return nil, NewError(http.StatusInternalServerError, "server_error", "Failed to generate entry ID.")
		}
		cid := caller.ID
		cname := caller.DisplayName
		entries[i] = &CollabTrackEntry{
			EntryID:              eid,
			PlaylistID:           pid,
			Position:             i,
			TrackID:              nt.TrackID,
			Provider:             nt.Provider,
			Title:                nt.Title,
			Artist:               nt.Artist,
			AlbumName:            nt.AlbumName,
			ThumbnailURL:         nt.ThumbnailURL,
			DurationMs:           nt.DurationMs,
			IsExplicit:           nt.IsExplicit,
			ContributorID:        &cid,
			ContributorName:      &cname,
			ContributorAvatarURL: cloneStrPtr(caller.AvatarURL),
			AddedAtMs:            nowMs,
		}
	}

	rec := &playlistRecord{
		Playlist: &CollabPlaylist{
			ID:             pid,
			Title:          title,
			Description:    desc,
			ArtworkURL:     artworkURL,
			OwnerUserID:    caller.ID,
			SharingEnabled: true,
			Permissions:    perms,
			Revision:       1,
			CreatedAtMs:    nowMs,
			UpdatedAtMs:    nowMs,
		},
		Members: []*CollabMember{
			{
				PlaylistID:  pid,
				UserID:      caller.ID,
				Role:        RoleOwner,
				DisplayName: caller.DisplayName,
				AvatarURL:   cloneStrPtr(caller.AvatarURL),
				JoinedAtMs:  nowMs,
			},
		},
		Tracks:     entries,
		Invites:    []*CollabInvite{},
		Activities: []*CollabActivity{},
	}
	appendActivityLocked(rec, caller, "playlist.created", fmt.Sprintf("%s created collaborative playlist %q.", caller.DisplayName, title), nowMs)
	s.playlists[pid] = rec

	if err := s.persistLocked(nowMs); err != nil {
		delete(s.playlists, pid)
		return nil, NewError(http.StatusInternalServerError, "server_error", "Failed to save playlist.")
	}
	return s.buildDetailLocked(rec, caller.ID), nil
}

type ConvertPlaylistParams struct {
	SourcePlaylistID               string
	SourceType                     string
	Title                          string
	Description                    string
	ArtworkURL                     *string
	AttributeExistingTracksToOwner bool
	Permissions                    *PermissionsPatch
	Tracks                         []TrackInput
}

// ConvertPersonalPlaylist transactionally & idempotently converts an existing personal playlist
// into a collaborative playlist without ever duplicating an active conversion.
func (s *Store) ConvertPersonalPlaylist(caller *auth.User, req ConvertPlaylistParams, nowMs int64) (*PlaylistDetail, bool, error) {
	sourceID := strings.TrimSpace(req.SourcePlaylistID)
	if sourceID == "" || len(sourceID) > 256 {
		return nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "sourcePlaylistId must be between 1 and 256 characters.")
	}
	sourceType := strings.ToLower(strings.TrimSpace(req.SourceType))
	if !validSourceTypes[sourceType] {
		return nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "sourceType must be ytm, local, desktop, or spotify.")
	}
	title, err := ValidateTitle(req.Title)
	if err != nil {
		return nil, false, err
	}
	desc, err := ValidateDescription(req.Description)
	if err != nil {
		return nil, false, err
	}
	artworkURL, err := sanitizeOptionalHTTPURL(req.ArtworkURL)
	if err != nil {
		return nil, false, err
	}
	perms, err := ApplyPermissionsPatch(DefaultPermissions(), req.Permissions)
	if err != nil {
		return nil, false, err
	}
	if len(req.Tracks) > s.limits.MaxTracksPerPlaylist {
		return nil, false, NewError(http.StatusUnprocessableEntity, "playlist_too_large", fmt.Sprintf("A collaborative playlist can hold at most %d tracks.", s.limits.MaxTracksPerPlaylist))
	}

	normalizedTracks := make([]TrackInput, len(req.Tracks))
	for i, t := range req.Tracks {
		nt, err := t.ValidateAndNormalize()
		if err != nil {
			return nil, false, err
		}
		normalizedTracks[i] = nt
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	// Anti-duplication idempotency check: active (sharingEnabled == true) playlist with same (ownerUserId, sourceType, sourcePlaylistId).
	for _, rec := range s.playlists {
		p := rec.Playlist
		if p.SharingEnabled && p.OwnerUserID == caller.ID &&
			p.SourceType != nil && *p.SourceType == sourceType &&
			p.SourcePlaylistID != nil && *p.SourcePlaylistID == sourceID {
			return s.buildDetailLocked(rec, caller.ID), true, nil
		}
	}

	if s.countOwnedPlaylistsLocked(caller.ID) >= s.limits.MaxPlaylistsPerUser {
		return nil, false, NewError(http.StatusConflict, "max_playlists_reached", fmt.Sprintf("You can own at most %d active collaborative playlists.", s.limits.MaxPlaylistsPerUser))
	}

	pid, err := NewPlaylistID()
	if err != nil {
		return nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to generate playlist ID.")
	}

	entries := make([]*CollabTrackEntry, len(normalizedTracks))
	for i, nt := range normalizedTracks {
		eid, err := NewEntryID()
		if err != nil {
			return nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to generate entry ID.")
		}
		var contribID *string
		var contribName *string
		var contribAvatar *string
		if req.AttributeExistingTracksToOwner {
			cid := caller.ID
			cname := caller.DisplayName
			contribID = &cid
			contribName = &cname
			contribAvatar = cloneStrPtr(caller.AvatarURL)
		}
		entries[i] = &CollabTrackEntry{
			EntryID:              eid,
			PlaylistID:           pid,
			Position:             i,
			TrackID:              nt.TrackID,
			Provider:             nt.Provider,
			Title:                nt.Title,
			Artist:               nt.Artist,
			AlbumName:            nt.AlbumName,
			ThumbnailURL:         nt.ThumbnailURL,
			DurationMs:           nt.DurationMs,
			IsExplicit:           nt.IsExplicit,
			ContributorID:        contribID,
			ContributorName:      contribName,
			ContributorAvatarURL: contribAvatar,
			AddedAtMs:            nowMs,
		}
	}

	stCopy := sourceType
	sidCopy := sourceID
	rec := &playlistRecord{
		Playlist: &CollabPlaylist{
			ID:               pid,
			Title:            title,
			Description:      desc,
			ArtworkURL:       artworkURL,
			OwnerUserID:      caller.ID,
			SharingEnabled:   true,
			Permissions:      perms,
			SourceType:       &stCopy,
			SourcePlaylistID: &sidCopy,
			Revision:         1,
			CreatedAtMs:      nowMs,
			UpdatedAtMs:      nowMs,
		},
		Members: []*CollabMember{
			{
				PlaylistID:  pid,
				UserID:      caller.ID,
				Role:        RoleOwner,
				DisplayName: caller.DisplayName,
				AvatarURL:   cloneStrPtr(caller.AvatarURL),
				JoinedAtMs:  nowMs,
			},
		},
		Tracks:     entries,
		Invites:    []*CollabInvite{},
		Activities: []*CollabActivity{},
	}
	appendActivityLocked(rec, caller, "playlist.converted", fmt.Sprintf("%s shared %q as a collaborative playlist.", caller.DisplayName, title), nowMs)
	s.playlists[pid] = rec

	if err := s.persistLocked(nowMs); err != nil {
		delete(s.playlists, pid)
		return nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to persist converted playlist.")
	}
	return s.buildDetailLocked(rec, caller.ID), false, nil
}

type UpdatePlaylistParams struct {
	Title       *string
	Description *string
	ArtworkURL  *string
	HasArtwork  bool // true when JSON explicitly included "artworkUrl" (even if null/empty to clear)
	Permissions *PermissionsPatch
	OpID        string
}

func (s *Store) UpdatePlaylistMetadata(caller *auth.User, playlistID string, req UpdatePlaylistParams, nowMs int64) (*PlaylistDetail, *CollabActivity, bool, error) {
	opID, err := ValidateOpID(req.OpID)
	if err != nil {
		return nil, nil, false, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, false, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil || member.Role != RoleOwner {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can edit playlist metadata or permissions.")
	}
	if !rec.Playlist.SharingEnabled {
		return nil, nil, false, NewError(http.StatusConflict, "sharing_stopped", "Sharing has been stopped for this playlist.")
	}

	if hasAppliedOpLocked(rec, caller.ID, opID) {
		return s.buildDetailLocked(rec, caller.ID), latestActivityCloneLocked(rec), true, nil
	}

	nextTitle := rec.Playlist.Title
	if req.Title != nil {
		nextTitle, err = ValidateTitle(*req.Title)
		if err != nil {
			return nil, nil, false, err
		}
	}
	nextDesc := rec.Playlist.Description
	if req.Description != nil {
		nextDesc, err = ValidateDescription(*req.Description)
		if err != nil {
			return nil, nil, false, err
		}
	}
	nextArtwork := rec.Playlist.ArtworkURL
	if req.HasArtwork {
		nextArtwork, err = sanitizeOptionalHTTPURL(req.ArtworkURL)
		if err != nil {
			return nil, nil, false, err
		}
	}
	nextPerms, err := ApplyPermissionsPatch(rec.Playlist.Permissions, req.Permissions)
	if err != nil {
		return nil, nil, false, err
	}

	rec.Playlist.Title = nextTitle
	rec.Playlist.Description = nextDesc
	rec.Playlist.ArtworkURL = nextArtwork
	rec.Playlist.Permissions = nextPerms
	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs
	recordAppliedOpLocked(rec, caller.ID, opID, nowMs)

	act := appendActivityLocked(rec, caller, "playlist.updated", fmt.Sprintf("%s updated playlist settings for %q.", caller.DisplayName, nextTitle), nowMs)
	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to save playlist changes.")
	}
	return s.buildDetailLocked(rec, caller.ID), act, false, nil
}

// StopSharing reverses sharing ("Make private"): revokes all active invites, removes non-owner members,
// sets sharingEnabled = false, and returns the complete preserved snapshot + removed collaborator userIds.
func (s *Store) StopSharing(caller *auth.User, playlistID string, nowMs int64) (*PlaylistDetail, []string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil || member.Role != RoleOwner {
		return nil, nil, NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can stop sharing.")
	}
	if !rec.Playlist.SharingEnabled {
		return s.buildDetailLocked(rec, caller.ID), nil, nil
	}

	// 1. Revoke all active invitations immediately.
	for _, inv := range rec.Invites {
		if !inv.Revoked {
			inv.Revoked = true
			rev := nowMs
			inv.RevokedAtMs = &rev
		}
	}

	// 2. Remove all non-owner members while collecting their userIds for WebSocket notification.
	removedUserIDs := make([]string, 0, len(rec.Members))
	keptMembers := make([]*CollabMember, 0, 1)
	for _, m := range rec.Members {
		if m.Role == RoleOwner {
			keptMembers = append(keptMembers, m)
		} else {
			removedUserIDs = append(removedUserIDs, m.UserID)
		}
	}
	rec.Members = keptMembers

	// 3. Mark sharingEnabled = false while preserving all tracks & historical contributor attribution.
	rec.Playlist.SharingEnabled = false
	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs
	appendActivityLocked(rec, caller, "playlist.stopped_sharing", fmt.Sprintf("%s stopped sharing %q.", caller.DisplayName, rec.Playlist.Title), nowMs)

	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, NewError(http.StatusInternalServerError, "server_error", "Failed to save stop-sharing state.")
	}
	return s.buildDetailLocked(rec, caller.ID), removedUserIDs, nil
}

// DeletePlaylist permanently deletes a collaborative playlist (OWNER only).
func (s *Store) DeletePlaylist(caller *auth.User, playlistID string, nowMs int64) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil || member.Role != RoleOwner {
		return nil, NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can delete this playlist.")
	}

	notifyUsers := make([]string, 0, len(rec.Members))
	for _, m := range rec.Members {
		if m.UserID != caller.ID {
			notifyUsers = append(notifyUsers, m.UserID)
		}
	}
	delete(s.playlists, playlistID)
	if err := s.persistLocked(nowMs); err != nil {
		s.playlists[playlistID] = rec
		return nil, NewError(http.StatusInternalServerError, "server_error", "Failed to persist playlist deletion.")
	}
	return notifyUsers, nil
}

type AddTracksParams struct {
	Tracks   []TrackInput
	Position *int
	OpID     string
}

func (s *Store) AddTracks(caller *auth.User, playlistID string, req AddTracksParams, nowMs int64) (*PlaylistDetail, []*CollabTrackEntry, *CollabActivity, bool, error) {
	opID, err := ValidateOpID(req.OpID)
	if err != nil {
		return nil, nil, nil, false, err
	}
	if len(req.Tracks) == 0 {
		return nil, nil, nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "At least one track is required.")
	}

	normalized := make([]TrackInput, len(req.Tracks))
	for i, t := range req.Tracks {
		nt, err := t.ValidateAndNormalize()
		if err != nil {
			return nil, nil, nil, false, err
		}
		normalized[i] = nt
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, nil, false, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil || !rec.Playlist.SharingEnabled {
		if member != nil && !rec.Playlist.SharingEnabled {
			return nil, nil, nil, false, NewError(http.StatusConflict, "sharing_stopped", "Sharing has been stopped for this playlist.")
		}
		return nil, nil, nil, false, NewError(http.StatusForbidden, "forbidden", "You are not a member of this playlist.")
	}
	if member.Role != RoleOwner && member.Role != RoleEditor {
		return nil, nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Viewers cannot add tracks to this playlist.")
	}

	if hasAppliedOpLocked(rec, caller.ID, opID) {
		return s.buildDetailLocked(rec, caller.ID), nil, latestActivityCloneLocked(rec), true, nil
	}

	if len(rec.Tracks)+len(normalized) > s.limits.MaxTracksPerPlaylist {
		return nil, nil, nil, false, NewError(http.StatusConflict, "playlist_full", fmt.Sprintf("Playlist track limit (%d) reached.", s.limits.MaxTracksPerPlaylist))
	}

	added := make([]*CollabTrackEntry, len(normalized))
	addedClones := make([]*CollabTrackEntry, len(normalized))
	for i, nt := range normalized {
		eid, err := NewEntryID()
		if err != nil {
			return nil, nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to generate entry ID.")
		}
		// CRITICAL: Always stamp contributor identity from the verified caller's server-side User record.
		cid := caller.ID
		cname := caller.DisplayName
		entry := &CollabTrackEntry{
			EntryID:              eid,
			PlaylistID:           playlistID,
			TrackID:              nt.TrackID,
			Provider:             nt.Provider,
			Title:                nt.Title,
			Artist:               nt.Artist,
			AlbumName:            nt.AlbumName,
			ThumbnailURL:         nt.ThumbnailURL,
			DurationMs:           nt.DurationMs,
			IsExplicit:           nt.IsExplicit,
			ContributorID:        &cid,
			ContributorName:      &cname,
			ContributorAvatarURL: cloneStrPtr(caller.AvatarURL),
			AddedAtMs:            nowMs,
		}
		added[i] = entry
	}

	insertPos := len(rec.Tracks)
	if req.Position != nil {
		insertPos = *req.Position
		if insertPos < 0 {
			insertPos = 0
		} else if insertPos > len(rec.Tracks) {
			insertPos = len(rec.Tracks)
		}
	}

	merged := make([]*CollabTrackEntry, 0, len(rec.Tracks)+len(added))
	merged = append(merged, rec.Tracks[:insertPos]...)
	merged = append(merged, added...)
	merged = append(merged, rec.Tracks[insertPos:]...)
	normalizeTrackPositions(merged)
	rec.Tracks = merged

	for i, a := range added {
		addedClones[i] = a.Clone()
	}

	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs
	recordAppliedOpLocked(rec, caller.ID, opID, nowMs)

	var summary string
	if len(added) == 1 {
		summary = fmt.Sprintf("%s added %q.", caller.DisplayName, added[0].Title)
	} else {
		summary = fmt.Sprintf("%s added %q and %d more tracks.", caller.DisplayName, added[0].Title, len(added)-1)
	}
	act := appendActivityLocked(rec, caller, "tracks.added", summary, nowMs)

	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to persist added tracks.")
	}
	return s.buildDetailLocked(rec, caller.ID), addedClones, act, false, nil
}

func (s *Store) RemoveTrack(caller *auth.User, playlistID, entryID, rawOpID string, nowMs int64) (*PlaylistDetail, *CollabActivity, bool, error) {
	opID, err := ValidateOpID(rawOpID)
	if err != nil {
		return nil, nil, false, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, false, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "You are not a member of this playlist.")
	}
	if !rec.Playlist.SharingEnabled {
		return nil, nil, false, NewError(http.StatusConflict, "sharing_stopped", "Sharing has been stopped for this playlist.")
	}
	if member.Role != RoleOwner && member.Role != RoleEditor {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Viewers cannot remove tracks from this playlist.")
	}

	if hasAppliedOpLocked(rec, caller.ID, opID) {
		return s.buildDetailLocked(rec, caller.ID), latestActivityCloneLocked(rec), true, nil
	}

	targetIdx := -1
	for i, tr := range rec.Tracks {
		if tr.EntryID == entryID {
			targetIdx = i
			break
		}
	}
	if targetIdx < 0 {
		return nil, nil, false, NewError(http.StatusNotFound, "track_not_found", "Track entry not found in this playlist.")
	}

	target := rec.Tracks[targetIdx]
	if member.Role == RoleEditor && rec.Playlist.Permissions.EditorCanRemove == EditorRemoveOwn {
		if target.ContributorID == nil || *target.ContributorID != caller.ID {
			return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Editors can only remove songs they added themselves.")
		}
	}

	removedTitle := target.Title
	rec.Tracks = append(rec.Tracks[:targetIdx], rec.Tracks[targetIdx+1:]...)
	normalizeTrackPositions(rec.Tracks)

	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs
	recordAppliedOpLocked(rec, caller.ID, opID, nowMs)
	act := appendActivityLocked(rec, caller, "tracks.removed", fmt.Sprintf("%s removed %q.", caller.DisplayName, removedTitle), nowMs)

	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to persist track removal.")
	}
	return s.buildDetailLocked(rec, caller.ID), act, false, nil
}

type ReorderTracksParams struct {
	EntryIDs  []string
	FromIndex *int
	ToIndex   *int
	EntryID   string
	OpID      string
}

func (s *Store) ReorderTracks(caller *auth.User, playlistID string, req ReorderTracksParams, nowMs int64) (*PlaylistDetail, *CollabActivity, bool, error) {
	opID, err := ValidateOpID(req.OpID)
	if err != nil {
		return nil, nil, false, err
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, false, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "You are not a member of this playlist.")
	}
	if !rec.Playlist.SharingEnabled {
		return nil, nil, false, NewError(http.StatusConflict, "sharing_stopped", "Sharing has been stopped for this playlist.")
	}
	if member.Role == RoleViewer {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Viewers cannot reorder tracks.")
	}
	if member.Role == RoleEditor && !rec.Playlist.Permissions.EditorCanReorder {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Reordering tracks by editors is disabled for this playlist.")
	}

	if hasAppliedOpLocked(rec, caller.ID, opID) {
		return s.buildDetailLocked(rec, caller.ID), latestActivityCloneLocked(rec), true, nil
	}

	var reordered []*CollabTrackEntry
	if len(req.EntryIDs) > 0 {
		if len(req.EntryIDs) != len(rec.Tracks) {
			return nil, nil, false, NewError(http.StatusConflict, "order_mismatch", "entryIds length does not match current playlist track count.")
		}
		byID := make(map[string]*CollabTrackEntry, len(rec.Tracks))
		for _, tr := range rec.Tracks {
			byID[tr.EntryID] = tr
		}
		seen := make(map[string]bool, len(req.EntryIDs))
		reordered = make([]*CollabTrackEntry, len(req.EntryIDs))
		for i, eid := range req.EntryIDs {
			if seen[eid] {
				return nil, nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "entryIds must not contain duplicates.")
			}
			tr, exists := byID[eid]
			if !exists {
				return nil, nil, false, NewError(http.StatusConflict, "order_mismatch", "entryIds contains an unknown or stale track entryId.")
			}
			seen[eid] = true
			reordered[i] = tr
		}
	} else if req.FromIndex != nil && req.ToIndex != nil {
		from := *req.FromIndex
		to := *req.ToIndex
		n := len(rec.Tracks)
		if from < 0 || from >= n || to < 0 || to >= n {
			return nil, nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "fromIndex and toIndex must be valid track positions.")
		}
		if req.EntryID != "" && rec.Tracks[from].EntryID != req.EntryID {
			return nil, nil, false, NewError(http.StatusConflict, "order_mismatch", "Track at fromIndex no longer matches entryId.")
		}
		reordered = make([]*CollabTrackEntry, 0, n)
		moving := rec.Tracks[from]
		for i, tr := range rec.Tracks {
			if i != from {
				reordered = append(reordered, tr)
			}
		}
		withMoved := make([]*CollabTrackEntry, 0, n)
		withMoved = append(withMoved, reordered[:to]...)
		withMoved = append(withMoved, moving)
		withMoved = append(withMoved, reordered[to:]...)
		reordered = withMoved
	} else {
		return nil, nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "Provide either entryIds or both fromIndex and toIndex.")
	}

	normalizeTrackPositions(reordered)
	rec.Tracks = reordered
	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs
	recordAppliedOpLocked(rec, caller.ID, opID, nowMs)
	act := appendActivityLocked(rec, caller, "tracks.reordered", fmt.Sprintf("%s reordered tracks.", caller.DisplayName), nowMs)

	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to persist track order.")
	}
	return s.buildDetailLocked(rec, caller.ID), act, false, nil
}

// --- Invitations & Membership ---

type CreateInviteParams struct {
	Role         string
	ExpiresInSec *int64 // nil => default 7 days; 0 => never; >0 => seconds
	MaxUses      int    // 0 => unlimited
}

func (s *Store) CreateInvite(caller *auth.User, playlistID string, req CreateInviteParams, origin string, nowMs int64) (*InviteView, *CollabActivity, error) {
	role := strings.ToUpper(strings.TrimSpace(req.Role))
	if role == "" {
		role = RoleEditor
	}
	if role != RoleEditor && role != RoleViewer {
		return nil, nil, NewError(http.StatusUnprocessableEntity, "validation_error", "Invite role must be EDITOR or VIEWER.")
	}
	if req.MaxUses < 0 || req.MaxUses > 100000 {
		return nil, nil, NewError(http.StatusUnprocessableEntity, "validation_error", "maxUses must be >= 0.")
	}

	var expiresAtMs *int64
	if req.ExpiresInSec == nil {
		exp := nowMs + 7*24*3600*1000
		expiresAtMs = &exp
	} else if *req.ExpiresInSec < 0 {
		return nil, nil, NewError(http.StatusUnprocessableEntity, "validation_error", "expiresInSec must be >= 0.")
	} else if *req.ExpiresInSec > 0 {
		exp := nowMs + (*req.ExpiresInSec)*1000
		expiresAtMs = &exp
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil {
		return nil, nil, NewError(http.StatusForbidden, "forbidden", "You are not a member of this playlist.")
	}
	if !rec.Playlist.SharingEnabled {
		return nil, nil, NewError(http.StatusConflict, "sharing_stopped", "Sharing has been stopped for this playlist.")
	}
	if member.Role != RoleOwner && !(member.Role == RoleEditor && rec.Playlist.Permissions.EditorCanInvite) {
		return nil, nil, NewError(http.StatusForbidden, "forbidden", "You do not have permission to create invite links for this playlist.")
	}

	invID, err := NewInviteID()
	if err != nil {
		return nil, nil, NewError(http.StatusInternalServerError, "server_error", "Failed to generate invite ID.")
	}
	tok, err := NewInviteToken()
	if err != nil {
		return nil, nil, NewError(http.StatusInternalServerError, "server_error", "Failed to generate invite token.")
	}

	inv := &CollabInvite{
		InviteID:    invID,
		PlaylistID:  playlistID,
		Token:       tok,
		Role:        role,
		CreatedByID: caller.ID,
		CreatedAtMs: nowMs,
		ExpiresAtMs: expiresAtMs,
		MaxUses:     req.MaxUses,
		UseCount:    0,
		Revoked:     false,
	}
	rec.Invites = append(rec.Invites, inv)
	act := appendActivityLocked(rec, caller, "invite.created", fmt.Sprintf("%s created a %s invite link.", caller.DisplayName, strings.ToLower(role)), nowMs)

	if err := s.persistLocked(nowMs); err != nil {
		rec.Invites = rec.Invites[:len(rec.Invites)-1]
		return nil, nil, NewError(http.StatusInternalServerError, "server_error", "Failed to persist invite.")
	}
	return toInviteView(inv, origin), act, nil
}

func toInviteView(inv *CollabInvite, origin string) *InviteView {
	base := strings.TrimRight(origin, "/")
	return &InviteView{
		InviteID:    inv.InviteID,
		PlaylistID:  inv.PlaylistID,
		Token:       inv.Token,
		Role:        inv.Role,
		ExpiresAtMs: cloneInt64Ptr(inv.ExpiresAtMs),
		MaxUses:     inv.MaxUses,
		UseCount:    inv.UseCount,
		CreatedAtMs: inv.CreatedAtMs,
		InviteURL:   base + "/collab/invite/" + inv.Token,
	}
}

func (s *Store) ListInvites(caller *auth.User, playlistID, origin string, nowMs int64) ([]*InviteView, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil || member.Role != RoleOwner {
		return nil, NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can list active invites.")
	}

	views := make([]*InviteView, 0)
	for _, inv := range rec.Invites {
		if inv.Revoked || inv.IsExpired(nowMs) {
			continue
		}
		views = append(views, toInviteView(inv, origin))
	}
	return views, nil
}

func (s *Store) RevokeInvite(caller *auth.User, playlistID, inviteID string, nowMs int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	member := findMemberLocked(rec, caller.ID)
	if member == nil || member.Role != RoleOwner {
		return NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can revoke invites.")
	}

	var target *CollabInvite
	for _, inv := range rec.Invites {
		if inv.InviteID == inviteID {
			target = inv
			break
		}
	}
	if target == nil {
		return NewError(http.StatusNotFound, "invite_not_found", "Invite not found.")
	}
	if !target.Revoked {
		target.Revoked = true
		rev := nowMs
		target.RevokedAtMs = &rev
		appendActivityLocked(rec, caller, "invite.revoked", fmt.Sprintf("%s revoked an invite link.", caller.DisplayName), nowMs)
		if err := s.persistLocked(nowMs); err != nil {
			return NewError(http.StatusInternalServerError, "server_error", "Failed to persist invite revocation.")
		}
	}
	return nil
}

func (s *Store) PreviewInvite(token string, nowMs int64) (*InvitePreview, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rec, inv := s.findInviteByTokenLocked(strings.TrimSpace(token))
	if rec == nil || inv == nil {
		return nil, NewError(http.StatusNotFound, "invite_not_found", "Invitation link not found.")
	}
	sum := s.buildSummaryLocked(rec, "")
	return &InvitePreview{
		PlaylistID:     rec.Playlist.ID,
		Title:          rec.Playlist.Title,
		Description:    rec.Playlist.Description,
		ArtworkURL:     cloneStrPtr(rec.Playlist.ArtworkURL),
		OwnerName:      sum.OwnerName,
		OwnerAvatarURL: sum.OwnerAvatarURL,
		MemberCount:    len(rec.Members),
		TrackCount:     len(rec.Tracks),
		Role:           inv.Role,
		Expired:        inv.IsExpired(nowMs),
		Revoked:        inv.Revoked || !rec.Playlist.SharingEnabled,
	}, nil
}

func (s *Store) AcceptInvite(caller *auth.User, token string, nowMs int64) (*PlaylistDetail, *CollabMember, *CollabActivity, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, inv := s.findInviteByTokenLocked(strings.TrimSpace(token))
	if rec == nil || inv == nil {
		return nil, nil, nil, false, NewError(http.StatusNotFound, "invite_not_found", "Invitation link not found.")
	}
	if inv.Revoked || !rec.Playlist.SharingEnabled {
		return nil, nil, nil, false, NewError(http.StatusGone, "invite_revoked", "This invitation link has been revoked.")
	}
	if inv.ExpiresAtMs != nil && nowMs >= *inv.ExpiresAtMs {
		return nil, nil, nil, false, NewError(http.StatusGone, "invite_expired", "This invitation link has expired.")
	}
	if inv.MaxUses > 0 && inv.UseCount >= inv.MaxUses {
		return nil, nil, nil, false, NewError(http.StatusGone, "invite_max_uses_reached", "This invitation link has reached its maximum number of uses.")
	}

	if existing := findMemberLocked(rec, caller.ID); existing != nil {
		return s.buildDetailLocked(rec, caller.ID), existing.Clone(), nil, true, nil
	}

	if len(rec.Members) >= s.limits.MaxMembersPerPlaylist {
		return nil, nil, nil, false, NewError(http.StatusConflict, "playlist_full", fmt.Sprintf("This playlist already has the maximum %d collaborators.", s.limits.MaxMembersPerPlaylist))
	}

	addedBy := inv.CreatedByID
	newMember := &CollabMember{
		PlaylistID:  rec.Playlist.ID,
		UserID:      caller.ID,
		Role:        inv.Role,
		DisplayName: caller.DisplayName,
		AvatarURL:   cloneStrPtr(caller.AvatarURL),
		JoinedAtMs:  nowMs,
		AddedByID:   &addedBy,
	}
	rec.Members = append(rec.Members, newMember)
	inv.UseCount++
	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs

	act := appendActivityLocked(rec, caller, "member.joined", fmt.Sprintf("%s joined this playlist.", caller.DisplayName), nowMs)
	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to save playlist membership.")
	}
	return s.buildDetailLocked(rec, caller.ID), newMember.Clone(), act, false, nil
}

func (s *Store) ListMembers(callerID, playlistID string) ([]*CollabMember, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	m := findMemberLocked(rec, callerID)
	if m == nil || (!rec.Playlist.SharingEnabled && rec.Playlist.OwnerUserID != callerID) {
		return nil, NewError(http.StatusForbidden, "forbidden", "You do not have access to this playlist.")
	}
	out := make([]*CollabMember, len(rec.Members))
	for i, mem := range rec.Members {
		out[i] = mem.Clone()
	}
	return out, nil
}

func (s *Store) ListActivities(callerID, playlistID string) ([]*CollabActivity, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	m := findMemberLocked(rec, callerID)
	if m == nil || (!rec.Playlist.SharingEnabled && rec.Playlist.OwnerUserID != callerID) {
		return nil, NewError(http.StatusForbidden, "forbidden", "You do not have access to this playlist.")
	}
	out := make([]*CollabActivity, len(rec.Activities))
	for i, act := range rec.Activities {
		out[i] = act.Clone()
	}
	return out, nil
}

func (s *Store) UpdateMemberRole(caller *auth.User, playlistID, targetUserID, newRole string, nowMs int64) (*PlaylistDetail, *CollabActivity, bool, error) {
	role := strings.ToUpper(strings.TrimSpace(newRole))
	if role == RoleOwner {
		detail, act, err := s.TransferOwnership(caller, playlistID, targetUserID, nowMs)
		return detail, act, err == nil, err
	}
	if role != RoleEditor && role != RoleViewer {
		return nil, nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "Role must be EDITOR, VIEWER, or OWNER.")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, false, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	callerMem := findMemberLocked(rec, caller.ID)
	if callerMem == nil || callerMem.Role != RoleOwner {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can change collaborator roles.")
	}
	if !rec.Playlist.SharingEnabled {
		return nil, nil, false, NewError(http.StatusConflict, "sharing_stopped", "Sharing has been stopped for this playlist.")
	}
	if targetUserID == caller.ID {
		return nil, nil, false, NewError(http.StatusUnprocessableEntity, "validation_error", "Transfer ownership to another collaborator before changing your own role.")
	}

	targetMem := findMemberLocked(rec, targetUserID)
	if targetMem == nil {
		return nil, nil, false, NewError(http.StatusNotFound, "member_not_found", "Collaborator is not a member of this playlist.")
	}

	targetMem.Role = role
	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs
	act := appendActivityLocked(rec, caller, "member.role_changed", fmt.Sprintf("%s changed %s's role to %s.", caller.DisplayName, targetMem.DisplayName, strings.ToLower(role)), nowMs)

	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to save role change.")
	}
	return s.buildDetailLocked(rec, caller.ID), act, false, nil
}

func (s *Store) TransferOwnership(caller *auth.User, playlistID, newOwnerUserID string, nowMs int64) (*PlaylistDetail, *CollabActivity, error) {
	newOwnerUserID = strings.TrimSpace(newOwnerUserID)
	if newOwnerUserID == "" {
		return nil, nil, NewError(http.StatusUnprocessableEntity, "validation_error", "newOwnerUserId is required.")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	callerMem := findMemberLocked(rec, caller.ID)
	if callerMem == nil || callerMem.Role != RoleOwner {
		return nil, nil, NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can transfer ownership.")
	}
	if !rec.Playlist.SharingEnabled {
		return nil, nil, NewError(http.StatusConflict, "sharing_stopped", "Sharing has been stopped for this playlist.")
	}
	if newOwnerUserID == caller.ID {
		return s.buildDetailLocked(rec, caller.ID), latestActivityCloneLocked(rec), nil
	}

	targetMem := findMemberLocked(rec, newOwnerUserID)
	if targetMem == nil {
		return nil, nil, NewError(http.StatusNotFound, "member_not_found", "New owner must already be a member of this playlist.")
	}

	callerMem.Role = RoleEditor
	targetMem.Role = RoleOwner
	rec.Playlist.OwnerUserID = targetMem.UserID
	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs

	act := appendActivityLocked(rec, caller, "playlist.ownership_transferred", fmt.Sprintf("%s transferred ownership to %s.", caller.DisplayName, targetMem.DisplayName), nowMs)
	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, NewError(http.StatusInternalServerError, "server_error", "Failed to persist ownership transfer.")
	}
	return s.buildDetailLocked(rec, caller.ID), act, nil
}

func (s *Store) RemoveOrLeaveMember(caller *auth.User, playlistID, targetUserID string, nowMs int64) (*PlaylistDetail, *CollabActivity, bool, error) {
	targetUserID = strings.TrimSpace(targetUserID)
	if targetUserID == "" {
		targetUserID = caller.ID
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.playlists[playlistID]
	if !ok {
		return nil, nil, false, NewError(http.StatusNotFound, "playlist_not_found", "Collaborative playlist not found.")
	}
	callerMem := findMemberLocked(rec, caller.ID)
	if callerMem == nil {
		return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "You are not a member of this playlist.")
	}

	selfLeave := targetUserID == caller.ID
	if selfLeave {
		if callerMem.Role == RoleOwner {
			return nil, nil, false, NewError(http.StatusConflict, "owner_cannot_leave", "Transfer ownership, stop sharing, or delete the playlist instead of leaving as owner.")
		}
	} else {
		if callerMem.Role != RoleOwner {
			return nil, nil, false, NewError(http.StatusForbidden, "forbidden", "Only the playlist owner can remove collaborators.")
		}
	}

	idx := -1
	var removed *CollabMember
	for i, m := range rec.Members {
		if m.UserID == targetUserID {
			idx = i
			removed = m
			break
		}
	}
	if idx < 0 {
		return nil, nil, false, NewError(http.StatusNotFound, "member_not_found", "Collaborator is not a member of this playlist.")
	}

	rec.Members = append(rec.Members[:idx], rec.Members[idx+1:]...)
	rec.Playlist.Revision++
	rec.Playlist.UpdatedAtMs = nowMs

	var act *CollabActivity
	if selfLeave {
		act = appendActivityLocked(rec, caller, "member.left", fmt.Sprintf("%s left this playlist.", caller.DisplayName), nowMs)
	} else {
		act = appendActivityLocked(rec, caller, "member.removed", fmt.Sprintf("%s removed %s from this playlist.", caller.DisplayName, removed.DisplayName), nowMs)
	}

	if err := s.persistLocked(nowMs); err != nil {
		return nil, nil, false, NewError(http.StatusInternalServerError, "server_error", "Failed to persist member removal.")
	}
	// Build detail from owner perspective if caller left (since caller is no longer in rec.Members).
	detail := s.buildDetailLocked(rec, rec.Playlist.OwnerUserID)
	if !selfLeave {
		detail.MyRole = callerMem.Role
	}
	return detail, act, selfLeave, nil
}
