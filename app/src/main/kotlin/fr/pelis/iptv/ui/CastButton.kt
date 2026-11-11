package fr.pelis.iptv.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.pelis.iptv.cast.CastSupport

/**
 * Bouton Google Cast. N'affiche rien si les services Google sont absents ;
 * le bouton se masque tout seul tant qu'aucun Chromecast n'est détecté sur le réseau.
 */
@Composable
fun CastButton() {
    val context = LocalContext.current
    var available by remember { mutableStateOf(CastSupport.context(context) != null) }
    if (!available) {
        // Services Google momentanément indisponibles : on retente régulièrement.
        LaunchedEffect(Unit) {
            while (!available) {
                delay(60_000)
                available = CastSupport.context(context) != null
            }
        }
        return
    }
    AndroidView(
        factory = { ctx -> CastSupport.createButton(ctx)!! },
        modifier = Modifier.size(48.dp),
    )
}
