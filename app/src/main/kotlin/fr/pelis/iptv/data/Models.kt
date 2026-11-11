package fr.pelis.iptv.data

import kotlinx.serialization.Serializable

/** Un profil = un compte Xtream Codes enregistré, avec ses propres favoris. */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
) {
    fun credentials() = Credentials(server, username, password)

    /** Jamais d'identifiants dans un log ou un message d'erreur par accident. */
    override fun toString() = "Profile(id=$id, name=$name, server=$server, username=***, password=***)"
}

data class Credentials(
    val server: String,
    val username: String,
    val password: String,
) {
    override fun toString() = "Credentials(server=$server, username=***, password=***)"
}

data class AccountInfo(
    val username: String,
    val status: String,
    /** Date d'expiration en secondes epoch, null = illimité. */
    val expDate: Long?,
    val maxConnections: String?,
    val activeConnections: String?,
    val isTrial: Boolean,
)

data class Category(val id: String, val name: String)

enum class ContentType { LIVE, VOD, SERIES }

/** Élément affiché dans les listes : chaîne, film ou série. */
data class MediaEntry(
    val id: String,
    val name: String,
    val image: String?,
    val categoryId: String?,
    val extension: String?,
    val rating: String?,
    val plot: String?,
    val type: ContentType,
    /** Identifiant EPG (chaînes uniquement), null si la chaîne n'a pas de guide. */
    val epgId: String? = null,
)

/** Un programme du guide TV. Horaires en millisecondes epoch. */
data class EpgProgram(
    val title: String,
    val description: String?,
    val start: Long,
    val end: Long,
)

data class MovieDetail(
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val duration: String?,
    val rating: String?,
    val trailerYoutubeId: String?,
    val extension: String?,
)

/** Film ou épisode commencé (« Continuer à regarder »). */
@Serializable
data class WatchProgress(
    val key: String,
    val kind: String,
    val contentId: String,
    val extension: String?,
    val title: String,
    val subtitle: String? = null,
    val image: String? = null,
    val position: Long,
    val duration: Long,
    val updatedAt: Long,
    /** Épisodes : série et saison, pour reprendre avec la suite de la saison. */
    val seriesId: String? = null,
    val season: Int? = null,
)

/** Chaîne regardée récemment. */
@Serializable
data class RecentChannel(
    val id: String,
    val name: String,
    val image: String? = null,
    val epgId: String? = null,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int,
    val extension: String,
    val plot: String?,
    val image: String?,
    val duration: String?,
)

data class SeriesDetail(
    val name: String,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: String?,
    val seasons: Map<Int, List<Episode>>,
)

class XtreamException(message: String) : Exception(message)
