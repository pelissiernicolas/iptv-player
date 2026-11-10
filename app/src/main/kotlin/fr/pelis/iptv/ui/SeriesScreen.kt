package fr.pelis.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pelis.iptv.MainViewModel
import fr.pelis.iptv.data.Library
import fr.pelis.iptv.data.MediaEntry
import fr.pelis.iptv.data.SeriesDetail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(vm: MainViewModel, entry: MediaEntry) {
    BackHandler { vm.closeSeries() }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::closeSeries) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val detail = vm.seriesDetail
            val error = vm.seriesError
            when {
                vm.seriesBusy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> ErrorBox(error) { vm.openSeries(entry) }
                detail != null -> SeriesContent(detail, entry) { season, index ->
                    val episodes = detail.seasons[season].orEmpty()
                    val saved = episodes.getOrNull(index)?.let { Library.progressFor("ep:${it.id}") }
                    vm.playEpisodes(
                        context,
                        seriesName = detail.name.ifBlank { entry.name },
                        seriesImage = detail.cover ?: entry.image,
                        episodes = episodes,
                        index = index,
                        startPositionMs = saved?.position ?: -1,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesContent(detail: SeriesDetail, entry: MediaEntry, onPlay: (season: Int, index: Int) -> Unit) {
    val seasons = detail.seasons.keys.toList()
    var selected by rememberSaveable { mutableIntStateOf(seasons.firstOrNull() ?: 1) }
    val episodes = detail.seasons[selected].orEmpty()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = detail.cover ?: entry.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 110.dp, height = 165.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(detail.name.ifBlank { entry.name }, style = MaterialTheme.typography.titleLarge)
                    val meta = listOfNotNull(
                        detail.releaseDate?.take(4),
                        detail.genre,
                        detail.rating?.let { "★ $it" },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    (detail.plot ?: entry.plot)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 7,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (seasons.isEmpty()) {
            item { Text("Aucun épisode disponible", Modifier.padding(16.dp)) }
        } else {
            item {
                ScrollableTabRow(
                    selectedTabIndex = seasons.indexOf(selected).coerceAtLeast(0),
                    edgePadding = 12.dp,
                ) {
                    seasons.forEach { s ->
                        Tab(selected = s == selected, onClick = { selected = s }, text = { Text("Saison $s") })
                    }
                }
            }
            itemsIndexed(episodes) { index, ep ->
                val saved = Library.progressFor("ep:${ep.id}")
                ListItem(
                    headlineContent = {
                        Text("${ep.number}. ${ep.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Column {
                            if (saved != null) {
                                Text(
                                    "Reprendre à ${formatDuration(saved.position)}",
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                ThinProgress(
                                    saved.position.toFloat() / saved.duration,
                                    Modifier.padding(top = 4.dp).clip(RoundedCornerShape(2.dp)),
                                )
                            } else {
                                ep.duration?.let { Text(it) }
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onPlay(selected, index) },
                )
            }
        }
    }
}
