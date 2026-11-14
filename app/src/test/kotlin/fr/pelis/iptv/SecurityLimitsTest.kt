package fr.pelis.iptv

import fr.pelis.iptv.data.Backup
import fr.pelis.iptv.data.RecentChannel
import fr.pelis.iptv.data.ResponseTooLargeException
import fr.pelis.iptv.data.WatchProgress
import fr.pelis.iptv.data.XtreamClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Correctifs de l'audit 1.4.12 : redirections, tailles, sauvegardes, saisie du serveur. */
class SecurityLimitsTest {

    @Test
    fun httpsIsNeverDowngraded() {
        val https = "https://exemple.invalid/player_api.php".toHttpUrl()
        assertTrue(XtreamClient.isDowngrade(https, "http://autre.invalid/player_api.php"))
        assertTrue(XtreamClient.isDowngrade(https, "HTTP://autre.invalid/"))
        assertFalse(XtreamClient.isDowngrade(https, "https://autre.invalid/"))
        assertFalse(XtreamClient.isDowngrade(https, "/autre/chemin"))
        // HTTP -> HTTPS reste permis ; HTTP -> HTTP ne change rien.
        val http = "http://exemple.invalid/".toHttpUrl()
        assertFalse(XtreamClient.isDowngrade(http, "https://exemple.invalid/"))
        assertFalse(XtreamClient.isDowngrade(http, "http://autre.invalid/"))
    }

    @Test
    fun responsesAreBounded() {
        val ok = XtreamClient.parseBounded("""  {"a":[1,2,3]}""".byteInputStream(), 1_000)
        assertTrue(ok is JsonObject)
        assertEquals(JsonArray(emptyList()), XtreamClient.parseBounded("  \n ".byteInputStream(), 1_000))
        val big = "[" + (1..10_000).joinToString(",") + "]"
        val failure = runCatching { XtreamClient.parseBounded(big.byteInputStream(), 1_000) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is ResponseTooLargeException)
    }

    @Test
    fun backupsNeverContainImageUrls() {
        val secret = "https://exemple.invalid/logo.png?token=AUDIT_SECRET"
        val backup = Backup(
            progress = listOf(
                WatchProgress(
                    key = "vod:1", kind = "vod", contentId = "1", extension = "mp4", title = "Film",
                    position = 60_000, duration = 600_000, updatedAt = 1, image = secret,
                ),
            ),
            recentChannels = listOf(RecentChannel("2", "Chaîne", secret, null)),
        )
        val text = Backup.encode(backup)
        assertFalse(text.contains("AUDIT_SECRET"))
        // Une ancienne sauvegarde qui en contient : ignorées à l'import.
        val legacy = text.replace("\"image\": null", "\"image\": \"$secret\"")
        val decoded = Backup.decode(legacy)
        assertTrue(decoded.progress.all { it.image == null } && decoded.recentChannels.all { it.image == null })
    }

    @Test
    fun pastedStreamUrlsKeepNoCredentials() {
        val typed = "https://exemple.invalid/live/demo/AUDIT_SECRET/1.ts"
        assertEquals("https://exemple.invalid", XtreamClient.redactServerInput(typed))
        assertEquals("exemple.invalid:8080/iptv", XtreamClient.redactServerInput("exemple.invalid:8080/iptv/movie/u/AUDIT_SECRET/9.mkv"))
        val creds = XtreamClient.parseM3uLink("http://exemple.invalid:8080/series/demo/p%40ss/77.mp4")!!
        assertEquals("http://exemple.invalid:8080", creds.server)
        assertEquals("demo", creds.username)
        assertEquals("p@ss", creds.password)
    }
}
