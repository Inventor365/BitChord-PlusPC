package auth

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/KabirSinghBhatia/BitChord/backend/clock"
)

const (
	DefaultGoogleJWKSURL  = "https://www.googleapis.com/oauth2/v3/certs"
	DefaultGoogleTokenURL = "https://oauth2.googleapis.com/token"

	UserIDPrefix       = "usr_"
	SessionIDPrefix    = "ses_"
	SessionTokenPrefix = "bcs_"

	clockSkewSec = 60
	maxJWTBytes  = 16 * 1024
)

// AuthError carries an HTTP status code and machine-readable error code.
type AuthError struct {
	Status  int    `json:"status"`
	Code    string `json:"error"`
	Message string `json:"message"`
}

func (e *AuthError) Error() string {
	return e.Message
}

func NewAuthError(status int, code, message string) *AuthError {
	return &AuthError{Status: status, Code: code, Message: message}
}

var ErrKeyNotFound = errors.New("jwks key id not found")

// VerifiedIdentity holds server-verified claims extracted from a Google OIDC ID token.
type VerifiedIdentity struct {
	Sub           string
	Email         string
	EmailVerified bool
	DisplayName   string
	AvatarURL     *string
}

// User is the persistent internal BitChord collaborator account mapped 1:1 to a Google sub.
type User struct {
	ID            string  `json:"id"`
	GoogleSub     string  `json:"googleSub"`
	Email         string  `json:"email"`
	EmailVerified bool    `json:"emailVerified"`
	DisplayName   string  `json:"displayName"`
	AvatarURL     *string `json:"avatarUrl"`
	CreatedAtMs   int64   `json:"createdAtMs"`
	UpdatedAtMs   int64   `json:"updatedAtMs"`
}

// Clone returns a deep copy of User safe to hand across goroutines.
func (u *User) Clone() *User {
	if u == nil {
		return nil
	}
	cp := *u
	if u.AvatarURL != nil {
		av := *u.AvatarURL
		cp.AvatarURL = &av
	}
	return &cp
}

// ToWire returns the public JSON representation of a User (excluding raw GoogleSub).
func (u *User) ToWire() map[string]interface{} {
	var avatar interface{}
	if u.AvatarURL != nil && *u.AvatarURL != "" {
		avatar = *u.AvatarURL
	}
	return map[string]interface{}{
		"id":            u.ID,
		"email":         u.Email,
		"emailVerified": u.EmailVerified,
		"displayName":   u.DisplayName,
		"avatarUrl":     avatar,
		"createdAtMs":   u.CreatedAtMs,
		"updatedAtMs":   u.UpdatedAtMs,
	}
}

// SessionRecord stores only sha256(token) on disk; raw tokens are never persisted.
type SessionRecord struct {
	ID           string `json:"id"`
	TokenHash    string `json:"tokenHash"`
	UserID       string `json:"userId"`
	CreatedAtMs  int64  `json:"createdAtMs"`
	ExpiresAtMs  int64  `json:"expiresAtMs"`
	RevokedAtMs  *int64 `json:"revokedAtMs,omitempty"`
	LastSeenAtMs int64  `json:"lastSeenAtMs"`
}

func (s *SessionRecord) Clone() *SessionRecord {
	if s == nil {
		return nil
	}
	cp := *s
	if s.RevokedAtMs != nil {
		rev := *s.RevokedAtMs
		cp.RevokedAtMs = &rev
	}
	return &cp
}

// SessionStore abstracts durable storage of Google-verified users and hashed sessions.
type SessionStore interface {
	UpsertGoogleUserAndCreateSession(identity VerifiedIdentity, tokenHash string, nowMs int64, ttlMs int64) (*User, *SessionRecord, error)
	AuthenticateSession(tokenHash string, nowMs int64) (*User, *SessionRecord, error)
	RevokeSession(tokenHash string, nowMs int64) error
}

// NewUserID generates a persistent internal BitChord User ID: usr_<24-hex> (96 random bits).
func NewUserID() (string, error) {
	var b [12]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return UserIDPrefix + hex.EncodeToString(b[:]), nil
}

// NewSessionID generates an internal session record ID: ses_<24-hex>.
func NewSessionID() (string, error) {
	var b [12]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return SessionIDPrefix + hex.EncodeToString(b[:]), nil
}

