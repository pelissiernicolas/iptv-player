package fr.pelis.iptv.player

import android.content.Context
import android.content.Intent

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
)

/**
 * Liste de lecture transmise au lecteur. Gardée en mémoire plutôt que dans l'Intent,
 * car une catégorie de chaînes peut contenir des milliers d'entrées.
 */
object PlaylistHolder {
    var items: List<PlayItem> = emptyList()
        private set
    var startIndex: Int = 0
        private set
    /** Position de départ (ms) de l'élément [startIndex], ou -1 pour le début / le direct. */
    var startPositionMs: Long = -1
        private set

    fun play(context: Context, items: List<PlayItem>, startIndex: Int, startPositionMs: Long = -1) {
        if (items.isEmpty()) return
        this.items = items
        this.startIndex = startIndex.coerceIn(0, items.lastIndex)
        this.startPositionMs = startPositionMs
        context.startActivity(Intent(context, PlayerActivity::class.java))
    }
}
