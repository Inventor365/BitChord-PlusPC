package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The mark's red: the accent an action tile glows in unless it is given another. */
val SonaraAccent = Color(0xFFFF2D55)

/** The corner every action tile is cut to. */
val ActionArtShape = RoundedCornerShape(18.dp)

/**
 * The square face of an action — New playlist, Join via invite, Import — sized like the covers
 * it sits among so a shelf stays one even row.
 *
 * Not a blank grey square with an icon dropped in the middle: the glyph sits in a disc of
 * [accent] in the top corner, the same glyph is drawn large and faint and cut off by the
 * opposite corner, a little of the accent glows up from below, and a grid of dots lies under
 * it all — the same build as Settings' category tiles, so the app's tiles read as one family.
 *
 * [painter] takes the glyph's place for a tile that stands for a brand (a full-colour mark).
 */
@Composable
fun ActionArt(
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    accent: Color = SonaraAccent,
    painter: Painter? = null,
    painterTint: Color? = null,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(ActionArtShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(0.5.dp, ink.copy(alpha = 0.08f), ActionArtShape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.15f, size.height * 1.05f),
                    radius = size.width * 0.95f,
                ),
            )
            val step = 13.dp.toPx()
            val r = 0.9.dp.toPx()
            var y = step / 2f
            while (y < size.height) {
                var x = step / 2f
                while (x < size.width) {
                    drawCircle(ink.copy(alpha = 0.05f), r, Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent.copy(alpha = 0.12f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 18.dp, y = 18.dp)
                    .size(110.dp)
                    .graphicsLayer { rotationZ = -12f },
            )
        }
        Box(
            Modifier
                .padding(12.dp)
                .size(46.dp)
                .clip(CircleShape)
                .background(if (painter != null) Color.Transparent else accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (painter != null) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    tint = painterTint ?: Color.Unspecified,
                    modifier = Modifier.size(40.dp),
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
