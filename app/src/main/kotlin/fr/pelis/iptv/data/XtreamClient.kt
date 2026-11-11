package fr.pelis.iptv.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client de l'API Xtream Codes (player_api.php).
 */
class XtreamClient(val credentials: Credentials) {

    private val base = credentials.server

    // ---------- Authentification ----------

    suspend fun login(): AccountInfo {
        val root = call(null) as? JsonObject
            ?: throw XtreamException("Réponse inattendue : vérifiez l'adresse du serveur")
        val user = root["user_info"] as? JsonObject
            ?: throw XtreamException("Identifiants invalides")
        if (user.str("auth") != "1") throw XtreamException("Identifiants invalides")
        val status = user.str("status") ?: "Inconnu"
        if (!status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Abonnement non actif (statut : $status)")
        }
        return AccountInfo(
            username = user.str("username") ?: credentials.username,
            status = status,
            expDate = user.str("exp_date")?.toLongOrNull(),
            maxConnections = user.str("max_connections"),
            activeConnections = user.str("active_cons"),
            isTrial = user.str("is_trial") == "1",
        )
    }

    // ---------- Catalogue ----------

    suspend fun categories(type: ContentType): List<Category> {
        val action = when (type) {
            ContentType.LIVE -> "get_live_categories"
            ContentType.VOD -> "get_vod_categories"
            ContentType.SERIES -> "get_series_categories"
        }
        val arr = call(action).asListOrFail()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(id, o.str("category_name")?.trim() ?: "Sans nom")
        }
    }

    /** Liste des flux d'une catégorie, ou de tout le catalogue si [categoryId] est null. */
    suspend fun streams(type: ContentType, categoryId: String?): List<MediaEntry> {
        val action = when (type) {
            ContentType.LIVE -> "get_live_streams"
            ContentType.VOD -> "get_vod_streams"
            ContentType.SERIES -> "get_series"
        }
        val el = if (categoryId != null) call(action, "category_id" to categoryId) else call(action)
        val arr = el.asListOrFail()
        return withContext(Dispatchers.Default) {
            arr.mapNotNull { (it as? JsonObject)?.let { o -> toEntry(o, type) } }
        }
    }

    suspend fun seriesInfo(seriesId: String): SeriesDetail {
        val root = call("get_series_info", "series_id" to seriesId) as? JsonObject
            ?: throw XtreamException("Série introuvable")
        val info = root["info"] as? JsonObject
        val episodes = mutableListOf<Episode>()

        fun parseEpisode(el: JsonElement, seasonKey: Int?) {
            val o = el as? JsonObject ?: return
            val epInfo = o["info"] as? JsonObject
            episodes += Episode(
                id = o.str("id") ?: return,
                title = o.str("title") ?: "Épisode",
                season = o.str("season")?.toIntOrNull() ?: seasonKey ?: 1,
                number = o.str("episode_num")?.toIntOrNull() ?: 0,
                extension = o.str("container_extension") ?: "mp4",
                plot = epInfo?.str("plot"),
                image = epInfo?.str("movie_image"),
                duration = epInfo?.str("duration"),
            )
        }

        // Selon les serveurs, "episodes" est un objet {"1":[...]} ou un tableau de tableaux.
        when (val eps = root["episodes"]) {
            is JsonObject -> eps.forEach { (key, value) ->
                (value as? JsonArray)?.forEach { parseEpisode(it, key.toIntOrNull()) }
            }
            is JsonArray -> eps.forEach { e ->
                if (e is JsonArray) e.forEach { parseEpisode(it, null) } else parseEpisode(e, null)
            }
            else -> Unit
        }

        val seasons = episodes.groupBy { it.season }
            .toSortedMap()
            .mapValues { (_, list) -> list.sortedBy { it.number } }

        return SeriesDetail(
            name = info?.str("name") ?: "",
            cover = info?.str("cover"),
            plot = info?.str("plot"),
            genre = info?.str("genre"),
            releaseDate = info?.str("releaseDate") ?: info?.str("release_date"),
            rating = info?.str("rating"),
            seasons = seasons,
        )
    }

    /** Programmes à venir d'une chaîne (en cours inclus). */
    suspend fun shortEpg(streamId: String, limit: Int = 20): List<EpgProgram> {
        val root = call("get_short_epg", "stream_id" to streamId, "limit" to limit.toString()) as? JsonObject
            ?: return emptyList()
        val arr = root["epg_listings"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val start = o.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val end = o.str("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            EpgProgram(
                title = decodeText(o.str("title")) ?: "Programme",
                description = decodeText(o.str("description")),
                start = start * 1000,
                end = end * 1000,
            )
        }.sortedBy { it.start }
    }

    suspend fun vodInfo(vodId: String): MovieDetail {
        val root = call("get_vod_info", "vod_id" to vodId) as? JsonObject
            ?: throw XtreamException("Film introuvable")
        val info = root["info"] as? JsonObject
        val data = root["movie_data"] as? JsonObject
        val backdrop = when (val b = info?.get("backdrop_path")) {
            is JsonArray -> (b.firstOrNull() as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> b.contentOrNull
            else -> null
        }?.takeIf { it.isNotBlank() }
        return MovieDetail(
            name = info?.str("name") ?: data?.str("name") ?: "",
            poster = info?.str("movie_image") ?: info?.str("cover_big"),
            backdrop = backdrop,
            plot = info?.str("plot") ?: info?.str("description"),
            genre = info?.str("genre"),
            director = info?.str("director"),
            cast = info?.str("cast") ?: info?.str("actors"),
            releaseDate = info?.str("releasedate") ?: info?.str("release_date"),
            duration = info?.str("duration"),
            rating = info?.str("rating"),
            trailerYoutubeId = info?.str("youtube_trailer"),
            extension = data?.str("container_extension"),
        )
    }

    // ---------- URLs de lecture ----------

    private val userPath get() = "${Uri.encode(credentials.username)}/${Uri.encode(credentials.password)}"

    fun liveUrl(streamId: String, ext: String = "ts") = "$base/live/$userPath/$streamId.$ext"

    fun vodUrl(streamId: String, ext: String?) = "$base/movie/$userPath/$streamId.${ext ?: "mp4"}"

    fun episodeUrl(episodeId: String, ext: String) = "$base/series/$userPath/$episodeId.$ext"

    // ---------- Interne ----------

    private fun toEntry(o: JsonObject, type: ContentType): MediaEntry? = when (type) {
        ContentType.LIVE -> MediaEntry(
            id = o.str("stream_id") ?: return null,
            name = o.str("name")?.trim() ?: "Sans nom",
            image = o.str("stream_icon"),
            categoryId = o.str("category_id"),
            extension = null, rating = null, plot = null,
            type = type,
            epgId = o.str("epg_channel_id"),
        )
        ContentType.VOD -> MediaEntry(
            id = o.str("stream_id") ?: return null,
            name = o.str("name")?.trim() ?: "Sans nom",
            image = o.str("stream_icon"),
            categoryId = o.str("category_id"),
            extension = o.str("container_extension"),
            rating = o.str("rating"),
            plot = null,
            type = type,
        )
        ContentType.SERIES -> MediaEntry(
            id = o.str("series_id") ?: return null,
            name = o.str("name")?.trim() ?: "Sans nom",
            image = o.str("cover"),
            categoryId = o.str("category_id"),
            extension = null,
            rating = o.str("rating"),
            plot = o.str("plot"),
            type = type,
        )
    }

    private suspend fun call(action: String?, vararg params: Pair<String, String>): JsonElement {
        val url = "$base/player_api.php".toHttpUrlOrNull()?.newBuilder()?.apply {
            addQueryParameter("username", credentials.username)
            addQueryParameter("password", credentials.password)
            if (action != null) addQueryParameter("action", action)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }?.build() ?: throw XtreamException("Adresse du serveur invalide")

        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        // Annulable : la requête est interrompue si l'écran n'en a plus besoin.
        val body = try {
            http.newCall(request).await { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        throw XtreamException("Accès refusé par le serveur (HTTP ${resp.code})")
                    !resp.isSuccessful -> throw XtreamException("Erreur serveur (HTTP ${resp.code})")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw XtreamException("Connexion impossible : ${e.message ?: e.javaClass.simpleName}")
        }
        if (body.isBlank()) return JsonArray(emptyList())
        return withContext(Dispatchers.Default) {
            try {
                json.parseToJsonElement(body)
            } catch (e: Exception) {
                throw XtreamException("Réponse invalide du serveur")
            }
        }
    }

    /** Liste attendue : une réponse d'un autre type (objet d'erreur…) est signalée, pas affichée comme vide. */
    private fun JsonElement.asListOrFail(): JsonArray = when (this) {
        is JsonArray -> this
        is JsonObject -> when {
            isEmpty() -> JsonArray(emptyList())
            // Certains panneaux renvoient {"1": {...}, "2": {...}} au lieu d'un tableau.
            "user_info" !in this && values.all { it is JsonObject } -> JsonArray(values.toList())
            else -> throw XtreamException("Réponse inattendue du serveur")
        }
        else -> JsonArray(emptyList())
    }

    companion object {
        const val USER_AGENT = "IPTVPlayer/1.0 (Linux; Android)"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /** true si l'adresse n'est pas chiffrée (identifiants et flux lisibles sur le réseau). */
        fun isCleartext(server: String) = server.trim().startsWith("http://", ignoreCase = true)

        /**
         * "exemple.com:8080/" -> "http://exemple.com:8080". Sans schéma, HTTP est utilisé car
         * la plupart des serveurs Xtream n'acceptent que lui ; l'écran du profil le signale.
         */
        fun normalizeServer(input: String): String {
            var s = stripUserInfo(input.trim())
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore('?').substringBefore('#').trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
            }
            return s.trimEnd('/')
        }

        /** Retire d'une adresse les identifiants éventuels « utilisateur:motdepasse@ ». */
        fun stripUserInfo(input: String): String =
            input.trim().replace(Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)?[^/?#@]*@"), "$1")

        /**
         * Version de ce qui a été saisi dans le champ serveur qui peut être conservée dans
         * l'état sauvegardé d'Android. Approche conservatrice : l'adresse est reconstruite
         * à partir de ses seuls éléments sûrs (schéma, hôte, port, chemin) ; une saisie
         * qu'on ne sait pas analyser et qui pourrait contenir un secret n'est pas conservée.
         */
        fun redactServerInput(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) return ""
            val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)
            val url = (if (hasScheme) t else "http://$t").toHttpUrlOrNull()
            if (url == null) {
                // Lien incomplet (« https://bob:secret@ », « …?password=… ») : rien n'est gardé.
                return if (t.any { it == '@' || it == '?' || it == '=' || it == '#' || it == '&' }) "" else t
            }
            val safe = url.newBuilder().username("").password("").query(null).fragment(null).build().toString()
            val cleaned = if (url.encodedPath == "/" && !t.trimEnd().endsWith("/")) safe.trimEnd('/') else safe
            return if (hasScheme) cleaned else cleaned.removePrefix("http://")
        }

        /**
         * Extrait serveur / identifiants d'un lien M3U du type get.php?username=..&password=..
         * (ou http://utilisateur:motdepasse@serveur/…). Le serveur retourné ne contient jamais d'identifiants.
         */
        fun parseM3uLink(input: String): Credentials? {
            val url = input.trim().toHttpUrlOrNull() ?: return null
            val user = url.queryParameter("username") ?: url.username.takeIf { it.isNotEmpty() }
            val pass = url.queryParameter("password") ?: url.password.takeIf { it.isNotEmpty() }
            if (user.isNullOrBlank() || pass.isNullOrEmpty()) return null
            // On garde un éventuel sous-chemin (ex. https://hote/iptv/get.php -> https://hote/iptv).
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val baseSegments = if (segments.lastOrNull()?.contains('.') == true) segments.dropLast(1) else segments
            val server = url.newBuilder()
                .username("")
                .password("")
                .query(null)
                .fragment(null)
                .encodedPath("/")
                .apply { baseSegments.forEach { addPathSegment(it) } }
                .build()
                .toString()
                .trimEnd('/')
            return Credentials(server, user, pass)
        }
    }
}

/** Les textes EPG Xtream sont encodés en Base64 (pas toujours) : on décode si c'est valide. */
private fun decodeText(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val decoded = try {
        String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        return raw
    }
    val looksBinary = decoded.any { it == '�' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }
    return (if (looksBinary) raw else decoded).trim().ifBlank { null }
}

/** Lit une valeur JSON (texte ou nombre) en String, null si absente/vide. */
internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return p.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
