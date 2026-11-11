package fr.pelis.iptv.player

import android.content.Context
import android.content.Intent
import fr.pelis.iptv.data.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

@Serializable
data class PlayItem(
    /** Titre affiché dans le lecteur. */
    val title: String,
    val url: String,
    /** URL alternative essayée si la première échoue (ex. .m3u8 au lieu de .ts). */
    val fallbackUrl: String? = null,
    val isLive: Boolean = false,
    /** Identifiant du flux (chaîne, film ou épisode). */
    val contentId: String? = null,
    val image: String? = null,
    /** Chaînes : identifiant du guide TV. */
    val epgId: String? = null,
    /** Films / épisodes : clé de progression ("vod:123", "ep:456"), null = pas de reprise. */
    val progressKey: String? = null,
    /** "vod" ou "episode". */
    val kind: String? = null,
    val extension: String? = null,
    /** Titre dans « Continuer à regarder » (ex. nom de la série). */
    val libraryTitle: String? = null,
    /** Sous-titre dans « Continuer à regarder » (ex. « S1 E3 · Titre »). */
    val librarySubtitle: String? = null,
    /** Épisodes : série et saison (reprise avec la suite de la saison). */
    val seriesId: String? = null,
    val season: Int? = null,
) {
    /** Les URL contiennent les identifiants Xtream : jamais dans un log. */
    override fun toString() = "PlayItem(title=$title, kind=$kind, contentId=$contentId)"
}

/** Une session de lecture, toujours rattachée au profil qui l'a lancée. */
@Serializable
data class PlaySession(
    val profileId: String,
    val items: List<PlayItem>,
    val startIndex: Int,
    /** Position de départ (ms) de l'élément [startIndex], ou -1 pour le début / le direct. */
    val startPositionMs: Long = -1,
)

/**
 * Liste de lecture transmise au lecteur. Gardée en mémoire plutôt que dans l'Intent, car une
 * catégorie de chaînes peut contenir des milliers d'entrées. Une copie chiffrée est aussi
 * écrite sur l'appareil : si Android arrête l'application en arrière-plan (PiP, Cast…),
 * le lecteur peut être recréé avec sa liste.
 */
object PlaylistHolder {
    private const val FILE_NAME = "playback_session"
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Executors.newSingleThreadExecutor()

    @Volatile
    var current: PlaySession? = null
        private set

    fun play(context: Context, profileId: String, items: List<PlayItem>, startIndex: Int, startPositionMs: Long = -1) {
        if (items.isEmpty()) return
        val session = PlaySession(profileId, items, startIndex.coerceIn(0, items.lastIndex), startPositionMs)
        current = session
        val dir = context.noBackupFilesDir
        writer.execute { persist(dir, session) }
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }

    /**
     * Écriture atomique : fichier temporaire puis renommage. Une interruption (processus
     * arrêté, batterie…) laisse l'ancienne session intacte au lieu d'un fichier tronqué.
     */
    private fun persist(dir: File, session: PlaySession) {
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, "$FILE_NAME.tmp")
        val ok = runCatching {
            tmp.outputStream().use { out ->
                out.write(SecretBox.encrypt(json.encodeToString(session)).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            tmp.renameTo(target)
        }.getOrDefault(false)
        // Échec (chiffrement impossible…) : pas de session restaurable plutôt qu'une session périmée.
        if (!ok) {
            tmp.delete()
            target.delete()
        }
    }

    /**
     * Session en mémoire, ou relue depuis l'appareil après l'arrêt du processus.
     * Lecture de fichier et déchiffrement : à appeler hors du thread principal.
     */
    fun restore(context: Context): PlaySession? = current ?: runCatching {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        json.decodeFromString<PlaySession>(SecretBox.decrypt(file.readText()))
    }.getOrNull()?.also { current = it }
}
