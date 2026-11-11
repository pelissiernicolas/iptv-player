package fr.pelis.iptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.pelis.iptv.MainViewModel

/** Fenêtre « Mise à jour disponible », affichée par-dessus n'importe quel écran. */
@Composable
fun UpdateDialog(vm: MainViewModel) {
    val info = vm.update ?: return
    val context = LocalContext.current
    val progress = vm.updateProgress
    val downloading = progress != null || vm.updateInstalling

    AlertDialog(
        onDismissRequest = { if (!downloading) vm.dismissUpdate(skipThisVersion = false) },
        icon = { Icon(Icons.Filled.SystemUpdate, null) },
        title = { Text("Mise à jour disponible") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Version ${info.version} (vous avez la ${vm.appVersion})" +
                        if (info.sizeBytes > 0) " · %.1f Mo".format(info.sizeBytes / 1_048_576.0) else "",
                    fontWeight = FontWeight.SemiBold,
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (vm.updateInstalling) {
                    Text("Préparation de l'installation…", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (progress != null) {
                    Text("Téléchargement… ${(progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                vm.updateError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.startUpdate(context) },
                enabled = !downloading,
                modifier = Modifier.tvItem(CircleShape),
            ) { Text(if (downloading) "Téléchargement…" else "Mettre à jour") }
        },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onClick = { vm.dismissUpdate(skipThisVersion = false) }, modifier = Modifier.tvItem(CircleShape)) {
                    Text(if (downloading) "Annuler" else "Plus tard")
                }
                if (!downloading) {
                    TextButton(onClick = { vm.dismissUpdate(skipThisVersion = true) }, modifier = Modifier.tvItem(CircleShape)) {
                        Text("Ignorer cette version")
                    }
                }
            }
        },
    )
}
