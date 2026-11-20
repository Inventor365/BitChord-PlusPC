package com.music.bitchord.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush

/**
 * The aurora as an AGSL shader (Android 13+). Curtains hang from a lower edge that wanders and
 * ripples (domain-warped noise), lit in fine vertical rays that drift sideways and flicker, and
 * coloured by height above that edge: green at the foot, teal, then violet with a faint magenta
 * fringe at the top — the way oxygen and nitrogen emission stack in a real display. A fainter
 * second curtain hangs behind, large patches brighten and dim along the band, and a soft halo
 * lights the sky around it. By day the same curtains are paler, tealer and much fainter.
 *
 * One shader and one brush for the life of the window; each frame only the uniforms change.
 * Drawn at a quarter of the screen's resolution into the shared aurora layer (see WinterScene).
 */
actual class AuroraShader actual constructor() {
    private val impl: Impl? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Impl() else null

    actual val supported: Boolean get() = impl != null

    actual fun brush(width: Float, height: Float, timeSec: Float, day: Boolean): Brush? =
        impl?.brush(width, height, timeSec, day)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class Impl {
        private val shader = RuntimeShader(AGSL)
        private val brush = ShaderBrush(shader)

        fun brush(width: Float, height: Float, timeSec: Float, day: Boolean): Brush {
            shader.setFloatUniform("size", width, height)
            shader.setFloatUniform("time", timeSec)
            shader.setFloatUniform("day", if (day) 1f else 0f)
            shader.setFloatUniform("intensity", if (day) 0.42f else 0.78f)
            return brush
        }
    }
}

private const val AGSL = """
uniform float2 size;
uniform float time;
uniform float day;
uniform float intensity;

float hash(float2 p) {
    float h = sin(dot(p, float2(127.1, 311.7))) * 43758.5453;
    return fract(h);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + float2(17.1, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / size;
    float x = uv.x;
    float y = uv.y;
    float t = time;

    // Where the curtain's lower edge hangs: a slow wander, a ripple, warped by noise.
    float warp = fbm(float2(x * 3.3, t * 0.03));
    float base = 0.62 - 0.22 * fbm(float2(x * 1.3 + t * 0.01, 3.7)) - 0.10 * sin(x * 4.0 + t * 0.07 + warp * 3.0);

    // Rays: fine vertical striations drifting sideways and flickering.
    float rays = fbm(float2(x * 38.0 + warp * 6.0 + t * 0.10, t * 0.15));
    rays = pow(rays, 1.7) * 1.7;

    // The curtain: brightest just above its lower edge, fading up over a ray's length.
    float d = base - y;
    float lower = smoothstep(-0.015, 0.02, d);
    float upper = exp(-max(d, 0.0) * mix(3.2, 4.5, clamp(rays, 0.0, 1.0)));
    float curtain = lower * upper * (0.35 + rays);

    // A fainter curtain further back.
    float base2 = base - 0.16 + 0.05 * sin(x * 3.0 - t * 0.05);
    float d2 = base2 - y;
    float back = smoothstep(-0.02, 0.03, d2) * exp(-max(d2, 0.0) * 5.0) * (0.25 + rays * 0.6) * 0.5;

    // Large patches brightening and dimming along the band.
    float patch = smoothstep(0.25, 0.75, fbm(float2(x * 1.7 - t * 0.02, t * 0.01 + 5.0)));
    float glow = (curtain + back) * (0.35 + 0.9 * patch);

    // Colour by height above the lower edge.
    float h = clamp(d / 0.35, 0.0, 1.0);
    half3 col = mix(half3(0.30, 1.00, 0.62), half3(0.20, 0.85, 0.85), smoothstep(0.15, 0.55, h));
    col = mix(col, half3(0.62, 0.38, 0.95), smoothstep(0.55, 0.85, h));
    col = mix(col, half3(0.85, 0.30, 0.70), smoothstep(0.85, 1.0, h) * 0.6);

    // A soft wide glow around the band.
    float halo = exp(-pow((y - base + 0.05) / 0.18, 2.0)) * 0.18 * (0.5 + patch);

    float a = clamp(glow * 0.85 + halo, 0.0, 1.0) * intensity;
    // Gone at the very top, and below the horizon (where the peaks stand).
    a *= smoothstep(0.0, 0.12, y) * (1.0 - smoothstep(0.66, 0.74, y));

    // By day: paler and tealer.
    col = mix(col, half3(0.55, 0.92, 0.85), day * 0.5);
    return half4(col * a, a);
}
"""
