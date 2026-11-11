package fr.pelis.iptv

import fr.pelis.iptv.transfer.PhoneLinkPage
import fr.pelis.iptv.transfer.PhoneLinkServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PhoneLinkTest {
    private val code = "AbC-123_xyz"

    private class FakeHandler : PhoneLinkServer.Handler {
        var logins = 0
        var finished = false
        override suspend fun login(request: PhoneLinkServer.LoginRequest): String {
            logins++
            if (request.password == "faux") throw IllegalStateException("Connexion refusée")
            return "ok ${request.username}"
        }
        override suspend fun importBackup(text: String) = "importé"
        override suspend fun exportBackup() = "s.json" to "{}"
        override fun onFinished() { finished = true }
    }

    private fun post(path: String, body: String) = PhoneLinkServer.Request("POST", path, emptyMap(), body)
    private fun get(path: String, c: String?) =
        PhoneLinkServer.Request("GET", path, if (c == null) emptyMap() else mapOf("c" to c), "")

    @Test
    fun parsesHttpRequests() {
        val raw = "POST /api/login HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\n{\"c\":\"abc\"}"
        val r = PhoneLinkServer.readRequest(raw.byteInputStream())!!
        assertEquals("POST", r.method)
        assertEquals("/api/login", r.path)
        assertEquals("{\"c\":\"abc\"}", r.body)
        val g = PhoneLinkServer.readRequest("GET /?c=a%2Db HTTP/1.1\r\n\r\n".byteInputStream())!!
        assertEquals("a-b", g.query["c"])
        assertNull(PhoneLinkServer.readRequest("DELETE / HTTP/1.1\r\n\r\n".byteInputStream()))
        assertNull(PhoneLinkServer.readRequest("POST / HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n".byteInputStream()))
        assertNull(PhoneLinkServer.readRequest("GET / HTTP/1.1\r\n".byteInputStream()))
    }

    @Test
    fun requiresTheCode() = runBlocking {
        val server = PhoneLinkServer(PhoneLinkServer.Mode.LOGIN, FakeHandler(), code)
        assertEquals(403, server.handle(get("/", null)).status)
        assertEquals(403, server.handle(get("/", "mauvais")).status)
        assertEquals(403, server.handle(post("/api/login", """{"c":"mauvais","password":"x"}""")).status)
        val page = server.handle(get("/", code))
        assertEquals(200, page.status)
        assertTrue(page.body.decodeToString().contains("'$code'"))
    }

    @Test
    fun loginSucceedsOnlyOnce() = runBlocking {
        val handler = FakeHandler()
        val server = PhoneLinkServer(PhoneLinkServer.Mode.LOGIN, handler, code)
        val failed = server.handle(post("/api/login", """{"c":"$code","username":"u","password":"faux"}"""))
        assertTrue(failed.body.decodeToString().contains("\"ok\":false"))
        assertFalse(handler.finished)
        val ok = server.handle(post("/api/login", """{"c":"$code","username":"u","password":"p"}"""))
        assertTrue(ok.body.decodeToString().contains("\"ok\":true"))
        assertTrue(handler.finished)
        assertEquals(410, server.handle(post("/api/login", """{"c":"$code","username":"u","password":"p"}""")).status)
        assertEquals(2, handler.logins)
        // Pas de route de sauvegarde en mode compte.
        assertEquals(404, server.handle(get("/api/export", code)).status)
    }

    @Test
    fun backupExportIsADownload() = runBlocking {
        val server = PhoneLinkServer(PhoneLinkServer.Mode.BACKUP, FakeHandler(), code)
        val r = server.handle(get("/api/export", code))
        assertEquals(200, r.status)
        assertTrue(r.extraHeaders.any { it.first == "Content-Disposition" && it.second.contains("s.json") })
        assertEquals(404, server.handle(post("/api/login", """{"c":"$code"}""")).status)
    }

    @Test
    fun onlyLocalAddresses() {
        assertTrue(PhoneLinkServer.isLocal(InetAddress.getByName("192.168.1.20")))
        assertTrue(PhoneLinkServer.isLocal(InetAddress.getByName("10.0.0.5")))
        assertFalse(PhoneLinkServer.isLocal(InetAddress.getByName("8.8.8.8")))
        assertFalse(PhoneLinkServer.isLocal(null))
    }

    @Test
    fun codesAreRandomAndUrlSafe() {
        val a = PhoneLinkServer.newCode()
        assertTrue(a.length == 32 && a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(a != PhoneLinkServer.newCode())
        PhoneLinkPage.render(PhoneLinkServer.Mode.BACKUP, a)
    }
}
