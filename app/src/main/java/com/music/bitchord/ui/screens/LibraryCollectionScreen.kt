package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.thumbnailBorder

private enum class TimeFilter(val labelRes: Int) {
    DAY(R.string.filter_day),
    WEEK(R.string.filter_week),
    MONTH(R.string.filter_month),
    YEAR(R.string.filter_year);
}

private enum class SongSort(val labelRes: Int) {
    TITLE(R.string.sort_title),
    ARTIST(R.string.sort_artist),
    ALBUM(R.string.sort_album),
    DATE_ADDED(R.string.sort_chip_date_added),
    DURATION(R.string.sort_duration);
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryCollectionScreen(
    shelf: HomeShelf,
    localSongs: List<Song>,
    onBack: () -> Unit,
    onPlaySong: (List<Song>, Int) -> Unit,
    onShufflePlay: (List<Song>) -> Unit,
    onSongLongPress: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val title = shelf.title
    // Keyed off the shelf's stable id, never its title: titles are translated.
    val isAllSongs = shelf.key == "all_songs"
    val isMostPlayed = shelf.key == "most_played"
    val isRecentlyPlayed = shelf.key == "recently_played"
    val isRecentlyAdded = shelf.key == "recently_added"
    val isNeverPlayed = shelf.key == "never_played"

    // State for Most Played
    var replayPeriod by remember { mutableStateOf(ReplayPeriod.ALL_TIME) }
    var replaySummary by remember { mutableStateOf<ReplaySummary?>(null) }

    LaunchedEffect(isMostPlayed, replayPeriod) {
        if (isMostPlayed) {
            replaySummary = runCatching { ListeningStats.summary(replayPeriod) }.getOrNull()
        }
    }

    // State for Recently Played & Recently Added
    var timeFilter by remember { mutableStateOf(TimeFilter.MONTH) }

    // State for All Songs sorting
    var songSort by remember { mutableStateOf(SongSort.TITLE) }

    // State for Never Played
    var playsMap by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var recentPairs by remember { mutableStateOf<List<Pair<Song, Long>>>(emptyList()) }

    LaunchedEffect(Unit) {
        playsMap = runCatching { ListeningStats.getTrackPlaysMap() }.getOrDefault(emptyMap())
        recentPairs = runCatching { ListeningStats.getRecentPlayedTracks() }.getOrDefault(emptyList())
    }

    // Compute songs to display
    val displaySongs = remember(
        shelf,
        localSongs,
        replaySummary,
        timeFilter,
        songSort,
        playsMap,
        recentPairs,
    ) {
        when {
            isAllSongs -> {
                when (songSort) {
                    SongSort.TITLE -> localSongs.sortedBy { it.title.lowercase() }
                    SongSort.ARTIST -> localSongs.sortedBy { it.artist.lowercase() }
                    SongSort.ALBUM -> localSongs.sortedBy { (it.albumName ?: "").lowercase() }
                    SongSort.DATE_ADDED -> localSongs.sortedByDescending { it.localDateAddedSeconds ?: 0L }
                    SongSort.DURATION -> localSongs.sortedByDescending { parseDuration(it.durationText) }
                }
            }
            isMostPlayed -> {
                val summary = replaySummary
                if (summary != null) {
                    summary.songs.map { it.song }
                } else {
                    localSongs
                        .map { it to (playsMap[it.videoId] ?: playsMap[it.localUri] ?: 0) }
                        .filter { it.second > 0 }
                        .sortedByDescending { it.second }
                        .map { it.first }
                }
            }
            isRecentlyPlayed -> {
                val now = System.currentTimeMillis()
                val windowMs = when (timeFilter) {
                    TimeFilter.DAY -> 86_400_000L
                    TimeFilter.WEEK -> 7 * 86_400_000L
                    TimeFilter.MONTH -> 30 * 86_400_000L
                    TimeFilter.YEAR -> 365 * 86_400_000L
                }
                recentPairs
                    .filter { (now - it.second) <= windowMs }
                    .map { it.first }
            }
            isRecentlyAdded -> {
                val nowSec = System.currentTimeMillis() / 1000L
                val windowSec = when (timeFilter) {
                    TimeFilter.DAY -> 86_400L
                    TimeFilter.WEEK -> 7 * 86_400L
                    TimeFilter.MONTH -> 30 * 86_400L
                    TimeFilter.YEAR -> 365 * 86_400L
                }
                localSongs
                    .filter { song ->
                        val added = song.localDateAddedSeconds ?: 0L
                        added > 0 && (nowSec - added) <= windowSec
                    }
                    .sortedByDescending { it.localDateAddedSeconds ?: 0L }
            }
            isNeverPlayed -> {
                localSongs.filter {
                    (playsMap[it.videoId] ?: playsMap[it.localUri] ?: 0) == 0
                }
            }
            else -> {
                val videoIds = shelf.items.mapNotNull { it.videoId }.toSet()
                localSongs.filter { it.videoId in videoIds }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PAGE_GUTTER,
            end = PAGE_GUTTER,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 80.dp,
        ),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "${displaySongs.size} ${if (displaySongs.size == 1) "track" else "tracks"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Stats card for Most Played
        if (isMostPlayed && replaySummary != null) {
            item {
                val sum = replaySummary!!
                val minutes = sum.totalMs / 60_000L
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${sum.totalPlays}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.total_plays),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.time_listened),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // Action buttons: Play All & Shuffle
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { if (displaySongs.isNotEmpty()) onPlaySong(displaySongs, 0) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = displaySongs.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.play_all))
                }
                OutlinedButton(
                    onClick = { if (displaySongs.isNotEmpty()) onShufflePlay(displaySongs) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = displaySongs.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.shuffle))
                }
            }
        }

        // Filter / Sort Chips
        item {
            if (isMostPlayed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ReplayPeriod.entries.forEach { p ->
                        val active = p == replayPeriod
                        FilterChip(
                            selected = active,
                            onClick = { replayPeriod = p },
                            label = { Text(p.chip) },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            } else if (isRecentlyPlayed || isRecentlyAdded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TimeFilter.entries.forEach { tf ->
                        val active = tf == timeFilter
                        FilterChip(
                            selected = active,
                            onClick = { timeFilter = tf },
                            label = { Text(stringResource(tf.labelRes)) },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            } else if (isAllSongs) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SongSort.entries.forEach { ss ->
                        val active = ss == songSort
                        FilterChip(
                            selected = active,
                            onClick = { songSort = ss },
                            label = { Text(stringResource(ss.labelRes)) },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
        }

        if (displaySongs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_songs_for_selection),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(displaySongs, key = { index, song -> "${song.videoId}_$index" }) { index, song ->
                SongCollectionRow(
                    index = index + 1,
                    song = song,
                    onClick = { onPlaySong(displaySongs, index) },
                    onLongClick = { onSongLongPress(song) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongCollectionRow(
    index: Int,
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(32.dp),
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .thumbnailBorder(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = song.thumbnailUrl?.artworkAt(ROW_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = painterResource(R.drawable.ic_logo),
                fallback = painterResource(R.drawable.ic_logo),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(song.artist.takeIf { it.isNotBlank() }, song.durationText).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun parseDuration(durationText: String?): Long {
    if (durationText == null) return 0L
    val parts = durationText.split(":")
    return when (parts.size) {
        2 -> (parts[0].toLongOrNull() ?: 0L) * 60 + (parts[1].toLongOrNull() ?: 0L)
        3 -> (parts[0].toLongOrNull() ?: 0L) * 3600 + (parts[1].toLongOrNull() ?: 0L) * 60 + (parts[2].toLongOrNull() ?: 0L)
        else -> 0L
    }
}
