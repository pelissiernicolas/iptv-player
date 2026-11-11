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

    /**
     * true si des profils enregistrés n'ont pas pu être relus (clé Keystore perdue, données
     * abîmées…). Ils sont mis de côté au lieu d'être effacés, et l'écran le signale.
     */
    var profilesUnreadable = false
        private set

    /** Profils en clair d'une ancienne version pas encore chiffrés (stockage sécurisé indisponible). */
    var migrationNotSaved = false
        private set

    /** Profils mis de côté relus, mais pas encore réenregistrés : ils restent aussi en copie de secours. */
    var recoveryNotSaved = false
        private set

    /**
     * Profils, chiffrés avec [SecretBox] (les anciennes versions les stockaient en clair).
     * Déchiffrement et écriture : à appeler hors du thread principal.
     */
    fun profiles(): List<Profile> {
        migrationNotSaved = false
        recoveryNotSaved = false
        val raw = prefs.getString(KEY_PROFILES, null)
        var list = when {
            raw == null -> migrateLegacy()
            else -> decode(raw) ?: run {
                // Illisibles : mis de côté sous une clé unique (jamais écrasés par un échec suivant).
                prefs.edit()
                    .putString("$KEY_PROFILES_UNREADABLE${System.currentTimeMillis()}", raw)
                    .remove(KEY_PROFILES)
                    .commit()
                emptyList()
            }
        }
        // Données en clair d'une version précédente : on les chiffre tout de suite
        // (si le chiffrement échoue, elles restent telles quelles et on réessaiera au prochain lancement).
        if (raw != null && list.isNotEmpty() && !SecretBox.isEncrypted(raw) && !saveProfiles(list)) {
            migrationNotSaved = true
        }

        // Profils mis de côté lors d'un échec précédent (Keystore momentanément indisponible…) :
        // on retente à chaque lancement et on les réintègre dès qu'ils redeviennent lisibles.
        val aside = prefs.all.keys.filter { it.startsWith(KEY_PROFILES_UNREADABLE.removeSuffix("_")) }
        val recoveredKeys = mutableListOf<String>()
        for (key in aside) {
            val recovered = prefs.getString(key, null)?.let(::decode) ?: continue
            list = list + recovered.filter { r -> list.none { it.id == r.id } }
            recoveredKeys += key
        }
        if (recoveredKeys.isNotEmpty()) {
            if (saveProfiles(list)) {
                prefs.edit().apply { recoveredKeys.forEach(::remove) }.commit()
            } else {
                recoveryNotSaved = true
            }
        }
        profilesUnreadable = aside.size > recoveredKeys.size
        return list
    }

    private fun decode(raw: String): List<Profile>? = runCatching {
        val text = if (SecretBox.isEncrypted(raw)) SecretBox.decrypt(raw) else raw
        json.decodeFromString<List<Profile>>(text)
    }.getOrNull()

    /**
     * Enregistre les profils chiffrés. Retourne false si le chiffrement est impossible
     * (Android Keystore indisponible) : rien n'est alors écrit, les identifiants ne sont
     * jamais enregistrés en clair et les données déjà présentes sont conservées.
     */
    fun saveProfiles(list: List<Profile>): Boolean {
        val stored = runCatching { SecretBox.encrypt(json.encodeToString(list)) }.getOrNull() ?: return false
        return prefs.edit().putString(KEY_PROFILES, stored).commit()
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
        if (server == null || user == null || pass == null) return emptyList()
        val p = Profile(UUID.randomUUID().toString(), "Mon IPTV", server, user, pass)
        // Anciennes données effacées seulement si le profil a bien été enregistré chiffré.
        if (!saveProfiles(listOf(p))) {
            migrationNotSaved = true
            return listOf(p)
        }
        val edit = prefs.edit()
        ContentType.entries.forEach { t ->
            prefs.getStringSet("fav_${t.name}", null)?.let { edit.putStringSet(favKey(p.id, t), it.toSet()) }
            edit.remove("fav_${t.name}")
        }
        if (prefs.getBoolean("logged_in", false)) edit.putString(KEY_ACTIVE, p.id)
        edit.remove("server").remove("username").remove("password").remove("logged_in").apply()
        return listOf(p)
    }

    private companion object {
        const val KEY_PROFILES = "profiles"
        const val KEY_PROFILES_UNREADABLE = "profiles_unreadable_"
        const val KEY_ACTIVE = "active_profile"
        const val KEY_DEVICE_MODE = "device_mode"
    }
}
