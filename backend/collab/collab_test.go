package collab

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/KabirSinghBhatia/BitChord/backend/auth"
)

type staticKeyProvider struct {
	pub *rsa.PublicKey
}

func (p *staticKeyProvider) PublicKey(_ context.Context, kid string, _ bool) (*rsa.PublicKey, error) {
	if kid != "test-kid" {
		return nil, auth.ErrKeyNotFound
	}
	return p.pub, nil
}

type collabTestEnv struct {
	t       *testing.T
	privKey *rsa.PrivateKey
	store   *Store
	authMgr *auth.Manager
	handler *Handler
	server  *httptest.Server
	nowMs   int64
	mu      sync.Mutex
}

func newCollabTestEnv(t *testing.T, dataPath string) *collabTestEnv {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("rsa key: %v", err)
	}
	env := &collabTestEnv{
		t:       t,
		privKey: priv,
		nowMs:   time.Date(2026, 11, 15, 15, 0, 0, 0, time.UTC).UnixMilli(),
	}
	env.reloadStore(dataPath)
	return env
}

func (e *collabTestEnv) getNowMs() int64 {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.nowMs
}

func (e *collabTestEnv) advanceMs(delta int64) {
	e.mu.Lock()
	e.nowMs += delta
	e.mu.Unlock()
}

func (e *collabTestEnv) reloadStore(dataPath string) {
	e.t.Helper()
	if e.server != nil {
		e.server.Close()
	}
	store, err := NewStore(dataPath, DefaultStoreLimits())
	if err != nil {
		e.t.Fatalf("NewStore(%q): %v", dataPath, err)
	}
	verifier := auth.NewVerifier(auth.VerifierConfig{
		KeyProvider:      &staticKeyProvider{pub: &e.privKey.PublicKey},
		AllowedClientIDs: []string{"bitchord-test-client"},
		Now:              func() time.Time { return time.UnixMilli(e.getNowMs()) },
	})
	authMgr := auth.NewManager(verifier, store, 30*24*3600*1000)
	authMgr.NowMs = e.getNowMs

	handler := NewHandler(store, authMgr)
	handler.NowMs = e.getNowMs
	handler.AuthLimiter = newRateLimiter(time.Minute, 1000, 10000)
	handler.PreviewLimiter = newRateLimiter(time.Minute, 1000, 10000)
	handler.MutateLimiter = newRateLimiter(time.Minute, 1000, 10000)

	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	e.store = store
	e.authMgr = authMgr
	e.handler = handler
	e.server = httptest.NewServer(mux)
}

func (e *collabTestEnv) close() {
	if e.server != nil {
		e.server.Close()
	}
}

func (e *collabTestEnv) mintGoogleIDToken(sub, email, name, picture string) string {
	e.t.Helper()
	nowSec := e.getNowMs() / 1000
	hdr, _ := json.Marshal(map[string]string{"alg": "RS256", "kid": "test-kid", "typ": "JWT"})
	claims, _ := json.Marshal(map[string]interface{}{
		"iss":            "https://accounts.google.com",
		"sub":            sub,
		"aud":            "bitchord-test-client",
		"exp":            nowSec + 3600,
		"iat":            nowSec,
		"email":          email,
		"email_verified": true,
		"name":           name,
		"picture":        picture,
	})
	signingInput := base64.RawURLEncoding.EncodeToString(hdr) + "." + base64.RawURLEncoding.EncodeToString(claims)
	sum := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, e.privKey, crypto.SHA256, sum[:])
	if err != nil {
		e.t.Fatalf("sign: %v", err)
	}
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func (e *collabTestEnv) signIn(sub, email, name, picture string) (token string, userID string) {
	e.t.Helper()
	idTok := e.mintGoogleIDToken(sub, email, name, picture)
	status, body := e.doJSON(http.MethodPost, "/api/collab/auth/google", "", map[string]string{"idToken": idTok})
	if status != http.StatusOK {
		e.t.Fatalf("signIn(%s) failed with %d: %v", name, status, body)
	}
	tok, _ := body["token"].(string)
	uMap, _ := body["user"].(map[string]interface{})
	uid, _ := uMap["id"].(string)
	return tok, uid
}

func (e *collabTestEnv) doJSON(method, path, bearerToken string, reqBody interface{}) (int, map[string]interface{}) {
	e.t.Helper()
	var reader io.Reader
	if reqBody != nil {
		b, err := json.Marshal(reqBody)
		if err != nil {
			e.t.Fatalf("marshal req: %v", err)
		}
		reader = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, e.server.URL+path, reader)
	if err != nil {
		e.t.Fatalf("new req: %v", err)
	}
	if reqBody != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+bearerToken)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatalf("%s %s failed: %v", method, path, err)
	}
	defer resp.Body.Close()

	var out map[string]interface{}
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp.StatusCode, out
}

