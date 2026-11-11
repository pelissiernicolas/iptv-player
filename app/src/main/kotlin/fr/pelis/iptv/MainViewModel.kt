package fr.pelis.iptv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.pelis.iptv.data.AccountInfo
import fr.pelis.iptv.data.Category
import fr.pelis.iptv.data.ContentType
import fr.pelis.iptv.data.Profile
import fr.pelis.iptv.data.Episode
import fr.pelis.iptv.data.EpgProgram
import fr.pelis.iptv.data.Backup
import fr.pelis.iptv.data.Library
import fr.pelis.iptv.data.MovieDetail
import fr.pelis.iptv.data.WatchProgress
import fr.pelis.iptv.data.MediaEntry
import fr.pelis.iptv.data.SeriesDetail
import fr.pelis.iptv.data.SessionStore
import fr.pelis.iptv.data.XtreamClient
import fr.pelis.iptv.data.XtreamException
import fr.pelis.iptv.player.PlayItem
import fr.pelis.iptv.player.PlaylistHolder
import fr.pelis.iptv.ui.isTvDevice
import fr.pelis.iptv.update.UpdateInfo
import fr.pelis.iptv.update.Updater
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

sealed interface Screen {
    /** Lecture des profils (déchiffrement) au démarrage. */
    data object Loading : Screen
    data object Profiles : Screen
    data class EditProfile(val profile: Profile?) : Screen
    data object Home : Screen
    data class Series(val entry: MediaEntry) : Screen
    data class Movie(val entry: MediaEntry) : Screen
    data object Guide : Screen
    /** Choix Téléviseur / Smartphone-tablette ([firstLaunch] = premier démarrage). */
    data class DeviceSetup(val firstLaunch: Boolean) : Screen
}

const val DEVICE_TV = "tv"
const val DEVICE_MOBILE = "mobile"

