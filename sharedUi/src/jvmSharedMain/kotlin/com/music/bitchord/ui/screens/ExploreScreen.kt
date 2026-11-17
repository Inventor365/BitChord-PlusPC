package com.music.bitchord.ui.screens

import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.ShimmerBox
import com.music.bitchord.ui.components.feedSkeleton
import com.music.bitchord.ui.components.pressScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<MoodGenreSection>>,
    listState: LazyListState,
    onCategoryClick: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** The big "Explore" heading; the desktop's pages carry none. */
    showTitle: Boolean = true,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = moodColumns(maxWidth)
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (showTitle) {
                    item {
                        Column(Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 8.dp, bottom = 14.dp)) {
                            val count = (state as? UiState.Success)?.data?.sumOf { it.items.size }
                            Text(
                                text = if (count != null) "BROWSE · $count MOODS & GENRES" else "BROWSE",
                                style = MONO_LABEL,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(Res.string.explore),
                                style = MaterialTheme.typography.displayLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
                when (state) {
                    UiState.Loading -> item { ExploreSkeletonRows(columns) }
                    is UiState.Error -> item {
                        MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                    }
                    is UiState.Success -> {
                        // The server's own grouping ("Moods & moments", "Genres") now titles
                        // each part of the page; the first category of all leads it as a
                        // wide featured card.
                        val seen = HashSet<Pair<String, String?>>()
                        val sections = state.data.map { section ->
                            section.title to section.items.filter { seen.add(it.browseId to it.params) }
                        }.filter { it.second.isNotEmpty() }
                        val featured = sections.firstOrNull()?.second?.firstOrNull()
                        if (featured != null) {
                            item(key = "featured") {
                                FeaturedMoodCard(
                                    item = featured,
                                    onClick = { onCategoryClick(featured) },
                                    modifier = Modifier.padding(horizontal = PAGE_GUTTER).padding(bottom = 18.dp),
                                )
                            }
                        }
                        var index = 0
                        sections.forEachIndexed { sectionIndex, (title, entries) ->
                            val rest = if (sectionIndex == 0) entries.drop(1) else entries
                            if (rest.isEmpty()) return@forEachIndexed
                            if (title.isNotBlank()) {
                                item(key = "section:$sectionIndex") {
                                    Row(
                                        verticalAlignment = Alignment.Bottom,
                                        modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 10.dp, bottom = 12.dp),
                                    ) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            text = rest.size.toString().padStart(2, '0'),
                                            style = MONO_LABEL,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            val rows = rest.chunked(columns)
                            val start = index
                            items(rows, key = { row -> row.first().let { "${it.browseId}|${it.params}" } }) { row ->
                                MoodGenreRow(
                                    row = row,
                                    columns = columns,
                                    onCategoryClick = onCategoryClick,
                                    firstIndex = start + rest.indexOf(row.first()) + 1,
                                )
                            }
                            index += rest.size
                        }
                    }
                }
            }
        }
    }
}

/**
 * The first category, drawn wide across the head of the page: its artwork in its own colours,
 * the title large, and a "FEATURED" line in ticket print.
 */
