package com.music.bitchord.ui.theme

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.ThemeIcons
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics

/** Display name of a theme. "Vice" and "Doom" are labels only; nothing else of either franchise. */
@Composable
fun themeName(id: ThemeId): String = stringResource(
    when (id) {
        ThemeId.SONARA -> R.string.theme_sonara
        ThemeId.VICE -> R.string.theme_vice
        ThemeId.DOOM -> R.string.theme_doom
    },
)

@Composable
private fun themeLine(id: ThemeId): String = stringResource(
    when (id) {
        ThemeId.SONARA -> R.string.theme_sonara_line
        ThemeId.VICE -> R.string.theme_vice_line
        ThemeId.DOOM -> R.string.theme_doom_line
    },
)

/**
 * Applies [id] at once, and queues its home-screen icon for when the app next leaves the screen.
 * Says so when the icon is going to change, since that part is not instant.
 */
fun chooseTheme(context: Context, id: ThemeId) {
    if (AppSettings.appTheme.value == id) return
    AppSettings.setAppTheme(id)
    if (ThemeIcons.follow(context, id)) {
        Toast.makeText(context, R.string.theme_icon_pending, Toast.LENGTH_LONG).show()
    }
}

/**
 * Settings → Appearance: one large live preview per theme — a miniature of Home drawn in that
 * theme's own colours, type and shapes — and the chosen one ringed in its accent.
 */
@Composable
fun ThemePickerRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val current by AppSettings.appTheme.collectAsStateWithLifecycle()
    // Three across, sharing the width: every theme in view at once, on any phone.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (id in ThemeId.entries) {
            ThemePreviewCard(
                id = id,
                modifier = Modifier.weight(1f),
                selected = id == current,
                onClick = {
                    haptics.play(Haptic.Select)
                    chooseTheme(context, id)
                },
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(id: ThemeId, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = remember(id) { themeTokensFor(id) }
    val scheme = remember(id) { themeColorScheme(id, dark = true) }
    val scale by animateFloatAsState(if (selected) 1f else 0.95f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "themeCardScale")
    val ring by animateColorAsState(if (selected) tokens.accent else Color.Transparent, tween(220), label = "themeCardRing")
    val name = themeName(id)
    val cardShape = if (tokens.angular) CutCornerShape(12.dp) else RoundedCornerShape(20.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = name
            }
            .padding(vertical = 6.dp),
    ) {
        Box(
            Modifier
                .scale(scale)
                .fillMaxWidth()
                .aspectRatio(0.56f)
                .border(2.dp, ring, cardShape)
                .padding(4.dp)
                .clip(cardShape),
        ) {
            MiniHome(tokens, scheme.background, scheme.surfaceVariant, scheme.onBackground, scheme.onSurfaceVariant)
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(tokens.chipShape)
                        .background(tokens.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.onAccent, modifier = Modifier.size(15.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = themeLine(id),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * A miniature of Home in a theme: its hero, a greeting in its heading face, two cards in its
 * shape and accent, and the tab bar with its selection. Drawn, not composed from the real
 * screens, so it costs one canvas and shows the theme even while another is active.
 */
@Composable
private fun MiniHome(tokens: ThemeTokens, background: Color, surface: Color, onBackground: Color, muted: Color) {
    val painter = remember { ThemeBackdropPainter() }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // The real hero, drawn at this size: the painter scales its motif to the canvas.
            painter.draw(this, tokens, background)
            val pad = 10.dp.toPx()
            val cardW = (size.width - pad * 3) / 2f
            val cardH = cardW * 1.25f
            val top = size.height * 0.47f
            // The theme's corners at miniature scale: a card's 26dp would make these ovals.
            val shape = when {
                tokens.angular -> CutCornerShape(5.dp)
                tokens.id == ThemeId.VICE -> RoundedCornerShape(10.dp)
                else -> RoundedCornerShape(7.dp)
            }
            for (i in 0 until 2) {
                val x = pad + i * (cardW + pad)
                val outline = shape.createOutline(Size(cardW, cardH), direction, density)
                translate(x, top) {
                    drawOutline(
                        outline,
                        Brush.linearGradient(
                            if (i == 0) listOf(tokens.accent, tokens.accentDeep) else listOf(tokens.accent2, surface),
                            start = Offset.Zero,
                            end = Offset(cardW, cardH),
                        ),
                    )
                }
                drawRoundRect(onBackground.copy(alpha = 0.85f), Offset(x, top + cardH + 6.dp.toPx()), Size(cardW * 0.8f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                drawRoundRect(muted.copy(alpha = 0.7f), Offset(x, top + cardH + 13.dp.toPx()), Size(cardW * 0.55f, 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            }
            // The tab bar, its first tab selected.
            val barH = 22.dp.toPx()
            val barTop = size.height - barH - 8.dp.toPx()
            val bar = (if (tokens.angular) CutCornerShape(5.dp) else RoundedCornerShape(50)).createOutline(Size(size.width - pad * 2, barH), direction, density)
            translate(pad, barTop) { drawOutline(bar, surface.copy(alpha = 0.92f)) }
            val slot = (size.width - pad * 2) / 4f
            for (i in 0 until 4) {
                val c = Offset(pad + slot * (i + 0.5f), barTop + barH / 2f)
                if (i == 0) drawCircle(tokens.accent.copy(alpha = 0.25f), barH * 0.42f, c)
                drawCircle(if (i == 0) tokens.accent else muted.copy(alpha = 0.8f), 3.dp.toPx(), c)
            }
        }
        Text(
            text = stringResource(R.string.theme_preview_greeting),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = tokens.headingFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            ),
            color = onBackground,
            maxLines = 2,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 40.dp),
        )
    }
}

/**
 * The welcome screen's theme switch: a small palette button that opens a row of three swatches.
 * Picking one applies the theme at once — the welcome screen itself is drawn in it.
 */
@Composable
fun ThemeQuickSwitch(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val current by AppSettings.appTheme.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.theme_switch)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = open,
            enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 6.dp)) {
                for (id in ThemeId.entries) {
                    val tokens = id.tokens
                    val name = themeName(id)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(tokens.accent, tokens.accent2, tokens.accentDeep)))
                            .border(2.dp, if (id == current) Color.White else Color.Transparent, CircleShape)
                            .clickable(role = Role.RadioButton) {
                                haptics.play(Haptic.Select)
                                chooseTheme(context, id)
                            }
                            .semantics {
                                selected = id == current
                                contentDescription = name
                            },
                    )
                }
            }
        }
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable {
                    haptics.play(Haptic.Select)
                    open = !open
                }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Palette, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
        }
    }
}
