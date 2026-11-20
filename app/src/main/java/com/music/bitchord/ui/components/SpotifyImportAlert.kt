package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.model.PlaylistPrivacy
import com.music.bitchord.data.spotify.FetchedPlaylist
import com.music.bitchord.data.spotify.PlaylistImport
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.AppleMusicImporter
import com.music.bitchord.data.spotify.AmazonMusicImporter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val IMPORT_LANGUAGES = listOf(
    "Tamil",
    "Telugu",
    "Kannada",
    "Malayalam",
    "Hindi",
    "English",
    "Multilingual",
    "No Preference",
    "Manual / Other",
)

/**
 * Paste a Spotify playlist link, match its songs on YouTube Music, keep the result.
 *
 * The same frosted card as the addon editor, rather than a Material dialog.
 * The "who can see it" row exists only for [signedIn]: it is a YouTube Music
 * setting, and a playlist kept on this device has no audience to choose.
 * Songs that matched nothing are listed once the playlist is made, instead of
 * being dropped without a word.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SpotifyImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    /** Opens the playlist an import made. */
    onOpenPlaylist: (browseId: String, title: String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Which tile opened the dialog: it sets the title, the hint and, for Amazon Music, a pasted
     * track list in place of a link. Spotify and Apple Music links are accepted in either of
     * their modes — the importer is chosen by the link itself.
     */
    source: ImportSource = ImportSource.SPOTIFY,
) {
    val appleMusic = source == ImportSource.APPLE_MUSIC
    val amazon = source == ImportSource.AMAZON_MUSIC
    val defaultAmazonName = stringResource(R.string.amazon_music_import_default_name)
    var playlistName by remember { mutableStateOf("") }
    val context = LocalContext.current
    // The import runs in PlaylistImport, outside this dialog: closing it, leaving the screen or
    // the app doesn't stop it, and opening the dialog again shows where it has got to.
    val job by PlaylistImport.state.collectAsStateWithLifecycle()
    var link by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var selectedLanguage by remember { mutableStateOf("No Preference") }
    var customLanguageInput by remember { mutableStateOf("") }
    // A link or list the dialog itself turned down, before any import started.
    var inputError by remember { mutableStateOf<String?>(null) }
    val working = job.working
    val failed = inputError != null || job.phase == PlaylistImport.Phase.FAILED
    val finished = job.phase == PlaylistImport.Phase.DONE
    val status: String? = inputError ?: when (job.phase) {
        PlaylistImport.Phase.IDLE, PlaylistImport.Phase.DONE -> null
        PlaylistImport.Phase.FETCHING -> when {
            job.fetched == 0 -> stringResource(R.string.import_reading)
            job.fetchTotal != null -> stringResource(R.string.import_reading_of, job.fetched, job.fetchTotal!!)
            else -> stringResource(R.string.import_reading_count, job.fetched)
        }
        PlaylistImport.Phase.MATCHING -> stringResource(R.string.spotify_import_matching, job.matchDone, job.fetched)
        PlaylistImport.Phase.ADDING -> stringResource(R.string.import_adding, job.added, job.toAdd)
        PlaylistImport.Phase.FAILED -> stringResource(R.string.spotify_import_failed, job.error.orEmpty())
    }
    val dismiss = {
        // A finished or failed import is cleared on the way out; a running one carries on.
        PlaylistImport.reset()
        onDismiss()
    }

    fun start() {
        inputError = null
        PlaylistImport.reset()
        val language = if (selectedLanguage == "Manual / Other") customLanguageInput.trim().ifBlank { null } else selectedLanguage
        if (amazon) {
            val tracks = AmazonMusicImporter.parseTracklist(link)
            if (tracks.isEmpty()) {
                inputError = context.getString(
                    if (AmazonMusicImporter.isAmazonMusicLink(link)) R.string.amazon_music_import_link_only else R.string.amazon_music_import_nothing,
                )
                return
            }
            val name = playlistName.trim().ifEmpty { defaultAmazonName }
            PlaylistImport.start(
                fetch = { FetchedPlaylist(name, tracks, declared = tracks.size) },
                language = language,
                privacy = privacy,
                signedIn = signedIn,
            )
            return
        }
        val appleUrl = AppleMusicImporter.extractPlaylistUrl(link)
        val id = if (appleUrl == null) SpotifyImporter.extractPlaylistId(link) else null
        if (appleUrl == null && id == null) {
            inputError = context.getString(
                if (appleMusic) R.string.apple_music_import_invalid else R.string.spotify_import_invalid,
            )
            return
        }
        PlaylistImport.start(
            fetch = { progress ->
                if (appleUrl != null) AppleMusicImporter.fetchAll(appleUrl, progress) else SpotifyImporter.fetchPlaylist(id!!, progress)
            },
            language = language,
            privacy = privacy,
            signedIn = signedIn,
        )
    }

    AlertScaffold(hazeState = hazeState, onDismiss = dismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(
                    when (source) {
                        ImportSource.SPOTIFY -> R.string.spotify_import_title
                        ImportSource.APPLE_MUSIC -> R.string.apple_music_import_title
                        ImportSource.AMAZON_MUSIC -> R.string.amazon_music_import_title
                    },
                ),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (finished) {
                ImportSummary(job)
                val missed = job.unmatched
                if (missed.isNotEmpty()) Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    missed.forEach { track ->
                        Text(
                            text = if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Text(
                    text = status ?: if (amazon) {
                        stringResource(R.string.amazon_music_import_description)
                    } else {
                        stringResource(
                            if (signedIn) R.string.spotify_import_description
                            else R.string.spotify_import_description_local,
                        )
                    },
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = when {
                        status == null -> MaterialTheme.colorScheme.onSurface
                        failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center,
                )
                if (working) {
                    Text(
                        text = stringResource(R.string.import_keeps_going),
                        modifier = Modifier.padding(bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (amazon) {
                    PillTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        placeholder = defaultAmazonName,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(8.dp))
                    TracklistField(
                        value = link,
                        onValueChange = {
                            link = it
                            if (failed) {
                                inputError = null
                                PlaylistImport.reset()
                            }
                        },
                        placeholder = stringResource(R.string.amazon_music_import_list_hint),
                        enabled = !working,
                    )
                } else {
                    PillTextField(
                        value = link,
                        onValueChange = {
                            link = it
                            if (failed) {
                                inputError = null
                                PlaylistImport.reset()
                            }
                        },
                        placeholder = stringResource(if (appleMusic) R.string.apple_music_import_hint else R.string.spotify_import_hint),
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank() && !working) start() }),
                    )
                }
                Text(
                    text = stringResource(R.string.spotify_import_language_preference),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IMPORT_LANGUAGES.forEach { lang ->
                        val selected = lang == selectedLanguage
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable(enabled = !working) {
                                    selectedLanguage = lang
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = lang,
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (selectedLanguage == "Manual / Other") {
                    Spacer(Modifier.height(8.dp))
                    PillTextField(
                        value = customLanguageInput,
                        onValueChange = { customLanguageInput = it },
                        placeholder = stringResource(R.string.spotify_import_language_hint),
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                    )
                }
                if (signedIn) {
                    Text(
                        text = stringResource(R.string.who_can_see_it),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PlaylistPrivacy.entries.forEach { option ->
                            val selected = option == privacy
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    .clickable(enabled = !working) { privacy = option }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        AlertRule()
        if (finished) {
            if (job.unmatched.isNotEmpty()) {
                AlertAction(
                    label = if (job.retrying) stringResource(R.string.spotify_import_matching, job.matchDone, job.unmatched.size)
                    else stringResource(R.string.import_retry_unmatched, job.unmatched.size),
                    emphasised = false,
                    onClick = PlaylistImport::retryUnmatched,
                    enabled = !job.retrying,
                )
                AlertRule()
            }
            job.browseId?.let { browseId ->
                AlertAction(
                    label = stringResource(R.string.import_open_playlist),
                    emphasised = false,
                    onClick = {
                        val title = job.title
                        dismiss()
                        onOpenPlaylist(browseId, title)
                    },
                    enabled = !job.retrying,
                )
                AlertRule()
            }
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = dismiss, enabled = !job.retrying)
        } else if (working) {
            // Closing doesn't stop it: the import goes on, and says so when it's done.
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
        } else {
            AlertAction(
                // After a failure, the same button tries again, and says so.
                label = stringResource(if (job.phase == PlaylistImport.Phase.FAILED) R.string.retry else R.string.spotify_import_button),
                emphasised = true,
                onClick = ::start,
                enabled = link.isNotBlank(),
            )
            AlertRule()
            AlertAction(
                label = stringResource(R.string.cancel),
                emphasised = false,
                onClick = dismiss,
            )
        }
    }
}

/** Which service an import dialog was opened for. */
enum class ImportSource { SPOTIFY, APPLE_MUSIC, AMAZON_MUSIC }

/**
 * How an import came out, in one or two lines: "Imported 781 of 812 — 31 couldn't be matched",
 * then whatever else fell short — songs the service didn't give up, songs YouTube Music
 * wouldn't take, songs never sent — and where the playlist went if not YouTube Music.
 */
@Composable
private fun ImportSummary(job: PlaylistImport.State) {
    val total = job.fetched + job.fetchMissing
    val head = buildString {
        append(stringResource(R.string.import_summary, job.added, total))
        if (job.unmatched.isNotEmpty()) append(" — ").append(stringResource(R.string.import_summary_unmatched, job.unmatched.size))
    }
    val notes = buildList {
        if (job.regionHidden > 0) add(stringResource(R.string.import_summary_region_hidden, job.regionHidden))
        val unread = job.fetchMissing - job.regionHidden
        if (unread > 0) add(stringResource(R.string.import_summary_unread, unread))
        if (job.rejected > 0) add(stringResource(R.string.import_summary_rejected, job.rejected))
        if (job.notSent > 0) add(stringResource(R.string.import_summary_not_sent, job.notSent))
        if (job.repeats > 0) add(stringResource(R.string.import_summary_repeats, job.repeats))
        // Signed out, saving here was the plan, not a fallback: the dialog said so up front.
        if (job.savedLocally && job.wantedRemote) add(stringResource(R.string.spotify_import_local_fallback, job.title))
    }
    Text(
        text = head,
        modifier = Modifier.padding(top = 4.dp, bottom = if (notes.isEmpty()) 10.dp else 4.dp),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.W600),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    if (notes.isNotEmpty()) {
        Text(
            text = notes.joinToString("\n"),
            modifier = Modifier.padding(bottom = 10.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A few lines tall, for a pasted track list; the pill field shows one line. */
@Composable
private fun TracklistField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = style,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        )
    }
}
