import { ChevronDown, LogOut, Settings, Users } from 'lucide-react';
import { useCallback, useState, type CSSProperties } from 'react';
import { useNavigate } from 'react-router';
import { signOutCollab, useCollabAuth, useCollabSignInPrompt } from '@/collab/auth';
import type { CollabMemberAvatar, CollabTrackEntry } from '@/collab/types';
import { Menu, MenuHeader, MenuItem, MenuSeparator } from '@/components/Menu';
import { Artwork } from '@/components/music/Artwork';
import { sizedArtwork } from '@/lib/artwork';

const AVATAR_PALETTE = [
  'bg-[#fa586a]/22 text-[#ff6b7b]',
  'bg-[#0a84ff]/22 text-[#5ac8fa]',
  'bg-[#32d74b]/22 text-[#4cd964]',
  'bg-[#bf5af2]/22 text-[#d98aff]',
  'bg-[#ff9f0a]/22 text-[#ffb340]',
  'bg-[#64d2ff]/22 text-[#70d7ff]',
];

function avatarTint(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  }
  return AVATAR_PALETTE[Math.abs(hash) % AVATAR_PALETTE.length]!;
}

export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 1).toUpperCase();
  return `${parts[0]!.slice(0, 1)}${parts[parts.length - 1]!.slice(0, 1)}`.toUpperCase();
}

/** Crisp circular avatar with automatic initials fallback when image is absent or throttled. */
export function CollabAvatar({
  src,
  name,
  seed,
  size = 24,
  className = '',
  title,
}: {
  src: string | null | undefined;
  name: string;
  seed?: string | null;
  size?: number;
  className?: string;
  title?: string;
}) {
  const [failed, setFailed] = useState(false);
  const tint = avatarTint(seed || name || 'collab');
  const fontSize = size <= 20 ? 9 : size <= 24 ? 10 : size <= 32 ? 12 : 14;

  if (src && !failed) {
    return (
      <img
        src={src}
        alt={name}
        title={title ?? name}
        width={size}
        height={size}
        referrerPolicy="no-referrer"
        loading="lazy"
        decoding="async"
        onError={() => setFailed(true)}
        className={`inline-block flex-none rounded-full bg-quaternary object-cover ${className}`}
        style={{ width: size, height: size }}
      />
    );
  }

  return (
    <span
      title={title ?? name}
      aria-label={name}
      className={`inline-grid flex-none select-none place-items-center rounded-full font-semibold tracking-tight ${tint} ${className}`}
      style={{ width: size, height: size, fontSize, lineHeight: 1 }}
    >
      {initialsOf(name)}
    </span>
  );
}

/** Overlapping circular member avatar stack for playlist cards and headers. */
export function CollabAvatarStack({
  members,
  totalCount,
  size = 24,
  max = 4,
  ringClass = 'ring-2 ring-page',
}: {
  members: readonly CollabMemberAvatar[];
  totalCount?: number;
  size?: number;
  max?: number;
  ringClass?: string;
}) {
  const visible = members.slice(0, max);
  const count = Math.max(totalCount ?? members.length, visible.length);
  const extra = Math.max(0, count - visible.length);
  if (visible.length === 0) return null;

  return (
    <span className="inline-flex items-center">
      {visible.map((m, idx) => (
        <span
          key={`${m.userId || m.displayName}-${idx}`}
          className={`inline-flex rounded-full ${ringClass}`}
          style={{ marginLeft: idx === 0 ? 0 : -Math.round(size * 0.28), zIndex: visible.length - idx }}
        >
          <CollabAvatar src={m.avatarUrl} name={m.displayName} seed={m.userId} size={size} title={m.displayName} />
        </span>
      ))}
      {extra > 0 && (
        <span
          className={`inline-grid flex-none place-items-center rounded-full bg-thick-material text-primary ${ringClass}`}
          style={{
            width: size,
            height: size,
            marginLeft: -Math.round(size * 0.24),
            fontSize: Math.max(9, Math.round(size * 0.42)),
            fontWeight: 600,
            zIndex: 0,
          }}
          title={`${extra} more ${extra === 1 ? 'collaborator' : 'collaborators'}`}
        >
          +{extra}
        </span>
      )}
    </span>
  );
}

/**
 * Artwork for collaborative playlists: explicit cover URL -> 2x2 track mosaic ->
 * single track art -> Apple-style editorial gradient cover.
 */
