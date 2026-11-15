package auth

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type memSessionStore struct {
	mu       sync.RWMutex
	users    map[string]*User          // userID -> User
	bySub    map[string]string         // googleSub -> userID
	sessions map[string]*SessionRecord // tokenHash -> SessionRecord
}

func newMemSessionStore() *memSessionStore {
	return &memSessionStore{
		users:    make(map[string]*User),
		bySub:    make(map[string]string),
		sessions: make(map[string]*SessionRecord),
	}
}

func (s *memSessionStore) UpsertGoogleUserAndCreateSession(identity VerifiedIdentity, tokenHash string, nowMs int64, ttlMs int64) (*User, *SessionRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var u *User
	if uid, ok := s.bySub[identity.Sub]; ok {
		u = s.users[uid]
		u.Email = identity.Email
		u.EmailVerified = identity.EmailVerified
		u.DisplayName = identity.DisplayName
		u.AvatarURL = identity.AvatarURL
		u.UpdatedAtMs = nowMs
	} else {
		uid, err := NewUserID()
		if err != nil {
			return nil, nil, err
		}
		u = &User{
			ID:            uid,
			GoogleSub:     identity.Sub,
			Email:         identity.Email,
			EmailVerified: identity.EmailVerified,
			DisplayName:   identity.DisplayName,
			AvatarURL:     identity.AvatarURL,
			CreatedAtMs:   nowMs,
			UpdatedAtMs:   nowMs,
		}
		s.users[uid] = u
		s.bySub[identity.Sub] = uid
	}

	sid, err := NewSessionID()
	if err != nil {
		return nil, nil, err
	}
	rec := &SessionRecord{
		ID:           sid,
		TokenHash:    tokenHash,
		UserID:       u.ID,
		CreatedAtMs:  nowMs,
		ExpiresAtMs:  nowMs + ttlMs,
		LastSeenAtMs: nowMs,
	}
	s.sessions[tokenHash] = rec
	return u.Clone(), rec.Clone(), nil
}

func (s *memSessionStore) AuthenticateSession(tokenHash string, nowMs int64) (*User, *SessionRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	rec, ok := s.sessions[tokenHash]
	if !ok || !ConstantTimeHashEqual(rec.TokenHash, tokenHash) {
		return nil, nil, NewAuthError(http.StatusUnauthorized, "auth_required", "Unknown session.")
	}
	if rec.RevokedAtMs != nil {
		return nil, nil, NewAuthError(http.StatusUnauthorized, "session_revoked", "Session was revoked.")
	}
	if nowMs >= rec.ExpiresAtMs {
		return nil, nil, NewAuthError(http.StatusUnauthorized, "session_expired", "Session expired.")
	}
	rec.LastSeenAtMs = nowMs
	u := s.users[rec.UserID]
	if u == nil {
		return nil, nil, NewAuthError(http.StatusUnauthorized, "auth_required", "User missing.")
	}
	return u.Clone(), rec.Clone(), nil
}

func (s *memSessionStore) RevokeSession(tokenHash string, nowMs int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	rec, ok := s.sessions[tokenHash]
	if !ok {
		return NewAuthError(http.StatusUnauthorized, "auth_required", "Unknown session.")
	}
	rec.RevokedAtMs = &nowMs
	return nil
}

func generateRSAKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate rsa key: %v", err)
	}
	return priv
}

func encodeJWK(kid string, pub *rsa.PublicKey) map[string]interface{} {
	eBytes := big.NewInt(int64(pub.E)).Bytes()
	return map[string]interface{}{
		"kty": "RSA",
		"alg": "RS256",
		"use": "sig",
		"kid": kid,
		"n":   base64.RawURLEncoding.EncodeToString(pub.N.Bytes()),
		"e":   base64.RawURLEncoding.EncodeToString(eBytes),
	}
}