data class TabState(
    val categories: List<Category> = emptyList(),
    val selected: String? = null,
    val items: List<MediaEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

const val CATEGORY_ALL = "__all__"
const val CATEGORY_FAV = "__fav__"
const val CATEGORY_RECENT = "__recent__"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SessionStore(app)
    private var client: XtreamClient? = null

    var screen by mutableStateOf<Screen>(Screen.Loading); private set
    /** Accès aux profils chiffrés : hors du thread principal, une opération à la fois. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val storeDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Profils / connexion
    var profiles by mutableStateOf<List<Profile>>(emptyList()); private set
    var activeProfile by mutableStateOf<Profile?>(null); private set
    var account by mutableStateOf<AccountInfo?>(null); private set
    var loginBusy by mutableStateOf(false); private set
    var loginError by mutableStateOf<String?>(null); private set
    val serverUrl get() = client?.credentials?.server

    // Catalogue
    /** true = onglet « Accueil » affiché, sinon l'onglet [currentTab]. */
    var homeSelected by mutableStateOf(false); private set
    /** Mode TV : dernier élément de l'accueil qui avait le focus (rendu au retour d'une fiche). */
    var lastFocusedItemKey: String? = null
    var currentTab by mutableStateOf(ContentType.LIVE); private set
    val tabStates = mutableStateMapOf<ContentType, TabState>()
    val favorites = mutableStateMapOf<ContentType, Set<String>>()

    // Recherche
    var query by mutableStateOf(""); private set
    var searchResults by mutableStateOf<List<MediaEntry>?>(null); private set
    var searching by mutableStateOf(false); private set

    // Série
    var seriesDetail by mutableStateOf<SeriesDetail?>(null); private set
    var seriesBusy by mutableStateOf(false); private set
    var seriesError by mutableStateOf<String?>(null); private set

    // Fiche film
    var movieDetail by mutableStateOf<MovieDetail?>(null); private set
    var movieBusy by mutableStateOf(false); private set
    var movieError by mutableStateOf<String?>(null); private set
    private var movieJob: Job? = null

    // Guide TV
    var guideChannels by mutableStateOf<List<MediaEntry>>(emptyList()); private set
    var guideTitle by mutableStateOf(""); private set
    /** Programmes par identifiant de chaîne (chargés à la demande). */
    val epg = mutableStateMapOf<String, List<EpgProgram>>()
    /** Chaînes dont le dernier chargement du guide a échoué (réseau…) : nouvel essai automatique. */
    val epgFailed = mutableStateMapOf<String, Boolean>()
    private val epgFetchedAt = HashMap<String, Long>()
    private val epgSemaphore = Semaphore(4)

    /** Catalogue en mémoire : 40 catégories au plus, renouvelé après 30 min (ou « Actualiser »). */
    private val categoryCache = TimedCache<Pair<ContentType, String>, List<MediaEntry>>(max = 40, ttlMs = CATALOG_TTL_MS)
    /** Catalogue complet (recherche, favoris) : le plus lourd, un par type au plus. */
    private val allCache = TimedCache<ContentType, List<MediaEntry>>(max = 3, ttlMs = CATALOG_TTL_MS)
    private val loadJobs = HashMap<ContentType, Job>()
    private var searchJob: Job? = null
    private var seriesJob: Job? = null
    private var accountJob: Job? = null
    private var loginJob: Job? = null

    // Type d'appareil
    /** Mode Téléviseur : navigation à la télécommande. */
    var tvMode by mutableStateOf(store.deviceMode == DEVICE_TV); private set
    /** Type d'appareil détecté automatiquement (proposé par défaut au premier démarrage). */
    val detectedTv: Boolean = isTvDevice(app)
    private var screenBeforeSetup: Screen? = null

    init {
        Library.init(app)
        viewModelScope.launch {
            profiles = withContext(storeDispatcher) { store.profiles() }
            storageMessage()?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
            if (store.deviceMode == null) {
                // Premier démarrage : on demande d'abord le type d'appareil.
                screen = Screen.DeviceSetup(firstLaunch = true)
            } else {
                startFlow()
            }
        }
    }

    // ---------- Mise à jour de l'application ----------

    val appVersion: String = Updater.currentVersion(app)
    /** Mise à jour proposée (fenêtre affichée tant que non null). */
    var update by mutableStateOf<UpdateInfo?>(null); private set
    /** Progression du téléchargement (0..1), null si aucun téléchargement en cours. */
    var updateProgress by mutableStateOf<Float?>(null); private set
    /** true pendant la préparation de l'installation (après le téléchargement). */
    var updateInstalling by mutableStateOf(false); private set
    var updateError by mutableStateOf<String?>(null); private set
    var checkingUpdate by mutableStateOf(false); private set
    private var updateJob: Job? = null

    init {
        // Après la déclaration des propriétés ci-dessus (ordre d'initialisation Kotlin).
        checkForUpdate(manual = false)
    }

    /** [manual] = demandé depuis le menu : on signale aussi « déjà à jour » et les erreurs. */
    fun checkForUpdate(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        viewModelScope.launch {
            try {
                val info = Updater.checkLatest(appVersion)
                when {
                    info == null -> if (manual) toast("Vous avez la dernière version ($appVersion)")
                    !manual && info.version == store.skippedUpdateVersion -> Unit
                    else -> {
                        updateError = null
                        update = info
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) toast("Impossible de vérifier les mises à jour : ${e.message ?: e.javaClass.simpleName}")
            } finally {
                checkingUpdate = false
            }
        }
    }

    /** Télécharge puis installe la mise à jour (demande d'abord l'autorisation d'installer si besoin). */
    fun startUpdate(context: Context) {
        val info = update ?: return
        if (!Updater.canInstall(context)) {
            updateError = "Autorisez IPTV Player à installer des applications, puis appuyez de nouveau sur « Mettre à jour »."
            Updater.openInstallPermissionSettings(context)
            return
        }
        if (updateJob?.isActive == true) return
        updateError = null
        updateProgress = 0f
        updateJob = viewModelScope.launch {
            var step = "du téléchargement"
            try {
                val apk = Updater.download(getApplication(), info) { if (updateJob?.isActive == true) updateProgress = it }
                step = "de la préparation de l'installation"
                updateInstalling = true
                Updater.install(getApplication(), apk)
                // La suite (confirmation Android) est affichée par le système.
                update = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError = "Échec $step : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                updateProgress = null
                updateInstalling = false
            }
        }
    }

    fun dismissUpdate(skipThisVersion: Boolean) {
        updateJob?.cancel()
        if (skipThisVersion) store.skippedUpdateVersion = update?.version
        update = null
        updateProgress = null
        updateError = null
    }

    /** Nom proposé pour le fichier de sauvegarde. */
    fun backupFileName(): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE).format(java.util.Date())
        val profile = activeProfile?.name.orEmpty().filter { it.isLetterOrDigit() }.take(20)
        return listOf("IPTV-Player", profile, date).filter { it.isNotEmpty() }.joinToString("-") + ".json"
    }

    /** Écrit favoris, progression et chaînes récentes du profil actif dans [uri] (choisi par l'utilisateur). */
    fun exportBackup(uri: android.net.Uri) {
        val owner = activeProfile?.id ?: return
        val (progressList, recent) = Library.snapshot(owner)
        val backup = Backup(
            exportedAt = System.currentTimeMillis(),
            favorites = Backup.Favorites(
                live = favorites[ContentType.LIVE].orEmpty().sorted(),
                vod = favorites[ContentType.VOD].orEmpty().sorted(),
                series = favorites[ContentType.SERIES].orEmpty().sorted(),
            ),
            progress = progressList,
            recentChannels = recent,
        )
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    resolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.encode(backup).toByteArray()) }
                }
            }
            toast(
                if (result.isSuccess) {
                    "Sauvegarde enregistrée : ${backup.favorites.count} favori(s), " +
                        "${backup.progress.size} lecture(s) en cours, ${backup.recentChannels.size} chaîne(s) récente(s)"
                } else {
                    "Échec de l'enregistrement de la sauvegarde"
                },
            )
        }
    }

    /** Fusionne la sauvegarde [uri] dans le profil actif : rien n'est supprimé sur l'appareil. */
    fun importBackup(uri: android.net.Uri) {
        val owner = activeProfile?.id ?: return
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = resolver.openInputStream(uri)!!.use { input ->
                        val buffer = input.readNBytesCompat(Backup.MAX_BYTES + 1)
                        require(buffer.size <= Backup.MAX_BYTES) { "fichier trop volumineux" }
                        buffer
                    }
                    Backup.decode(bytes.decodeToString())
                }
            }
            val backup = result.getOrElse { e ->
                val reason = (e as? IllegalArgumentException)?.message ?: "fichier illisible"
                toast("Import impossible : $reason")
                return@launch
            }
            // Le profil a pu changer pendant la lecture du fichier : on n'importe que dans celui choisi.
            if (activeProfile?.id != owner) return@launch
            var addedFavorites = 0
            ContentType.entries.forEach { type ->
                val current = favorites[type].orEmpty()
                val merged = current + backup.favorites[type]
                addedFavorites += merged.size - current.size
                if (merged.size != current.size) {
                    favorites[type] = merged
                    store.setFavorites(owner, type, merged)
                }
            }
            Library.importData(owner, backup.progress, backup.recentChannels)
            // Les listes affichées sont recalculées (catégorie Favoris) ; le catalogue en cache reste.
            loadJobs.values.forEach { it.cancel() }
            loadJobs.clear()
            tabStates.clear()
            if (!homeSelected) selectTab(currentTab)
            toast(
                "Import terminé : $addedFavorites nouveau(x) favori(s), " +
                    "${backup.progress.size} lecture(s) en cours, ${backup.recentChannels.size} chaîne(s) récente(s)",
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }

    /** Ouvre le profil actif, sinon le choix / la création de profil. */
    private fun startFlow() {
        val active = store.activeProfileId?.let { id -> profiles.find { it.id == id } }
        when {
            active != null -> openProfile(active)
            profiles.isEmpty() -> screen = Screen.EditProfile(null)
            else -> screen = Screen.Profiles
        }
    }

    // ---------- Type d'appareil ----------

    fun chooseDeviceMode(tv: Boolean) {
        val first = (screen as? Screen.DeviceSetup)?.firstLaunch == true
        store.deviceMode = if (tv) DEVICE_TV else DEVICE_MOBILE
        tvMode = tv
        if (first) startFlow() else screen = screenBeforeSetup ?: Screen.Profiles
        screenBeforeSetup = null
    }

    /** Permet de changer de type d'appareil plus tard. */
    fun openDeviceSetup() {
        screenBeforeSetup = screen
        screen = Screen.DeviceSetup(firstLaunch = false)
    }

    fun cancelDeviceSetup() {
        screen = screenBeforeSetup ?: Screen.Profiles
        screenBeforeSetup = null
    }

    // ---------- Profils ----------

    /** Ouvre un profil enregistré : accueil immédiat, infos du compte chargées en arrière-plan. */
    fun openProfile(profile: Profile) {
        activate(profile, account = null)
        accountJob = viewModelScope.launch {
            try {
                account = client?.login()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Hors ligne ou serveur lent : les listes proposeront de réessayer.
            }
        }
    }

    fun addProfile() = editProfile(null)

    fun editProfile(profile: Profile?) {
        loginError = null
        screen = Screen.EditProfile(profile)
    }

    fun cancelEdit() {
        loginJob?.cancel()
        loginBusy = false
        loginError = null
        screen = if (profiles.isEmpty()) Screen.EditProfile(null) else Screen.Profiles
    }

    /** Vérifie les identifiants auprès du serveur, enregistre le profil puis l'ouvre. */
    fun saveProfile(editing: Profile?, name: String, server: String, username: String, password: String) {
        if (loginBusy) return
        if (server.isBlank() || username.isBlank() || password.isEmpty()) {
            loginError = "Remplissez tous les champs"
            return
        }
        val url = XtreamClient.normalizeServer(server)
        val profile = Profile(
            id = editing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { url.substringAfter("://").substringBefore(':').substringBefore('/') },
            server = url,
            username = username.trim(),
            password = password,
        )
        loginBusy = true
        loginError = null
        loginJob = viewModelScope.launch {
            try {
                val info = XtreamClient(profile.credentials()).login()
                profiles = if (editing != null) profiles.map { if (it.id == profile.id) profile else it }
                else profiles + profile
                persistProfiles()
                activate(profile, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loginError = e.message ?: e.toString()
            } finally {
                loginBusy = false
            }
        }
    }

    fun deleteProfile(profile: Profile) {
        profiles = profiles.filterNot { it.id == profile.id }
        if (store.activeProfileId == profile.id) store.activeProfileId = null
        // Favoris, progression et historique effacés seulement une fois la liste bien enregistrée :
        // sinon le profil réapparaîtrait au prochain lancement sans ses données.
        persistProfiles {
            store.deleteProfileData(profile.id)
            Library.deleteProfileData(profile.id)
        }
        if (profiles.isEmpty()) screen = Screen.EditProfile(null)
    }

    /**
     * Enregistre les profils chiffrés. Si le stockage sécurisé est indisponible, rien n'est
     * écrit (jamais d'identifiants en clair) : les profils restent utilisables jusqu'à la
     * fermeture de l'application, et l'utilisateur est prévenu.
     */
    private fun persistProfiles(onSaved: () -> Unit = {}) {
        val snapshot = profiles
        viewModelScope.launch {
            val saved = withContext(storeDispatcher) { store.saveProfiles(snapshot) }
            if (saved) {
                onSaved()
            } else {
                toast(
                    "Stockage sécurisé indisponible : les modifications de profils ne seront pas " +
                        "conservées après la fermeture de l'application.",
                )
            }
        }
    }

    /** Problème rencontré à la lecture des profils au démarrage (null si tout va bien). */
    private fun storageMessage(): String? = when {
        store.profilesUnreadable ->
            "Des profils enregistrés n'ont pas pu être relus sur cet appareil. Ils sont conservés et " +
                "seront récupérés automatiquement s'ils redeviennent lisibles ; sinon, ajoutez à nouveau votre compte."
        store.migrationNotSaved ->
            "Vos profils n'ont pas pu être chiffrés (stockage sécurisé indisponible). Ils restent dans l'ancien " +
                "format ; nouvel essai au prochain démarrage."
        store.recoveryNotSaved ->
            "Des profils ont été récupérés mais pas encore réenregistrés (stockage sécurisé indisponible) : " +
                "nouvel essai au prochain démarrage."
        else -> null
    }

    /** Ferme la session en cours et revient au choix du profil. */
    fun switchProfile() {
        resumeJob?.cancel()
        store.activeProfileId = null
        accountJob?.cancel()
        client = null
        activeProfile = null
        account = null
        Library.setProfile(null)
        resetCatalog()
        screen = Screen.Profiles
    }

    private fun activate(profile: Profile, account: AccountInfo?) {
        accountJob?.cancel()
        resumeJob?.cancel()
        activeProfile = profile
        store.activeProfileId = profile.id
        this.account = account
        ContentType.entries.forEach { favorites[it] = store.favorites(profile.id, it) }
        client = XtreamClient(profile.credentials())
        Library.setProfile(profile.id)
        resetCatalog()
        screen = Screen.Home
        selectTab(ContentType.LIVE)
        // On ouvre l'Accueil s'il y a quelque chose à reprendre.
        homeSelected = Library.progress.isNotEmpty() || Library.recentChannels.isNotEmpty()
    }

    private fun resetCatalog() {
        lastFocusedItemKey = null
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        searchJob?.cancel()
        categoryCache.clear()
        allCache.clear()
        tabStates.clear()
        epg.clear()
        epgFailed.clear()
        epgFetchedAt.clear()
        query = ""
        searchResults = null
    }

    // ---------- Catalogue ----------

    fun selectHome() {
        homeSelected = true
        onQueryChange("")
    }

    fun selectTab(type: ContentType) {
        homeSelected = false
        currentTab = type
        val state = tabStates[type]
        if (state == null || (state.categories.isEmpty() && !state.loading)) loadCategories(type)
        if (query.isNotBlank()) runSearch()
    }

    fun retry(type: ContentType) {
        val state = tabStates[type]
        if (state?.selected == null) loadCategories(type) else selectCategory(type, state.selected)
    }

    private fun loadCategories(type: ContentType) {
        val c = client ?: return
        update(type) { it.copy(loading = true, error = null) }
        loadJobs[type]?.cancel()
        loadJobs[type] = viewModelScope.launch {
            try {
                val cats = c.categories(type)
                update(type) { it.copy(categories = cats) }
                val first = when {
                    favorites[type].orEmpty().isNotEmpty() -> CATEGORY_FAV
                    cats.isNotEmpty() -> cats.first().id
                    else -> CATEGORY_ALL
                }
                selectCategory(type, first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(type) { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun selectCategory(type: ContentType, categoryId: String) {
        val c = client ?: return
        loadJobs[type]?.cancel()
        if (categoryId == CATEGORY_RECENT) {
            // Liste locale : l'écran lit directement Library.recentChannels.
            update(type) { it.copy(selected = categoryId, items = emptyList(), loading = false, error = null) }
            return
        }
        update(type) { it.copy(selected = categoryId, items = emptyList(), loading = true, error = null) }
        loadJobs[type] = viewModelScope.launch {
            try {
                val items = when (categoryId) {
                    CATEGORY_ALL -> loadAll(c, type)
                    CATEGORY_FAV -> favoritesOf(c, type)
                    else -> categoryCache[type to categoryId]
                        ?: c.streams(type, categoryId).also { categoryCache[type to categoryId] = it }
                }
                update(type) { it.copy(items = items, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(type) { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun toggleFavorite(entry: MediaEntry) {
        val profileId = activeProfile?.id ?: return
        val type = entry.type
        val current = favorites[type].orEmpty()
        val updated = if (entry.id in current) current - entry.id else current + entry.id
        favorites[type] = updated
        store.setFavorites(profileId, type, updated)
        if (tabStates[type]?.selected == CATEGORY_FAV) {
            update(type) { s -> s.copy(items = s.items.filter { it.id in updated }) }
        }
    }

    private suspend fun loadAll(c: XtreamClient, type: ContentType): List<MediaEntry> =
        allCache[type] ?: c.streams(type, null).also { allCache[type] = it }

    /** Recharge le catalogue depuis le serveur (nouvelles chaînes, films ajoutés…). */
    fun refreshCatalog() {
        if (client == null) return
        val home = homeSelected
        val tab = currentTab
        resetCatalog()
        selectTab(tab)
        homeSelected = home
    }

    private suspend fun favoritesOf(c: XtreamClient, type: ContentType): List<MediaEntry> {
        val ids = favorites[type].orEmpty()
        return loadAll(c, type).filter { it.id in ids }
    }

    private inline fun update(type: ContentType, f: (TabState) -> TabState) {
        tabStates[type] = f(tabStates[type] ?: TabState())
    }

    // ---------- Recherche ----------

    fun onQueryChange(q: String) {
        query = q
        runSearch()
    }

    private fun runSearch() {
        searchJob?.cancel()
        val q = query.trim()
        val c = client
        if (q.isEmpty() || c == null) {
            searchResults = null
            searching = false
            return
        }
        val type = currentTab
        searchJob = viewModelScope.launch {
            delay(300)
            searching = true
            try {
                val all = loadAll(c, type)
                searchResults = withContext(Dispatchers.Default) {
                    all.filter { it.name.contains(q, ignoreCase = true) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                searchResults = emptyList()
                update(type) { it.copy(error = e.message) }
            } finally {
                searching = false
            }
        }
    }

    // ---------- Séries ----------

    fun openSeries(entry: MediaEntry) {
        val c = client ?: return
        screen = Screen.Series(entry)
        seriesDetail = null
        seriesError = null
        seriesBusy = true
        seriesJob?.cancel()
        seriesJob = viewModelScope.launch {
            try {
                seriesDetail = c.seriesInfo(entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                seriesError = (e as? XtreamException)?.message ?: e.toString()
            } finally {
                seriesBusy = false
            }
        }
    }

    fun closeSeries() {
        seriesJob?.cancel()
        screen = Screen.Home
    }

    // ---------- Fiche film ----------

    fun openMovie(entry: MediaEntry) {
        val c = client ?: return
        screen = Screen.Movie(entry)
        movieDetail = null
        movieError = null
        movieBusy = true
        movieJob?.cancel()
        movieJob = viewModelScope.launch {
            try {
                movieDetail = c.vodInfo(entry.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                movieError = (e as? XtreamException)?.message ?: e.toString()
            } finally {
                movieBusy = false
            }
        }
    }

    fun closeMovie() {
        movieJob?.cancel()
        screen = Screen.Home
    }

    // ---------- Guide TV ----------

    fun openGuide(channels: List<MediaEntry>, title: String) {
        guideChannels = channels
        guideTitle = title
        screen = Screen.Guide
    }

    fun closeGuide() {
        screen = Screen.Home
    }

    /**
     * Guide d'une chaîne, tant qu'elle est visible à l'écran : chargé à l'apparition de la
     * ligne, puis rafraîchi à la fin du programme en cours. Fonction suspendue : elle
     * s'arrête (requête comprise) quand la ligne sort de l'écran.
     */
    suspend fun loadEpg(entry: MediaEntry) {
        if (entry.type != ContentType.LIVE) return
        // Sans identifiant EPG, les serveurs Xtream ne renvoient pas de programme pour la chaîne.
        if (entry.epgId.isNullOrBlank()) {
            if (epg[entry.id] == null) epg[entry.id] = emptyList()
            return
        }
        while (true) {
            val c = client ?: return
            val wait = epgFetchedAt[entry.id]?.let { it - System.currentTimeMillis() } ?: 0L
            if (wait > 0) delay(wait)
            if (client !== c) return
            val next = try {
                val programs = epgSemaphore.withPermit { c.shortEpg(entry.id) }
                epg[entry.id] = programs
                epgFailed.remove(entry.id)
                // Prochain rafraîchissement : fin du programme en cours (au plus tard dans 20 min).
                val now = System.currentTimeMillis()
                val end = programs.firstOrNull { it.end > now }?.end ?: (now + EPG_TTL_MS)
                (end + 5_000L).coerceIn(now + 60_000L, now + EPG_TTL_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Erreur : on garde l'éventuel guide déjà affiché et on retente plus tôt.
                if (epg[entry.id] == null) epg[entry.id] = emptyList()
                epgFailed[entry.id] = true
                System.currentTimeMillis() + EPG_RETRY_MS
            }
            epgFetchedAt[entry.id] = next
        }
    }

    /** Chaînes récentes sous forme d'éléments de liste. */
    fun recentChannelEntries(): List<MediaEntry> = Library.recentChannels.map {
        MediaEntry(it.id, it.name, it.image, null, null, null, null, ContentType.LIVE, it.epgId)
    }

    // ---------- Lecture ----------

    fun playLive(context: Context, channels: List<MediaEntry>, index: Int) {
        val c = client ?: return
        val owner = activeProfile?.id ?: return
        val items = channels.map {
            PlayItem(
                title = it.name,
                url = c.liveUrl(it.id, "ts"),
                fallbackUrl = c.liveUrl(it.id, "m3u8"),
                isLive = true,
                contentId = it.id,
                image = it.image,
                epgId = it.epgId,
            )
        }
        PlaylistHolder.play(context, owner, items, index)
    }

    fun playVod(context: Context, entry: MediaEntry, extension: String?, startPositionMs: Long = -1) {
        val c = client ?: return
        val owner = activeProfile?.id ?: return
        val ext = extension ?: entry.extension
        val item = PlayItem(
            title = entry.name,
            url = c.vodUrl(entry.id, ext),
            contentId = entry.id,
            image = entry.image,
            progressKey = "vod:${entry.id}",
            kind = "vod",
            extension = ext,
        )
        PlaylistHolder.play(context, owner, listOf(item), 0, startPositionMs)
    }

    fun playEpisodes(
        context: Context,
        seriesId: String?,
        seriesName: String,
        seriesImage: String?,
        episodes: List<Episode>,
        index: Int,
        startPositionMs: Long = -1,
    ) {
        val c = client ?: return
        val owner = activeProfile?.id ?: return
        playEpisodesFor(c, owner, context, seriesId, seriesName, seriesImage, episodes, index, startPositionMs)
    }

    /** [c] et [owner] : client et profil explicitement liés (jamais ceux devenus actifs entre-temps). */
    private fun playEpisodesFor(
        c: XtreamClient,
        owner: String,
        context: Context,
        seriesId: String?,
        seriesName: String,
        seriesImage: String?,
        episodes: List<Episode>,
        index: Int,
        startPositionMs: Long,
    ) {
        val items = episodes.map {
            val label = "S${it.season} E${it.number} · ${it.title}"
            PlayItem(
                title = label,
                url = c.episodeUrl(it.id, it.extension),
                contentId = it.id,
                image = it.image ?: seriesImage,
                progressKey = "ep:${it.id}",
                kind = "episode",
                extension = it.extension,
                libraryTitle = seriesName,
                librarySubtitle = label,
                seriesId = seriesId,
                season = it.season,
            )
        }
        PlaylistHolder.play(context, owner, items, index, startPositionMs)
    }

    fun removeProgress(key: String) {
        val owner = activeProfile?.id ?: return
        Library.removeProgress(owner, key)
    }

    /** true pendant le chargement de la saison d'un épisode à reprendre. */
    var resuming by mutableStateOf(false); private set
    private var resumeJob: Job? = null

    /**
     * Reprend un film / épisode depuis « Continuer à regarder ». Pour un épisode, toute la
     * saison est rechargée afin d'enchaîner sur les épisodes suivants, comme depuis la fiche série.
     */
    fun resume(context: Context, progress: WatchProgress) {
        // Client et profil capturés maintenant : la reprise reste celle de ce compte.
        val c = client ?: return
        val owner = activeProfile?.id ?: return
        val seriesId = progress.seriesId
        if (progress.kind == "episode" && seriesId != null) {
            if (resuming) return
            resuming = true
            resumeJob = viewModelScope.launch {
                val detail = try {
                    c.seriesInfo(seriesId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    resuming = false
                }
                // Profil changé pendant le chargement : on abandonne la reprise.
                if (activeProfile?.id != owner || client !== c) return@launch
                val episodes = detail?.seasons?.get(progress.season ?: -1)
                val index = episodes?.indexOfFirst { it.id == progress.contentId } ?: -1
                if (detail != null && episodes != null && index >= 0) {
                    playEpisodesFor(
                        c, owner, context, seriesId, progress.title, detail.cover ?: progress.image,
                        episodes, index, progress.position,
                    )
                } else {
                    resumeSingle(c, owner, context, progress)
                }
            }
            return
        }
        resumeSingle(c, owner, context, progress)
    }

    private fun resumeSingle(c: XtreamClient, owner: String, context: Context, progress: WatchProgress) {
        val url = if (progress.kind == "episode") c.episodeUrl(progress.contentId, progress.extension ?: "mp4")
        else c.vodUrl(progress.contentId, progress.extension)
        val item = PlayItem(
            title = progress.subtitle ?: progress.title,
            url = url,
            contentId = progress.contentId,
            image = progress.image,
            progressKey = progress.key,
            kind = progress.kind,
            extension = progress.extension,
            libraryTitle = progress.title,
            librarySubtitle = progress.subtitle,
            seriesId = progress.seriesId,
            season = progress.season,
        )
        PlaylistHolder.play(context, owner, listOf(item), 0, progress.position)
    }

    private companion object {
        const val EPG_TTL_MS = 20 * 60_000L
        const val CATALOG_TTL_MS = 30 * 60_000L
        const val EPG_RETRY_MS = 2 * 60_000L
    }
}

/** Cache borné (les entrées les moins récemment utilisées sortent en premier) avec durée de validité. */
private class TimedCache<K, V>(private val max: Int, private val ttlMs: Long) {
    private val map = object : LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Long, V>>?) = size > max
    }

    operator fun get(key: K): V? {
        val (time, value) = map[key] ?: return null
        if (System.currentTimeMillis() - time > ttlMs) {
            map.remove(key)
            return null
        }
        return value
    }

    operator fun set(key: K, value: V) {
        map[key] = System.currentTimeMillis() to value
    }

    fun clear() = map.clear()
}

/** Lit au plus [limit] octets (InputStream.readNBytes n'existe qu'à partir d'Android 13). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