export function CollabCover({
  artworkUrl,
  tracks,
  title,
  displayWidth,
  radius = 'var(--radius-artwork-medium)',
  eager,
}: {
  artworkUrl: string | null | undefined;
  tracks?: readonly Pick<CollabTrackEntry, 'thumbnailUrl'>[] | null;
  title: string;
  displayWidth: number;
  radius?: CSSProperties['borderRadius'];
  eager?: boolean;
}) {
  if (artworkUrl) {
    return <Artwork src={artworkUrl} displayWidth={displayWidth} radius={radius} eager={eager} />;
  }

  const thumbs: string[] = [];
  if (tracks) {
    for (const t of tracks) {
      if (t.thumbnailUrl && !thumbs.includes(t.thumbnailUrl)) {
        thumbs.push(t.thumbnailUrl);
        if (thumbs.length === 4) break;
      }
    }
  }

  if (thumbs.length === 4) {
    const cellWidth = Math.ceil(displayWidth / 2);
    return (
      <div
        className="relative isolate grid aspect-square w-full grid-cols-2 grid-rows-2 overflow-hidden bg-joe artwork-stroke"
        style={{ borderRadius: radius }}
      >
        {thumbs.map((src, i) => (
          <img
            key={`${src}-${i}`}
            src={sizedArtwork(src, cellWidth, 1)}
            alt=""
            referrerPolicy="no-referrer"
            loading={eager ? 'eager' : 'lazy'}
            decoding="async"
            className="size-full object-cover"
          />
        ))}
      </div>
    );
  }

  if (thumbs.length > 0) {
    return <Artwork src={thumbs[0]!} displayWidth={displayWidth} radius={radius} eager={eager} />;
  }

  // Generated editorial cover when empty or no track art yet.
  let hash = 0;
  for (let i = 0; i < title.length; i++) hash = (hash * 33 + title.charCodeAt(i)) | 0;
  const gradients = [
    'linear-gradient(135deg, #fa233b 0%, #8e2de2 100%)',
    'linear-gradient(135deg, #0a84ff 0%, #5e5ce6 100%)',
    'linear-gradient(135deg, #ff375f 0%, #ff9f0a 100%)',
    'linear-gradient(135deg, #30d158 0%, #0a84ff 100%)',
  ];
  const background = gradients[Math.abs(hash) % gradients.length]!;

  return (
    <div
      className="relative isolate flex aspect-square w-full flex-col justify-between overflow-hidden p-4 text-white artwork-stroke"
      style={{ borderRadius: radius, background }}
    >
      <Users size={Math.max(20, Math.round(displayWidth * 0.14))} strokeWidth={2} className="opacity-90" />
      <span className="line-clamp-2 type-title-3-emphasized leading-tight text-white/95 drop-shadow-xs">{title}</span>
    </div>
  );
}

/** Header account status pill for Collaborative Playlists (Google OIDC). */
export function CollabAccountPill() {
  const user = useCollabAuth((s) => s.user);
  const verifying = useCollabAuth((s) => s.verifying);
  const requestSignIn = useCollabSignInPrompt((s) => s.requestSignIn);
  const navigate = useNavigate();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const close = useCallback(() => setAnchor(null), []);

  if (!user) {
    return (
      <button
        type="button"
        onClick={() => requestSignIn()}
        className="inline-flex h-8 items-center gap-1.5 rounded-pill border border-quaternary bg-quinary px-3 type-callout-emphasized text-primary hover:bg-quaternary"
      >
        <Users size={14} className="text-key" />
        <span>{verifying ? 'Verifying…' : 'Sign in for Collab'}</span>
      </button>
    );
  }

  return (
    <>
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={anchor !== null}
        onClick={(e) => setAnchor(anchor ? null : e.currentTarget)}
        className="inline-flex h-8 max-w-[210px] items-center gap-2 rounded-pill border border-quaternary bg-quinary pr-2.5 pl-1 type-callout-emphasized text-primary hover:bg-quaternary"
      >
        <CollabAvatar src={user.avatarUrl} name={user.displayName} seed={user.id} size={22} />
        <span className="min-w-0 truncate">{user.displayName}</span>
        <span className="size-1.5 flex-none rounded-full bg-green" title="Verified Google Account" />
        <ChevronDown size={13} className="flex-none text-secondary" />
      </button>
      {anchor && (
        <Menu anchor={anchor} placement="below-end" onClose={close} label="Collaborative Account">
          <MenuHeader>{user.email || user.displayName}</MenuHeader>
          <MenuItem
            icon={<Settings size={14} />}
            onSelect={() => {
              close();
              navigate('/settings');
            }}
          >
            Collaboration Settings
          </MenuItem>
          <MenuSeparator />
          <MenuItem
            destructive
            icon={<LogOut size={14} />}
            onSelect={() => {
              close();
              void signOutCollab();
            }}
          >
            Sign Out of Collab
          </MenuItem>
        </Menu>
      )}
    </>
  );
}
