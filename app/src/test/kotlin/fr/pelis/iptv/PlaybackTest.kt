package fr.pelis.iptv

import fr.pelis.iptv.data.XtreamClient
import fr.pelis.iptv.player.castQueueOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CastQueueTest {
    private val local = (0 until 50).map { "http://srv/live/u/p/$it.m3u8" }

    @Test
    fun joinsWindowInsideLocalList() {
        val remote = (20 until 40).map { local[it] }
        assertEquals(20, castQueueOffset(remote, local))
    }

    @Test
    fun joinsWhenSomeRemoteUrlsAreUnknown() {
        val remote = listOf(null, local[11], null, local[13])
        assertEquals(10, castQueueOffset(remote, local))
    }

    @Test
    fun refusesOtherContent() {
        assertNull(castQueueOffset(listOf("http://autre/film.mp4"), local))
        // Même éléments mais pas dans le même ordre : ce n'est pas notre file.
        assertNull(castQueueOffset(listOf(local[5], local[3]), local))
        assertNull(castQueueOffset(emptyList(), local))
        assertNull(castQueueOffset(listOf(null, null), local))
    }

    @Test
    fun refusesQueueOverflowingLocalList() {
        assertNull(castQueueOffset(listOf(local[49], "http://srv/live/u/p/50.m3u8"), local))
    }
}

class SensitiveInputTest {

    @Test
    fun incompleteM3uLinkIsRedactedBeforeStateSave() {
        val typed = "http://exemple.com:8080/get.php?password=s3cr3t"
        val saved = XtreamClient.redactServerInput(typed)
        assertFalse(saved.contains("s3cr3t"))
        assertEquals("http://exemple.com:8080/get.php", saved)
    }

    @Test
    fun incompleteInputWithSecretIsNotSavedAtAll() {
        // Cas reproduit par l'audit 1.4.2 : espaces initiaux, hôte encore absent.
        assertEquals("", XtreamClient.redactServerInput("  https://bob:s3cr3t@"))
        assertEquals("", XtreamClient.redactServerInput("bob:s3cr3t@"))
        assertEquals("get.php", XtreamClient.redactServerInput("get.php?username=bob&password=s3cr3t"))
        for (typed in listOf("  https://bob:s3cr3t@", " http://bob:s3cr3t@hote", "	https://bob:s3cr3t@hote:8080/x?password=s3cr3t")) {
            assertFalse(typed, XtreamClient.redactServerInput(typed).contains("s3cr3t"))
        }
    }

    @Test
    fun ordinaryInputIsKeptWhileTyping() {
        assertEquals("exemple.com", XtreamClient.redactServerInput("exemple.com"))
        assertEquals("http://exemple.com:8080", XtreamClient.redactServerInput("http://exemple.com:8080"))
        assertEquals("exem", XtreamClient.redactServerInput("exem"))
    }

    @Test
    fun userInfoIsRemovedFromSavedInput() {
        assertEquals("https://exemple.com/iptv", XtreamClient.redactServerInput("https://bob:s3cr3t@exemple.com/iptv"))
        assertEquals("exemple.com:8080", XtreamClient.redactServerInput("bob:s3cr3t@exemple.com:8080"))
    }

    @Test
    fun userInfoLinkGivesCredentialsAndCleanServer() {
        val c = XtreamClient.parseM3uLink("https://bob:s3cr3t@exemple.com/iptv/get.php")
        assertEquals("bob", c?.username)
        assertEquals("s3cr3t", c?.password)
        assertEquals("https://exemple.com/iptv", c?.server)
    }

    @Test
    fun normalizedServerNeverKeepsUserInfo() {
        assertEquals("https://exemple.com:8443", XtreamClient.normalizeServer("https://bob:s3cr3t@exemple.com:8443/"))
        assertEquals("http://exemple.com", XtreamClient.normalizeServer("bob:pw@exemple.com"))
    }
}
