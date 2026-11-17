# Collaborative Playlists & Contributor Attribution

Sonara includes a first-party **Collaborative Playlists** system modelled on Apple Music Collaborative Playlists and Spotify Blend contributor attribution across **Android**, **Desktop (macOS, Windows, Linux)**, and **Web**.

Collaborative Playlists are completely decoupled from **Listen Together** (`party/`): a collaborative playlist is persistent, asynchronous or live, and remains accessible at any time regardless of whether a Listen Together session is active.

---

## 1. Dual-Engine Architecture: Zero-Config YouTube Music + Optional Self-Hosted Server

- **Guest Mode Unchanged**: Browsing, searching, local playback, streaming, scrobbling, and Listen Together continue to work in Guest Mode without signing in.
- **Default Zero-Config Mode (Built-In Google / YouTube Music Sign-In)**:
  - Signing into Sonara once with your Google / YouTube Music account (Android WebView, Desktop browser/WebView, or Web companion extension) **immediately activates Collaborative Playlists** with zero extra server URL or OAuth Client ID setup.
  - Uses YouTube Music's native `browse/edit_playlist` collaboration protocol (`ACTION_CREATE_COLLABORATION_INVITE_LINK`, `ACTION_JOIN_COLLABORATION`, `ACTION_SET_CLOSED_TO_CONTRIBUTIONS`, and `PAplaylist_collaborate` collaborator facepile/panel) and interoperates directly with official `https://music.youtube.com/playlist?list=...&jct=...` invite links.
- **Optional Self-Hosted PlusPC Collaboration Server Mode (`backend/`)**:
  - Advanced self-hosters who configure a custom Collaboration Server URL (`https://...`) authenticate via server-verified Google OpenID Connect (`backend/auth/oidc.go`, RS256 JWKS + OAuth 2.0 PKCE S256) with 256-bit `bcs_<64-hex>` session tokens and `/ws/collab` WebSocket sync.
---

## 2. Converting Existing Personal Playlists ("Make Collaborative")

Any existing personal playlist (YouTube Music playlist, device/local playlist, Desktop playlist, or imported Spotify playlist) can be made collaborative directly from its playlist menu (`Make Collaborative`):

1. **Non-Destructive Conversion**: Your original personal playlist is **never deleted or mutated**.
2. **Idempotent Source Mapping**: Re-converting the same `(ownerUserId, sourceType, sourcePlaylistId)` returns the existing active collaborative playlist (`alreadyConverted: true`) rather than creating duplicates.
3. **Owner Attribution**: Existing songs in the converted playlist are attributed to the converting owner (`attributeExistingTracksToOwner: true`) without fabricating historical timestamps.
4. **Reversal ("Stop Sharing" / "Make Private")**: At any time, the playlist owner can choose **Stop Sharing (Make Private)**:
   - All active invite tokens (`bci_...`) are immediately revoked.
   - Collaborators are removed and notified in real time over WebSocket (`collab.stopped`).
   - The owner retains a complete private snapshot of the playlist, including all tracks and historical contributor attribution.

---

## 3. Roles & Granular Permissions

Every member holds one server-enforced role:

| Role | Capabilities |
| :--- | :--- |
| **OWNER** | Edit title/description/artwork, add/remove/reorder any track, create & revoke invite links, change collaborator roles, remove members, configure editor policies, transfer ownership, stop sharing, delete playlist. |
| **EDITOR** | Add tracks; remove tracks (`any` or `own` per playlist policy); reorder tracks (when `editorCanReorder` is enabled); generate invite links (when `editorCanInvite` is enabled); leave playlist. |
| **VIEWER** | View and play the collaborative playlist, filter by contributor, inspect activity feed, and receive live WebSocket updates; leave playlist. |

---

## 4. Invitations & Deep Links

- Invite tokens (`bci_<base64url>`, 192 bits of `crypto/rand` entropy) are validated in constant time on the server.
- Each invite link specifies:
  - Granted role (`EDITOR` or `VIEWER`)
  - Expiration (`24h`, `7d`, custom seconds, or `Never`)
  - Optional maximum redemptions (`maxUses`)
- Supported link formats across platforms:
  - HTTPS landing & Web route: `https://<collab-server>/collab/invite/bci_<token>`
  - Android / Desktop custom scheme: `bitchord://collab/invite/bci_<token>?server=https://...`
  - Android browser intent link: `intent://collab/invite/bci_<token>?server=https://...#Intent;scheme=bitchord;end`
- Logged-out visitors can preview the playlist title, owner, member count, track count, and granted role (`GET /api/collab/invites/{token}/preview`), and are prompted to sign in with Google before `POST /api/collab/invites/{token}/accept` adds them to the playlist.

---

## 5. Realtime Synchronization Protocol (`/ws/collab`)

- Authenticated via `Authorization: Bearer <sessionToken>` (Android/Desktop) or `Sec-WebSocket-Protocol: bitchord.collab.v1, bitchord.token.<sessionToken>` (Web browsers).
- Clients subscribe per playlist with `{"type": "subscribe", "playlistId": "cpl_...", "sinceRevision": <rev>}`:
  - If `sinceRevision < serverRevision`, the server pushes a full `collab.snapshot` immediately so reconnecting clients reconcile missed offline edits cleanly.
  - Otherwise, incremental monotonic-revision events (`collab.tracks_changed`, `collab.metadata_changed`, `collab.member_joined`, `collab.members_changed`, `collab.member_removed`, `collab.stopped`) keep every open client synchronized in real time.
- Duplicate mutations are prevented on both ends via `(userId, opId)` idempotency tracking.

---

## 6. Backend Configuration (`backend/`)

| Environment Variable | Default | Description |
| :--- | :--- | :--- |
| `COLLAB_DATA_PATH` | `./data/collab.json` | Atomic fsync+rename JSON persistence path (`:memory:` for ephemeral tests). |
| `COLLAB_GOOGLE_CLIENT_IDS` | *(empty)* | Comma-separated allowed Google OAuth 2.0 Client IDs (`aud` claim). |
| `COLLAB_GOOGLE_CLIENT_SECRET` | *(empty)* | Optional client secret for confidential OAuth Web clients during PKCE code exchange. |
| `COLLAB_SESSION_TTL_MS` | `2592000000` (30d) | Session token lifetime in milliseconds. |
| `ALLOWED_ORIGINS` | `*` | Comma-separated allowed CORS and WebSocket `Origin` values. |
