package fr.pelis.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.foundation.shape.CircleShape
import fr.pelis.iptv.transfer.PhoneLinkServer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.pelis.iptv.MainViewModel
import fr.pelis.iptv.data.Profile
import fr.pelis.iptv.data.XtreamClient

private val RedactedServerSaver = Saver<String, String>(
    save = { XtreamClient.redactServerInput(it) },
    restore = { it },
)

/** Création ou modification d'un profil (compte Xtream Codes). */
@Composable
fun ProfileEditScreen(vm: MainViewModel, editing: Profile?) {
    val canCancel = vm.profiles.isNotEmpty()
    BackHandler(enabled = canCancel) { vm.cancelEdit() }

    var name by rememberSaveable(editing?.id) { mutableStateOf(editing?.name ?: "") }
    // Champ serveur : un lien M3U incomplet collé ici peut contenir un mot de passe.
    // Seule sa version expurgée (sans paramètres ni identifiants) est sauvegardée par Android.
    var server by rememberSaveable(editing?.id, stateSaver = RedactedServerSaver) {
        mutableStateOf(editing?.server ?: "")
    }
    var user by rememberSaveable(editing?.id) { mutableStateOf(editing?.username ?: "") }
    // Mot de passe : jamais dans l'état sauvegardé par Android (remember, pas rememberSaveable).
    var pass by remember(editing?.id) { mutableStateOf(editing?.password ?: "") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    val submit = { vm.saveProfile(editing, name, server, user, pass) }
    val fieldMod = Modifier.widthIn(max = 480.dp).fillMaxWidth()

    val tv = LocalTvMode.current
    val header: @Composable () -> Unit = {
        Icon(
            Icons.Filled.LiveTv, contentDescription = null,
            Modifier.size(if (tv) 48.dp else 64.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            when {
                editing != null -> "Modifier le profil"
                vm.profiles.isEmpty() -> "IPTV Player"
                else -> "Nouveau profil"
            },
            style = if (tv) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
        )
        Text(
            "Identifiants Xtream Codes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
    val tip: @Composable () -> Unit = {
        Text(
            "Astuce : collez votre lien M3U complet (…/get.php?username=…&password=…) " +
                "dans le champ serveur pour tout remplir automatiquement.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.widthIn(max = 480.dp),
        )
    }
    val form: @Composable () -> Unit = {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nom du profil (facultatif)") },
            placeholder = { Text("Ex. : Salon, Abonnement 2…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = fieldMod,
        )
        OutlinedTextField(
            value = server,
            onValueChange = { v ->
                val parsed = XtreamClient.parseM3uLink(v)
                if (parsed != null) {
                    server = parsed.server; user = parsed.username; pass = parsed.password
                } else {
                    server = v
                }
            },
            label = { Text("URL du serveur") },
            placeholder = { Text("http://exemple.com:8080") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = fieldMod,
        )
        if (XtreamClient.isCleartext(server) || (server.isNotBlank() && "://" !in server)) {
            Text(
                "Connexion HTTP non chiffrée : identifiants et vidéos circulent en clair sur le réseau. " +
                    "Utilisez une adresse https:// si votre fournisseur la propose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = fieldMod,
            )
        }
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Nom d'utilisateur") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = fieldMod,
        )
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(
                        if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPass) "Masquer" else "Afficher",
                    )
                }
            },
            modifier = fieldMod,
        )

        vm.loginError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = fieldMod)
        }

        Button(onClick = submit, enabled = !vm.loginBusy, modifier = fieldMod.height(52.dp)) {
            if (vm.loginBusy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(if (editing != null) "Enregistrer et se connecter" else "Se connecter")
            }
        }
        if (canCancel) {
            TextButton(onClick = vm::cancelEdit) { Text("Annuler") }
        }
    }

    if (tv) {
        // Focus initial sur « Saisir depuis un téléphone » : un simple OK suffit.
        val phoneFocus = remember { FocusRequester() }
        if (editing == null) LaunchedEffect(Unit) { runCatching { phoneFocus.requestFocus() } }
        // Téléviseur : deux colonnes, tout est visible d'un coup sans faire défiler.
        Row(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f, fill = false).widthIn(max = 400.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header()
                if (editing == null) {
                    // Saisie au clavier de la télécommande fastidieuse : le téléphone s'en charge.
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { vm.openPhoneLink(PhoneLinkServer.Mode.LOGIN) },
                        modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(phoneFocus).tvItem(CircleShape),
                    ) {
                        Icon(Icons.Filled.QrCode2, contentDescription = null)
                        Text("  Saisir depuis un téléphone")
                    }
                    Text(
                        "Le plus simple : scannez un QR code et saisissez vos identifiants sur le téléphone.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                tip()
            }
            Column(
                Modifier.weight(1f, fill = false).widthIn(max = 460.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                form()
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            header()
            Spacer(Modifier.height(8.dp))
            form()
            tip()
        }
    }
}
