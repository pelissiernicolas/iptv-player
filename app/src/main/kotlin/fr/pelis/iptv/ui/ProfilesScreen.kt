package fr.pelis.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.pelis.iptv.MainViewModel
import fr.pelis.iptv.data.Profile
import kotlin.math.abs

private val AvatarColors = listOf(
    Color(0xFF29B6F6), Color(0xFFEF5350), Color(0xFF66BB6A), Color(0xFFFFA726),
    Color(0xFFAB47BC), Color(0xFF26A69A), Color(0xFFEC407A), Color(0xFF8D6E63),
)

@Composable
fun ProfileAvatar(profile: Profile, size: Int = 48) {
    Box(
        Modifier
            .size(size.dp)
            .background(AvatarColors[abs(profile.id.hashCode()) % AvatarColors.size], CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            profile.name.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = if (size >= 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        )
    }
}

/** Choix du profil (compte Xtream) à ouvrir. */
@Composable
fun ProfilesScreen(vm: MainViewModel) {
    var toDelete by remember { mutableStateOf<Profile?>(null) }
    val firstFocus = remember { FocusRequester() }
    if (LocalTvMode.current) LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        // Changer le type d'appareil (Téléviseur / Smartphone)
        IconButton(
            onClick = vm::openDeviceSetup,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).tvItem(CircleShape),
        ) {
            Icon(if (vm.tvMode) Icons.Filled.Tv else Icons.Filled.Smartphone, "Type d'appareil")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    "Qui regarde ?",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            itemsIndexed(vm.profiles, key = { _, it -> it.id }) { index, profile ->
                Card(
                    onClick = { vm.openProfile(profile) },
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .tvItem(RoundedCornerShape(12.dp)),
                ) {
                    Row(
                        Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileAvatar(profile)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${profile.username} · ${profile.server.substringAfter("://")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { vm.editProfile(profile) }, modifier = Modifier.tvItem(CircleShape)) {
                            Icon(Icons.Filled.Edit, "Modifier")
                        }
                        IconButton(onClick = { toDelete = profile }, modifier = Modifier.tvItem(CircleShape)) {
                            Icon(Icons.Filled.Delete, "Supprimer")
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = vm::addProfile,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("Ajouter un profil") },
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).tvItem(RoundedCornerShape(16.dp)),
        )
    }

    toDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Supprimer « ${profile.name} » ?") },
            text = { Text("Le profil, ses favoris, sa progression de lecture et son historique de chaînes seront supprimés de cet appareil.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProfile(profile); toDelete = null }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
}
