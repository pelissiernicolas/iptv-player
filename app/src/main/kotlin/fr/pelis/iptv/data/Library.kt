package fr.pelis.iptv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Données locales par profil : progression de lecture et chaînes récentes.
 *
 * Les listes observables ([progress], [recentChannels]) reflètent le profil affiché par
 * l'écran principal. Les écritures, elles, indiquent toujours le profil concerné : un
 * lecteur lancé depuis le profil A (PiP, Cast) n'écrit jamais dans le profil B même si
 * l'utilisateur a changé de profil entre-temps.
 */
object Library {
    private const val MAX_PROGRESS = 60
    private const val MAX_RECENT = 30
    const val MIN_POSITION_MS = 15_000L
    const val FINISHED_RATIO = 0.95

    private var prefs: SharedPreferences? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var profileId: String? = null

    /** Triée du plus récent au plus ancien. */
    val progress = mutableStateListOf<WatchProgress>()
    val recentChannels = mutableStateListOf<RecentChannel>()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("iptv_library", Context.MODE_PRIVATE)
        }
    }

    /** Profil affiché par l'écran principal. */
    fun setProfile(id: String?) {
        profileId = id
        progress.clear()
        recentChannels.clear()
        if (id == null) return
        progress.addAll(readProgress(id))
        recentChannels.addAll(readRecent(id))
    }

    fun progressFor(key: String): WatchProgress? = progress.firstOrNull { it.key == key }

    /** Ce qu'il faut faire d'une position de lecture. */
    enum class ProgressAction { SAVE, REMOVE, IGNORE }

    /** Vidéo quasiment terminée -> retirée ; moins de 15 s regardées -> ignorée. */
    fun progressAction(position: Long, duration: Long): ProgressAction = when {
        duration <= 0 -> ProgressAction.IGNORE
        position >= duration * FINISHED_RATIO -> ProgressAction.REMOVE
        position < MIN_POSITION_MS -> ProgressAction.IGNORE
        else -> ProgressAction.SAVE
    }

    /** Enregistre la position dans le profil [owner] ; supprime l'entrée si la vidéo est quasiment terminée. */
    fun saveProgress(owner: String, entry: WatchProgress) {
        when (progressAction(entry.position, entry.duration)) {
            ProgressAction.IGNORE -> Unit
            ProgressAction.REMOVE -> removeProgress(owner, entry.key)
            ProgressAction.SAVE -> editProgress(owner) { list ->
                list.removeAll { it.key == entry.key }
                list.add(0, entry)
                while (list.size > MAX_PROGRESS) list.removeAt(list.lastIndex)
            }
        }
    }

    fun removeProgress(owner: String, key: String) {
        editProgress(owner) { list -> list.removeAll { it.key == key } }
    }

    fun addRecentChannel(owner: String, channel: RecentChannel) {
        val shown = owner == profileId
        val list = if (shown) recentChannels.toMutableList() else readRecent(owner).toMutableList()
        if (list.firstOrNull()?.id == channel.id) return
        list.removeAll { it.id == channel.id }
        list.add(0, channel)
        while (list.size > MAX_RECENT) list.removeAt(list.lastIndex)
        prefs?.edit()?.putString(recentKey(owner), json.encodeToString(list))?.apply()
        if (shown) {
            recentChannels.clear()
            recentChannels.addAll(list)
        }
    }

    /** Copie des données du profil [owner], pour une sauvegarde. */
    fun snapshot(owner: String): Pair<List<WatchProgress>, List<RecentChannel>> =
        if (owner == profileId) progress.toList() to recentChannels.toList()
        else readProgress(owner) to readRecent(owner)

    /** Fusionne une sauvegarde dans le profil [owner] (la donnée la plus récente l'emporte). */
    fun importData(owner: String, imported: List<WatchProgress>, importedRecent: List<RecentChannel>) {
        val (currentProgress, currentRecent) = snapshot(owner)
        val mergedProgress = BackupMerge.progress(currentProgress, imported, MAX_PROGRESS)
        val mergedRecent = BackupMerge.recent(currentRecent, importedRecent, MAX_RECENT)
        prefs?.edit()
            ?.putString(progressKey(owner), json.encodeToString(mergedProgress))
            ?.putString(recentKey(owner), json.encodeToString(mergedRecent))
            ?.apply()
        if (owner == profileId) {
            progress.clear()
            progress.addAll(mergedProgress)
            recentChannels.clear()
            recentChannels.addAll(mergedRecent)
        }
    }

    fun deleteProfileData(id: String) {
        prefs?.edit()?.remove(progressKey(id))?.remove(recentKey(id))?.apply()
        if (profileId == id) setProfile(null)
    }

    private inline fun editProgress(owner: String, change: (MutableList<WatchProgress>) -> Unit) {
        val shown = owner == profileId
        val list = if (shown) progress.toMutableList() else readProgress(owner).toMutableList()
        val before = list.toList()
        change(list)
        if (list == before) return
        prefs?.edit()?.putString(progressKey(owner), json.encodeToString(list))?.apply()
        if (shown) {
            progress.clear()
            progress.addAll(list)
        }
    }

    private fun readProgress(id: String): List<WatchProgress> =
        prefs?.getString(progressKey(id), null)
            ?.let { raw -> runCatching { json.decodeFromString<List<WatchProgress>>(raw) }.getOrNull() }
            .orEmpty()

    private fun readRecent(id: String): List<RecentChannel> =
        prefs?.getString(recentKey(id), null)
            ?.let { raw -> runCatching { json.decodeFromString<List<RecentChannel>>(raw) }.getOrNull() }
            .orEmpty()

    private fun progressKey(id: String) = "progress_$id"
    private fun recentKey(id: String) = "recent_$id"
}
