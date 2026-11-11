package fr.pelis.iptv.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pelis.iptv.MainViewModel
import fr.pelis.iptv.data.ContentType
import fr.pelis.iptv.data.Library
import fr.pelis.iptv.data.MediaEntry

/** Fiche détaillée d'un film avant lecture. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieScreen(vm: MainViewModel, entry: MediaEntry) {
    BackHandler { vm.closeMovie() }
    val tv = LocalTvMode.current
    val detail = vm.movieDetail
    val isFavorite = entry.id in vm.favorites[ContentType.VOD].orEmpty()

    // Télécommande : focus sur « Lecture / Reprendre » à l'ouverture, puis de nouveau quand les
    // détails arrivent (la mise en page change) — seulement si rien n'a le focus, pour ne jamais
    // reprendre la main à quelqu'un qui a déjà commencé à naviguer.
    val playFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }
    if (tv) {
        LaunchedEffect(vm.movieBusy, detail) {
            withFrameNanos { }
            if (!contentHasFocus) runCatching { playFocus.requestFocus() }
        }
    }

    Scaffold(
        modifier = Modifier.onFocusChanged { contentHasFocus = it.hasFocus },
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::closeMovie, modifier = Modifier.tvItem(CircleShape)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleFavorite(entry) }, modifier = Modifier.tvItem(CircleShape)) {
                        Icon(
                            if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (isFavorite) "Retirer des favoris" else "Ajouter aux favoris",
                            tint = if (isFavorite) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (tv) {
            MovieTvLayout(vm, entry, playFocus, Modifier.padding(padding))
        } else {
            MovieMobileLayout(vm, entry, playFocus, Modifier.padding(padding))
        }
    }
}

/**
 * Téléviseur : tout tient dans la première vue. Affiche à gauche ; titre, actions puis résumé
 * à droite. Le visuel de fond est une simple ambiance derrière le contenu (il ne prend pas de place).
 */
@Composable
private fun MovieTvLayout(vm: MainViewModel, entry: MediaEntry, playFocus: FocusRequester, modifier: Modifier) {
    val detail = vm.movieDetail
    Box(modifier.fillMaxSize()) {
        detail?.backdrop?.let { backdrop ->
            AsyncImage(
                model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.background,
                        0.55f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                    ),
                ),
            )
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            AsyncImage(
                model = detail?.poster ?: entry.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(260.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MovieHeader(vm, entry, titleStyle = MaterialTheme.typography.headlineMedium)
                MovieActions(vm, entry, playFocus)
                MovieDetails(vm, entry)
            }
        }
    }
}

/** Téléphone / tablette : bandeau, en-tête, actions puis détails, dans une colonne défilante. */
@Composable
private fun MovieMobileLayout(vm: MainViewModel, entry: MediaEntry, playFocus: FocusRequester, modifier: Modifier) {
    val detail = vm.movieDetail
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        detail?.backdrop?.let { backdrop ->
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AsyncImage(
                    model = backdrop, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.5f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background,
                        ),
                    ),
                )
            }
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AsyncImage(
                model = detail?.poster ?: entry.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 110.dp, height = 165.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            MovieHeader(vm, entry, titleStyle = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 16.dp)) { MovieActions(vm, entry, playFocus) }
        Column(Modifier.padding(16.dp).widthIn(max = 720.dp)) { MovieDetails(vm, entry) }
    }
}

@Composable
private fun MovieHeader(vm: MainViewModel, entry: MediaEntry, titleStyle: TextStyle) {
    val detail = vm.movieDetail
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(detail?.name?.ifBlank { null } ?: entry.name, style = titleStyle, fontWeight = FontWeight.SemiBold)
        val meta = listOfNotNull(
            detail?.releaseDate?.take(4),
            detail?.duration,
            (detail?.rating ?: entry.rating)?.takeIf { it != "0" }?.let { "★ $it" },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        detail?.genre?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (vm.movieBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovieActions(vm: MainViewModel, entry: MediaEntry, playFocus: FocusRequester) {
    val context = LocalContext.current
    val detail = vm.movieDetail
    val progress = Library.progressFor("vod:${entry.id}")
    val extension = detail?.extension ?: entry.extension
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (progress != null) {
            Button(
                onClick = { vm.playVod(context, entry, extension, progress.position) },
                modifier = Modifier.focusRequester(playFocus).tvItem(CircleShape),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Text("  Reprendre à ${formatDuration(progress.position)}")
            }
            OutlinedButton(
                onClick = { vm.playVod(context, entry, extension) },
                modifier = Modifier.tvItem(CircleShape),
            ) {
                Icon(Icons.Filled.Replay, null)
                Text("  Depuis le début")
            }
        } else {
            Button(
                onClick = { vm.playVod(context, entry, extension) },
                modifier = Modifier.focusRequester(playFocus).tvItem(CircleShape),
            ) {
                Icon(Icons.Filled.PlayArrow, null)
                Text("  Lecture")
            }
        }
        detail?.trailerYoutubeId?.let { id ->
            OutlinedButton(modifier = Modifier.tvItem(CircleShape), onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")))
                }
            }) {
                Icon(Icons.Filled.Theaters, null)
                Text("  Bande-annonce")
            }
        }
    }
    if (progress != null) {
        ThinProgress(
            progress.position.toFloat() / progress.duration,
            Modifier.padding(vertical = 12.dp).widthIn(max = 480.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun MovieDetails(vm: MainViewModel, entry: MediaEntry) {
    val detail = vm.movieDetail
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        (detail?.plot ?: entry.plot)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        detail?.director?.let { InfoBlock("Réalisation", it) }
        detail?.cast?.let { InfoBlock("Distribution", it) }
        vm.movieError?.let {
            Text(
                "Détails indisponibles ($it). La lecture reste possible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InfoBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