@Composable
private fun FeaturedMoodCard(item: MoodGenre, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MoodGenreSleeve(
        item = item,
        label = "FEATURED",
        titleStyle = MaterialTheme.typography.headlineLarge,
        modifier = modifier
            .pressScale(0.98f)
            .fillMaxWidth()
            .aspectRatio(2.1f)
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
private fun MoodGenreRow(
    row: List<MoodGenre>,
    columns: Int,
    onCategoryClick: (MoodGenre) -> Unit,
    firstIndex: Int = 0,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .padding(bottom = MOOD_SPACING),
    ) {
        row.forEachIndexed { i, item ->
            MoodGenreCard(
                item = item,
                onClick = { onCategoryClick(item) },
                modifier = Modifier.weight(1f),
                label = if (firstIndex > 0) (firstIndex + i).toString().padStart(2, '0') else null,
            )
        }
        // Short rows keep their cards the size of a full row's.
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun MoodGenreCard(
    item: MoodGenre,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    MoodGenreSleeve(
        item = item,
        label = label,
        modifier = modifier.pressScale()
            .aspectRatio(MOOD_CARD_ASPECT)
            .clip(MOOD_CARD_SHAPE)
            .clickable(onClick = onClick),
    )
}

/**
 * A category's sleeve: its colour fills the card as a gradient, its cover is drawn as a duotone
 * of that colour and fades into it from the right, the title sits at the foot in heavy white,
 * and an optional [label] (an index, "FEATURED") is set in ticket print at the top.
 *
 * Only the artwork: the caller sizes, clips and makes it clickable, which is how the TV app
 * wraps the same sleeve in its own focus treatment.
 */
@Composable
fun MoodGenreSleeve(
    item: MoodGenre,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    label: String? = null,
) {
    val tone = remember(item.stripeColor, item.title) { moodTone(item) }
    Box(modifier = modifier.background(Brush.linearGradient(listOf(tone.stripe, tone.shadow)))) {
        item.thumbnailUrl?.let { artwork ->
            // The cover's own opacity fades in from its left edge, so the card's gradient
            // shows through rather than meeting the picture at a hard line.
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = tone.duotone,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(ART_FRACTION)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 0.6f to Color.Black),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        // A shade under the title, so pale colours (Energize's cream) keep white type legible.
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = .38f))),
        )
        if (label != null) {
            Text(
                text = label,
                style = MONO_LABEL,
                color = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        Text(
            text = item.title,
            style = titleStyle.copy(
                shadow = Shadow(Color.Black.copy(alpha = .35f), offset = Offset(0f, 1f), blurRadius = 6f),
            ),
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Ticket print: small, spaced, fixed-width — the same labels as the rest of Sonara. */
private val MONO_LABEL = TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = androidx.compose.ui.unit.TextUnit(10.5f, androidx.compose.ui.unit.TextUnitType.Sp),
    letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp),
)

/** How much of the card's width the cover takes, from the right. */
private const val ART_FRACTION = 0.72f

private class MoodTone(val stripe: Color, val shadow: Color, val placeholder: Color, val duotone: ColorFilter)

private fun moodTone(item: MoodGenre): MoodTone {
    val base = item.stripeColor?.let { Color(it.toInt()) } ?: fallbackMoodColor(item.title)
    // YouTube's palette is already pastel; a touch of black keeps it from
    // glowing against the black page.
    val stripe = lerp(base, Color.Black, .08f)
    val shadow = lerp(base, Color.Black, .68f)
    val highlight = lerp(base, Color.White, .32f)
    return MoodTone(
        stripe = stripe,
        shadow = lerp(base, Color.Black, .55f),
        placeholder = lerp(shadow, highlight, .45f),
        duotone = duotone(shadow, highlight),
    )
}

/**
 * Maps each pixel's luminance onto the ramp [shadow] to [highlight]. Offsets
 * are in 0..255, which Compose honours on Skia too (it rescales them).
 */
private fun duotone(shadow: Color, highlight: Color): ColorFilter {
    fun channel(from: Float, to: Float): FloatArray {
        val span = to - from
        return floatArrayOf(span * .299f, span * .587f, span * .114f, 0f, from * 255f)
    }
    return ColorFilter.colorMatrix(
        ColorMatrix(
            channel(shadow.red, highlight.red) +
                channel(shadow.green, highlight.green) +
                channel(shadow.blue, highlight.blue) +
                floatArrayOf(0f, 0f, 0f, 1f, 0f),
        ),
    )
}

/** For a button that came without YouTube's colour: muted, never neon. */
private fun fallbackMoodColor(title: String): Color = when ((title.hashCode() and Int.MAX_VALUE) % 8) {
    0 -> Color(0xFFCC6A55)
    1 -> Color(0xFFC07A92)
    2 -> Color(0xFF9C8AC0)
    3 -> Color(0xFF8090C8)
    4 -> Color(0xFFD0A060)
    5 -> Color(0xFF6A88B0)
    6 -> Color(0xFF7AAED0)
    else -> Color(0xFF86B890)
}

/** Gap between mood cards, in both directions. */
private val MOOD_SPACING = 12.dp

private val MOOD_CARD_SHAPE = RoundedCornerShape(22.dp)

/** Width over height; a two-column phone row lands at about 100dp tall. */
const val MOOD_CARD_ASPECT = 1.72f


/**
 * The narrowest a mood card is let get before the row drops a column.
 *
 * Pitched so every phone still gets its two (the widest, 448dp, has room for
 * 1.8 of these), while a tablet or a window lays out as many as fit rather
 * than two cards each half a screen wide.
 */
private val MOOD_MIN_CARD_WIDTH = 220.dp

private const val MOOD_MAX_COLUMNS = 6

private fun moodColumns(available: Dp): Int {
    val row = available - PAGE_GUTTER * 2
    return ((row + MOOD_SPACING) / (MOOD_MIN_CARD_WIDTH + MOOD_SPACING)).toInt().coerceIn(2, MOOD_MAX_COLUMNS)
}

@Composable
private fun ExploreSkeletonRows(columns: Int) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER)) {
        repeat(6) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
                modifier = Modifier.padding(bottom = MOOD_SPACING),
            ) {
                repeat(columns) {
                    ShimmerBox(Modifier.weight(1f).aspectRatio(MOOD_CARD_ASPECT), MOOD_CARD_SHAPE)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodGenrePlaylistsScreen(
    title: String,
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
            )
        }
        when (state) {
            UiState.Loading -> feedSkeleton()
            is UiState.Error -> item {
                MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
            }
            is UiState.Success -> items(state.data, key = { it.title }) { shelf ->
                Shelf(shelf = shelf, onItemClick = onItemClick)
            }
        }
    }
}
