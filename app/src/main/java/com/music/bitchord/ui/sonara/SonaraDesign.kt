package com.music.bitchord.ui.sonara

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.ui.components.pressScale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sonara's own visual vocabulary: the pieces that make a page read as this app rather than
 * the player it grew out of.
 *
 * - **Red, used sparingly.** The mark's red is the only colour the chrome owns; everything
 *   else is ink and grey, so artwork stays the loudest thing on a page.
 * - **Monospace labels.** Metadata — a period, an id, a unit — is set small, spaced and in
 *   a fixed-width face, like the printing on a ticket. Headings stay large and heavy.
 * - **Floating cards** with generous corners rather than inset lists with hairlines.
 * - **The Pass**: the listener's card, made on the welcome screen and carried at the head
 *   of their Library, with a soundprint drawn from their own id.
 */
object SonaraColors {
    val Red = Color(0xFFFF2D55)
    val Coral = Color(0xFFFF6B81)
    val Blush = Color(0xFFFF9AAB)
    val Ember = Color(0xFF5C0A1C)
    val Ink = Color(0xFF0A0A0C)
    val PassTop = Color(0xFF2A0B14)
    val PassBottom = Color(0xFF111114)
}

/** The large corner every floating card uses. */
val SonaraCardShape = RoundedCornerShape(26.dp)

/** A ticket-printing label: small, spaced, fixed-width, upper case. */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: TextUnit = 10.sp,
    maxLines: Int = 1,
) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MonoStyle.copy(fontSize = size, letterSpacing = (size.value * 0.14f).sp),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

/** A faint grid of dots behind a page's head, the way graph paper sits under a sketch. */
fun Modifier.dottedGrid(color: Color, spacing: Dp = 18.dp, dot: Dp = 1.1.dp): Modifier = drawBehind {
    val step = spacing.toPx()
    val r = dot.toPx()
    var y = step / 2f
    while (y < size.height) {
        var x = step / 2f
        while (x < size.width) {
            drawCircle(color, r, Offset(x, y))
            x += step
        }
        y += step
    }
}

/**
 * The three arcs of the mark, drawn to fit [DrawScope.size] with the arcs' centre near the
 * top. [progress] (0..1, one per arc) opens each from its middle; [phase] lets them breathe.
 */
fun DrawScope.drawSonaraArcs(
    colors: List<Color> = listOf(SonaraColors.Blush, SonaraColors.Coral, SonaraColors.Red),
    progress: List<Float> = listOf(1f, 1f, 1f),
    phase: Float = 0f,
    strokeScale: Float = 1f,
    alpha: Float = 1f,
) {
    val unit = size.minDimension / 108f
    val centre = Offset(size.width / 2f, 22f * unit)
    val radii = floatArrayOf(22f, 36f, 50f)
    radii.forEachIndexed { index, r ->
        val drift = sin(phase - index * 0.9f) * 1.6f * unit
        val radius = r * unit
        val open = 38f * progress[index]
        if (open > 0f) {
            drawArc(
                color = colors[index],
                startAngle = 90f - open,
                sweepAngle = open * 2f,
                useCenter = false,
                topLeft = Offset(centre.x - radius, centre.y - radius + drift),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = 8.5f * unit * strokeScale, cap = StrokeCap.Round),
                alpha = progress[index] * alpha,
            )
        }
    }
}

/** The mark, drawn live: each arc sweeps open in turn, then the three breathe out of step. */
@Composable
fun AnimatedSonaraMark(modifier: Modifier = Modifier, glow: Boolean = true, startDelayMs: Long = 180L) {
    val sweep = remember { List(3) { Animatable(0f) } }
    LaunchedEffect(Unit) {
        sweep.forEachIndexed { index, arc ->
            launch {
                delay(startDelayMs + index * 140L)
                arc.animateTo(1f, tween(durationMillis = 720, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            }
        }
    }
    val breath = rememberInfiniteTransition(label = "sonara-mark")
    val phase by breath.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 4_200, easing = LinearEasing)),
        label = "sonara-mark-phase",
    )
    Canvas(modifier) {
        if (glow) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SonaraColors.Red.copy(alpha = 0.34f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.56f),
                    radius = size.minDimension * 0.62f,
                ),
                radius = size.minDimension * 0.62f,
                center = Offset(size.width / 2f, size.height * 0.56f),
            )
        }
        drawSonaraArcs(progress = sweep.map { it.value }, phase = phase)
    }
}

