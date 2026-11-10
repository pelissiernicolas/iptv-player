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

    var screen by mutableStateOf<Screen>(Screen.Profiles); private set

    // Profils / connexion
    var profiles by mutableStateOf(store.profiles()); private set
    var activeProfile by mutableStateOf<Profile?>(null); private set
    var account by mutableStateOf<AccountInfo?>(null); private set
    var loginBusy by mutableStateOf(false); private set
    var loginError by mutableStateOf<String?>(null); private set
    val serverUrl get() = client?.credentials?.server

    // Catalogue
    /** true = onglet « Accueil » affiché, sinon l'onglet [currentTab]. */
    var homeSelected by mutableStateOf(false); private set
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
    private val epgFetchedAt = HashMap<String, Long>()
    private val epgSemaphore = Semaphore(4)

    private val categoryCache = HashMap<Pair<ContentType, String>, List<MediaEntry>>()
    private val allCache = HashMap<ContentType, List<MediaEntry>>()
    private val loadJobs = HashMap<ContentType, Job>()
    private var searchJob: Job? = null
    private var seriesJob: Job? = null
    private var accountJob: Job? = null

    // Type d'appareil
    /** Mode Téléviseur : navigation à la télécommande. */
    var tvMode by mutableStateOf(store.deviceMode == DEVICE_TV); private set
    /** Type d'appareil détecté automatiquement (proposé par défaut au premier démarrage). */
    val detectedTv: Boolean = isTvDevice(app)
    private var screenBeforeSetup: Screen? = null

    init {
        Library.init(app)
        if (store.deviceMode == null) {
            // Premier démarrage : on demande d'abord le type d'appareil.
            screen = Screen.DeviceSetup(firstLaunch = true)
        } else {
            startFlow()
        }
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
        loginError = null
        screen = if (profiles.isEmpty()) Screen.EditProfile(null) else Screen.Profiles
    }

    /** Vérifie les identifiants auprès du serveur, enregistre le profil puis l'ouvre. */
    fun saveProfile(editing: Profile?, name: String, server: String, username: String, password: String) {
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
        viewModelScope.launch {
            try {
                val info = XtreamClient(profile.credentials()).login()
                profiles = if (editing != null) profiles.map { if (it.id == profile.id) profile else it }
                else profiles + profile
                store.saveProfiles(profiles)
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
        store.saveProfiles(profiles)
        store.deleteProfileData(profile.id)
        Library.deleteProfileData(profile.id)
        if (store.activeProfileId == profile.id) store.activeProfileId = null
        if (profiles.isEmpty()) screen = Screen.EditProfile(null)
    }

    /** Ferme la session en cours et revient au choix du profil. */
    fun switchProfile() {
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
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        searchJob?.cancel()
        categoryCache.clear()
        allCache.clear()
        tabStates.clear()
        epg.clear()
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
                    else -> categoryCache.getOrPut(type to categoryId) { c.streams(type, categoryId) }
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
     * Charge le guide d'une chaîne (appelé quand elle devient visible à l'écran).
     * Fonction suspendue : annulée si la ligne sort de l'écran avant d'être servie.
     */
    suspend fun loadEpg(entry: MediaEntry) {
        if (entry.type != ContentType.LIVE) return
        if (entry.epgId.isNullOrBlank()) {
            if (epg[entry.id] == null) epg[entry.id] = emptyList()
            return
        }
        val c = client ?: return
        val now = System.currentTimeMillis()
        val fetched = epgFetchedAt[entry.id]
        if (fetched != null && now - fetched < EPG_TTL_MS) return
        epgFetchedAt[entry.id] = now
        try {
            val programs = epgSemaphore.withPermit { c.shortEpg(entry.id) }
            epg[entry.id] = programs
        } catch (e: CancellationException) {
            epgFetchedAt.remove(entry.id)
            throw e
        } catch (_: Exception) {
            epg[entry.id] = emptyList()
        }
    }

    /** Chaînes récentes sous forme d'éléments de liste. */
    fun recentChannelEntries(): List<MediaEntry> = Library.recentChannels.map {
        MediaEntry(it.id, it.name, it.image, null, null, null, null, ContentType.LIVE, it.epgId)
    }

    // ---------- Lecture ----------

    fun playLive(context: Context, channels: List<MediaEntry>, index: Int) {
        val c = client ?: return
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
        PlaylistHolder.play(context, items, index)
    }

    fun playVod(context: Context, entry: MediaEntry, extension: String?, startPositionMs: Long = -1) {
        val c = client ?: return
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
        PlaylistHolder.play(context, listOf(item), 0, startPositionMs)
    }

    fun playEpisodes(
        context: Context,
        seriesName: String,
        seriesImage: String?,
        episodes: List<Episode>,
        index: Int,
        startPositionMs: Long = -1,
    ) {
        val c = client ?: return
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
            )
        }
        PlaylistHolder.play(context, items, index, startPositionMs)
    }

    /** Reprend un film / épisode depuis « Continuer à regarder ». */
    fun resume(context: Context, progress: WatchProgress) {
        val c = client ?: return
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
        )
        PlaylistHolder.play(context, listOf(item), 0, progress.position)
    }

    private companion object {
        const val EPG_TTL_MS = 20 * 60_000L
    }
}
