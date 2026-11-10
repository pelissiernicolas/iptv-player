package fr.pelis.iptv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Données locales du profil actif : progression de lecture et chaînes récentes.
 * Partagé entre l'écran principal et le lecteur (même processus) ; l'UI Compose
 * se met à jour automatiquement car les listes sont observables.
 */
object Library {
    private const val MAX_PROGRESS = 60
    private const val MAX_RECENT = 30
    private const val MIN_POSITION_MS = 15_000L
    private const val FINISHED_RATIO = 0.95

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

    fun setProfile(id: String?) {
        profileId = id
        progress.clear()
        recentChannels.clear()
        val p = prefs ?: return
        if (id == null) return
        p.getString(progressKey(id), null)?.let { raw ->
            runCatching { json.decodeFromString<List<WatchProgress>>(raw) }.getOrNull()?.let(progress::addAll)
        }
        p.getString(recentKey(id), null)?.let { raw ->
            runCatching { json.decodeFromString<List<RecentChannel>>(raw) }.getOrNull()?.let(recentChannels::addAll)
        }
    }

    fun progressFor(key: String): WatchProgress? = progress.firstOrNull { it.key == key }

    /** Enregistre la position ; supprime l'entrée si la vidéo est quasiment terminée. */
    fun saveProgress(entry: WatchProgress) {
        val id = profileId ?: return
        if (entry.duration <= 0) return
        val index = progress.indexOfFirst { it.key == entry.key }
        val finished = entry.position >= entry.duration * FINISHED_RATIO
        if (finished || entry.position < MIN_POSITION_MS) {
            if (finished && index >= 0) {
                progress.removeAt(index)
                persistProgress(id)
            }
            return
        }
        if (index >= 0) progress.removeAt(index)
        progress.add(0, entry)
        while (progress.size > MAX_PROGRESS) progress.removeAt(progress.lastIndex)
        persistProgress(id)
    }

    fun removeProgress(key: String) {
        val id = profileId ?: return
        if (progress.removeAll { it.key == key }) persistProgress(id)
    }

    fun addRecentChannel(channel: RecentChannel) {
        val id = profileId ?: return
        if (recentChannels.firstOrNull()?.id == channel.id) return
        recentChannels.removeAll { it.id == channel.id }
        recentChannels.add(0, channel)
        while (recentChannels.size > MAX_RECENT) recentChannels.removeAt(recentChannels.lastIndex)
        prefs?.edit()?.putString(recentKey(id), json.encodeToString(recentChannels.toList()))?.apply()
    }

    fun deleteProfileData(id: String) {
        prefs?.edit()?.remove(progressKey(id))?.remove(recentKey(id))?.apply()
        if (profileId == id) setProfile(null)
    }

    private fun persistProgress(id: String) {
        prefs?.edit()?.putString(progressKey(id), json.encodeToString(progress.toList()))?.apply()
    }

    private fun progressKey(id: String) = "progress_$id"
    private fun recentKey(id: String) = "recent_$id"
}