// NewSessionToken mints a 256-bit crypto/rand hex session token (bcs_<64-hex>)
// and returns both the raw token (for the client) and its SHA-256 hex digest (for disk storage).
func NewSessionToken() (rawToken string, tokenHash string, err error) {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", "", err
	}
	rawToken = SessionTokenPrefix + hex.EncodeToString(b[:])
	return rawToken, HashSessionToken(rawToken), nil
}

// HashSessionToken computes the lowercase hex-encoded SHA-256 digest of a raw session token.
func HashSessionToken(rawToken string) string {
	sum := sha256.Sum256([]byte(rawToken))
	return hex.EncodeToString(sum[:])
}

// IsWellFormedSessionToken checks that rawToken has the bcs_<64-hex> structure.
func IsWellFormedSessionToken(rawToken string) bool {
	if len(rawToken) != len(SessionTokenPrefix)+64 || !strings.HasPrefix(rawToken, SessionTokenPrefix) {
		return false
	}
	hexPart := rawToken[len(SessionTokenPrefix):]
	for i := range len(hexPart) {
		c := hexPart[i]
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

// ConstantTimeHashEqual compares two SHA-256 hex strings in constant time.
func ConstantTimeHashEqual(a, b string) bool {
	if len(a) != len(b) || len(a) == 0 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// KeyProvider supplies RSA public keys by JWT kid for OIDC signature verification.
type KeyProvider interface {
	PublicKey(ctx context.Context, kid string, forceRefresh bool) (*rsa.PublicKey, error)
}

// HTTPJWKSProvider fetches and caches Google's JWKS RSA public keys over HTTPS.
type HTTPJWKSProvider struct {
	JWKSURL            string
	Client             *http.Client
	Now                func() time.Time
	MinRefreshInterval time.Duration

	mu          sync.RWMutex
	keys        map[string]*rsa.PublicKey
	expiresAt   time.Time
	lastFetchAt time.Time
	refreshMu   sync.Mutex
}

// NewHTTPJWKSProvider creates a caching JWKS provider for jwksURL.
func NewHTTPJWKSProvider(jwksURL string, client *http.Client) *HTTPJWKSProvider {
	if strings.TrimSpace(jwksURL) == "" {
		jwksURL = DefaultGoogleJWKSURL
	}
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	return &HTTPJWKSProvider{
		JWKSURL: jwksURL,
		Client:  client,
		Now:     time.Now,
		keys:    make(map[string]*rsa.PublicKey),
	}
}

func (p *HTTPJWKSProvider) now() time.Time {
	if p.Now != nil {
		return p.Now()
	}
	return time.Now()
}

func (p *HTTPJWKSProvider) PublicKey(ctx context.Context, kid string, forceRefresh bool) (*rsa.PublicKey, error) {
	now := p.now()
	if !forceRefresh {
		p.mu.RLock()
		key := p.keys[kid]
		fresh := now.Before(p.expiresAt)
		p.mu.RUnlock()
		if fresh && key != nil {
			return key, nil
		}
	}

	p.refreshMu.Lock()
	defer p.refreshMu.Unlock()

	now = p.now()
	p.mu.RLock()
	existing := p.keys[kid]
	fresh := now.Before(p.expiresAt)
	sinceLast := now.Sub(p.lastFetchAt)
	hasFetched := !p.lastFetchAt.IsZero()
	p.mu.RUnlock()

	if !forceRefresh && fresh && existing != nil {
		return existing, nil
	}
	if forceRefresh && hasFetched && p.MinRefreshInterval > 0 && sinceLast < p.MinRefreshInterval {
		if existing != nil {
			return existing, nil
		}
		return nil, ErrKeyNotFound
	}

	if err := p.refresh(ctx); err != nil {
		if existing != nil {
			return existing, nil
		}
		return nil, err
	}

	p.mu.RLock()
	key := p.keys[kid]
	p.mu.RUnlock()
	if key == nil {
		return nil, ErrKeyNotFound
	}
	return key, nil
}

type jwksDocument struct {
	Keys []jwkEntry `json:"keys"`
}

type jwkEntry struct {
	Kty string `json:"kty"`
	Alg string `json:"alg"`
	Use string `json:"use"`
	Kid string `json:"kid"`
	N   string `json:"n"`
	E   string `json:"e"`
}

func (p *HTTPJWKSProvider) refresh(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, p.JWKSURL, nil)
	if err != nil {
		return fmt.Errorf("build jwks request: %w", err)
	}
	resp, err := p.Client.Do(req)
	if err != nil {
		return fmt.Errorf("fetch jwks: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("jwks endpoint returned status %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 256*1024))
	if err != nil {
		return fmt.Errorf("read jwks: %w", err)
	}

	var doc jwksDocument
	if err := json.Unmarshal(body, &doc); err != nil {
		return fmt.Errorf("decode jwks: %w", err)
	}

	parsed := make(map[string]*rsa.PublicKey, len(doc.Keys))
	for _, k := range doc.Keys {
		if k.Kty != "RSA" || k.Kid == "" {
			continue
		}
		if k.Use != "" && k.Use != "sig" {
			continue
		}
		if k.Alg != "" && k.Alg != "RS256" {
			continue
		}
		pub, err := parseRSAPublicKey(k.N, k.E)
		if err != nil {
			continue
		}
		parsed[k.Kid] = pub
	}
	if len(parsed) == 0 {
		return errors.New("jwks contained no valid RSA signing keys")
	}

	ttl := parseCacheMaxAge(resp.Header.Get("Cache-Control"))
	now := p.now()

	p.mu.Lock()
	p.keys = parsed
	p.lastFetchAt = now
	p.expiresAt = now.Add(ttl)
	p.mu.Unlock()
	return nil
}

func parseRSAPublicKey(nB64, eB64 string) (*rsa.PublicKey, error) {
	nBytes, err := base64.RawURLEncoding.DecodeString(nB64)
	if err != nil || len(nBytes) == 0 {
		return nil, errors.New("invalid jwk modulus")
	}
	eBytes, err := base64.RawURLEncoding.DecodeString(eB64)
	if err != nil || len(eBytes) == 0 || len(eBytes) > 8 {
		return nil, errors.New("invalid jwk exponent")
	}

	n := new(big.Int).SetBytes(nBytes)
	if n.BitLen() < 2048 {
		return nil, errors.New("rsa modulus smaller than 2048 bits")
	}

	var eUint uint64
	for _, b := range eBytes {
		eUint = (eUint << 8) | uint64(b)
	}
	if eUint < 3 || eUint%2 == 0 || eUint > 1<<31-1 {
		return nil, errors.New("invalid rsa public exponent")
	}

	return &rsa.PublicKey{
		N: n,
		E: int(eUint),
	}, nil
}

func parseCacheMaxAge(header string) time.Duration {
	const (
		defaultTTL = time.Hour
		minTTL     = 5 * time.Minute
		maxTTL     = 6 * time.Hour
	)
	for _, directive := range strings.Split(header, ",") {
		d := strings.TrimSpace(directive)
		if strings.HasPrefix(strings.ToLower(d), "max-age=") {
			secs, err := strconv.Atoi(strings.TrimSpace(d[len("max-age="):]))
			if err == nil && secs > 0 {
				ttl := time.Duration(secs) * time.Second
				if ttl < minTTL {
					return minTTL
				}
				if ttl > maxTTL {
					return maxTTL
				}
				return ttl
			}
		}
	}
	return defaultTTL
}

// Verifier validates Google RS256 OIDC ID tokens and performs OAuth2 PKCE code exchanges.
type Verifier struct {
	KeyProvider      KeyProvider
	AllowedClientIDs []string
	AllowAnyAudience bool
	ClientSecret     string
	TokenURL         string
	HTTPClient       *http.Client
	Now              func() time.Time
}

// VerifierConfig configures a Google OIDC Verifier.
type VerifierConfig struct {
	JWKSURL          string
	TokenURL         string
	KeyProvider      KeyProvider
	AllowedClientIDs []string
	AllowAnyAudience bool
	ClientSecret     string
	HTTPClient       *http.Client
	Now              func() time.Time
}

func NewVerifier(cfg VerifierConfig) *Verifier {
	httpClient := cfg.HTTPClient
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 10 * time.Second}
	}
	kp := cfg.KeyProvider
	if kp == nil {
		kp = NewHTTPJWKSProvider(cfg.JWKSURL, httpClient)
	}
	tokenURL := strings.TrimSpace(cfg.TokenURL)
	if tokenURL == "" {
		tokenURL = DefaultGoogleTokenURL
	}
	nowFn := cfg.Now
	if nowFn == nil {
		nowFn = time.Now
	}
	allowed := make([]string, 0, len(cfg.AllowedClientIDs))
	for _, cid := range cfg.AllowedClientIDs {
		if trimmed := strings.TrimSpace(cid); trimmed != "" {
			allowed = append(allowed, trimmed)
		}
	}
	return &Verifier{
		KeyProvider:      kp,
		AllowedClientIDs: allowed,
		AllowAnyAudience: cfg.AllowAnyAudience,
		ClientSecret:     strings.TrimSpace(cfg.ClientSecret),
		TokenURL:         tokenURL,
		HTTPClient:       httpClient,
		Now:              nowFn,
	}
}

