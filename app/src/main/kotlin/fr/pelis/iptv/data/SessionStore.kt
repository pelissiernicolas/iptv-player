package fr.pelis.iptv.data

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Mémorise les profils, le profil actif et les favoris (stockage privé de l'application). */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("iptv_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun profiles(): List<Profile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return migrateLegacy()
        return runCatching { json.decodeFromString<List<Profile>>(raw) }.getOrDefault(emptyList())
    }

    fun saveProfiles(list: List<Profile>) {
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(list)).apply()
    }

    var activeProfileId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    /** Type d'appareil choisi au premier démarrage : "tv", "mobile", ou null si pas encore choisi. */
    var deviceMode: String?
        get() = prefs.getString(KEY_DEVICE_MODE, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_MODE, value).apply()

    /** Version de mise à jour que l'utilisateur a choisi d'ignorer. */
    var skippedUpdateVersion: String?
        get() = prefs.getString("skipped_update", null)
        set(value) = prefs.edit().putString("skipped_update", value).apply()

    fun favorites(profileId: String, type: ContentType): Set<String> =
        prefs.getStringSet(favKey(profileId, type), emptySet())?.toSet() ?: emptySet()

    fun setFavorites(profileId: String, type: ContentType, ids: Set<String>) {
        prefs.edit().putStringSet(favKey(profileId, type), ids).apply()
    }

    fun deleteProfileData(profileId: String) {
        prefs.edit().apply { ContentType.entries.forEach { remove(favKey(profileId, it)) } }.apply()
    }

    private fun favKey(profileId: String, type: ContentType) = "fav_${profileId}_${type.name}"

    /** Version 1 de l'appli : un seul compte enregistré -> converti en premier profil. */
    private fun migrateLegacy(): List<Profile> {
        val server = prefs.getString("server", null)
        val user = prefs.getString("username", null)
        val pass = prefs.getString("password", null)
        val list = if (server != null && user != null && pass != null) {
            val p = Profile(UUID.randomUUID().toString(), "Mon IPTV", server, user, pass)
            val edit = prefs.edit()
            ContentType.entries.forEach { t ->
                prefs.getStringSet("fav_${t.name}", null)?.let { edit.putStringSet(favKey(p.id, t), it.toSet()) }
                edit.remove("fav_${t.name}")
            }
            if (prefs.getBoolean("logged_in", false)) edit.putString(KEY_ACTIVE, p.id)
            edit.remove("server").remove("username").remove("password").remove("logged_in").apply()
            listOf(p)
        } else {
            emptyList()
        }
        saveProfiles(list)
        return list
    }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_ACTIVE = "active_profile"
        const val KEY_DEVICE_MODE = "device_mode"
    }
}
