package fr.pelis.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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

/** Création ou modification d'un profil (compte Xtream Codes). */
@Composable
fun ProfileEditScreen(vm: MainViewModel, editing: Profile?) {
    val canCancel = vm.profiles.isNotEmpty()
    BackHandler(enabled = canCancel) { vm.cancelEdit() }

    var name by rememberSaveable(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var server by rememberSaveable(editing?.id) { mutableStateOf(editing?.server ?: "") }
    var user by rememberSaveable(editing?.id) { mutableStateOf(editing?.username ?: "") }
    var pass by rememberSaveable(editing?.id) { mutableStateOf(editing?.password ?: "") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    val submit = { vm.saveProfile(editing, name, server, user, pass) }
    val fieldMod = Modifier.widthIn(max = 480.dp).fillMaxWidth()

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
        Icon(
            Icons.Filled.LiveTv, contentDescription = null,
            Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            when {
                editing != null -> "Modifier le profil"
                vm.profiles.isEmpty() -> "IPTV Player"
                else -> "Nouveau profil"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            "Identifiants Xtream Codes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(8.dp))

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
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
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

        Text(
            "Astuce : collez votre lien M3U complet (…/get.php?username=…&password=…) " +
                "dans le champ serveur pour tout remplir automatiquement.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.widthIn(max = 480.dp),
        )
    }
}