type jwtHeader struct {
	Alg string `json:"alg"`
	Kid string `json:"kid"`
	Typ string `json:"typ"`
}

type jwtAudience []string

func (a *jwtAudience) UnmarshalJSON(data []byte) error {
	var single string
	if err := json.Unmarshal(data, &single); err == nil {
		if trimmed := strings.TrimSpace(single); trimmed != "" {
			*a = []string{trimmed}
		}
		return nil
	}
	var list []string
	if err := json.Unmarshal(data, &list); err == nil {
		out := make([]string, 0, len(list))
		for _, item := range list {
			if trimmed := strings.TrimSpace(item); trimmed != "" {
				out = append(out, trimmed)
			}
		}
		*a = out
		return nil
	}
	return errors.New("aud must be a string or array of strings")
}

type flexibleBool bool

func (b *flexibleBool) UnmarshalJSON(data []byte) error {
	var bv bool
	if err := json.Unmarshal(data, &bv); err == nil {
		*b = flexibleBool(bv)
		return nil
	}
	var sv string
	if err := json.Unmarshal(data, &sv); err == nil {
		*b = flexibleBool(strings.EqualFold(strings.TrimSpace(sv), "true"))
		return nil
	}
	return errors.New("invalid boolean")
}

type googleJWTClaims struct {
	Iss           string       `json:"iss"`
	Sub           string       `json:"sub"`
	Aud           jwtAudience  `json:"aud"`
	Exp           int64        `json:"exp"`
	Iat           int64        `json:"iat"`
	Email         string       `json:"email"`
	EmailVerified flexibleBool `json:"email_verified"`
	Name          string       `json:"name"`
	Picture       string       `json:"picture"`
}