/** When this install first ran, which is when its listener joined. */
fun memberSinceMillis(context: Context): Long = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
}.getOrNull() ?: System.currentTimeMillis()

/** "NOV 2026": the month a pass was issued. */
fun memberSinceLabel(millis: Long): String =
    DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
        .uppercase(Locale.getDefault())

/** "SNR 4821 0937": a pass number derived from the install, stable for its life. */
fun passIdFor(millis: Long): String {
    val n = abs((millis * 2_654_435_761L) xor (millis ushr 17)) % 100_000_000L
    val digits = n.toString().padStart(8, '0')
    return "SNR ${digits.substring(0, 4)} ${digits.substring(4)}"
}

/**
 * The Sonara Pass: the listener's card. Ink with a red ember in one corner, the mark and
 * the pass number across the top, the holder's picture and name, and along the foot a
 * soundprint — a bar code of sound drawn from the pass number, so no two look alike.
 *
 * Always drawn dark, whatever the theme: it is an object, like a card in a wallet.
 */
@Composable
fun SonaraPassCard(
    name: String,
    memberSince: String,
    passId: String,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    val bars = remember(passId) { soundprint(passId, 38) }
    Box(
        modifier
            .then(if (onClick != null) Modifier.pressScale(0.97f) else Modifier)
            .fillMaxWidth()
            .aspectRatio(1.62f)
            .clip(shape)
            .background(Brush.linearGradient(listOf(SonaraColors.PassTop, SonaraColors.PassBottom, Color(0xFF18181C))))
            .drawBehind {
                // The ember: the mark's red, glowing out of the top-right corner.
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(SonaraColors.Red.copy(alpha = 0.55f), SonaraColors.Red.copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(size.width * 0.96f, size.height * 0.02f),
                        radius = size.width * 0.72f,
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(size.width * 0.96f, size.height * 0.02f),
                )
                // A sheen across the card, as on a laminated face.
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.07f),
                        0.58f to Color.Transparent,
                        1f to Color.Transparent,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            }
            .dottedGrid(Color.White.copy(alpha = 0.045f), spacing = 14.dp, dot = 0.9.dp)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // The mark again, large and faint, cut off by the card's edge.
        Canvas(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 0.dp)
                .size(190.dp)
                .padding(top = 40.dp),
        ) {
            drawSonaraArcs(
                colors = List(3) { Color.White },
                strokeScale = 0.55f,
                alpha = 0.06f,
            )
        }
        // Laid out at its design width and scaled to fit, so a pass drawn small is the same
        // card at a smaller size rather than a crowded one.
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val k = (maxWidth / PASS_DESIGN_WIDTH).coerceAtMost(1f)
        val outer = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(outer.density * k, outer.fontScale)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(width = 26.dp, height = 18.dp)) { drawSonaraArcs(strokeScale = 1.15f) }
                Spacer(Modifier.width(8.dp))
                MonoLabel("Sonara Pass", color = Color.White.copy(alpha = 0.82f), size = 10.sp)
                Spacer(Modifier.weight(1f))
                MonoLabel(passId, color = Color.White.copy(alpha = 0.55f), size = 9.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PassAvatar(name = name, url = avatarUrl, size = 54.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    MonoLabel("Holder", color = Color.White.copy(alpha = 0.5f), size = 9.sp)
                    Text(
                        text = name.ifBlank { "Listener" },
                        style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp, lineHeight = 32.sp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Canvas(Modifier.weight(1f).height(26.dp)) {
                    val gap = size.width / bars.size
                    val w = gap * 0.46f
                    bars.forEachIndexed { i, h ->
                        val barH = size.height * (0.22f + 0.78f * h)
                        drawRoundRect(
                            color = if (i % 9 == 4) SonaraColors.Coral else Color.White.copy(alpha = 0.72f),
                            topLeft = Offset(i * gap, size.height - barH),
                            size = Size(w, barH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.End) {
                    MonoLabel("Member since", color = Color.White.copy(alpha = 0.5f), size = 8.sp)
                    MonoLabel(memberSince, color = Color.White, size = 11.sp)
                }
            }
        }
        }
        }
    }
}

