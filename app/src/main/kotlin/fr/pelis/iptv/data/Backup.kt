package fr.pelis.iptv.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sauvegarde des données personnelles d'un profil : favoris, progression de visionnage et
 * chaînes récentes. Jamais d'identifiants (serveur, utilisateur, mot de passe) : le fichier
 * peut être partagé ou stocké n'importe où.
 *
 * Les identifiants de contenu sont ceux du fournisseur : l'import n'a de sens qu'avec le même
 * fournisseur. Les éléments inconnus restent simplement sans effet.
 */
@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long = 0,
    val favorites: Favorites = Favorites(),
    val progress: List<WatchProgress> = emptyList(),
    val recentChannels: List<RecentChannel> = emptyList(),
) {
    @Serializable
    data class Favorites(
        @SerialName("live") val live: List<String> = emptyList(),
        @SerialName("vod") val vod: List<String> = emptyList(),
        @SerialName("series") val series: List<String> = emptyList(),
    ) {
        operator fun get(type: ContentType): List<String> = when (type) {
            ContentType.LIVE -> live
            ContentType.VOD -> vod
            ContentType.SERIES -> series
        }

        val count get() = live.size + vod.size + series.size
    }

    companion object {
        const val FORMAT = "iptv-player-backup"
        const val VERSION = 1
        /** Au-delà, le fichier n'est pas une sauvegarde de l'application. */
        const val MAX_BYTES = 4 * 1024 * 1024

        private const val MAX_FAVORITES = 5_000
        private const val MAX_ID = 64
        private const val MAX_TEXT = 300
        private const val MAX_URL = 2_048

        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun encode(backup: Backup): String = json.encodeToString(backup)

        /** Lit et assainit une sauvegarde ; lève [IllegalArgumentException] si ce n'en est pas une. */
        fun decode(text: String): Backup {
            val raw = runCatching { json.decodeFromString<Backup>(text) }
                .getOrElse { throw IllegalArgumentException("fichier illisible") }
            require(raw.format == FORMAT) { "ce n'est pas une sauvegarde IPTV Player" }
            require(raw.version <= VERSION) { "sauvegarde créée par une version plus récente" }
            return raw.sanitized()
        }

        private fun id(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID }
        private fun text(s: String?) = s?.take(MAX_TEXT)
        private fun url(s: String?) =
            s?.takeIf { it.length <= MAX_URL && (it.startsWith("http://") || it.startsWith("https://")) }

        private fun ids(list: List<String>) = list.mapNotNull(::id).distinct().take(MAX_FAVORITES)
    }

    /** Écarte les entrées invalides et borne les textes : le fichier vient de l'extérieur. */
    private fun sanitized() = copy(
        favorites = Favorites(ids(favorites.live), ids(favorites.vod), ids(favorites.series)),
        progress = progress.mapNotNull { p ->
            val contentId = id(p.contentId) ?: return@mapNotNull null
            val expectedKey = when (p.kind) {
                "vod" -> "vod:$contentId"
                "episode" -> "ep:$contentId"
                else -> return@mapNotNull null
            }
            if (p.key != expectedKey ||p.duration <= 0 || p.position !in 0..p.duration) {
                return@mapNotNull null
            }
            p.copy(
                extension = p.extension?.takeIf { it.length <= 8 && it.all(Char::isLetterOrDigit) },
                title = text(p.title).orEmpty(),
                subtitle = text(p.subtitle),
                image = url(p.image),
                seriesId = id(p.seriesId),
            )
        }.distinctBy { it.key },
        recentChannels = recentChannels.mapNotNull { c ->
            val channelId = id(c.id) ?: return@mapNotNull null
            c.copy(id = channelId, name = text(c.name).orEmpty(), image = url(c.image), epgId = text(c.epgId))
        }.distinctBy { it.id },
    )
}

/** Fusions à l'import : rien n'est perdu sur l'appareil, la donnée la plus récente l'emporte. */
object BackupMerge {
    /** Par contenu, la position mise à jour le plus récemment ; du plus récent au plus ancien. */
    fun progress(existing: List<WatchProgress>, imported: List<WatchProgress>, max: Int): List<WatchProgress> =
        (existing + imported)
            .groupBy { it.key }
            .map { (_, entries) -> entries.maxBy { it.updatedAt } }
            .sortedByDescending { it.updatedAt }
            .take(max)

    /** Les chaînes de l'appareil d'abord (ordre conservé), puis celles importées absentes. */
    fun recent(existing: List<RecentChannel>, imported: List<RecentChannel>, max: Int): List<RecentChannel> =
        (existing + imported.filter { i -> existing.none { it.id == i.id } }).take(max)
}
