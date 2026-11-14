package fr.pelis.iptv.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fr.pelis.iptv.MainViewModel
import fr.pelis.iptv.transfer.PhoneLinkServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * QR code à scanner avec un téléphone : ouvre une page servie par l'appareil sur le réseau local,
 * pour saisir un compte ou échanger une sauvegarde sans clavier ni gestionnaire de fichiers.
 */
@Composable
fun PhoneLinkScreen(vm: MainViewModel, mode: PhoneLinkServer.Mode) {
    BackHandler { vm.closePhoneLink() }
    val scope = rememberCoroutineScope()
    var generation by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var expired by remember { mutableStateOf(false) }
    var noNetwork by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mode, generation) {
        val server = PhoneLinkServer(
            mode,
            object : PhoneLinkServer.Handler {
                override suspend fun login(request: PhoneLinkServer.LoginRequest): String {
                    scope.launch { status = "Vérification du compte…" }
                    return try {
                        vm.loginFromPhone(request)
                    } catch (e: Exception) {
                        scope.launch { status = e.message }
                        throw e
                    }
                }

                override suspend fun importBackup(text: String): String =
                    vm.importBackupFromPhone(text).also { message -> scope.launch { status = message } }

                override suspend fun exportBackup(): Pair<String, String> =
                    vm.exportBackupForPhone().also { scope.launch { status = "Sauvegarde envoyée au téléphone" } }

                override fun onFinished() {
                    scope.launch { finished = true }
                }
            },
        )
        url = runCatching { server.start() }.getOrNull()
        noNetwork = url == null
        expired = false
        finished = false
        // L'appli passe en arrière-plan (écran d'accueil, autre appli, veille) : le serveur
        // est fermé tout de suite, sans attendre l'expiration ; un nouveau code sera demandé.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                server.stop()
                if (!finished) expired = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            server.stop()
        }
    }
    LaunchedEffect(generation) {
        delay(PhoneLinkServer.LIFETIME_MS)
        expired = true
    }

    val qr = remember(url) { url?.let { qrBitmap(it) } }
    val mainFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { mainFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (qr != null && !expired && !finished) Color.White else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    noNetwork -> Text("Pas de réseau local", Modifier.padding(16.dp))
                    finished -> Text("✓", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
                    expired -> Text("Code expiré", Modifier.padding(16.dp))
                    qr != null -> Image(
                        qr, contentDescription = "QR code à scanner avec le téléphone",
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(272.dp),
                    )
                }
            }
            Column(
                Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (mode == PhoneLinkServer.Mode.LOGIN) "Ajouter un compte depuis un téléphone"
                    else "Sauvegarde depuis un téléphone",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("1. Connectez le téléphone au même Wi-Fi que cet appareil.")
                Text("2. Scannez le QR code avec l'appareil photo.")
                Text(
                    if (mode == PhoneLinkServer.Mode.LOGIN) "3. Saisissez vos identifiants (ou collez le lien M3U) et envoyez."
                    else "3. Envoyez un fichier de sauvegarde, ou téléchargez celle de cet appareil.",
                )
                if (url != null && !expired && !finished) {
                    Text(
                        "Sans appareil photo : ouvrez $url",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Les données passent directement du téléphone à cet appareil par votre réseau local, " +
                        "sans Internet, mais sans chiffrement (HTTP) : à utiliser uniquement sur un Wi-Fi " +
                        "de confiance. Le code ne sert qu'une fois et expire après 10 minutes ou dès que " +
                        "vous quittez cet écran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (expired || noNetwork) {
                        Button(
                            onClick = { status = null; generation++ },
                            modifier = Modifier.tvItem(CircleShape),
                        ) { Text("Nouveau code") }
                    }
                    OutlinedButton(
                        onClick = vm::closePhoneLink,
                        modifier = Modifier.focusRequester(mainFocus).tvItem(CircleShape),
                    ) { Text(if (finished) "Terminé" else "Annuler") }
                }
            }
        }
    }
}

private fun qrBitmap(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix[i % w, i / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