func (e *collabTestEnv) dialWS(bearerToken string, useSubprotocol bool) (*websocket.Conn, *http.Response) {
	e.t.Helper()
	u, _ := url.Parse(e.server.URL)
	u.Scheme = "ws"
	u.Path = "/ws/collab"

	if useSubprotocol {
		dialer := websocket.Dialer{
			Subprotocols: []string{CollabSocketProtocol, CollabSocketTokenPrefix + bearerToken},
		}
		ws, resp, err := dialer.Dial(u.String(), nil)
		if err != nil {
			e.t.Fatalf("dialWS subprotocol failed: %v", err)
		}
		return ws, resp
	}

	hdr := http.Header{}
	if bearerToken != "" {
		hdr.Set("Authorization", "Bearer "+bearerToken)
	}
	ws, resp, err := websocket.DefaultDialer.Dial(u.String(), hdr)
	if err != nil {
		return nil, resp
	}
	return ws, resp
}

func readWSFrame(t *testing.T, ws *websocket.Conn, wantType string) map[string]interface{} {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		_ = ws.SetReadDeadline(deadline)
		var frame map[string]interface{}
		if err := ws.ReadJSON(&frame); err != nil {
			t.Fatalf("waiting for WS frame %q failed: %v", wantType, err)
		}
		if wantType == "" || frame["type"] == wantType {
			return frame
		}
	}
}

func TestGuestRejectionAndPublicInvitePreview(t *testing.T) {
	env := newCollabTestEnv(t, ":memory:")
	defer env.close()

	// Guest cannot list or create collaborative playlists or open WebSocket.
	if status, body := env.doJSON(http.MethodGet, "/api/collab/playlists", "", nil); status != http.StatusUnauthorized || body["error"] != "auth_required" {
		t.Fatalf("expected 401 auth_required on GET /playlists, got %d %v", status, body)
	}
	if status, body := env.doJSON(http.MethodPost, "/api/collab/playlists", "", map[string]string{"title": "Nope"}); status != http.StatusUnauthorized || body["error"] != "auth_required" {
		t.Fatalf("expected 401 auth_required on POST /playlists, got %d %v", status, body)
	}
	if ws, resp := env.dialWS("", false); ws != nil || resp == nil || resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 on unauthenticated /ws/collab, got %v", resp)
	}

	// Owner signs in, creates playlist + 1 song + invite.
	ownerTok, _ := env.signIn("sub-alice", "alice@example.com", "Alice", "https://img.example.com/alice.png")
	status, created := env.doJSON(http.MethodPost, "/api/collab/playlists", ownerTok, map[string]interface{}{
		"title":       "Late Night Synth",
		"description": "Neon classics",
		"artworkUrl":  "https://img.example.com/cover.jpg",
		"tracks": []map[string]interface{}{
			{"trackId": "yt_1", "provider": "youtube", "title": "Nightcall", "artist": "Kavinsky"},
		},
	})
	if status != http.StatusCreated {
		t.Fatalf("create playlist failed: %d %v", status, created)
	}
	pid := created["playlist"].(map[string]interface{})["id"].(string)

	invStatus, inv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), ownerTok, map[string]interface{}{
		"role": "EDITOR",
	})
	if invStatus != http.StatusCreated {
		t.Fatalf("create invite failed: %d %v", invStatus, inv)
	}
	invToken := inv["token"].(string)

	// Logged-out guest CAN preview the invite and load HTML landing page!
	prevStatus, preview := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/invites/%s/preview", invToken), "", nil)
	if prevStatus != http.StatusOK {
		t.Fatalf("guest preview failed: %d %v", prevStatus, preview)
	}
	if preview["title"] != "Late Night Synth" || preview["ownerName"] != "Alice" || preview["trackCount"] != float64(1) || preview["role"] != "EDITOR" || preview["expired"] != false || preview["revoked"] != false {
		t.Fatalf("unexpected guest preview: %+v", preview)
	}

	landingRes, err := http.Get(fmt.Sprintf("%s/collab/invite/%s", env.server.URL, invToken))
	if err != nil || landingRes.StatusCode != http.StatusOK {
		t.Fatalf("GET /collab/invite landing failed: %v %v", landingRes, err)
	}
	htmlBytes, _ := io.ReadAll(landingRes.Body)
	landingRes.Body.Close()
	htmlStr := string(htmlBytes)
	for _, needle := range []string{
		"Late Night Synth",
		"Alice",
		"intent://collab/invite/" + invToken,
		"bitchord://collab/invite/" + invToken,
		"Google sign-in is required",
		"https://github.com/Inventor365/Sonara/releases",
	} {
		if !strings.Contains(htmlStr, needle) {
			t.Errorf("expected landing HTML to contain %q", needle)
		}
	}

	// Logged-out guest CANNOT accept the invite (401 auth_required)!
	if accStatus, accBody := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", invToken), "", nil); accStatus != http.StatusUnauthorized || accBody["error"] != "auth_required" {
		t.Fatalf("expected 401 auth_required on logged-out invite accept, got %d %v", accStatus, accBody)
	}
}