func (v *Verifier) isAllowedAudience(audiences []string) bool {
	if len(audiences) == 0 {
		return false
	}
	if len(v.AllowedClientIDs) > 0 {
		for _, aud := range audiences {
			for _, allowed := range v.AllowedClientIDs {
				if aud == allowed {
					return true
				}
			}
		}
		return false
	}
	return v.AllowAnyAudience
}

func (v *Verifier) isAllowedClientID(clientID string) bool {
	clientID = strings.TrimSpace(clientID)
	if clientID == "" {
		return false
	}
	return v.isAllowedAudience([]string{clientID})
}

// VerifyIDToken validates a Google RS256 OIDC ID token and extracts the verified identity.
func (v *Verifier) VerifyIDToken(ctx context.Context, rawToken string) (VerifiedIdentity, error) {
	rawToken = strings.TrimSpace(rawToken)
	if rawToken == "" || len(rawToken) > maxJWTBytes {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Missing or oversized Google ID token.")
	}

	parts := strings.Split(rawToken, ".")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Malformed JWT structure.")
	}

	headerBytes, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Invalid JWT header encoding.")
	}
	var hdr jwtHeader
	if err := json.Unmarshal(headerBytes, &hdr); err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Invalid JWT header JSON.")
	}
	if hdr.Alg != "RS256" {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Unsupported JWT signing algorithm; expected RS256.")
	}
	if strings.TrimSpace(hdr.Kid) == "" {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Missing JWT key ID (kid).")
	}

	sigBytes, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(sigBytes) == 0 {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Invalid JWT signature encoding.")
	}

	pubKey, err := v.KeyProvider.PublicKey(ctx, hdr.Kid, false)
	if errors.Is(err, ErrKeyNotFound) {
		pubKey, err = v.KeyProvider.PublicKey(ctx, hdr.Kid, true)
	}
	if err != nil || pubKey == nil {
		if errors.Is(err, ErrKeyNotFound) {
			return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Unknown Google signing key ID.")
		}
		return VerifiedIdentity{}, NewAuthError(http.StatusServiceUnavailable, "oidc_unavailable", "Unable to fetch Google OIDC signing keys.")
	}

	signingInput := parts[0] + "." + parts[1]
	digest := sha256.Sum256([]byte(signingInput))
	if err := rsa.VerifyPKCS1v15(pubKey, crypto.SHA256, digest[:], sigBytes); err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Google ID token signature verification failed.")
	}

	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Invalid JWT payload encoding.")
	}
	var claims googleJWTClaims
	if err := json.Unmarshal(payloadBytes, &claims); err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_id_token", "Invalid JWT claims JSON.")
	}

	if claims.Iss != "https://accounts.google.com" && claims.Iss != "accounts.google.com" {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_issuer", "Token issuer is not Google Accounts.")
	}

	if !v.isAllowedAudience(claims.Aud) {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_audience", "Token audience does not match an allowed BitChord Google client ID.")
	}

	nowSec := v.Now().Unix()
	if claims.Exp <= 0 || claims.Exp <= nowSec-clockSkewSec {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "token_expired", "Google ID token has expired.")
	}
	if claims.Iat <= 0 || claims.Iat > nowSec+clockSkewSec {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_iat", "Google ID token issued-at timestamp is invalid.")
	}

	sub := strings.TrimSpace(claims.Sub)
	if sub == "" || len(sub) > 255 {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_sub", "Google ID token is missing a valid sub claim.")
	}

	email := strings.TrimSpace(claims.Email)
	if len(email) > 320 {
		email = email[:320]
	}

	displayName := deriveDisplayName(claims.Name, email)
	avatarURL := sanitizeHTTPSURL(claims.Picture)

	return VerifiedIdentity{
		Sub:           sub,
		Email:         email,
		EmailVerified: bool(claims.EmailVerified),
		DisplayName:   displayName,
		AvatarURL:     avatarURL,
	}, nil
}