func signTestJWT(t *testing.T, priv *rsa.PrivateKey, header map[string]interface{}, claims map[string]interface{}) string {
	t.Helper()
	hb, err := json.Marshal(header)
	if err != nil {
		t.Fatalf("marshal header: %v", err)
	}
	cb, err := json.Marshal(claims)
	if err != nil {
		t.Fatalf("marshal claims: %v", err)
	}
	signingInput := base64.RawURLEncoding.EncodeToString(hb) + "." + base64.RawURLEncoding.EncodeToString(cb)
	sum := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, priv, crypto.SHA256, sum[:])
	if err != nil {
		t.Fatalf("sign jwt: %v", err)
	}
	return signingInput + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func TestVerifyIDTokenAndClaimsValidation(t *testing.T) {
	key1 := generateRSAKey(t)
	key2 := generateRSAKey(t)
	otherKey := generateRSAKey(t)

	fixedNow := time.Date(2026, 11, 15, 12, 0, 0, 0, time.UTC)
	nowSec := fixedNow.Unix()

	var currentKeys atomic.Value
	currentKeys.Store([]map[string]interface{}{encodeJWK("kid-1", &key1.PublicKey)})
	var jwksFetches atomic.Int32

	jwksSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		jwksFetches.Add(1)
		w.Header().Set("Cache-Control", "public, max-age=3600")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"keys": currentKeys.Load(),
		})
	}))
	defer jwksSrv.Close()

	provider := NewHTTPJWKSProvider(jwksSrv.URL, jwksSrv.Client())
	provider.Now = func() time.Time { return fixedNow }

	verifier := NewVerifier(VerifierConfig{
		KeyProvider:      provider,
		AllowedClientIDs: []string{"web-client.apps.googleusercontent.com", "desktop-client.apps.googleusercontent.com"},
		Now:              func() time.Time { return fixedNow },
	})

	// 1. Valid Google ID token
	validToken := signTestJWT(t, key1,
		map[string]interface{}{"alg": "RS256", "kid": "kid-1", "typ": "JWT"},
		map[string]interface{}{
			"iss":            "https://accounts.google.com",
			"sub":            "google-sub-alice-123",
			"aud":            "web-client.apps.googleusercontent.com",
			"exp":            nowSec + 3600,
			"iat":            nowSec - 10,
			"email":          "alice@example.com",
			"email_verified": true,
			"name":           "Alice Wonderland",
			"picture":        "https://lh3.googleusercontent.com/a/alice-avatar",
		},
	)

	ident, err := verifier.VerifyIDToken(context.Background(), validToken)
	if err != nil {
		t.Fatalf("expected valid token to pass, got error: %v", err)
	}
	if ident.Sub != "google-sub-alice-123" || ident.Email != "alice@example.com" || !ident.EmailVerified {
		t.Fatalf("unexpected identity: %+v", ident)
	}
	if ident.DisplayName != "Alice Wonderland" || ident.AvatarURL == nil || *ident.AvatarURL != "https://lh3.googleusercontent.com/a/alice-avatar" {
		t.Fatalf("unexpected profile claims: %+v", ident)
	}
	if jwksFetches.Load() != 1 {
		t.Fatalf("expected 1 JWKS fetch, got %d", jwksFetches.Load())
	}

	// Second verification with cached kid-1 must not re-fetch JWKS.
	if _, err := verifier.VerifyIDToken(context.Background(), validToken); err != nil {
		t.Fatalf("cached verification failed: %v", err)
	}
	if jwksFetches.Load() != 1 {
		t.Fatalf("expected cached JWKS key to avoid refetch, got %d fetches", jwksFetches.Load())
	}

	// 2. Alternate valid issuer "accounts.google.com", array aud, fallback name from email, insecure http picture stripped
	altToken := signTestJWT(t, key1,
		map[string]interface{}{"alg": "RS256", "kid": "kid-1"},
		map[string]interface{}{
			"iss":     "accounts.google.com",
			"sub":     "google-sub-bob-456",
			"aud":     []string{"other-aud", "desktop-client.apps.googleusercontent.com"},
			"exp":     nowSec - 30, // within 60s skew tolerance
			"iat":     nowSec + 30, // within 60s skew tolerance
			"email":   "bob.builder@example.com",
			"picture": "http://insecure.example.com/pic.png",
		},
	)
	identBob, err := verifier.VerifyIDToken(context.Background(), altToken)
	if err != nil {
		t.Fatalf("expected alt valid token to pass: %v", err)
	}
	if identBob.DisplayName != "bob.builder" {
		t.Errorf("expected email local-part fallback 'bob.builder', got %q", identBob.DisplayName)
	}
	if identBob.AvatarURL != nil {
		t.Errorf("expected http:// picture to be rejected, got %v", *identBob.AvatarURL)
	}

	// 3. Expired token (beyond 60s skew)
	expiredToken := signTestJWT(t, key1,
		map[string]interface{}{"alg": "RS256", "kid": "kid-1"},
		map[string]interface{}{
			"iss": "https://accounts.google.com",
			"sub": "google-sub-alice-123",
			"aud": "web-client.apps.googleusercontent.com",
			"exp": nowSec - 61,
			"iat": nowSec - 3600,
		},
	)
	if _, err := verifier.VerifyIDToken(context.Background(), expiredToken); err == nil {
		t.Fatal("expected expired token to be rejected")
	}

	// 4. Future iat (beyond 60s skew)
	futureIatToken := signTestJWT(t, key1,
		map[string]interface{}{"alg": "RS256", "kid": "kid-1"},
		map[string]interface{}{
			"iss": "https://accounts.google.com",
			"sub": "google-sub-alice-123",
			"aud": "web-client.apps.googleusercontent.com",
			"exp": nowSec + 3600,
			"iat": nowSec + 120,
		},
	)
	if _, err := verifier.VerifyIDToken(context.Background(), futureIatToken); err == nil {
		t.Fatal("expected future-iat token to be rejected")
	}

	// 5. Wrong issuer
	wrongIssToken := signTestJWT(t, key1,
		map[string]interface{}{"alg": "RS256", "kid": "kid-1"},
		map[string]interface{}{
			"iss": "https://login.evil.example",
			"sub": "google-sub-alice-123",
			"aud": "web-client.apps.googleusercontent.com",
			"exp": nowSec + 3600,
			"iat": nowSec,
		},
	)
	if _, err := verifier.VerifyIDToken(context.Background(), wrongIssToken); err == nil {
		t.Fatal("expected wrong issuer to be rejected")
	}

	// 6. Wrong audience
	wrongAudToken := signTestJWT(t, key1,
		map[string]interface{}{"alg": "RS256", "kid": "kid-1"},
		map[string]interface{}{
			"iss": "https://accounts.google.com",
			"sub": "google-sub-alice-123",
			"aud": "unrelated-app.apps.googleusercontent.com",
			"exp": nowSec + 3600,
			"iat": nowSec,
		},
	)
	if _, err := verifier.VerifyIDToken(context.Background(), wrongAudToken); err == nil {
		t.Fatal("expected wrong audience to be rejected")
	}

	// 7. Empty AllowedClientIDs rejects unless AllowAnyAudience=true
	strictEmptyVerifier := NewVerifier(VerifierConfig{
		KeyProvider:      provider,
		AllowedClientIDs: nil,
		AllowAnyAudience: false,
		Now:              func() time.Time { return fixedNow },
	})
	if _, err := strictEmptyVerifier.VerifyIDToken(context.Background(), validToken); err == nil {
		t.Fatal("expected empty AllowedClientIDs with AllowAnyAudience=false to reject")
	}
	permissiveVerifier := NewVerifier(VerifierConfig{
		KeyProvider:      provider,
		AllowedClientIDs: nil,
		AllowAnyAudience: true,
		Now:              func() time.Time { return fixedNow },
	})
	if _, err := permissiveVerifier.VerifyIDToken(context.Background(), validToken); err != nil {
		t.Fatalf("expected AllowAnyAudience=true to accept valid Google token: %v", err)
	}

	// 8. Tampered signature / wrong signing key
	wrongKeyToken := signTestJWT(t, otherKey,
		map[string]interface{}{"alg": "RS256", "kid": "kid-1"},
		map[string]interface{}{
			"iss": "https://accounts.google.com",
			"sub": "google-sub-alice-123",
			"aud": "web-client.apps.googleusercontent.com",
			"exp": nowSec + 3600,
			"iat": nowSec,
		},
	)
	if _, err := verifier.VerifyIDToken(context.Background(), wrongKeyToken); err == nil {
		t.Fatal("expected token signed with wrong RSA key to be rejected")
	}

	parts := strings.Split(validToken, ".")
	tamperedClaims := base64.RawURLEncoding.EncodeToString([]byte(`{"iss":"https://accounts.google.com","sub":"admin","aud":"web-client.apps.googleusercontent.com","exp":9999999999,"iat":1000}`))
	tamperedToken := parts[0] + "." + tamperedClaims + "." + parts[2]
	if _, err := verifier.VerifyIDToken(context.Background(), tamperedToken); err == nil {
		t.Fatal("expected tampered payload token to be rejected")
	}

	// 9. Unknown kid triggers automatic JWKS refresh and succeeds once key2 is published!
	currentKeys.Store([]map[string]interface{}{
		encodeJWK("kid-1", &key1.PublicKey),
		encodeJWK("kid-2", &key2.PublicKey),
	})
	rotatedToken := signTestJWT(t, key2,
		map[string]interface{}{"alg": "RS256", "kid": "kid-2"},
		map[string]interface{}{
			"iss": "https://accounts.google.com",
			"sub": "google-sub-rotated",
			"aud": "web-client.apps.googleusercontent.com",
			"exp": nowSec + 3600,
			"iat": nowSec,
		},
	)
	identRot, err := verifier.VerifyIDToken(context.Background(), rotatedToken)
	if err != nil {
		t.Fatalf("expected unknown kid-2 to refresh JWKS and succeed, got: %v", err)
	}
	if identRot.Sub != "google-sub-rotated" || identRot.DisplayName != "BitChord Listener" {
		t.Fatalf("unexpected rotated identity: %+v", identRot)
	}
	if jwksFetches.Load() != 2 {
		t.Fatalf("expected exactly 2 JWKS fetches after rotation, got %d", jwksFetches.Load())
	}
}

