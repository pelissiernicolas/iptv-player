package fr.pelis.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pelis.iptv.MainViewModel

/** Premier démarrage (ou réglage) : l'application tourne-t-elle sur un téléviseur ou un mobile ? */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceSetupScreen(vm: MainViewModel, firstLaunch: Boolean) {
    BackHandler(enabled = !firstLaunch) { vm.cancelDeviceSetup() }
    val tvFocus = remember { FocusRequester() }
    val mobileFocus = remember { FocusRequester() }
    // Focus initial sur l'option détectée : un simple « OK » sur la télécommande suffit.
    LaunchedEffect(Unit) {
        runCatching { (if (vm.detectedTv) tvFocus else mobileFocus).requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) {
        Text(
            if (firstLaunch) "Bienvenue dans IPTV Player" else "Type d'appareil",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "Sur quel appareil utilisez-vous l'application ?",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            DeviceOption(
                icon = Icons.Filled.Tv,
                title = "Téléviseur",
                subtitle = "Android TV, Google TV, box TV\nNavigation à la télécommande",
                detected = vm.detectedTv,
                selected = !firstLaunch && vm.tvMode,
                modifier = Modifier.focusRequester(tvFocus),
                onClick = { vm.chooseDeviceMode(tv = true) },
            )
            DeviceOption(
                icon = Icons.Filled.Smartphone,
                title = "Smartphone / tablette",
                subtitle = "Écran tactile\nPicture-in-Picture et Chromecast",
                detected = !vm.detectedTv,
                selected = !firstLaunch && !vm.tvMode,
                modifier = Modifier.focusRequester(mobileFocus),
                onClick = { vm.chooseDeviceMode(tv = false) },
            )
        }
        Text(
            "Vous pourrez changer ce choix plus tard depuis l'écran des profils ou le menu Compte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
        )
        if (!firstLaunch) {
            TextButton(onClick = vm::cancelDeviceSetup) { Text("Annuler") }
        }
    }
}

@Composable
private fun DeviceOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    detected: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // Mise en évidence du focus toujours active ici : à ce stade le mode n'est pas encore choisi.
    val shape = RoundedCornerShape(16.dp)
    Card(
        onClick = onClick,
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.width(260.dp).tvFocusAlways(shape),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            if (detected) {
                Text(
                    "✓ Détecté sur cet appareil",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
