package fr.pelis.iptv.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.BackHandler
import fr.pelis.iptv.transfer.PhoneLinkServer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import fr.pelis.iptv.CATEGORY_ALL
import fr.pelis.iptv.CATEGORY_FAV
import fr.pelis.iptv.CATEGORY_RECENT
import fr.pelis.iptv.data.Library
import fr.pelis.iptv.data.EpgProgram
import fr.pelis.iptv.MainViewModel
import fr.pelis.iptv.TabState
import fr.pelis.iptv.data.AccountInfo
import fr.pelis.iptv.data.ContentType
import fr.pelis.iptv.data.MediaEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ContentType.label
    get() = when (this) {
        ContentType.LIVE -> "Direct"
        ContentType.VOD -> "Films"
        ContentType.SERIES -> "Séries"
    }

private val ContentType.icon: ImageVector
    get() = when (this) {
        ContentType.LIVE -> Icons.Filled.LiveTv
        ContentType.VOD -> Icons.Filled.Movie
        ContentType.SERIES -> Icons.Filled.Tv
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    // Sauvegarde : l'utilisateur choisit l'emplacement (Téléchargements, Drive…) via le sélecteur système.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        uri -> uri?.let(vm::exportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> uri?.let(vm::importBackup)
    }
    fun launchPicker(start: () -> Unit) {
        val available = hasDocumentPicker(context)
        if (available) {
            try {
                start()
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        // Fréquent sur les téléviseurs : pas de gestionnaire de fichiers (ou un simple bouchon système).
        Toast.makeText(
            context,
            "Aucun gestionnaire de fichiers sur cet appareil : utilisez l'export depuis un téléphone ou une tablette",
            Toast.LENGTH_LONG,
        ).show()
    }

    val closeSearch = { searchOpen = false; vm.onQueryChange("") }
    BackHandler(enabled = searchOpen) { closeSearch() }

    val type = vm.currentTab
    val state = vm.tabStates[type] ?: TabState()
    val favs = vm.favorites[type].orEmpty()
    val searchMode = searchOpen && vm.query.isNotBlank()
    val showRecent = type == ContentType.LIVE && state.selected == CATEGORY_RECENT && !searchMode
    val items = when {
        searchMode -> vm.searchResults.orEmpty()
        showRecent -> vm.recentChannelEntries()
        else -> state.items
    }
    val home = vm.homeSelected
    val tv = vm.tvMode
    val navFocus = remember { FocusRequester() }
    // Télécommande : au retour d'une fiche, le focus revient sur l'élément quitté ;
    // sinon (ou s'il n'est plus affiché) focus initial sur le menu latéral.
    val focusMemory = LocalFocusMemory.current
    val loading = if (searchMode) vm.searching || vm.searchResults == null else state.loading
    val currentLoading by rememberUpdatedState(loading)
    var screenHasFocus by remember { mutableStateOf(false) }
    if (tv) LaunchedEffect(Unit) {
        if (focusMemory?.pending != null) {
            // On attend que la liste soit chargée (pas un délai fixe), puis qu'elle soit placée.
            snapshotFlow { currentLoading }.first { !it }
            kotlinx.coroutines.delay(400)
            // Élément retrouvé, ou l'utilisateur a déjà bougé : on ne touche à rien.
            if (focusMemory.pending == null || screenHasFocus) {
                focusMemory.pending = null
                return@LaunchedEffect
            }
            focusMemory.pending = null
        }
        if (!screenHasFocus) runCatching { navFocus.requestFocus() }
    }

    Scaffold(
        // Dès qu'un élément de l'écran a le focus (restauré ou choisi par l'utilisateur),
        // plus aucune restauration différée ne doit le déplacer.
        modifier = Modifier.onFocusChanged {
            screenHasFocus = it.hasFocus
            if (it.hasFocus) focusMemory?.pending = null
        },
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen && !home) {
                        SearchField(vm.query, vm::onQueryChange, "Rechercher : ${type.label.lowercase()}")
                    } else {
                        val profile = vm.activeProfile
                        if (profile != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ProfileAvatar(profile, size = 32)
                                Spacer(Modifier.width(12.dp))
                                Text(profile.name, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text("IPTV Player", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                actions = {
                    if (searchOpen && !home) {
                        IconButton(onClick = closeSearch, modifier = Modifier.tvItem(CircleShape)) {
                            Icon(Icons.Filled.Close, "Fermer la recherche")
                        }
                    } else {
                        if (!home && type == ContentType.LIVE && items.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    val title = if (searchMode) "Recherche" else categoryName(state)
                                    vm.openGuide(items, title)
                                },
                                modifier = Modifier.tvItem(CircleShape),
                            ) { Icon(Icons.Filled.CalendarMonth, "Guide TV") }
                        }
                        if (!home) {
                            IconButton(onClick = { searchOpen = true }, modifier = Modifier.tvItem(CircleShape)) {
                                Icon(Icons.Filled.Search, "Rechercher")
                            }
                        }
                        if (!tv) CastButton()
                        IconButton(onClick = { showAccount = true }, modifier = Modifier.tvItem(CircleShape)) {
                            Icon(Icons.Filled.AccountCircle, "Compte")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (!tv) NavigationBar {
                NavigationBarItem(
                    selected = home,
                    onClick = { searchOpen = false; vm.selectHome() },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Accueil") },
                )
                ContentType.entries.forEach { t ->
                    NavigationBarItem(
                        selected = !home && t == type,
                        onClick = { vm.selectTab(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
        if (tv) {
            NavigationRail {
                Spacer(Modifier.height(8.dp))
                NavigationRailItem(
                    selected = home,
                    onClick = { searchOpen = false; vm.selectHome() },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Accueil") },
                    modifier = Modifier
                        .then(if (home) Modifier.focusRequester(navFocus) else Modifier)
                        .tvItem(RoundedCornerShape(16.dp)),
                )
                ContentType.entries.forEach { t ->
                    NavigationRailItem(
                        selected = !home && t == type,
                        onClick = { vm.selectTab(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                        modifier = Modifier
                            .then(if (!home && t == type) Modifier.focusRequester(navFocus) else Modifier)
                            .tvItem(RoundedCornerShape(16.dp)),
                    )
                }
            }
        }
        if (home) {
            HomeTab(vm, Modifier.weight(1f))
        } else Column(Modifier.weight(1f).fillMaxSize()) {
            if (!searchMode && state.selected != null) {
                CategoryChips(
                    state,
                    hasFavorites = favs.isNotEmpty(),
                    hasRecent = type == ContentType.LIVE && Library.recentChannels.isNotEmpty(),
                ) { vm.selectCategory(type, it) }
                HorizontalDivider()
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val error = state.error
                when {
                    loading && items.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error != null && items.isEmpty() && !showRecent ->
                        ErrorBox(error) { vm.retry(type); if (searchMode) vm.onQueryChange(vm.query) }
                    items.isEmpty() ->
                        Text(
                            when {
                                searchMode -> "Aucun résultat"
                                state.selected == CATEGORY_FAV -> "Aucun favori.\n" +
                                    if (tv) "Appui long sur OK (ou touche Menu) sur un élément pour l'ajouter."
                                    else "Appui long sur un élément pour l'ajouter."
                                else -> "Aucun contenu dans cette catégorie"
                            },
                            Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    type == ContentType.LIVE ->
                        ChannelList(
                            items, favs, vm.epg,
                            loadEpg = vm::loadEpg,
                            onClick = { i -> vm.playLive(context, items, i) },
                            onLongClick = vm::toggleFavorite,
                        )
                    else ->
                        PosterGrid(
                            items, favs,
                            onClick = { e -> if (type == ContentType.VOD) vm.openMovie(e) else vm.openSeries(e) },
                            onLongClick = vm::toggleFavorite,
                        )
                }
            }
        }
        }
    }

    if (showAccount) {
        AccountDialog(
            account = vm.account,
            server = vm.serverUrl,
            onSwitchProfile = { showAccount = false; vm.switchProfile() },
            tvMode = tv,
            onChangeDevice = { showAccount = false; vm.openDeviceSetup() },
            appVersion = vm.appVersion,
            checkingUpdate = vm.checkingUpdate,
            onCheckUpdate = { showAccount = false; vm.checkForUpdate(manual = true) },
            onRefreshCatalog = { showAccount = false; vm.refreshCatalog() },
            onPhoneBackup = { showAccount = false; vm.openPhoneLink(PhoneLinkServer.Mode.BACKUP) },
            onExport = { showAccount = false; launchPicker { exportLauncher.launch(vm.backupFileName()) } },
            onImport = {
                showAccount = false
                launchPicker { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            },
            onDismiss = { showAccount = false },
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

private fun categoryName(state: TabState): String = when (state.selected) {
    CATEGORY_ALL -> "Toutes les chaînes"
    CATEGORY_FAV -> "Favoris"
    CATEGORY_RECENT -> "Récents"
    else -> state.categories.firstOrNull { it.id == state.selected }?.name ?: "Chaînes"
}

@Composable
private fun CategoryChips(state: TabState, hasFavorites: Boolean, hasRecent: Boolean, onSelect: (String) -> Unit) {
    val entries = buildList {
        if (hasRecent || state.selected == CATEGORY_RECENT) add(CATEGORY_RECENT to "🕘 Récents")
        if (hasFavorites || state.selected == CATEGORY_FAV) add(CATEGORY_FAV to "★ Favoris")
        add(CATEGORY_ALL to "Tout")
        state.categories.forEach { add(it.id to it.name) }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries) { (id, name) ->
            FilterChip(
                modifier = Modifier.tvItem(),
                selected = id == state.selected,
                onClick = { onSelect(id) },
                label = { Text(name, maxLines = 1) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelList(
    items: List<MediaEntry>,
    favorites: Set<String>,
    epg: Map<String, List<EpgProgram>>,
    loadEpg: suspend (MediaEntry) -> Unit,
    onClick: (Int) -> Unit,
    onLongClick: (MediaEntry) -> Unit,
) {
    val now by rememberNow()
    val keys = remember(items) { uniqueKeys(items.map { "live:${it.id}" }) }
    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
        itemsIndexed(items, key = { i, _ -> keys[i] }) { index, entry ->
            LaunchedEffect(entry.id) { loadEpg(entry) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .rememberFocus(keys[index])
                    .tvItem(onLongPress = { onLongClick(entry) })
                    .combinedClickable(onClick = { onClick(index) }, onLongClick = { onLongClick(entry) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = entry.image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NowNext(epg[entry.id], now)
                }
                if (entry.id in favorites) {
                    Icon(Icons.Filled.Star, "Favori", tint = Color(0xFFFFC107), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterGrid(
    items: List<MediaEntry>,
    favorites: Set<String>,
    onClick: (MediaEntry) -> Unit,
    onLongClick: (MediaEntry) -> Unit,
) {
    val keys = remember(items) { uniqueKeys(items.map { "${it.type}:${it.id}" }) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (LocalTvMode.current) 140.dp else 112.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(items, key = { i, _ -> keys[i] }) { index, entry ->
            Column(
                Modifier
                    .rememberFocus(keys[index])
                    .tvItem(onLongPress = { onLongClick(entry) })
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = { onClick(entry) }, onLongClick = { onLongClick(entry) }),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (entry.type == ContentType.VOD) Icons.Filled.Movie else Icons.Filled.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    )
                    AsyncImage(
                        model = entry.image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (entry.id in favorites) {
                        Icon(
                            Icons.Filled.Star, "Favori", tint = Color(0xFFFFC107),
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
                        )
                    }
                    if (entry.type == ContentType.VOD) {
                        Library.progressFor("vod:${entry.id}")?.let { p ->
                            ThinProgress(
                                p.position.toFloat() / p.duration,
                                Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).height(34.dp),
                )
            }
        }
    }
}

@Composable
private fun AccountDialog(
    account: AccountInfo?,
    server: String?,
    onSwitchProfile: () -> Unit,
    tvMode: Boolean,
    onChangeDevice: () -> Unit,
    appVersion: String,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onPhoneBackup: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mon abonnement") },
        text = {
            // Défilant : sur un téléviseur, la fenêtre est plus haute que l'écran.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (account == null) {
                    Text("Informations du compte indisponibles.")
                } else {
                    val exp = account.expDate?.let {
                        SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it * 1000))
                    } ?: "Illimité"
                    InfoRow("Utilisateur", account.username)
                    InfoRow("Statut", account.status + if (account.isTrial) " (essai)" else "")
                    InfoRow("Expiration", exp)
                    InfoRow("Connexions", "${account.activeConnections ?: "?"} / ${account.maxConnections ?: "?"}")
                }
                server?.let { InfoRow("Serveur", it) }
                TextButton(onClick = onRefreshCatalog, contentPadding = PaddingValues(0.dp)) {
                    Text("Actualiser le catalogue (chaînes, films, séries)")
                }
                if (tvMode) {
                    // Téléviseur : pas de gestionnaire de fichiers, l'échange passe par un téléphone.
                    TextButton(onClick = onPhoneBackup, contentPadding = PaddingValues(0.dp)) {
                        Text("Sauvegarde via un téléphone (QR code)")
                    }
                } else {
                    TextButton(onClick = onExport, contentPadding = PaddingValues(0.dp)) {
                        Text("Exporter favoris et historique")
                    }
                    TextButton(onClick = onImport, contentPadding = PaddingValues(0.dp)) {
                        Text("Importer une sauvegarde")
                    }
                }
                InfoRow("Appareil", if (tvMode) "Téléviseur" else "Smartphone / tablette")
                TextButton(onClick = onChangeDevice, contentPadding = PaddingValues(0.dp)) {
                    Text("Changer le type d'appareil")
                }
                InfoRow("Application", "IPTV Player $appVersion")
                TextButton(onClick = onCheckUpdate, enabled = !checkingUpdate, contentPadding = PaddingValues(0.dp)) {
                    Text(if (checkingUpdate) "Recherche en cours…" else "Rechercher une mise à jour")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
        dismissButton = { TextButton(onClick = onSwitchProfile) { Text("Changer de profil") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text("$label : ", fontWeight = FontWeight.SemiBold)
        Text(value)
    }
}

@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(4.dp),
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/** Programme en cours (avec progression) et programme suivant. */
@Composable
fun NowNext(programs: List<EpgProgram>?, now: Long) {
    if (programs.isNullOrEmpty()) return
    val current = programs.firstOrNull { now >= it.start && now < it.end }
    val next = programs.firstOrNull { it.start >= (current?.end ?: now) }
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    if (current != null) {
        Text(
            "${formatTime(current.start)}  ${current.title}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = { ((now - current.start).toFloat() / (current.end - current.start)).coerceIn(0f, 1f) },
            modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth().height(3.dp),
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
    }
    if (next != null) {
        Text(
            "Ensuite ${formatTime(next.start)} · ${next.title}",
            style = MaterialTheme.typography.labelSmall,
            color = dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Onglet Accueil : reprise de lecture et chaînes récentes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeTab(vm: MainViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val progress = Library.progress
    val recents = vm.recentChannelEntries()
    val now by rememberNow()

    if (progress.isEmpty() && recents.isEmpty()) {
        Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Bienvenue !\n\nLes films et épisodes commencés apparaîtront ici, " +
                    "ainsi que vos dernières chaînes regardées.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (progress.isNotEmpty()) {
            item {
                Column {
                    SectionTitle("Continuer à regarder")
                    if (vm.resuming) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(progress.toList(), key = { it.key }) { p ->
                        Column(
                            Modifier
                                .width(124.dp)
                                .rememberFocus("cw:${p.key}")
                                .tvItem(onLongPress = { vm.removeProgress(p.key) })
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { vm.resume(context, p) },
                                    onLongClick = { vm.removeProgress(p.key) },
                                ),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                AsyncImage(
                                    model = p.image,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Icon(
                                    Icons.Filled.PlayCircle, null,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(44.dp),
                                )
                                ThinProgress(
                                    p.position.toFloat() / p.duration,
                                    Modifier.align(Alignment.BottomCenter),
                                )
                            }
                            Text(
                                p.title, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                p.subtitle ?: "Reste ${formatDuration(p.duration - p.position)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    if (LocalTvMode.current) "Appui long sur OK pour retirer de la liste" else "Appui long pour retirer de la liste",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        if (recents.isNotEmpty()) {
            item { SectionTitle("Chaînes récentes") }
            itemsIndexed(recents.take(10), key = { _, e -> "rec:${e.id}" }) { index, entry ->
                LaunchedEffect(entry.id) { vm.loadEpg(entry) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .rememberFocus("rec:${entry.id}")
                        .tvItem()
                        .combinedClickable(onClick = { vm.playLive(context, recents, index) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = entry.image, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(4.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NowNext(vm.epg[entry.id], now)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
    )
}

/**
 * Un vrai sélecteur de documents est-il installé ? Android TV en déclare souvent un « bouchon »
 * (frameworkpackagestubs) qui se contente d'afficher « action non prise en charge ».
 */
private fun hasDocumentPicker(context: android.content.Context): Boolean {
    val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(android.content.Intent.CATEGORY_OPENABLE)
        .setType("*/*")
    val pkg = runCatching {
        context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }.getOrNull() ?: return false
    return !pkg.contains("frameworkpackagestubs")
}
