package collab

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"net/http"
	"net/url"
	"strings"
)

// Roles for collaborative playlist membership.
const (
	RoleOwner  = "OWNER"
	RoleEditor = "EDITOR"
	RoleViewer = "VIEWER"
)

// Editor remove policies.
const (
	EditorRemoveAny = "any"
	EditorRemoveOwn = "own"
)

// Allowed track providers.
var validTrackProviders = map[string]bool{
	"youtube": true,
	"spotify": true,
	"local":   true,
	"addon":   true,
}

// Allowed personal playlist conversion source types.
var validSourceTypes = map[string]bool{
	"ytm":     true,
	"local":   true,
	"desktop": true,
	"spotify": true,
}

const (
	PlaylistIDPrefix  = "cpl_"
	EntryIDPrefix     = "cte_"
	InviteIDPrefix    = "inv_"
	InviteTokenPrefix = "bci_"
	ActivityIDPrefix  = "act_"

	MaxOpHistoryPerPlaylist  = 128
	MaxActivitiesPerPlaylist = 200
)

// CollabError represents a structured domain error with HTTP status and wire code.
type CollabError struct {
	Status  int    `json:"status"`
	Code    string `json:"error"`
	Message string `json:"message"`
}

func (e *CollabError) Error() string {
	return e.Message
}

func NewError(status int, code, message string) *CollabError {
	return &CollabError{Status: status, Code: code, Message: message}
}