/** The width the pass's contents are laid out for; narrower passes scale them down. */
private val PASS_DESIGN_WIDTH = 330.dp

/** The holder's picture, or their initial on the mark's gradient when there is none. */
@Composable
fun PassAvatar(name: String, url: String?, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(SonaraColors.Coral, SonaraColors.Red, SonaraColors.Ember)))
            .border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "♪",
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = (size.value * 0.42f).sp),
            color = Color.White,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

/** Bar heights in 0..1 from [seed]: a smooth-ish wave with a little grit, like a waveform. */
private fun soundprint(seed: String, count: Int): List<Float> {
    var state = seed.fold(1125899906842597L) { h, c -> 31 * h + c.code }
    fun next(): Float {
        state = state xor (state shl 13); state = state xor (state ushr 7); state = state xor (state shl 17)
        return ((state ushr 11) and 0xFFFF).toFloat() / 0xFFFF
    }
    val f1 = 1.4f + next() * 2.2f
    val f2 = 4f + next() * 5f
    val p = next() * 6.28f
    return List(count) { i ->
        val t = i / count.toFloat()
        val wave = 0.5f + 0.32f * sin(t * f1 * 6.28f + p) + 0.14f * sin(t * f2 * 6.28f)
        (wave * 0.8f + next() * 0.28f).coerceIn(0.05f, 1f)
    }
}

/**
 * Time listened, as two large numbers — hours, then minutes — with the period in ticket
 * print beside them, and a row of smaller figures under a rule.
 */
@Composable
fun ListeningTimeCard(
    totalMs: Long,
    periodLabel: String,
    plays: Int,
    artists: Int,
    topArtist: String?,
    onViewStats: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val hours = totalMs / 3_600_000
    val minutes = (totalMs / 60_000) % 60
    val ink = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxWidth()
            .pressScale(0.98f)
            .clip(SonaraCardShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onViewStats)
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigFigure(if (loading) "–" else hours.toString(), "h", ink, muted)
            Box(
                Modifier
                    .padding(horizontal = 14.dp)
                    .width(1.dp)
                    .height(40.dp)
                    .background(muted.copy(alpha = 0.35f)),
            )
            BigFigure(if (loading) "–" else minutes.toString(), "m", ink, muted)
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                MonoLabel("Total listening", color = muted, size = 10.sp)
                MonoLabel(periodLabel, color = SonaraColors.Red, size = 10.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(muted.copy(alpha = 0.22f)))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniFigure("Plays", if (loading) "–" else plays.toString(), Modifier.weight(0.8f))
            MiniFigure("Artists", if (loading) "–" else artists.toString(), Modifier.weight(0.8f))
            MiniFigure("Top artist", topArtist ?: "–", Modifier.weight(1.4f))
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "View stats",
                style = MaterialTheme.typography.labelLarge,
                color = ink,
            )
            Text(
                text = "  ›",
                style = MaterialTheme.typography.labelLarge,
                color = muted,
            )
        }
    }
}

@Composable
private fun BigFigure(value: String, unit: String, ink: Color, muted: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = value,
            style = MaterialTheme.typography.displayLarge.copy(fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-1.5).sp),
            color = ink,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.titleLarge,
            color = muted,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun MiniFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        MonoLabel(label, size = 9.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Fills its parent with a dotted grid fading out by [fade] of the way down. */
@Composable
fun FadingDotGrid(modifier: Modifier = Modifier, color: Color, fade: Float = 1f) {
    Canvas(modifier) {
        val step = 18.dp.toPx()
        val r = 1.1.dp.toPx()
        var y = step / 2f
        while (y < size.height) {
            val a = (1f - (y / (size.height * fade))).coerceIn(0f, 1f)
            if (a > 0f) {
                var x = step / 2f
                while (x < size.width) {
                    drawCircle(color.copy(alpha = color.alpha * a), r, Offset(x, y))
                    x += step
                }
            }
            y += step
        }
    }
}
