package fr.pelis.iptv

import fr.pelis.iptv.data.Library
import fr.pelis.iptv.data.Library.ProgressAction
import fr.pelis.iptv.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun newerVersions() {
        assertTrue(Updater.isNewer("1.4.0", "1.3.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.2"))
        assertTrue(Updater.isNewer("2.0", "1.99.99"))
        assertTrue(Updater.isNewer("1.3.1", "1.3"))
    }

    @Test
    fun sameOrOlderVersions() {
        assertFalse(Updater.isNewer("1.3.0", "1.3.0"))
        assertFalse(Updater.isNewer("1.3", "1.3.0"))
        assertFalse(Updater.isNewer("1.2.9", "1.3.0"))
    }

    @Test
    fun preReleases() {
        // Une version de test est remplacée par la version finale du même numéro…
        assertTrue(Updater.isNewer("1.2.2", "1.2.2-test"))
        // …mais une préversion ne remplace jamais la version finale.
        assertFalse(Updater.isNewer("1.4.0-beta", "1.4.0"))
        assertTrue(Updater.isNewer("1.5.0-beta", "1.4.0"))
    }
}

class ProgressTest {
    private val minute = 60_000L
    private val film = 100 * minute

    @Test
    fun thresholds() {
        assertEquals(ProgressAction.IGNORE, Library.progressAction(10_000, film))
        assertEquals(ProgressAction.SAVE, Library.progressAction(Library.MIN_POSITION_MS, film))
        assertEquals(ProgressAction.SAVE, Library.progressAction(50 * minute, film))
        assertEquals(ProgressAction.REMOVE, Library.progressAction(95 * minute, film))
        assertEquals(ProgressAction.REMOVE, Library.progressAction(film, film))
        assertEquals(ProgressAction.IGNORE, Library.progressAction(5 * minute, 0))
    }
}
