#!/usr/bin/env python3
"""Renders the Winter theme's backplates: an arctic sky and a glacier landscape, night and day.

    python3 scripts/winter/render_backplates.py [out_dir]

Writes four WebP files (default: sharedUi/src/commonMain/composeResources/drawable/):

    winter_night_sky.webp        opaque: night gradient, Milky Way, faint stars, horizon airglow
    winter_night_land.webp       with alpha: glacier peaks lit by the aurora, ice field, haze, penguins
    winter_day_sky.webp          opaque: arctic day sky, cirrus, bright horizon haze
    winter_day_land.webp         with alpha: sunlit snow peaks with blue shadows, glacier ice field
    winter_aurora_0..3.webp      with alpha, quarter size: aurora frames for phones without AGSL
                                 (below Android 13), crossfaded and drifted by the app

Everything is procedural and seeded, so the art is original and reproducible (CC0, see
app/src/main/assets/licenses/WINTER-ARTWORK.txt). The app draws, back to front: sky, live stars and
shooting stars, the live aurora shader, the sun (day), the landscape, snow. So the sky carries only
what never moves, and the landscape is cut out so the aurora sits behind the peaks.

Sized for xxhdpi: 1080 wide, the scene's 460 dp tall at 3x = 1380.
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageFilter
from scipy.ndimage import gaussian_filter

W, H = 1080, 1380
HORIZON = 0.70  # share of the height where the ice field meets the peaks' feet
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "../../sharedUi/src/commonMain/composeResources/drawable")


# ---------------------------------------------------------------- noise

def value_noise(shape, scale, rng):
    """Smooth value noise in [0, 1] at a feature size of `scale` pixels."""
    gh, gw = int(shape[0] / scale) + 3, int(shape[1] / scale) + 3
    grid = rng.random((gh, gw))
    ys = np.arange(shape[0]) / scale
    xs = np.arange(shape[1]) / scale
    y0, x0 = ys.astype(int), xs.astype(int)
    fy, fx = ys - y0, xs - x0
    fy = fy * fy * (3 - 2 * fy)
    fx = fx * fx * (3 - 2 * fx)
    a = grid[y0][:, x0]
    b = grid[y0][:, x0 + 1]
    c = grid[y0 + 1][:, x0]
    d = grid[y0 + 1][:, x0 + 1]
    top = a + (b - a) * fx[None, :]
    bot = c + (d - c) * fx[None, :]
    return top + (bot - top) * fy[:, None]


def fbm(shape, scale, octaves, rng, gain=0.5):
    total, amp, norm = np.zeros(shape), 1.0, 0.0
    for o in range(octaves):
        total += amp * value_noise(shape, max(scale / (2 ** o), 1.5), rng)
        norm += amp
        amp *= gain
    return total / norm


def fbm1(n, scale, octaves, rng, gain=0.5):
    return fbm((1, n), scale, octaves, rng, gain)[0]


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def mix(a, b, t):
    return a + (b - a) * t


def rgb(hexstr):
    h = hexstr.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float64) / 255.0


def vgradient(stops):
    """A vertical gradient through (position, colour) stops."""
    y = np.linspace(0, 1, H)
    out = np.zeros((H, 3))
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        m = (y >= p0) & (y <= p1)
        t = ((y[m] - p0) / max(p1 - p0, 1e-6))[:, None]
        t = t * t * (3 - 2 * t)
        out[m] = mix(rgb(c0), rgb(c1), t)
    return np.repeat(out[:, None, :], W, axis=1)


def grain(img, amount, rng):
    """Film grain: hides banding in the gradients once compressed."""
    return img + (rng.random(img.shape[:2])[..., None] - 0.5) * amount


# ---------------------------------------------------------------- sky

def stars_layer(rng, count, band_mask, sky_mask):
    """Stars as a real sky has them: thousands at the edge of seeing, a few hundred clear, a
    handful bright with a little bloom; colours from blue-white to orange; denser in the Milky Way.
    Each is splatted at sub-pixel position so none sit on the same grid."""
    img = np.zeros((H, W, 3))
    glow = np.zeros((H, W, 3))
    temps = [rgb("#A8C0FF"), rgb("#D2DEFF"), rgb("#F6F7FF"), rgb("#FFF0D8"), rgb("#FFCF9C")]
    xs = rng.random(count * 3) * W
    ys = rng.random(count * 3) * H * HORIZON
    keep = rng.random(count * 3) < sky_mask[ys.astype(int), xs.astype(int)] * (0.35 + 1.3 * band_mask[ys.astype(int), xs.astype(int)])
    xs, ys = xs[keep][:count], ys[keep][:count]
    # Magnitudes: brightness falls ~2.5x per magnitude, counts rise ~3x per magnitude.
    mags = rng.uniform(0, 1, len(xs)) ** 0.28 * 6.5  # 0 (brightest) .. 6.5
    # Only the faint ones live in the plate: the bright, twinkling stars are the app's live
    # layer, so the "Stars" switch visibly takes them away.
    bright = np.minimum(2.512 ** (-mags) * 1.6, 0.28)
    cols = np.array(temps)[np.clip(np.abs(rng.normal(2, 1.0, len(xs))).astype(int), 0, 4)]
    for x, y, b, c in zip(xs, ys, bright, cols):
        xi, yi = int(x), int(y)
        fx, fy = x - xi, y - yi
        if xi + 1 >= W or yi + 1 >= H:
            continue
        img[yi, xi] += c * b * (1 - fx) * (1 - fy)
        img[yi, xi + 1] += c * b * fx * (1 - fy)
        img[yi + 1, xi] += c * b * (1 - fx) * fy
        img[yi + 1, xi + 1] += c * b * fx * fy
        if b > 10:  # no bloom in the plate: that belongs to the live stars
            glow[yi, xi] += c * b
    glow = np.stack([gaussian_filter(glow[..., k], 2.6) for k in range(3)], axis=-1) * 9
    soft = np.stack([gaussian_filter(img[..., k], 0.45) for k in range(3)], axis=-1) * 3.0
    return np.clip(soft + glow, 0, 1)


def milky_way(rng):
    """A diagonal band of unresolved stars with dark dust lanes, strongest near its core."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    # A band from upper left to the right, bending gently.
    cx = xx / W
    centre = H * (0.10 + 0.34 * cx + 0.05 * np.sin(cx * 3.1))
    width = H * (0.11 + 0.05 * cx)
    d = (yy - centre) / width
    band = np.exp(-d * d * 1.6)
    clouds = fbm((H, W), 220, 6, rng)
    fine = fbm((H, W), 40, 4, rng)
    lanes = fbm((H, W), 120, 5, rng)
    # Dust lanes: broad, soft, only partly dark — a real lane dims the glow, it doesn't cut it.
    lane = gaussian_filter(smoothstep(0.50, 0.78, lanes), 6) * np.exp(-d * d * 3.0) * 0.55
    glow = band * (0.30 + 0.70 * clouds ** 1.5) * (0.70 + 0.30 * fine)
    glow = glow * (1 - 0.85 * lane)
    core = np.exp(-((cx - 0.78) ** 2) / 0.02) * np.exp(-d * d * 3)  # the galactic centre, right
    colour = mix(rgb("#8FA6D8"), rgb("#E9DCC6"), np.clip(core * 1.4 + 0.2 * clouds, 0, 1)[..., None])
    return glow[..., None] * colour * 0.30 + core[..., None] * rgb("#F1DFC2") * 0.10, band