func TestPersonalPlaylistConversionIdempotencyAndAttribution(t *testing.T) {
	env := newCollabTestEnv(t, ":memory:")
	defer env.close()

	aliceTok, aliceID := env.signIn("sub-alice", "alice@example.com", "Alice", "https://img.example.com/alice.png")
	bobTok, bobID := env.signIn("sub-bob", "bob@example.com", "Bob", "https://img.example.com/bob.png")

	// 1. Convert with attributeExistingTracksToOwner = false -> historical tracks MUST have null contributor fields,
	// even if client tries to inject fake contributorId/contributorName in the JSON!
	convPayload := map[string]interface{}{
		"sourcePlaylistId":               "local_pl_42",
		"sourceType":                     "desktop",
		"title":                          "My Desktop Favorites",
		"description":                    "Converted from PlusPC",
		"artworkUrl":                     "https://img.example.com/art.png",
		"attributeExistingTracksToOwner": false,
		"tracks": []map[string]interface{}{
			{
				"trackId":         "yt_a",
				"provider":        "youtube",
				"title":           "Song One",
				"artist":          "Artist A",
				"contributorId":   "usr_fake_injected",
				"contributorName": "Fake Person",
			},
			{
				"trackId":  "yt_b",
				"provider": "spotify",
				"title":    "Song Two",
				"artist":   "Artist B",
			},
		},
	}
	status1, conv1 := env.doJSON(http.MethodPost, "/api/collab/playlists/convert", aliceTok, convPayload)
	if status1 != http.StatusCreated || conv1["alreadyConverted"] != false {
		t.Fatalf("first convert expected 201 alreadyConverted=false, got %d %v", status1, conv1)
	}
	pl1 := conv1["playlist"].(map[string]interface{})
	pid1 := pl1["id"].(string)
	tracks1 := pl1["tracks"].([]interface{})
	if len(tracks1) != 2 {
		t.Fatalf("expected 2 tracks, got %d", len(tracks1))
	}
	for i, raw := range tracks1 {
		tr := raw.(map[string]interface{})
		if tr["contributorId"] != nil || tr["contributorName"] != nil || tr["contributorAvatarUrl"] != nil {
			t.Fatalf("track %d expected nil contributor fields when attributeExistingTracksToOwner=false, got %+v", i, tr)
		}
	}
	acts := pl1["activities"].([]interface{})
	if len(acts) != 1 || acts[0].(map[string]interface{})["summary"] != `Alice shared "My Desktop Favorites" as a collaborative playlist.` {
		t.Fatalf("unexpected conversion activity: %+v", acts)
	}

	// 2. Repeat conversion of same (ownerUserId, sourceType, sourcePlaylistId) -> 200 OK alreadyConverted=true, NO duplicate!
	status2, conv2 := env.doJSON(http.MethodPost, "/api/collab/playlists/convert", aliceTok, convPayload)
	if status2 != http.StatusOK || conv2["alreadyConverted"] != true {
		t.Fatalf("repeat convert expected 200 alreadyConverted=true, got %d %v", status2, conv2)
	}
	if conv2["playlist"].(map[string]interface{})["id"].(string) != pid1 {
		t.Fatal("duplicate playlist created instead of returning existing active collaborative playlist")
	}

	// 3. Convert another personal playlist with attributeExistingTracksToOwner = true -> stamps Alice!
	status3, conv3 := env.doJSON(http.MethodPost, "/api/collab/playlists/convert", aliceTok, map[string]interface{}{
		"sourcePlaylistId":               "ytm_pl_99",
		"sourceType":                     "ytm",
		"title":                          "Attributed Mix",
		"attributeExistingTracksToOwner": true,
		"tracks": []map[string]interface{}{
			{"trackId": "yt_owned", "provider": "youtube", "title": "Owned Song", "artist": "Me"},
		},
	})
	if status3 != http.StatusCreated {
		t.Fatalf("second distinct convert failed: %d %v", status3, conv3)
	}
	trOwned := conv3["playlist"].(map[string]interface{})["tracks"].([]interface{})[0].(map[string]interface{})
	if trOwned["contributorId"] != aliceID || trOwned["contributorName"] != "Alice" {
		t.Fatalf("expected owner attribution on attributeExistingTracksToOwner=true, got %+v", trOwned)
	}

	// 4. Invite Bob as EDITOR on pid1; Bob adds a song while trying to spoof contributorId=aliceID -> stamped as Bob!
	_, inv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid1), aliceTok, map[string]interface{}{"role": "EDITOR"})
	_, _ = env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", inv["token"].(string)), bobTok, nil)

	addStatus, addResp := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/tracks", pid1), bobTok, map[string]interface{}{
		"tracks": []map[string]interface{}{
			{
				"trackId":              "yt_bob_song",
				"provider":             "youtube",
				"title":                "Bob's Pick",
				"artist":               "Daft Punk",
				"contributorId":        aliceID,
				"contributorName":      "Spoofed Alice",
				"contributorAvatarUrl": "https://evil.example/spoof.png",
			},
		},
	})
	if addStatus != http.StatusOK {
		t.Fatalf("Bob add track failed: %d %v", addStatus, addResp)
	}
	addedEntry := addResp["added"].([]interface{})[0].(map[string]interface{})
	if addedEntry["contributorId"] != bobID || addedEntry["contributorName"] != "Bob" || addedEntry["contributorAvatarUrl"] != "https://img.example.com/bob.png" {
		t.Fatalf("server failed to enforce authenticated contributor identity: %+v", addedEntry)
	}
}