func deriveDisplayName(name, email string) string {
	trimmed := strings.TrimSpace(name)
	if trimmed == "" && email != "" {
		if at := strings.IndexByte(email, '@'); at > 0 {
			trimmed = strings.TrimSpace(email[:at])
		}
	}
	if trimmed == "" {
		trimmed = "BitChord Listener"
	}
	runes := []rune(trimmed)
	if len(runes) > 80 {
		trimmed = string(runes[:80])
	}
	return trimmed
}

func sanitizeHTTPSURL(raw string) *string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" || len(trimmed) > 1000 {
		return nil
	}
	u, err := url.Parse(trimmed)
	if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Host == "" || u.User != nil {
		return nil
	}
	return &trimmed
}

// CodeExchangeRequest holds OAuth2 Authorization Code + PKCE parameters.
type CodeExchangeRequest struct {
	Code         string `json:"code"`
	CodeVerifier string `json:"codeVerifier"`
	RedirectURI  string `json:"redirectUri"`
	ClientID     string `json:"clientId"`
	ClientSecret string `json:"clientSecret,omitempty"`
}

// ExchangeCode exchanges an OAuth2 authorization code (+ PKCE verifier) at TokenURL
// and verifies the returned Google OIDC id_token.
func (v *Verifier) ExchangeCode(ctx context.Context, req CodeExchangeRequest) (VerifiedIdentity, error) {
	code := strings.TrimSpace(req.Code)
	clientID := strings.TrimSpace(req.ClientID)
	redirectURI := strings.TrimSpace(req.RedirectURI)
	codeVerifier := strings.TrimSpace(req.CodeVerifier)

	if code == "" || clientID == "" || redirectURI == "" {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnprocessableEntity, "validation_error", "code, clientId, and redirectUri are required for code exchange.")
	}
	if !v.isAllowedClientID(clientID) {
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "invalid_audience", "clientId is not an allowed BitChord Google client ID.")
	}

	form := url.Values{}
	form.Set("code", code)
	form.Set("client_id", clientID)
	form.Set("redirect_uri", redirectURI)
	form.Set("grant_type", "authorization_code")
	if codeVerifier != "" {
		form.Set("code_verifier", codeVerifier)
	}
	secret := strings.TrimSpace(req.ClientSecret)
	if secret == "" {
		secret = v.ClientSecret
	}
	if secret != "" {
		form.Set("client_secret", secret)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, v.TokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusInternalServerError, "server_error", "Failed to construct Google token exchange request.")
	}
	httpReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	httpReq.Header.Set("Accept", "application/json")

	resp, err := v.HTTPClient.Do(httpReq)
	if err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusServiceUnavailable, "oidc_unavailable", "Failed to contact Google token endpoint.")
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return VerifiedIdentity{}, NewAuthError(http.StatusServiceUnavailable, "oidc_unavailable", "Failed to read Google token response.")
	}

	var tokenResp struct {
		IDToken          string `json:"id_token"`
		Error            string `json:"error"`
		ErrorDescription string `json:"error_description"`
	}
	_ = json.Unmarshal(body, &tokenResp)

	if resp.StatusCode != http.StatusOK || strings.TrimSpace(tokenResp.IDToken) == "" {
		msg := "Google authorization code exchange failed."
		if tokenResp.ErrorDescription != "" {
			msg = tokenResp.ErrorDescription
		} else if tokenResp.Error != "" {
			msg = "Google token error: " + tokenResp.Error
		}
		return VerifiedIdentity{}, NewAuthError(http.StatusUnauthorized, "code_exchange_failed", msg)
	}

	return v.VerifyIDToken(ctx, tokenResp.IDToken)
}

