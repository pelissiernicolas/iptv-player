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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MovieScreen(vm: MainViewModel, entry: MediaEntry) {
    BackHandler { vm.closeMovie() }
    val context = LocalContext.current
    val detail = vm.movieDetail
    val isFavorite = entry.id in vm.favorites[ContentType.VOD].orEmpty()
    val progress = Library.progressFor("vod:${entry.id}")
    val extension = detail?.extension ?: entry.extension

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::closeMovie) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
                actions = {
                    IconButton(onClick = { vm.toggleFavorite(entry) }) {
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // Bandeau
            val backdrop = detail?.backdrop
            if (backdrop != null) {
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        detail?.name?.ifBlank { null } ?: entry.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
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

            // Boutons
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (progress != null) {
                    Button(onClick = { vm.playVod(context, entry, extension, progress.position) }) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text("  Reprendre à ${formatDuration(progress.position)}")
                    }
                    OutlinedButton(onClick = { vm.playVod(context, entry, extension) }) {
                        Icon(Icons.Filled.Replay, null)
                        Text("  Depuis le début")
                    }
                } else {
                    Button(onClick = { vm.playVod(context, entry, extension) }) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text("  Lecture")
                    }
                }
                detail?.trailerYoutubeId?.let { id ->
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$id")),
                            )
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
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp).clip(RoundedCornerShape(2.dp)),
                )
            }

            Column(
                Modifier.padding(16.dp).widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                (detail?.plot ?: entry.plot)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
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
    }
}

@Composable
private fun InfoBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
