package fr.pelis.iptv

import fr.pelis.iptv.data.Backup
import fr.pelis.iptv.data.BackupMerge
import fr.pelis.iptv.data.RecentChannel
import fr.pelis.iptv.data.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private fun vod(id: String, updatedAt: Long, position: Long = 60_000) = WatchProgress(
        key = "vod:$id", kind = "vod", contentId = id, extension = "mkv", title = "Film $id",
        position = position, duration = 600_000, updatedAt = updatedAt,
    )

    @Test
    fun roundTrip() {
        val backup = Backup(
            exportedAt = 1,
            favorites = Backup.Favorites(live = listOf("1", "2"), vod = listOf("3")),
            progress = listOf(vod("10", 5)),
            recentChannels = listOf(RecentChannel("1", "TF1", "https://img/1.png")),
        )
        // Tout est conservé, sauf l'adresse des images (retrouvée dans le catalogue à l'import).
        val expected = backup.copy(recentChannels = listOf(RecentChannel("1", "TF1", null)))
        assertEquals(expected, Backup.decode(Backup.encode(backup)))
    }

    @Test
    fun rejectsForeignFiles() {
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("pas du json") }
        assertThrows(IllegalArgumentException::class.java) { Backup.decode("""{"format":"autre"}""") }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.decode("""{"format":"iptv-player-backup","version":99}""")
        }
    }

    @Test
    fun sanitizesUntrustedEntries() {
        val text = Backup.encode(
            Backup(
                favorites = Backup.Favorites(live = listOf("1", "1", " ", "x".repeat(200))),
                progress = listOf(
                    vod("10", 5),
                    vod("11", 5).copy(key = "vod:autre"),
                    vod("12", 5, position = 900_000),
                    vod("13", 5).copy(image = "javascript:alert(1)", extension = "../x"),
                    WatchProgress("ep:7", "episode", "7", "mp4", "S1E1", position = 1, duration = 2, updatedAt = 1),
                ),
            ),
        )
        val b = Backup.decode(text)
        assertEquals(listOf("1"), b.favorites.live)
        assertEquals(listOf("vod:10", "vod:13", "ep:7"), b.progress.map { it.key })
        val p13 = b.progress.first { it.key == "vod:13" }
        assertNull(p13.image)
        assertNull(p13.extension)
    }

    @Test
    fun mergeKeepsMostRecentProgress() {
        val merged = BackupMerge.progress(
            existing = listOf(vod("1", 10, position = 100_000), vod("2", 5)),
            imported = listOf(vod("1", 20, position = 200_000), vod("3", 1)),
            max = 60,
        )
        assertEquals(listOf("vod:1", "vod:2", "vod:3"), merged.map { it.key })
        assertEquals(200_000L, merged.first().position)
        assertEquals(2, BackupMerge.progress(merged, emptyList(), max = 2).size)
    }

    @Test
    fun mergeRecentKeepsDeviceOrderFirst() {
        val merged = BackupMerge.recent(
            existing = listOf(RecentChannel("2", "B"), RecentChannel("1", "A")),
            imported = listOf(RecentChannel("1", "A"), RecentChannel("3", "C")),
            max = 30,
        )
        assertEquals(listOf("2", "1", "3"), merged.map { it.id })
        assertTrue(BackupMerge.recent(merged, emptyList(), max = 1).size == 1)
    }
}
