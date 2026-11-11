package fr.pelis.iptv

import fr.pelis.iptv.data.Credentials
import fr.pelis.iptv.data.Profile
import fr.pelis.iptv.data.XtreamClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamClientTest {

    @Test
    fun m3uLink_simple() {
        val c = XtreamClient.parseM3uLink("http://exemple.com:8080/get.php?username=bob&password=s3cr3t&type=m3u_plus")
        assertEquals(Credentials("http://exemple.com:8080", "bob", "s3cr3t"), c)
    }

    @Test
    fun m3uLink_defaultPortIsDropped() {
        assertEquals("https://exemple.com", XtreamClient.parseM3uLink("https://exemple.com:443/get.php?username=a&password=b")?.server)
        assertEquals("http://exemple.com", XtreamClient.parseM3uLink("http://exemple.com/get.php?username=a&password=b")?.server)
    }

    @Test
    fun m3uLink_keepsSubPath() {
        val c = XtreamClient.parseM3uLink("https://exemple.com/iptv/get.php?username=a&password=b")
        assertEquals("https://exemple.com/iptv", c?.server)
    }

    @Test
    fun m3uLink_decodesEncodedCredentials() {
        val c = XtreamClient.parseM3uLink("http://exemple.com/get.php?username=jean%20d&password=p%26ss%3D1")
        assertEquals("jean d", c?.username)
        assertEquals("p&ss=1", c?.password)
    }

    @Test
    fun m3uLink_ipv6() {
        val c = XtreamClient.parseM3uLink("http://[2001:db8::1]:8080/get.php?username=a&password=b")
        assertEquals("http://[2001:db8::1]:8080", c?.server)
    }

    @Test
    fun m3uLink_rejectsIncompleteLinks() {
        assertNull(XtreamClient.parseM3uLink("http://exemple.com/get.php?username=a"))
        assertNull(XtreamClient.parseM3uLink("exemple.com:8080"))
        assertNull(XtreamClient.parseM3uLink("pas une url"))
    }

    @Test
    fun normalizeServer() {
        assertEquals("http://exemple.com:8080", XtreamClient.normalizeServer(" exemple.com:8080/ "))
        assertEquals("https://exemple.com", XtreamClient.normalizeServer("https://exemple.com/player_api.php?x=1"))
        assertEquals("http://exemple.com/iptv", XtreamClient.normalizeServer("http://exemple.com/iptv/get.php"))
    }

    @Test
    fun cleartextDetection() {
        assertTrue(XtreamClient.isCleartext("http://exemple.com"))
        assertFalse(XtreamClient.isCleartext("https://exemple.com"))
    }

    @Test
    fun credentialsAreNeverPrinted() {
        val p = Profile("1", "Salon", "http://exemple.com", "bob", "s3cr3t")
        assertFalse(p.toString().contains("s3cr3t"))
        assertFalse(p.toString().contains("bob"))
        assertFalse(p.credentials().toString().contains("s3cr3t"))
    }
}