def night_sky(rng):
    sky = vgradient([
        (0.00, "#010309"), (0.30, "#040A18"), (0.52, "#071629"), (0.64, "#0B2236"), (0.72, "#123344"), (1.00, "#0A1A26"),
    ])
    yy = np.mgrid[0:H, 0:W][0] / H
    # Airglow and aurora light pooled above the horizon: teal-green, faint.
    airglow = np.exp(-((yy - 0.66) / 0.08) ** 2)
    sky += airglow[..., None] * rgb("#1F6B5E") * 0.22
    mw, band = milky_way(rng)
    sky += mw * (1 - smoothstep(0.50, 0.68, yy))[..., None]
    sky_mask = 1 - smoothstep(0.55, 0.69, yy)
    sky += stars_layer(rng, 14000, band, sky_mask) * 0.9
    return np.clip(grain(sky, 0.012, rng), 0, 1)


def day_sky(rng):
    sky = vgradient([
        (0.00, "#3E86C9"), (0.28, "#6FA9DC"), (0.50, "#A6CDEB"), (0.66, "#D9EBF6"), (0.72, "#EEF6FB"), (1.00, "#F4F8FB"),
    ])
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    # Cirrus: long thin wisps, stretched along the wind.
    stretched = fbm((H, W // 8 + 1), 40, 5, rng)
    wisps = np.repeat(stretched, 8, axis=1)[:, :W]
    wisps = gaussian_filter(wisps, (1.5, 24))
    cirrus = smoothstep(0.58, 0.80, wisps) * (1 - smoothstep(0.15, 0.55, yy / H)) * smoothstep(0.02, 0.10, yy / H)
    sky = mix(sky, rgb("#FFFFFF")[None, None, :], (cirrus * 0.35)[..., None])
    return np.clip(grain(sky, 0.008, rng), 0, 1)


# ---------------------------------------------------------------- landscape

MAP = 2048  # heightmap cells, x across and z into the distance


def terrain(rng):
    """An alpine heightmap: flat sea ice near the camera, a glacier range rising beyond it.
    Ridged multifractal noise gives sharp aretes and gullies; a low-frequency mask breaks the
    range into massifs and saddles."""
    def ridged(scale, octaves):
        total = np.zeros((MAP, MAP))
        weight = np.ones((MAP, MAP))
        amp, norm = 1.0, 0.0
        for o in range(octaves):
            n = value_noise((MAP, MAP), max(scale / 2 ** o, 2), rng)
            r = (1 - np.abs(2 * n - 1)) ** 2
            r = r * weight
            weight = np.clip(r * 1.6, 0, 1)
            total += r * amp
            norm += amp
            amp *= 0.52
        return total / norm
    z = np.arange(MAP)[:, None].astype(np.float64)
    peaks = ridged(420, 7)
    massifs = fbm((MAP, MAP), 700, 3, rng)
    rise = smoothstep(420, 820, z) * (1 - smoothstep(1950, 2047, z))
    height = rise * (0.25 + 0.75 * smoothstep(0.30, 0.70, massifs)) * peaks ** 1.25 * 520
    # Sastrugi and pressure ridges on the ice: low, long, across the wind.
    sas = fbm((MAP // 2, MAP // 8), 10, 4, rng)
    sas = np.kron(sas, np.ones((2, 8)))[:MAP, :MAP]
    ice = (sas - 0.5) * 2.2 + (fbm((MAP, MAP), 120, 3, rng) - 0.5) * 3.0
    height = height + ice * (1 - rise)
    return gaussian_filter(height, 0.7)


def render_terrain(height, night, rng):
    """Voxel-space perspective render of the heightmap from a camera on the ice: each screen
    column marches into the terrain, the nearest surface above each pixel wins. Returns RGB and
    alpha (zero where the column sees sky)."""
    hy, hx = np.gradient(height)
    normal = np.dstack([-hx, np.ones_like(height), -hy])
    normal /= np.linalg.norm(normal, axis=2, keepdims=True)
    fov = math.radians(62)
    focal = (W / 2) / math.tan(fov / 2)
    horizon_y = H * HORIZON - 40  # the camera tilts down a touch: horizon just above the ice line
    cam_x, cam_h = MAP * 0.5, 7.0
    zs = [3.0]
    while zs[-1] < MAP - 4:
        zs.append(zs[-1] + 0.6 + zs[-1] * 0.0035)
    zs = np.array(zs)
    cols = (np.arange(W) - W / 2) / focal
    xs = np.clip(cam_x + cols[None, :] * zs[:, None], 0, MAP - 1)
    zi = np.clip(zs, 0, MAP - 1).astype(int)[:, None].repeat(W, 1)
    xi = xs.astype(int)
    h = height[zi, xi]
    n = normal[zi, xi]
    screen_y = horizon_y - (h - cam_h) / zs[:, None] * focal
    # Light and materials.
    up = n[..., 1]
    steep = 1 - up
    slope_noise = fbm((MAP, MAP), 30, 3, rng)[zi, xi]
    # Snow holds on all but the steepest faces; on night ones it reads as dim blue.
    snow = 1 - smoothstep(0.46 if night else 0.52, 0.66 if night else 0.72, steep + (slope_noise - 0.5) * 0.25)
    if night:
        light = np.array([0.12, 0.22, 1.0]); light /= np.linalg.norm(light)  # aurora low, behind the range
        key = rgb("#5FE0B8") * 0.30
        sky_amb = rgb("#122a42") * 0.62
        snow_alb, rock_alb = rgb("#C9D8E6"), rgb("#1B2430")
        fog_col, fog_k = rgb("#0E2434"), 1 / 2600
    else:
        light = np.array([-0.75, 0.48, -0.35]); light /= np.linalg.norm(light)  # low sun, left
        key = rgb("#FFF6E8") * 1.05
        sky_amb = rgb("#9CC0E2") * 0.62
        snow_alb, rock_alb = rgb("#F4F8FC"), rgb("#55606E")
        fog_col, fog_k = rgb("#E6F0F7"), 1 / 2200
    lambert = np.clip(np.einsum("swk,k->sw", n, light), 0, 1)
    # Backlit ridges catch a rim of light where the surface turns away from the camera.
    rim = np.clip(1 - np.abs(n[..., 2]), 0, 1) ** 6 * (0.6 if night else 0.15)
    albedo = mix(rock_alb[None, None, :], snow_alb[None, None, :], snow[..., None])
    lit = albedo * (sky_amb[None, None, :] * (0.55 + 0.45 * up[..., None]) + key[None, None, :] * lambert[..., None])
    lit = lit + key[None, None, :] * (rim * snow)[..., None] * 0.6
    fog = 1 - np.exp(-zs[:, None] * fog_k)
    if night:
        # The ice near the horizon mirrors a little of the aurora.
        sheen = np.exp(-((zs[:, None] - 380) / 90) ** 2) * (h < 3)
        lit = lit + rgb("#2F8F78")[None, None, :] * (sheen * 0.16)[..., None]
    colour = mix(lit, fog_col[None, None, :], fog[..., None])
    # Occlusion: walking away from the camera, a sample shows only above everything nearer.
    top = np.minimum.accumulate(screen_y, axis=0)
    img = np.zeros((H, W, 3))
    alpha = np.zeros((H, W))
    rows = np.arange(H, dtype=np.float64)
    for c in range(W):
        t = top[:, c]
        # For each pixel row, the first (nearest) step whose running top reaches it.
        idx = np.searchsorted(-t, -rows, side="left")
        hit = idx < len(zs)
        idx = np.clip(idx, 0, len(zs) - 1)
        img[hit, c] = colour[idx[hit], c]
        alpha[hit, c] = 1.0
    alpha = gaussian_filter(alpha, 0.5)
    return img, alpha


def penguin(img, alpha, cx, foot, height, body, belly, beak):
    """An emperor penguin seen side-on, small, standing on the ice."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    h = height
    by = foot - h * 0.42
    bodym = ((xx - cx) / (h * 0.22)) ** 2 + ((yy - by) / (h * 0.44)) ** 2 <= 1
    head = ((xx - (cx + h * 0.05)) / (h * 0.13)) ** 2 + ((yy - (foot - h * 0.90)) / (h * 0.12)) ** 2 <= 1
    bill = (xx > cx + h * 0.14) & (xx < cx + h * 0.30) & (np.abs(yy - (foot - h * 0.88)) < h * 0.025 + (cx + h * 0.30 - xx) * 0.08)
    front = bodym & (xx > cx + h * 0.06) & (yy > foot - h * 0.78)
    m = bodym | head
    img[m] = body
    img[front] = belly
    img[bill] = beak
    alpha[m | bill] = 1.0


def compose_land(rng, night):
    height = terrain(np.random.default_rng(424242))
    img, alpha = render_terrain(height, night, rng)
    yy = np.mgrid[0:H, 0:W][0].astype(np.float64)
    # Mist lying along the foot of the range.
    mist = rgb("#1C3A50") if night else rgb("#F2F7FB")
    band = np.exp(-((yy - H * (HORIZON - 0.03)) / (H * 0.028)) ** 2) * (0.45 + 0.55 * fbm((H, W), 140, 3, rng))
    img = mix(img, mist[None, None, :], (band * (0.45 if night else 0.55) * alpha)[..., None])
    if night:
        body, belly, beak = rgb("#04070B"), rgb("#1E2B37"), rgb("#5E4824")
    else:
        body, belly, beak = rgb("#10151C"), rgb("#EEF2F5"), rgb("#E0A33A")
    foot = H * (HORIZON + 0.035)
    penguin(img, alpha, W * 0.70, foot, 30, body, belly, beak)
    penguin(img, alpha, W * 0.732, foot + 2, 24, body, belly, beak)
    fade = 1 - smoothstep(0.80, 1.0, yy / H)
    alpha = alpha * fade
    img = np.clip(grain(img, 0.010, rng), 0, 1)
    return img, alpha


# ---------------------------------------------------------------- aurora fallback frames

def hash2(x, y):
    h = np.sin(x * 127.1 + y * 311.7) * 43758.5453
    return h - np.floor(h)


def noise2(x, y):
    xi, yi = np.floor(x), np.floor(y)
    fx, fy = x - xi, y - yi
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b = hash2(xi, yi), hash2(xi + 1, yi)
    c, d = hash2(xi, yi + 1), hash2(xi + 1, yi + 1)
    return mix(mix(a, b, fx), mix(c, d, fx), fy)


def fbm2(x, y, octaves=4):
    v, a = 0.0, 0.5
    for _ in range(octaves):
        v = v + a * noise2(x, y)
        x, y = x * 2.03 + 17.1, y * 2.03 + 9.2
        a *= 0.5
    return v


def aurora_frame(t, w=270, h=345):
    """The same curtain model as the app's AGSL shader (AuroraShader.android.kt), in numpy."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    x, y = xx / w, yy / h
    warp = fbm2(x * 3.3, t * 0.03 + x * 0.0)
    base = 0.62 - 0.22 * fbm2(x * 1.3 + t * 0.01, np.full_like(x, 3.7)) - 0.10 * np.sin(x * 4.0 + t * 0.07 + warp * 3.0)
    rays = fbm2(x * 38.0 + warp * 6.0 + t * 0.10, np.full_like(x, t * 0.15)) ** 2.2 * 2.2
    d = base - y
    curtain = smoothstep(-0.015, 0.02, d) * np.exp(-np.maximum(d, 0) * mix(3.2, 4.5, np.clip(rays, 0, 1))) * (0.35 + rays)
    patch = smoothstep(0.25, 0.75, fbm2(x * 1.7 - t * 0.02, np.full_like(x, t * 0.01 + 5.0)))
    glow = curtain * (0.35 + 0.9 * patch)
    hh = np.clip(d / 0.35, 0, 1)
    col = mix(rgb("#4DFF9E")[None, None, :], rgb("#33D9D9")[None, None, :], smoothstep(0.15, 0.55, hh)[..., None])
    col = mix(col, rgb("#9E61F2")[None, None, :], smoothstep(0.55, 0.85, hh)[..., None])
    halo = np.exp(-((y - base + 0.05) / 0.18) ** 2) * 0.18 * (0.5 + patch)
    a = np.clip(glow * 0.85 + halo, 0, 1) * smoothstep(0.0, 0.12, y) * (1 - smoothstep(0.66, 0.74, y))
    return col, a


def save(name, img, alpha=None, quality=82):
    arr = (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)
    if alpha is None:
        im = Image.fromarray(arr, "RGB")
    else:
        a = (np.clip(alpha, 0, 1) * 255 + 0.5).astype(np.uint8)
        im = Image.fromarray(np.dstack([arr, a]), "RGBA")
    path = os.path.join(OUT, name)
    im.save(path, "WEBP", quality=quality, method=6, alpha_quality=90)
    print(f"{name}: {os.path.getsize(path) // 1024} KB")


def main():
    os.makedirs(OUT, exist_ok=True)
    save("winter_night_sky.webp", night_sky(np.random.default_rng(2026)))
    save("winter_night_land.webp", *compose_land(np.random.default_rng(1111), night=True))
    save("winter_day_sky.webp", day_sky(np.random.default_rng(7)))
    save("winter_day_land.webp", *compose_land(np.random.default_rng(1111), night=False))
    for i in range(4):
        save(f"winter_aurora_{i}.webp", *aurora_frame(i * 37.0), quality=80)


if __name__ == "__main__":
    main()