func TestCodeExchangeAndSessionLifecycle(t *testing.T) {
	priv := generateRSAKey(t)
	fixedNow := time.Date(2026, 11, 15, 14, 0, 0, 0, time.UTC)
	nowMs := fixedNow.UnixMilli()

	jwksSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"keys": []map[string]interface{}{encodeJWK("k-pkce", &priv.PublicKey)},
		})
	}))
	defer jwksSrv.Close()

	const clientID = "desktop-pkce.apps.googleusercontent.com"

	tokenSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad form", http.StatusBadRequest)
			return
		}
		if r.FormValue("code") != "valid-auth-code" || r.FormValue("code_verifier") != "pkce-verifier-123" || r.FormValue("client_id") != clientID {
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(map[string]string{
				"error":             "invalid_grant",
				"error_description": "Invalid code or verifier.",
			})
			return
		}
		idTok := signTestJWT(t, priv,
			map[string]interface{}{"alg": "RS256", "kid": "k-pkce"},
			map[string]interface{}{
				"iss":            "https://accounts.google.com",
				"sub":            "sub-stable-999",
				"aud":            clientID,
				"exp":            fixedNow.Unix() + 3600,
				"iat":            fixedNow.Unix(),
				"email":          "user@bitchord.dev",
				"email_verified": true,
				"name":           "PKCE User",
				"picture":        "https://lh3.googleusercontent.com/a/pkce",
			},
		)
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"access_token": "ya29.mock",
			"id_token":     idTok,
			"token_type":   "Bearer",
			"expires_in":   3599,
		})
	}))
	defer tokenSrv.Close()

	verifier := NewVerifier(VerifierConfig{
		JWKSURL:          jwksSrv.URL,
		TokenURL:         tokenSrv.URL,
		AllowedClientIDs: []string{clientID},
		HTTPClient:       tokenSrv.Client(),
		Now:              func() time.Time { return fixedNow },
	})

	store := newMemSessionStore()
	mgr := NewManager(verifier, store, 3600*1000)
	mgr.NowMs = func() int64 { return nowMs }

	user1, rawToken1, exp1, err := mgr.SignInWithCode(context.Background(), CodeExchangeRequest{
		Code:         "valid-auth-code",
		CodeVerifier: "pkce-verifier-123",
		RedirectURI:  "http://127.0.0.1:49215/callback",
		ClientID:     clientID,
	})
	if err != nil {
		t.Fatalf("SignInWithCode failed: %v", err)
	}
	if !strings.HasPrefix(user1.ID, "usr_") || len(user1.ID) != 28 {
		t.Fatalf("expected usr_<24-hex> user ID, got %q", user1.ID)
	}
	if !IsWellFormedSessionToken(rawToken1) {
		t.Fatalf("expected bcs_<64-hex> session token, got %q", rawToken1)
	}
	if exp1 != nowMs+3600*1000 {
		t.Fatalf("unexpected expiresAtMs: %d", exp1)
	}
	// Verify raw token is NOT stored in SessionStore map keys
	if _, leaked := store.sessions[rawToken1]; leaked {
		t.Fatal("raw session token must never be stored in store")
	}
	if _, hashedOK := store.sessions[HashSessionToken(rawToken1)]; !hashedOK {
		t.Fatal("expected sha256(token) in store")
	}

	// Re-signing in with the same Google sub maps to the exact same usr_<24-hex> ID and updates profile!
	updatedIDTok := signTestJWT(t, priv,
		map[string]interface{}{"alg": "RS256", "kid": "k-pkce"},
		map[string]interface{}{
			"iss":            "https://accounts.google.com",
			"sub":            "sub-stable-999",
			"aud":            clientID,
			"exp":            fixedNow.Unix() + 3600,
			"iat":            fixedNow.Unix(),
			"email":          "user@bitchord.dev",
			"email_verified": true,
			"name":           "Renamed PKCE User",
		},
	)
	nowMs += 5000
	user2, rawToken2, _, err := mgr.SignInWithIDToken(context.Background(), updatedIDTok)
	if err != nil {
		t.Fatalf("SignInWithIDToken failed: %v", err)
	}
	if user2.ID != user1.ID {
		t.Fatalf("same Google sub must map to stable internal user ID: %q != %q", user2.ID, user1.ID)
	}
	if user2.DisplayName != "Renamed PKCE User" {
		t.Fatalf("expected updated displayName, got %q", user2.DisplayName)
	}
	if rawToken1 == rawToken2 {
		t.Fatal("expected distinct session tokens per sign-in")
	}

	// Authenticate session 1
	authedUser, _, err := mgr.Authenticate(rawToken1)
	if err != nil || authedUser.ID != user1.ID {
		t.Fatalf("Authenticate failed: %v", err)
	}

	// SignOut revokes session 1 immediately while session 2 stays active
	if err := mgr.SignOut(rawToken1); err != nil {
		t.Fatalf("SignOut failed: %v", err)
	}
	if _, _, err := mgr.Authenticate(rawToken1); err == nil {
		t.Fatal("revoked session must fail Authenticate")
	}
	if _, _, err := mgr.Authenticate(rawToken2); err != nil {
		t.Fatalf("second session should still be valid: %v", err)
	}

	// Advance clock past TTL -> session 2 expires
	nowMs += 3600*1000 + 1
	if _, _, err := mgr.Authenticate(rawToken2); err == nil {
		t.Fatal("expired session must fail Authenticate")
	}
}
