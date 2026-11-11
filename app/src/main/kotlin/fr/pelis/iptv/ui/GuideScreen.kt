package fr.pelis.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pelis.iptv.MainViewModel
import fr.pelis.iptv.data.EpgProgram
import fr.pelis.iptv.data.MediaEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private const val HOURS = 8
private val DP_PER_MINUTE = 4.dp
private val CHANNEL_COLUMN = 110.dp
private val ROW_HEIGHT = 64.dp
private const val MINUTE = 60_000L

private fun minutesToDp(ms: Long): Dp = DP_PER_MINUTE * (ms / MINUTE.toFloat())

/** Grille des programmes : chaînes en lignes, heures en colonnes (défilement horizontal partagé). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(vm: MainViewModel) {
    BackHandler { vm.closeGuide() }
    val context = LocalContext.current
    val channels = vm.guideChannels
    val now by rememberNow()

    // Fenêtre : de la demi-heure précédente à +8 h, renouvelée toutes les 30 min si l'écran
    // reste ouvert. La couverture réelle dépend du guide fourni par le serveur (guide court).
    val windowStart = remember(now / (30 * MINUTE)) { now - now % (30 * MINUTE) - 30 * MINUTE }
    val windowEnd = windowStart + HOURS * 60 * MINUTE
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    fun nowOffsetPx(): Int =
        with(density) { (minutesToDp(System.currentTimeMillis() - windowStart) - 40.dp).toPx() }.toInt().coerceAtLeast(0)

    LaunchedEffect(Unit) {
        snapshotFlow { hScroll.maxValue }.first { it > 0 && it < Int.MAX_VALUE }
        hScroll.scrollTo(nowOffsetPx())
    }
    // Fenêtre décalée de 30 min : on décale aussi le défilement pour garder les mêmes programmes à l'écran.
    var previousStart by remember { mutableStateOf(windowStart) }
    LaunchedEffect(windowStart) {
        val shift = windowStart - previousStart
        previousStart = windowStart
        if (shift != 0L) hScroll.scrollTo((hScroll.value - with(density) { minutesToDp(shift).toPx() }.toInt()).coerceAtLeast(0))
    }

    var selected by remember { mutableStateOf<Pair<Int, EpgProgram>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guide TV · ${vm.guideTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::closeGuide, modifier = Modifier.tvItem(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { scope.launch { hScroll.animateScrollTo(nowOffsetPx()) } },
                        modifier = Modifier.tvItem(CircleShape),
                    ) { Text("Maintenant") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // En-tête des heures
            Row(Modifier.fillMaxWidth().height(32.dp)) {
                Box(Modifier.width(CHANNEL_COLUMN))
                TimeLine(hScroll, Modifier.weight(1f)) {
                    var t = windowStart
                    while (t < windowEnd) {
                        Text(
                            formatTime(t),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.offset(x = minutesToDp(t - windowStart) + 4.dp).padding(top = 6.dp),
                        )
                        t += 30 * MINUTE
                    }
                    NowLine(now, windowStart)
                }
            }
            HorizontalDivider()

            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(channels) { index, channel ->
                    LaunchedEffect(channel.id) { vm.loadEpg(channel) }
                    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                        ChannelCell(channel, Modifier.tvItem().clickable { vm.playLive(context, channels, index) })
                        TimeLine(hScroll, Modifier.weight(1f)) {
                            val programs = vm.epg[channel.id]
                            when {
                                programs == null -> GuideHint("Chargement…")
                                programs.isEmpty() && vm.epgFailed[channel.id] == true ->
                                    GuideHint("Guide momentanément indisponible (nouvel essai automatique)")
                                programs.isEmpty() -> GuideHint("Programmes non fournis par le serveur")
                                programs.none { it.end > windowStart && it.start < windowEnd } ->
                                    GuideHint("Pas de programme sur cette période")
                                else -> programs.filter { it.end > windowStart && it.start < windowEnd }.forEach { p ->
                                    val start = maxOf(p.start, windowStart)
                                    val end = minOf(p.end, windowEnd)
                                    val live = now >= p.start && now < p.end
                                    Box(
                                        Modifier
                                            .offset(x = minutesToDp(start - windowStart))
                                            .width(minutesToDp(end - start))
                                            .fillMaxHeight()
                                            .padding(2.dp)
                                            .tvItem(RoundedCornerShape(6.dp))
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (live) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                                                else MaterialTheme.colorScheme.surfaceVariant,
                                            )
                                            .clickable { selected = index to p }
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    ) {
                                        Column {
                                            Text(
                                                p.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                "${formatTime(p.start)} – ${formatTime(p.end)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                            NowLine(now, windowStart)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    selected?.let { (index, program) ->
        val channel = channels.getOrNull(index)
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(program.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${channel?.name.orEmpty()} · ${formatTime(program.start)} – ${formatTime(program.end)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(program.description ?: "Pas de description.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selected = null
                    vm.playLive(context, channels, index)
                }) { Text("Regarder la chaîne") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Fermer") } },
        )
    }
}

/** Zone défilant horizontalement, synchronisée entre toutes les lignes via [state]. */
@Composable
private fun TimeLine(state: ScrollState, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight().horizontalScroll(state)) {
        Box(Modifier.width(minutesToDp(HOURS * 60 * MINUTE)).fillMaxHeight()) {
            content()
        }
    }
}

@Composable
private fun NowLine(now: Long, windowStart: Long) {
    Box(
        Modifier
            .offset(x = minutesToDp(now - windowStart))
            .width(2.dp)
            .fillMaxHeight()
            .background(Color(0xFFEF5350)),
    )
}

@Composable
private fun GuideHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(12.dp),
    )
}

@Composable
private fun ChannelCell(channel: MediaEntry, modifier: Modifier) {
    Row(
        modifier.width(CHANNEL_COLUMN).fillMaxHeight().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = channel.image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 56.dp, height = 30.dp),
            )
            Text(
                channel.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
