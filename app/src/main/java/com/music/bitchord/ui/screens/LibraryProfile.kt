package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.pressScale
import com.music.bitchord.ui.components.revealOnAppear
import com.music.bitchord.ui.sonara.FadingDotGrid
import com.music.bitchord.ui.sonara.ListeningTimeCard
import com.music.bitchord.ui.sonara.MonoLabel
import com.music.bitchord.ui.sonara.SonaraPassCard

/**
 * The head of the Library, which is also the listener's profile: who they are (their Pass),
 * how much they have listened (in hours and minutes, with plays, artists and the artist they
 * played most), and the way into the full stats.
 *
 * A dotted grid fades out behind it, so the page reads as somewhere of its own before the
 * shelves start.
 */
@Composable
fun LibraryProfileHeader(
    name: String,
    avatarUrl: String?,
    memberSince: String,
    passId: String,
    summary: ReplaySummary?,
    loading: Boolean,
    onEditProfile: () -> Unit,
    onViewStats: () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        FadingDotGrid(
            Modifier.matchParentSize(),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.09f),
            fade = 0.75f,
        )
        Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    MonoLabel("Your library", color = com.music.bitchord.ui.theme.heroSubtitleColor(MaterialTheme.colorScheme.onSurfaceVariant))
                    Text(
                        text = name.ifBlank { "Library" },
                        style = MaterialTheme.typography.displayLarge.copy(shadow = com.music.bitchord.ui.theme.heroTextShadow()),
                        color = com.music.bitchord.ui.theme.heroTitleColor(MaterialTheme.colorScheme.onBackground),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .padding(bottom = 6.dp, start = 12.dp)
                        .pressScale(0.94f)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(onClick = onEditProfile)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            SonaraPassCard(
                name = name,
                memberSince = memberSince,
                passId = passId,
                avatarUrl = avatarUrl,
                onClick = onEditProfile,
                modifier = Modifier
                    .revealOnAppear(order = 0, rise = 18.dp)
                    .graphicsLayer { rotationZ = -1.2f },
            )
            Spacer(Modifier.height(14.dp))
            val top = summary?.artists?.firstOrNull()?.title
            ListeningTimeCard(
                totalMs = summary?.totalMs ?: 0L,
                periodLabel = summary?.label ?: "This year",
                plays = summary?.totalPlays ?: 0,
                artists = summary?.distinctArtists ?: 0,
                topArtist = top,
                loading = loading && summary == null,
                onViewStats = onViewStats,
                modifier = Modifier.revealOnAppear(order = 1, rise = 18.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}