func TestRolePermissionsInvitesAndOwnershipTransfer(t *testing.T) {
	env := newCollabTestEnv(t, ":memory:")
	defer env.close()

	ownerTok, ownerID := env.signIn("sub-owner", "owner@example.com", "Owner", "")
	editorTok, editorID := env.signIn("sub-editor", "editor@example.com", "Editor", "")
	viewerTok, viewerID := env.signIn("sub-viewer", "viewer@example.com", "Viewer", "")
	extraTok, _ := env.signIn("sub-extra", "extra@example.com", "Extra", "")

	_, created := env.doJSON(http.MethodPost, "/api/collab/playlists", ownerTok, map[string]interface{}{
		"title": "Permissions Test",
		"tracks": []map[string]interface{}{
			{"trackId": "t_owner", "provider": "youtube", "title": "Owner Track", "artist": "A"},
		},
	})
	pid := created["playlist"].(map[string]interface{})["id"].(string)
	ownerEntryID := created["playlist"].(map[string]interface{})["tracks"].([]interface{})[0].(map[string]interface{})["entryId"].(string)

	// Invite with maxUses: 1 for EDITOR
	_, edInv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), ownerTok, map[string]interface{}{
		"role":    "EDITOR",
		"maxUses": 1,
	})
	edToken := edInv["token"].(string)

	if st, _ := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", edToken), editorTok, nil); st != http.StatusOK {
		t.Fatalf("editor accept failed: %d", st)
	}
	// Second user trying to use maxUses=1 invite must get 410 Gone!
	if st, body := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", edToken), extraTok, nil); st != http.StatusGone || body["error"] != "invite_max_uses_reached" {
		t.Fatalf("expected 410 invite_max_uses_reached, got %d %v", st, body)
	}

	// Invite with 10s expiry -> advance 15s -> 410 invite_expired!
	_, expInv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), ownerTok, map[string]interface{}{
		"role":         "VIEWER",
		"expiresInSec": 10,
	})
	env.advanceMs(15 * 1000)
	if st, body := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", expInv["token"].(string)), viewerTok, nil); st != http.StatusGone || body["error"] != "invite_expired" {
		t.Fatalf("expected 410 invite_expired, got %d %v", st, body)
	}

	// Invite that gets revoked immediately -> 410 invite_revoked!
	_, revInv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), ownerTok, map[string]interface{}{"role": "VIEWER"})
	if st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s/invites/%s", pid, revInv["inviteId"].(string)), ownerTok, nil); st != http.StatusOK {
		t.Fatalf("revoke invite failed: %d", st)
	}
	if st, body := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", revInv["token"].(string)), viewerTok, nil); st != http.StatusGone || body["error"] != "invite_revoked" {
		t.Fatalf("expected 410 invite_revoked, got %d %v", st, body)
	}

	// Valid VIEWER invite for Viewer
	_, vwInv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), ownerTok, map[string]interface{}{"role": "VIEWER", "expiresInSec": 0})
	if st, _ := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", vwInv["token"].(string)), viewerTok, nil); st != http.StatusOK {
		t.Fatalf("viewer accept failed: %d", st)
	}

	// VIEWER can read playlist/members/activity, but gets 403 on every mutation!
	if st, _ := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/playlists/%s", pid), viewerTok, nil); st != http.StatusOK {
		t.Fatalf("viewer GET playlist failed: %d", st)
	}
	if st, _ := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/tracks", pid), viewerTok, map[string]interface{}{
		"track": map[string]interface{}{"trackId": "v1", "title": "Nope", "artist": "X"},
	}); st != http.StatusForbidden {
		t.Fatalf("expected 403 on viewer add track, got %d", st)
	}
	if st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s/tracks/%s", pid, ownerEntryID), viewerTok, nil); st != http.StatusForbidden {
		t.Fatalf("expected 403 on viewer remove track, got %d", st)
	}
	if st, _ := env.doJSON(http.MethodPut, fmt.Sprintf("/api/collab/playlists/%s/tracks/order", pid), viewerTok, map[string]interface{}{"entryIds": []string{ownerEntryID}}); st != http.StatusForbidden {
		t.Fatalf("expected 403 on viewer reorder, got %d", st)
	}
	if st, _ := env.doJSON(http.MethodPatch, fmt.Sprintf("/api/collab/playlists/%s", pid), viewerTok, map[string]interface{}{"title": "Hijack"}); st != http.StatusForbidden {
		t.Fatalf("expected 403 on viewer metadata patch, got %d", st)
	}

	// EDITOR adds their own track
	_, edAdded := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/tracks", pid), editorTok, map[string]interface{}{
		"track": map[string]interface{}{"trackId": "t_editor", "title": "Editor Track", "artist": "B"},
	})
	editorEntryID := edAdded["added"].([]interface{})[0].(map[string]interface{})["entryId"].(string)

	// EDITOR cannot patch metadata or list invites
	if st, _ := env.doJSON(http.MethodPatch, fmt.Sprintf("/api/collab/playlists/%s", pid), editorTok, map[string]interface{}{"title": "Nope"}); st != http.StatusForbidden {
		t.Fatalf("expected 403 on editor metadata patch, got %d", st)
	}
	if st, _ := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), editorTok, nil); st != http.StatusForbidden {
		t.Fatalf("expected 403 on editor listing invites, got %d", st)
	}

	// OWNER restricts editorCanRemove="own" and editorCanReorder=false
	if st, _ := env.doJSON(http.MethodPatch, fmt.Sprintf("/api/collab/playlists/%s", pid), ownerTok, map[string]interface{}{
		"permissions": map[string]interface{}{
			"editorCanRemove":  "own",
			"editorCanReorder": false,
		},
	}); st != http.StatusOK {
		t.Fatalf("owner permissions patch failed: %d", st)
	}

	// EDITOR now gets 403 trying to reorder OR remove owner's track, but CAN remove their own track!
	if st, _ := env.doJSON(http.MethodPut, fmt.Sprintf("/api/collab/playlists/%s/tracks/order", pid), editorTok, map[string]interface{}{
		"entryIds": []string{editorEntryID, ownerEntryID},
	}); st != http.StatusForbidden {
		t.Fatalf("expected 403 when editorCanReorder=false, got %d", st)
	}
	if st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s/tracks/%s", pid, ownerEntryID), editorTok, nil); st != http.StatusForbidden {
		t.Fatalf("expected 403 when editor tries to remove owner track under editorCanRemove=own, got %d", st)
	}
	if st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s/tracks/%s", pid, editorEntryID), editorTok, nil); st != http.StatusOK {
		t.Fatalf("expected 200 when editor removes own track under editorCanRemove=own, got %d", st)
	}

	// VIEWER leaves playlist; OWNER transfers ownership to EDITOR -> former owner becomes EDITOR!
	if st, _ := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/leave", pid), viewerTok, nil); st != http.StatusOK {
		t.Fatalf("viewer leave failed: %d", st)
	}
	_ = viewerID

	if st, xfer := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/transfer-ownership", pid), ownerTok, map[string]string{
		"newOwnerUserId": editorID,
	}); st != http.StatusOK || xfer["playlist"].(map[string]interface{})["ownerUserId"] != editorID {
		t.Fatalf("ownership transfer failed: %d %v", st, xfer)
	}
	// Former owner (now EDITOR) can no longer delete playlist, while new owner CAN!
	_ = ownerID
	if st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s", pid), ownerTok, nil); st != http.StatusForbidden {
		t.Fatalf("expected former owner to get 403 on delete, got %d", st)
	}
	if st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s", pid), editorTok, nil); st != http.StatusOK {
		t.Fatalf("expected new owner to succeed on delete, got %d", st)
	}
}