func randomHexID(prefix string, numBytes int) (string, error) {
	buf := make([]byte, numBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return prefix + hex.EncodeToString(buf), nil
}

func NewPlaylistID() (string, error) { return randomHexID(PlaylistIDPrefix, 10) }
func NewEntryID() (string, error)    { return randomHexID(EntryIDPrefix, 10) }
func NewInviteID() (string, error)   { return randomHexID(InviteIDPrefix, 10) }
func NewActivityID() (string, error) { return randomHexID(ActivityIDPrefix, 8) }

// NewInviteToken generates an unpredictable 24-byte crypto/rand URL-safe token (bci_<base64url>).
func NewInviteToken() (string, error) {
	var b [24]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return InviteTokenPrefix + base64.RawURLEncoding.EncodeToString(b[:]), nil
}

// PlaylistPermissions controls what EDITOR collaborators can do on a playlist.
type PlaylistPermissions struct {
	EditorCanRemove  string `json:"editorCanRemove"`  // "any" (default) | "own"
	EditorCanReorder bool   `json:"editorCanReorder"` // default true
	EditorCanInvite  bool   `json:"editorCanInvite"`  // default false
}

func DefaultPermissions() PlaylistPermissions {
	return PlaylistPermissions{
		EditorCanRemove:  EditorRemoveAny,
		EditorCanReorder: true,
		EditorCanInvite:  false,
	}
}

// PermissionsPatch allows partial updates to PlaylistPermissions.
type PermissionsPatch struct {
	EditorCanRemove  *string `json:"editorCanRemove,omitempty"`
	EditorCanReorder *bool   `json:"editorCanReorder,omitempty"`
	EditorCanInvite  *bool   `json:"editorCanInvite,omitempty"`
}

func ApplyPermissionsPatch(base PlaylistPermissions, patch *PermissionsPatch) (PlaylistPermissions, error) {
	if patch == nil {
		return base, nil
	}
	next := base
	if patch.EditorCanRemove != nil {
		mode := strings.ToLower(strings.TrimSpace(*patch.EditorCanRemove))
		if mode != EditorRemoveAny && mode != EditorRemoveOwn {
			return base, NewError(http.StatusUnprocessableEntity, "validation_error", "editorCanRemove must be 'any' or 'own'.")
		}
		next.EditorCanRemove = mode
	}
	if patch.EditorCanReorder != nil {
		next.EditorCanReorder = *patch.EditorCanReorder
	}
	if patch.EditorCanInvite != nil {
		next.EditorCanInvite = *patch.EditorCanInvite
	}
	return next, nil
}

// CollabPlaylist holds metadata and state for a collaborative playlist.
type CollabPlaylist struct {
	ID               string              `json:"id"`
	Title            string              `json:"title"`
	Description      string              `json:"description"`
	ArtworkURL       *string             `json:"artworkUrl"`
	OwnerUserID      string              `json:"ownerUserId"`
	SharingEnabled   bool                `json:"sharingEnabled"`
	Permissions      PlaylistPermissions `json:"permissions"`
	SourceType       *string             `json:"sourceType"`
	SourcePlaylistID *string             `json:"sourcePlaylistId"`
	Revision         int64               `json:"revision"`
	CreatedAtMs      int64               `json:"createdAtMs"`
	UpdatedAtMs      int64               `json:"updatedAtMs"`
}

func (p *CollabPlaylist) Clone() *CollabPlaylist {
	if p == nil {
		return nil
	}
	cp := *p
	cp.ArtworkURL = cloneStrPtr(p.ArtworkURL)
	cp.SourceType = cloneStrPtr(p.SourceType)
	cp.SourcePlaylistID = cloneStrPtr(p.SourcePlaylistID)
	return &cp
}

// CollabTrackEntry represents an ordered song entry in a collaborative playlist with verified attribution.
type CollabTrackEntry struct {
	EntryID              string  `json:"entryId"`
	PlaylistID           string  `json:"playlistId"`
	Position             int     `json:"position"`
	TrackID              string  `json:"trackId"`
	Provider             string  `json:"provider"` // "youtube"|"spotify"|"local"|"addon"
	Title                string  `json:"title"`
	Artist               string  `json:"artist"`
	AlbumName            *string `json:"albumName"`
	ThumbnailURL         *string `json:"thumbnailUrl"`
	DurationMs           *int64  `json:"durationMs"`
	IsExplicit           bool    `json:"isExplicit"`
	ContributorID        *string `json:"contributorId"`
	ContributorName      *string `json:"contributorName"`
	ContributorAvatarURL *string `json:"contributorAvatarUrl"`
	AddedAtMs            int64   `json:"addedAtMs"`
}

func (t *CollabTrackEntry) Clone() *CollabTrackEntry {
	if t == nil {
		return nil
	}
	cp := *t
	cp.AlbumName = cloneStrPtr(t.AlbumName)
	cp.ThumbnailURL = cloneStrPtr(t.ThumbnailURL)
	cp.DurationMs = cloneInt64Ptr(t.DurationMs)
	cp.ContributorID = cloneStrPtr(t.ContributorID)
	cp.ContributorName = cloneStrPtr(t.ContributorName)
	cp.ContributorAvatarURL = cloneStrPtr(t.ContributorAvatarURL)
	return &cp
}

// CollabMember represents a user's membership and role in a collaborative playlist.
type CollabMember struct {
	PlaylistID  string  `json:"playlistId"`
	UserID      string  `json:"userId"`
	Role        string  `json:"role"` // "OWNER"|"EDITOR"|"VIEWER"
	DisplayName string  `json:"displayName"`
	AvatarURL   *string `json:"avatarUrl"`
	JoinedAtMs  int64   `json:"joinedAtMs"`
	AddedByID   *string `json:"addedById,omitempty"`
}

func (m *CollabMember) Clone() *CollabMember {
	if m == nil {
		return nil
	}
	cp := *m
	cp.AvatarURL = cloneStrPtr(m.AvatarURL)
	cp.AddedByID = cloneStrPtr(m.AddedByID)
	return &cp
}

// CollabInvite represents a shareable, revocable invitation link token.
type CollabInvite struct {
	InviteID    string `json:"inviteId"`
	PlaylistID  string `json:"playlistId"`
	Token       string `json:"token"`
	Role        string `json:"role"` // "EDITOR"|"VIEWER"
	CreatedByID string `json:"createdById"`
	CreatedAtMs int64  `json:"createdAtMs"`
	ExpiresAtMs *int64 `json:"expiresAtMs"`
	MaxUses     int    `json:"maxUses"` // 0 = unlimited
	UseCount    int    `json:"useCount"`
	Revoked     bool   `json:"revoked"`
	RevokedAtMs *int64 `json:"revokedAtMs,omitempty"`
}

func (inv *CollabInvite) Clone() *CollabInvite {
	if inv == nil {
		return nil
	}
	cp := *inv
	cp.ExpiresAtMs = cloneInt64Ptr(inv.ExpiresAtMs)
	cp.RevokedAtMs = cloneInt64Ptr(inv.RevokedAtMs)
	return &cp
}

func (inv *CollabInvite) IsExpired(nowMs int64) bool {
	if inv.ExpiresAtMs != nil && nowMs >= *inv.ExpiresAtMs {
		return true
	}
	if inv.MaxUses > 0 && inv.UseCount >= inv.MaxUses {
		return true
	}
	return false
}

// InviteView is the owner-visible wire representation of an active invitation.
type InviteView struct {
	InviteID    string `json:"inviteId"`
	PlaylistID  string `json:"playlistId"`
	Token       string `json:"token"`
	Role        string `json:"role"`
	ExpiresAtMs *int64 `json:"expiresAtMs"`
	MaxUses     int    `json:"maxUses"`
	UseCount    int    `json:"useCount"`
	CreatedAtMs int64  `json:"createdAtMs"`
	InviteURL   string `json:"inviteUrl"`
}

// InvitePreview is the public logged-out preview of a collaborative playlist invite.
type InvitePreview struct {
	PlaylistID     string  `json:"playlistId"`
	Title          string  `json:"title"`
	Description    string  `json:"description"`
	ArtworkURL     *string `json:"artworkUrl"`
	OwnerName      string  `json:"ownerName"`
	OwnerAvatarURL *string `json:"ownerAvatarUrl"`
	MemberCount    int     `json:"memberCount"`
	TrackCount     int     `json:"trackCount"`
	Role           string  `json:"role"`
	Expired        bool    `json:"expired"`
	Revoked        bool    `json:"revoked"`
}

// CollabActivity records an auditable event in a collaborative playlist.
type CollabActivity struct {
	ID           string  `json:"id"`
	PlaylistID   string  `json:"playlistId"`
	ActorUserID  string  `json:"actorUserId"`
	ActorName    string  `json:"actorName"`
	ActorAvatar  *string `json:"actorAvatarUrl"`
	Action       string  `json:"action"`
	Summary      string  `json:"summary"`
	CreatedAtMs  int64   `json:"createdAtMs"`
}

func (a *CollabActivity) Clone() *CollabActivity {
	if a == nil {
		return nil
	}
	cp := *a
	cp.ActorAvatar = cloneStrPtr(a.ActorAvatar)
	return &cp
}

// AppliedOp tracks (userId, opId) for duplicate-event idempotency.
type AppliedOp struct {
	UserID    string `json:"userId"`
	OpID      string `json:"opId"`
	AppliedMs int64  `json:"appliedMs"`
}

// PlaylistSummary is the compact wire representation returned in list endpoints and metadata broadcasts.
type PlaylistSummary struct {
	ID               string              `json:"id"`
	Title            string              `json:"title"`
	Description      string              `json:"description"`
	ArtworkURL       *string             `json:"artworkUrl"`
	OwnerUserID      string              `json:"ownerUserId"`
	OwnerName        string              `json:"ownerName"`
	OwnerAvatarURL   *string             `json:"ownerAvatarUrl"`
	MyRole           string              `json:"myRole,omitempty"`
	SharingEnabled   bool                `json:"sharingEnabled"`
	Permissions      PlaylistPermissions `json:"permissions"`
	SourceType       *string             `json:"sourceType"`
	SourcePlaylistID *string             `json:"sourcePlaylistId"`
	MemberCount      int                 `json:"memberCount"`
	TrackCount       int                 `json:"trackCount"`
	Revision         int64               `json:"revision"`
	CreatedAtMs      int64               `json:"createdAtMs"`
	UpdatedAtMs      int64               `json:"updatedAtMs"`
}

// PlaylistDetail is the full collaborative playlist snapshot returned on detail/snapshot/convert/stop-sharing.
type PlaylistDetail struct {
	PlaylistSummary
	Members    []*CollabMember     `json:"members"`
	Tracks     []*CollabTrackEntry `json:"tracks"`
	Activities []*CollabActivity   `json:"activities"`
}

// TrackInput parses incoming track payloads while ignoring any client-supplied contributor fields.
type TrackInput struct {
	TrackID      string  `json:"trackId"`
	VideoID      string  `json:"videoId"` // optional alias for YouTube tracks
	Provider     string  `json:"provider"`
	Title        string  `json:"title"`
	Artist       string  `json:"artist"`
	AlbumName    *string `json:"albumName"`
	ThumbnailURL *string `json:"thumbnailUrl"`
	DurationMs   *int64  `json:"durationMs"`
	IsExplicit   bool    `json:"isExplicit"`
}

func (in TrackInput) ValidateAndNormalize() (TrackInput, error) {
	trackID := strings.TrimSpace(in.TrackID)
	if trackID == "" {
		trackID = strings.TrimSpace(in.VideoID)
	}
	if trackID == "" || len(trackID) > 256 {
		return TrackInput{}, NewError(http.StatusUnprocessableEntity, "validation_error", "Each track must include a valid trackId (1..256 chars).")
	}

	provider := strings.ToLower(strings.TrimSpace(in.Provider))
	if provider == "" {
		provider = "youtube"
	}
	if !validTrackProviders[provider] {
		return TrackInput{}, NewError(http.StatusUnprocessableEntity, "validation_error", "Track provider must be youtube, spotify, local, or addon.")
	}

	title := strings.TrimSpace(in.Title)
	if title == "" {
		return TrackInput{}, NewError(http.StatusUnprocessableEntity, "validation_error", "Track title is required.")
	}
	title = truncateRunes(title, 300)

	artist := truncateRunes(strings.TrimSpace(in.Artist), 300)
	if artist == "" {
		artist = "Unknown Artist"
	}

	var albumPtr *string
	if in.AlbumName != nil {
		if alb := truncateRunes(strings.TrimSpace(*in.AlbumName), 300); alb != "" {
			albumPtr = &alb
		}
	}

	thumbPtr, err := sanitizeOptionalHTTPURL(in.ThumbnailURL)
	if err != nil {
		return TrackInput{}, err
	}

	var durPtr *int64
	if in.DurationMs != nil {
		if *in.DurationMs < 0 || *in.DurationMs > 24*60*60*1000 {
			return TrackInput{}, NewError(http.StatusUnprocessableEntity, "validation_error", "Track durationMs is out of range.")
		}
		d := *in.DurationMs
		durPtr = &d
	}

	return TrackInput{
		TrackID:      trackID,
		Provider:     provider,
		Title:        title,
		Artist:       artist,
		AlbumName:    albumPtr,
		ThumbnailURL: thumbPtr,
		DurationMs:   durPtr,
		IsExplicit:   in.IsExplicit,
	}, nil
}

func ValidateOpID(opID string) (string, error) {
	trimmed := strings.TrimSpace(opID)
	if trimmed == "" {
		return "", nil
	}
	if len(trimmed) > 64 {
		return "", NewError(http.StatusUnprocessableEntity, "validation_error", "opId must be between 1 and 64 characters.")
	}
	return trimmed, nil
}

func ValidateTitle(title string) (string, error) {
	trimmed := strings.TrimSpace(title)
	if trimmed == "" {
		return "", NewError(http.StatusUnprocessableEntity, "validation_error", "Playlist title cannot be empty.")
	}
	if len([]rune(trimmed)) > 200 {
		return "", NewError(http.StatusUnprocessableEntity, "validation_error", "Playlist title must not exceed 200 characters.")
	}
	return trimmed, nil
}

func ValidateDescription(desc string) (string, error) {
	trimmed := strings.TrimSpace(desc)
	if len([]rune(trimmed)) > 2000 {
		return "", NewError(http.StatusUnprocessableEntity, "validation_error", "Playlist description must not exceed 2000 characters.")
	}
	return trimmed, nil
}

func sanitizeOptionalHTTPURL(raw *string) (*string, error) {
	if raw == nil {
		return nil, nil
	}
	trimmed := strings.TrimSpace(*raw)
	if trimmed == "" {
		return nil, nil
	}
	if len(trimmed) > 1000 {
		return nil, NewError(http.StatusUnprocessableEntity, "validation_error", "URL must not exceed 1000 characters.")
	}
	u, err := url.Parse(trimmed)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil {
		return nil, NewError(http.StatusUnprocessableEntity, "validation_error", "URL must be a valid http(s) URL.")
	}
	return &trimmed, nil
}

func truncateRunes(s string, max int) string {
	r := []rune(s)
	if len(r) <= max {
		return s
	}
	return string(r[:max])
}

func cloneStrPtr(p *string) *string {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

func cloneInt64Ptr(p *int64) *int64 {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

var ErrStopIteration = errors.New("stop")