// Manager coordinates OIDC verification, persistent user mapping, and hashed session lifecycle.
type Manager struct {
	Verifier     *Verifier
	Store        SessionStore
	SessionTTLMs int64
	NowMs        func() int64
}

func NewManager(verifier *Verifier, store SessionStore, sessionTTLMs int64) *Manager {
	if sessionTTLMs <= 0 {
		sessionTTLMs = 30 * 24 * 60 * 60 * 1000
	}
	return &Manager{
		Verifier:     verifier,
		Store:        store,
		SessionTTLMs: sessionTTLMs,
		NowMs:        clock.NowMs,
	}
}

func (m *Manager) nowMs() int64 {
	if m.NowMs != nil {
		return m.NowMs()
	}
	return clock.NowMs()
}

// SignInWithIDToken verifies rawIDToken and mints a fresh BitChord session.
func (m *Manager) SignInWithIDToken(ctx context.Context, rawIDToken string) (*User, string, int64, error) {
	identity, err := m.Verifier.VerifyIDToken(ctx, rawIDToken)
	if err != nil {
		return nil, "", 0, err
	}
	return m.establishSession(identity)
}

// SignInWithCode exchanges an OAuth2 authorization code (+ PKCE), verifies the ID token, and mints a session.
func (m *Manager) SignInWithCode(ctx context.Context, req CodeExchangeRequest) (*User, string, int64, error) {
	identity, err := m.Verifier.ExchangeCode(ctx, req)
	if err != nil {
		return nil, "", 0, err
	}
	return m.establishSession(identity)
}

func (m *Manager) establishSession(identity VerifiedIdentity) (*User, string, int64, error) {
	rawToken, tokenHash, err := NewSessionToken()
	if err != nil {
		return nil, "", 0, NewAuthError(http.StatusInternalServerError, "server_error", "Failed to generate session token.")
	}
	now := m.nowMs()
	user, session, err := m.Store.UpsertGoogleUserAndCreateSession(identity, tokenHash, now, m.SessionTTLMs)
	if err != nil {
		var ae *AuthError
		if errors.As(err, &ae) {
			return nil, "", 0, ae
		}
		return nil, "", 0, NewAuthError(http.StatusInternalServerError, "server_error", "Failed to persist session.")
	}
	return user, rawToken, session.ExpiresAtMs, nil
}

// Authenticate validates a raw bcs_<64-hex> bearer token and returns the verified User.
func (m *Manager) Authenticate(rawToken string) (*User, *SessionRecord, error) {
	rawToken = strings.TrimSpace(rawToken)
	if rawToken == "" {
		return nil, nil, NewAuthError(http.StatusUnauthorized, "auth_required", "Google sign-in is required for collaborative playlists.")
	}
	if !IsWellFormedSessionToken(rawToken) {
		return nil, nil, NewAuthError(http.StatusUnauthorized, "auth_required", "Invalid collaborative session token.")
	}
	tokenHash := HashSessionToken(rawToken)
	user, session, err := m.Store.AuthenticateSession(tokenHash, m.nowMs())
	if err != nil {
		var ae *AuthError
		if errors.As(err, &ae) {
			return nil, nil, ae
		}
		return nil, nil, NewAuthError(http.StatusUnauthorized, "auth_required", "Session is invalid or has expired.")
	}
	return user, session, nil
}

// SignOut revokes a raw session token immediately.
func (m *Manager) SignOut(rawToken string) error {
	rawToken = strings.TrimSpace(rawToken)
	if !IsWellFormedSessionToken(rawToken) {
		return NewAuthError(http.StatusUnauthorized, "auth_required", "Invalid collaborative session token.")
	}
	return m.Store.RevokeSession(HashSessionToken(rawToken), m.nowMs())
}