func TestOpIdIdempotencyConcurrencyAndDiskPersistence(t *testing.T) {
	tmpDir := t.TempDir()
	dbPath := filepath.Join(tmpDir, "collab_state.json")
	env := newCollabTestEnv(t, dbPath)
	defer env.close()

	aliceTok, aliceID := env.signIn("sub-alice", "alice@example.com", "Alice", "https://img.example.com/a.png")
	bobTok, bobID := env.signIn("sub-bob", "bob@example.com", "Bob", "https://img.example.com/b.png")

	_, created := env.doJSON(http.MethodPost, "/api/collab/playlists", aliceTok, map[string]interface{}{
		"title": "Durable Playlist",
	})
	pid := created["playlist"].(map[string]interface{})["id"].(string)

	_, inv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), aliceTok, map[string]interface{}{"role": "EDITOR"})
	_, _ = env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", inv["token"].(string)), bobTok, nil)

	// 1. opId duplicate-event protection on POST /tracks, PATCH /{id}, PUT /tracks/order, DELETE /tracks/{entryId}
	for range 3 {
		st, resp := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/tracks", pid), aliceTok, map[string]interface{}{
			"opId":  "op-add-1",
			"track": map[string]interface{}{"trackId": "s1", "title": "Idempotent Song", "artist": "X"},
		})
		if st != http.StatusOK {
			t.Fatalf("opId add failed: %d %v", st, resp)
		}
	}
	_, detailAfterAdd := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/playlists/%s", pid), aliceTok, nil)
	tracks := detailAfterAdd["playlist"].(map[string]interface{})["tracks"].([]interface{})
	if len(tracks) != 1 {
		t.Fatalf("opId retry duplicated track: got %d tracks", len(tracks))
	}
	firstEntryID := tracks[0].(map[string]interface{})["entryId"].(string)

	// Retry DELETE with same opId -> both succeed with 200 OK instead of 404!
	for range 2 {
		st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s/tracks/%s?opId=op-del-1", pid, firstEntryID), aliceTok, nil)
		if st != http.StatusOK {
			t.Fatalf("idempotent DELETE failed with status %d", st)
		}
	}

	// 2. Concurrent track additions from 20 goroutines (Alice & Bob)
	const workers = 20
	var wg sync.WaitGroup
	for i := range workers {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			tok := aliceTok
			if idx%2 == 1 {
				tok = bobTok
			}
			st, _ := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/tracks", pid), tok, map[string]interface{}{
				"opId":  fmt.Sprintf("conc-op-%d", idx),
				"track": map[string]interface{}{"trackId": fmt.Sprintf("vid_%d", idx), "title": fmt.Sprintf("Song %d", idx), "artist": "Concurrent"},
			})
			if st != http.StatusOK {
				t.Errorf("concurrent add %d failed with status %d", idx, st)
			}
		}(i)
	}
	wg.Wait()

	_, afterConc := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/playlists/%s", pid), aliceTok, nil)
	concTracks := afterConc["playlist"].(map[string]interface{})["tracks"].([]interface{})
	if len(concTracks) != workers {
		t.Fatalf("expected %d tracks after concurrent adds, got %d", workers, len(concTracks))
	}
	for i, raw := range concTracks {
		if int(raw.(map[string]interface{})["position"].(float64)) != i {
			t.Fatalf("non-contiguous position at %d: %v", i, raw)
		}
	}

	// 3. Verify on-disk snapshot JSON does NOT contain raw session tokens, then reload store from disk!
	diskBytes, err := os.ReadFile(dbPath)
	if err != nil {
		t.Fatalf("read snapshot %q: %v", dbPath, err)
	}
	if strings.Contains(string(diskBytes), aliceTok) || strings.Contains(string(diskBytes), bobTok) {
		t.Fatal("SECURITY VIOLATION: raw session token persisted to disk!")
	}

	env.reloadStore(dbPath)

	// Existing session tokens STILL authenticate after server restart, and Google sub maps to identical usr_ ID!
	stMe, meResp := env.doJSON(http.MethodGet, "/api/collab/auth/me", aliceTok, nil)
	if stMe != http.StatusOK || meResp["user"].(map[string]interface{})["id"] != aliceID {
		t.Fatalf("reloaded session auth failed: %d %v", stMe, meResp)
	}
	_, reSignedID := env.signIn("sub-bob", "bob@example.com", "Bob Reloaded", "https://img.example.com/b.png")
	if reSignedID != bobID {
		t.Fatalf("Google sub failed to keep stable user ID across restart: %q != %q", reSignedID, bobID)
	}

	stPl, reloadedPl := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/playlists/%s", pid), aliceTok, nil)
	if stPl != http.StatusOK || len(reloadedPl["playlist"].(map[string]interface{})["tracks"].([]interface{})) != workers {
		t.Fatalf("reloaded playlist lost tracks: %d %v", stPl, reloadedPl)
	}
}

func TestStopSharingAndRealtimeWebSocketReconciliation(t *testing.T) {
	env := newCollabTestEnv(t, ":memory:")
	defer env.close()

	aliceTok, _ := env.signIn("sub-alice", "alice@example.com", "Alice", "")
	bobTok, bobID := env.signIn("sub-bob", "bob@example.com", "Bob", "")
	carolTok, _ := env.signIn("sub-carol", "carol@example.com", "Carol", "")

	_, created := env.doJSON(http.MethodPost, "/api/collab/playlists", aliceTok, map[string]interface{}{
		"title": "Live Collab Room",
		"tracks": []map[string]interface{}{
			{"trackId": "t0", "title": "Opening Track", "artist": "Intro"},
		},
	})
	pid := created["playlist"].(map[string]interface{})["id"].(string)

	_, inv := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/invites", pid), aliceTok, map[string]interface{}{"role": "EDITOR"})
	invTok := inv["token"].(string)
	_, _ = env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", invTok), bobTok, nil)
	_, _ = env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/invites/%s/accept", invTok), carolTok, nil)

	// Connect Alice via Bearer header, Bob via browser Sec-WebSocket-Protocol!
	wsAlice, _ := env.dialWS(aliceTok, false)
	defer wsAlice.Close()

	wsBob, bobUpgradeResp := env.dialWS(bobTok, true)
	defer wsBob.Close()
	if gotProto := bobUpgradeResp.Header.Get("Sec-WebSocket-Protocol"); gotProto != CollabSocketProtocol {
		t.Fatalf("expected echoed subprotocol %q, got %q", CollabSocketProtocol, gotProto)
	}

	// Ping -> Pong
	if err := wsAlice.WriteJSON(map[string]interface{}{"type": "ping", "clientMs": 777}); err != nil {
		t.Fatalf("write ping: %v", err)
	}
	pong := readWSFrame(t, wsAlice, "pong")
	if pong["clientMs"] != float64(777) {
		t.Fatalf("unexpected pong: %+v", pong)
	}

	// Alice subscribes with sinceRevision=0 -> gets immediate collab.snapshot
	_ = wsAlice.WriteJSON(map[string]interface{}{"type": "subscribe", "playlistId": pid, "sinceRevision": 0})
	snap := readWSFrame(t, wsAlice, EventSnapshot)
	currentRev := int64(snap["revision"].(float64))

	// Bob subscribes with matching sinceRevision == currentRev -> gets collab.subscribed (upToDate: true)
	_ = wsBob.WriteJSON(map[string]interface{}{"type": "subscribe", "playlistId": pid, "sinceRevision": currentRev})
	subAck := readWSFrame(t, wsBob, EventSubscribed)
	if subAck["upToDate"] != true {
		t.Fatalf("expected upToDate=true on current revision subscribe, got %+v", subAck)
	}

	// Carol adds a track over HTTP -> both Alice and Bob receive collab.tracks_changed in realtime!
	_, _ = env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/tracks", pid), carolTok, map[string]interface{}{
		"opId":  "carol-op-1",
		"track": map[string]interface{}{"trackId": "t_carol", "title": "Carol's Song", "artist": "Muse"},
	})
	aliceTracksEvt := readWSFrame(t, wsAlice, EventTracksChanged)
	bobTracksEvt := readWSFrame(t, wsBob, EventTracksChanged)
	if aliceTracksEvt["opId"] != "carol-op-1" || bobTracksEvt["opId"] != "carol-op-1" {
		t.Fatalf("expected opId propagated on tracks_changed: %+v", aliceTracksEvt)
	}
	revBeforeDisconnect := int64(bobTracksEvt["revision"].(float64))

	// Simulate Bob going offline while Alice reorders + renames playlist -> Bob reconnects with sinceRevision = revBeforeDisconnect -> receives collab.snapshot!
	_ = wsBob.Close()

	_, _ = env.doJSON(http.MethodPut, fmt.Sprintf("/api/collab/playlists/%s/tracks/order", pid), aliceTok, map[string]interface{}{
		"fromIndex": 1,
		"toIndex":   0,
	})
	_ = readWSFrame(t, wsAlice, EventTracksChanged)

	_, _ = env.doJSON(http.MethodPatch, fmt.Sprintf("/api/collab/playlists/%s", pid), aliceTok, map[string]interface{}{
		"title": "Renamed Live Room",
	})
	_ = readWSFrame(t, wsAlice, EventMetadataChanged)

	wsBob2, _ := env.dialWS(bobTok, true)
	defer wsBob2.Close()
	_ = wsBob2.WriteJSON(map[string]interface{}{"type": "subscribe", "playlistId": pid, "sinceRevision": revBeforeDisconnect})
	reconciledSnap := readWSFrame(t, wsBob2, EventSnapshot)
	reconciledPl := reconciledSnap["playlist"].(map[string]interface{})
	if reconciledPl["title"] != "Renamed Live Room" || int64(reconciledSnap["revision"].(float64)) <= revBeforeDisconnect {
		t.Fatalf("expected reconciled snapshot with updated title & higher revision, got %+v", reconciledSnap)
	}

	// Alice removes Bob -> Bob receives collab.member_removed AND his subscription is dropped!
	if st, _ := env.doJSON(http.MethodDelete, fmt.Sprintf("/api/collab/playlists/%s/members/%s", pid, bobID), aliceTok, nil); st != http.StatusOK {
		t.Fatalf("remove Bob failed: %d", st)
	}
	removedEvt := readWSFrame(t, wsBob2, EventMemberRemoved)
	if removedEvt["playlistId"] != pid || removedEvt["userId"] != bobID {
		t.Fatalf("unexpected member_removed frame: %+v", removedEvt)
	}
	_ = readWSFrame(t, wsAlice, EventMembersChanged)

	// Bob cannot subscribe again after removal
	_ = wsBob2.WriteJSON(map[string]interface{}{"type": "subscribe", "playlistId": pid, "sinceRevision": 0})
	errFrame := readWSFrame(t, wsBob2, EventError)
	if errFrame["error"] != "forbidden" {
		t.Fatalf("removed member subscribe must return forbidden, got %+v", errFrame)
	}

	// Carol connects WS and subscribes; Alice clicks "Stop sharing" -> Carol gets collab.stopped, invite is revoked,
	// and Alice receives full preserved snapshot including Carol's historical song contribution!
	wsCarol, _ := env.dialWS(carolTok, false)
	defer wsCarol.Close()
	_ = wsCarol.WriteJSON(map[string]interface{}{"type": "subscribe", "playlistId": pid, "sinceRevision": 0})
	_ = readWSFrame(t, wsCarol, EventSnapshot)

	stopStatus, stopResp := env.doJSON(http.MethodPost, fmt.Sprintf("/api/collab/playlists/%s/stop-sharing", pid), aliceTok, nil)
	if stopStatus != http.StatusOK {
		t.Fatalf("stop-sharing failed: %d %v", stopStatus, stopResp)
	}
	stoppedFrame := readWSFrame(t, wsCarol, EventStopped)
	if stoppedFrame["message"] != "The owner stopped sharing this playlist." {
		t.Fatalf("unexpected collab.stopped frame: %+v", stoppedFrame)
	}

	preserved := stopResp["playlist"].(map[string]interface{})
	if preserved["sharingEnabled"] != false {
		t.Fatalf("expected sharingEnabled=false after stop-sharing, got %v", preserved["sharingEnabled"])
	}
	preservedTracks := preserved["tracks"].([]interface{})
	if len(preservedTracks) != 2 || preservedTracks[0].(map[string]interface{})["contributorName"] != "Carol" {
		t.Fatalf("expected Carol's historical track contribution preserved in owner snapshot, got %+v", preservedTracks)
	}

	// Invite preview now reports revoked=true, and Carol gets 403 reading the stopped playlist!
	_, prevAfterStop := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/invites/%s/preview", invTok), "", nil)
	if prevAfterStop["revoked"] != true {
		t.Fatalf("expected invite revoked=true after stop-sharing, got %+v", prevAfterStop)
	}
	if st, _ := env.doJSON(http.MethodGet, fmt.Sprintf("/api/collab/playlists/%s", pid), carolTok, nil); st != http.StatusForbidden {
		t.Fatalf("expected 403 for former collaborator after stop-sharing, got %d", st)
	}
}

func TestSnapshotSchemaMigrationHook(t *testing.T) {
	dir := t.TempDir()

	// v0 legacy file (missing version) upgrades cleanly to v1
	v0Path := filepath.Join(dir, "v0.json")
	if err := os.WriteFile(v0Path, []byte(`{"users":[{"id":"usr_000000000000000000000001","googleSub":"sub-v0","email":"v0@example.com","displayName":"Legacy User","createdAtMs":100,"updatedAtMs":100}]}`), 0o600); err != nil {
		t.Fatalf("write v0: %v", err)
	}
	s, err := NewStore(v0Path, DefaultStoreLimits())
	if err != nil {
		t.Fatalf("expected v0 -> v1 migration to succeed, got: %v", err)
	}
	if s.users["usr_000000000000000000000001"] == nil {
		t.Fatal("expected migrated v0 user to load")
	}

	// Unsupported future version is safely rejected
	v99Path := filepath.Join(dir, "v99.json")
	if err := os.WriteFile(v99Path, []byte(`{"version":99}`), 0o600); err != nil {
		t.Fatalf("write v99: %v", err)
	}
	if _, err := NewStore(v99Path, DefaultStoreLimits()); err == nil {
		t.Fatal("expected future schema version 99 to fail safely")
	}
}
